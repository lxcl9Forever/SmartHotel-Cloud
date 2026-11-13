param(
    [switch]$Nacos,
    [ValidatePattern('^[a-zA-Z0-9][a-zA-Z0-9.-]*$')]
    [string]$DependencyHost = '127.0.0.1'
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Import-CloudEnv.ps1')
$projectRoot = Split-Path $PSScriptRoot -Parent
$runDir = Join-Path $projectRoot '.run'
New-Item -ItemType Directory -Path $runDir -Force | Out-Null
$pidFile = Join-Path $runDir 'processes.json'
if (Test-Path -LiteralPath $pidFile) { throw 'A run record exists. Run scripts/Stop-Cloud.ps1 before starting again.' }
$inventoryRemote = $env:INVENTORY_MODE -eq 'remote'
if ($env:INVENTORY_MODE -and $env:INVENTORY_MODE -notin @('local','remote')) { throw 'Unsupported INVENTORY_MODE.' }
if ($env:INVENTORY_MYSQL_USER -and !$inventoryRemote) { throw 'Inventory ownership was migrated. Do not switch to local mode without reconciling pending decisions and permissions.' }
if ($env:HOTEL_WRITE_OWNER -ne 'enabled') { throw 'Run Upgrade-HotelWrite.ps1 with applications stopped before using hotel management delegation.' }
if ($env:HOTEL_CATALOG_REMOTE -ne 'enabled') { throw 'Run Upgrade-HotelCatalog.ps1 with applications stopped before using the hotel catalog boundary.' }
if ($env:HOTEL_DATA_LAYOUT -notin @('split','rollback-shared')) { throw 'Run Split-HotelDatabase.ps1 with applications stopped before starting the independent hotel schema.' }
if ($env:INVENTORY_DATA_LAYOUT -notin @('split','rollback-shared')) { throw 'Run Split-InventoryDatabase.ps1 with applications stopped before using the independent inventory database.' }
$databaseSettings = @{}
if ($env:DB_LAYOUT) {
    if ($env:DB_LAYOUT -notin @('split','rollback-shared')) { throw 'Unsupported DB_LAYOUT in .env.' }
    $roles = @('USER','BUSINESS','HOTEL'); if ($inventoryRemote) { $roles += 'INVENTORY' }
    foreach ($role in $roles) {
        $schema = [Environment]::GetEnvironmentVariable($role + '_MYSQL_DATABASE')
        $username = [Environment]::GetEnvironmentVariable($role + '_MYSQL_USER')
        $password = [Environment]::GetEnvironmentVariable($role + '_MYSQL_PASSWORD')
        if (!$schema -or !$username -or !$password -or $schema -notmatch '^[A-Za-z0-9_]+$') {
            throw "Incomplete $role database settings. Restore the migration-generated .env; do not fall back to the old shared account."
        }
        $databaseSettings[$role] = @{Schema=$schema;Username=$username;Password=$password}
    }
} else {
    throw 'Initialize database separation and run Initialize-HotelRead.ps1 before starting the four-service layout.'
}
$userJwtSecret = $env:JWT_SECRET
# Do not pass the root password, other service credentials, or JWT signing key to every child.
foreach ($key in @('MYSQL_ROOT_PASSWORD','MYSQL_PASSWORD','JWT_SECRET','USER_MYSQL_PASSWORD','BUSINESS_MYSQL_PASSWORD',
                  'USER_MYSQL_USER','BUSINESS_MYSQL_USER','USER_MYSQL_DATABASE','BUSINESS_MYSQL_DATABASE','USER_MYSQL_URL','BUSINESS_MYSQL_URL','HOTEL_MYSQL_DATABASE','HOTEL_MYSQL_USER','HOTEL_MYSQL_PASSWORD','HOTEL_MYSQL_URL','INVENTORY_MYSQL_DATABASE','INVENTORY_MYSQL_USER','INVENTORY_MYSQL_PASSWORD','INVENTORY_MYSQL_URL')) {
    Remove-Item -LiteralPath ('Env:' + $key) -ErrorAction SilentlyContinue
}
$env:REDIS_HOST = $DependencyHost; $env:REDIS_PORT = '16379'
$env:SERVER_ADDRESS = '127.0.0.1'
$env:HOTEL_SERVICE_URL = 'http://127.0.0.1:8083'
$env:USER_SERVICE_URL = 'http://127.0.0.1:8081'
$env:INVENTORY_SERVICE_URL = 'http://127.0.0.1:8084'
if ($Nacos) {
    if ([string]::IsNullOrWhiteSpace($env:NACOS_ADDR)) { $env:NACOS_ADDR = $DependencyHost + ':8848' }
    if ([string]::IsNullOrWhiteSpace($env:NACOS_USERNAME)) { $env:NACOS_USERNAME = 'nacos' }
    if ([string]::IsNullOrWhiteSpace($env:NACOS_PASSWORD)) {
        throw 'Add NACOS_PASSWORD (the console login password) to the project .env first. Do not use NACOS_AUTH_TOKEN.'
    }
    # All Java services run on this Windows host and listen on loopback.
    # The VM hosts only Nacos; its IP must never become a Java instance address.
    $env:NACOS_INSTANCE_IP = '127.0.0.1'
    if ($env:NACOS_ADDR -notmatch '^([a-zA-Z0-9.-]+):([0-9]+)$') {
        throw 'NACOS_ADDR must be host:port, for example 192.168.229.131:8848 (not the console port 8088).'
    }
    $nacosHost = $Matches[1]; $nacosPort = [int]$Matches[2]
    if ($nacosPort -lt 1 -or $nacosPort -gt 64535) { throw 'Invalid NACOS_ADDR port.' }
    foreach ($probePort in @($nacosPort, ($nacosPort + 1000))) {
        $probeClient = New-Object System.Net.Sockets.TcpClient
        try {
            $pending = $probeClient.BeginConnect($nacosHost, $probePort, $null, $null)
            if (!$pending.AsyncWaitHandle.WaitOne(3000)) { throw "Nacos TCP port ${nacosHost}:$probePort is unreachable." }
            $probeClient.EndConnect($pending)
        } finally { $probeClient.Dispose() }
    }
    Write-Host "Nacos discovery mode: $($env:NACOS_ADDR); Windows instances register as 127.0.0.1."
}
$processes = @()
try {
    $entries = ,@('smarthotel-user-service',8081); if ($inventoryRemote) { $entries += ,@('smarthotel-inventory-service',8084) }
    $entries += @(@('smarthotel-business-service',8082),@('smarthotel-hotel-service',8083),@('smarthotel-gateway',8080))
    foreach ($entry in $entries) {
        $module = $entry[0]; $port = $entry[1]
        if ($module -eq 'smarthotel-gateway') {
            foreach ($key in @('MYSQL_URL','MYSQL_USER','MYSQL_PASSWORD','JWT_SECRET')) { Remove-Item -LiteralPath ('Env:' + $key) -ErrorAction SilentlyContinue }
        } else {
            $role = if ($module -eq 'smarthotel-user-service') { 'USER' } elseif ($module -eq 'smarthotel-hotel-service') { 'HOTEL' } elseif ($module -eq 'smarthotel-inventory-service') { 'INVENTORY' } else { 'BUSINESS' }
            $settings = $databaseSettings[$role]
            $env:MYSQL_URL = 'jdbc:mysql://' + $DependencyHost + ':13306/' + $settings.Schema + '?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true'
            $env:MYSQL_USER = $settings.Username
            $env:MYSQL_PASSWORD = $settings.Password
            if ($role -eq 'USER') { $env:JWT_SECRET = $userJwtSecret } else { Remove-Item -LiteralPath Env:JWT_SECRET -ErrorAction SilentlyContinue }
        }
        if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) { throw "Port $port is already in use." }
        $jar = Join-Path $projectRoot "$module/target/$module-1.0.0-SNAPSHOT.jar"
        if (!(Test-Path -LiteralPath $jar)) { throw 'Build the project with mvn verify first.' }
        $argsList = @('-Dfile.encoding=UTF-8', '-Duser.timezone=Asia/Shanghai', '-jar', ('"' + $jar + '"'))
        if ($Nacos) { $argsList += '--spring.profiles.active=nacos' }
        $proc = Start-Process -FilePath (Get-Command java).Source -ArgumentList $argsList -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runDir "$module.out.log") -RedirectStandardError (Join-Path $runDir "$module.err.log")
        $processes += [pscustomobject]@{Id=$proc.Id;StartTime=$proc.StartTime.ToUniversalTime().Ticks;Module=$module}
        ConvertTo-Json -InputObject @($processes) | Set-Content -LiteralPath $pidFile -Encoding UTF8
        $ready = $false
        for ($attempt=0;$attempt -lt 90;$attempt++) {
            if ($proc.HasExited) { throw "$module exited. Inspect .run/$module.err.log and .out.log." }
            try { $health = Invoke-RestMethod "http://127.0.0.1:$port/actuator/health" -TimeoutSec 2; if ($health.status -eq 'UP') { $ready=$true;break } } catch { }
            Start-Sleep -Seconds 1
        }
        if (!$ready) { throw "$module is not healthy. Inspect .run logs." }
        Write-Host "$module ready on $port"
    }
    Write-Host 'Gateway: http://127.0.0.1:8080 . Start the Vue frontend with npm run dev.'
} catch {
    if ($processes.Count -gt 0) { & (Join-Path $PSScriptRoot 'Stop-Cloud.ps1') }
    throw
}
