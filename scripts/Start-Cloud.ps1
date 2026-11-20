param([switch]$Nacos,[ValidatePattern('^[a-zA-Z0-9][a-zA-Z0-9.-]*$')][string]$DependencyHost='127.0.0.1',[ValidateRange(1,65535)][int]$DatabasePort=13306,[ValidateRange(1,65535)][int]$RedisPort=16379)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'Import-CloudEnv.ps1')
$projectRoot=Split-Path $PSScriptRoot -Parent
$runDir=Join-Path $projectRoot '.run'
New-Item -ItemType Directory -Path $runDir -Force | Out-Null
$pidFile=Join-Path $runDir 'processes.json'
if (Test-Path -LiteralPath $pidFile) { throw 'A project run record exists. Stop-Cloud.ps1 before starting again.' }
if ($env:PAYMENT_AI_LAYOUT -ne 'enabled') { throw 'Run scripts/Upgrade-PaymentAi.ps1 with Java stopped before starting this version.' }
if ($env:FULL_DOMAIN_LAYOUT -ne 'split') { throw 'Complete Split-FullDomains.ps1 rehearsal and migration before starting the nine-domain layout.' }
if ($env:INVENTORY_DATA_LAYOUT -ne 'split' -or $env:HOTEL_DATA_LAYOUT -ne 'split') { throw 'Independent hotel and inventory schemas are required.' }
$roles=@('USER','HOTEL','INVENTORY','ORDER','REVIEW','MARKETING','PAYMENT','STAY','STATISTICS')
$settings=@{}
foreach ($role in $roles) {
    $schema=[Environment]::GetEnvironmentVariable($role+'_MYSQL_DATABASE')
    $user=[Environment]::GetEnvironmentVariable($role+'_MYSQL_USER')
    $password=[Environment]::GetEnvironmentVariable($role+'_MYSQL_PASSWORD')
    if (!$schema -or !$user -or !$password -or $schema -notmatch '^[A-Za-z0-9_]+$') { throw "Incomplete $role database credentials. Restore the private migration environment." }
    $settings[$role]=@{Schema=$schema;User=$user;Password=$password}
}
$signingSecret=$env:JWT_SECRET
$paymentEnvironment=@{}
Get-ChildItem Env: | Where-Object { $_.Name -like 'ALIPAY_*' } | ForEach-Object { $paymentEnvironment[$_.Name]=$_.Value }
$aiSecret=$env:AI_LLM_API_KEY
# Each child receives only its own database credentials and required integration secrets.
Get-ChildItem Env: | Where-Object { $_.Name -match '(^|_)MYSQL_(PASSWORD|ROOT_PASSWORD|USER|DATABASE|URL)$|^JWT_SECRET$|^ALIPAY_|^AI_LLM_API_KEY$' } | ForEach-Object { Remove-Item -LiteralPath ('Env:'+$_.Name) }
$env:REDIS_HOST=$DependencyHost;$env:REDIS_PORT=[string]$RedisPort;$env:SERVER_ADDRESS='127.0.0.1';$env:INVENTORY_MODE='remote'
$ports=@{USER=8081;HOTEL=8083;INVENTORY=8084;ORDER=8082;REVIEW=8085;MARKETING=8086;PAYMENT=8087;STAY=8089;STATISTICS=8090}
foreach ($role in $roles) { [Environment]::SetEnvironmentVariable($role+'_SERVICE_URL',('http://127.0.0.1:'+$ports[$role]),'Process') }
if ($Nacos) {
    if (!$env:NACOS_ADDR) { $env:NACOS_ADDR=$DependencyHost+':8848' }
    if (!$env:NACOS_USERNAME) { $env:NACOS_USERNAME='nacos' }
    if (!$env:NACOS_PASSWORD) { throw 'NACOS_PASSWORD is required for discovery mode.' }
    $env:NACOS_INSTANCE_IP='127.0.0.1'
    foreach ($role in $roles) { [Environment]::SetEnvironmentVariable($role+'_SERVICE_URL',('lb://smarthotel-'+$role.ToLowerInvariant()+'-service'),'Process') }
    if ($env:NACOS_ADDR -notmatch '^([A-Za-z0-9.-]+):([0-9]+)$') { throw 'NACOS_ADDR must be host:port.' }
    $nacosHost=$Matches[1];$nacosPort=[int]$Matches[2]
    foreach ($probePort in @($nacosPort,($nacosPort+1000))) {
        $probe=New-Object System.Net.Sockets.TcpClient
        try { $pending=$probe.BeginConnect($nacosHost,$probePort,$null,$null);if (!$pending.AsyncWaitHandle.WaitOne(3000)) { throw "Nacos port $probePort is unavailable." };$probe.EndConnect($pending) } finally { $probe.Dispose() }
    }
    Write-Host "Nacos mode: $($env:NACOS_ADDR); all Windows instances register as 127.0.0.1."
}
$processes=@()
try {
    foreach ($role in @('USER','HOTEL','INVENTORY','MARKETING','ORDER','PAYMENT','STAY','REVIEW','STATISTICS','GATEWAY')) {
        $module='smarthotel-'+$role.ToLowerInvariant()+'-service'
        $port=$ports[$role]
        foreach ($key in @('MYSQL_URL','MYSQL_USER','MYSQL_PASSWORD','JWT_SECRET','AI_LLM_API_KEY')) { Remove-Item -LiteralPath ('Env:'+$key) -ErrorAction SilentlyContinue }
        Get-ChildItem Env: | Where-Object { $_.Name -like 'ALIPAY_*' } | ForEach-Object { Remove-Item -LiteralPath ('Env:'+$_.Name) }
        if ($role -eq 'GATEWAY') { $module='smarthotel-gateway';$port=8080 } else {
            $db=$settings[$role]
            $env:MYSQL_URL='jdbc:mysql://'+$DependencyHost+':'+$DatabasePort+'/'+$db.Schema+'?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true'
            $env:MYSQL_USER=$db.User;$env:MYSQL_PASSWORD=$db.Password
            if ($role -eq 'USER') { $env:JWT_SECRET=$signingSecret }
            if ($role -eq 'ORDER') { $env:AI_LLM_API_KEY=$aiSecret }
            if ($role -eq 'PAYMENT') { foreach ($key in $paymentEnvironment.Keys) { [Environment]::SetEnvironmentVariable($key,$paymentEnvironment[$key],'Process') } }
        }
        if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) { throw "Port $port is already in use." }
        $jar=Join-Path $projectRoot "$module/target/$module-1.0.0-SNAPSHOT.jar"
        if (!(Test-Path -LiteralPath $jar)) { throw "Build missing module first: $module" }
        $launchArgs=@('-Dfile.encoding=UTF-8','-Duser.timezone=Asia/Shanghai','-Xms64m','-Xmx384m','-jar',('"'+$jar+'"'))
        if ($Nacos) { $launchArgs+='--spring.profiles.active=nacos' }
        $app=Start-Process -FilePath (Get-Command java).Source -ArgumentList $launchArgs -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runDir "$module.out.log") -RedirectStandardError (Join-Path $runDir "$module.err.log")
        $processes+=[pscustomobject]@{Id=$app.Id;StartTime=$app.StartTime.ToUniversalTime().Ticks;Module=$module}
        ConvertTo-Json -InputObject @($processes) | Set-Content -LiteralPath $pidFile -Encoding UTF8
        $ready=$false
        for ($attempt=0;$attempt -lt 90;$attempt++) { if ($app.HasExited) { throw "$module exited; inspect .run logs." };try { $health=Invoke-RestMethod "http://127.0.0.1:$port/actuator/health" -TimeoutSec 2;if ($health.status -eq 'UP') { $ready=$true;break } } catch {};Start-Sleep -Seconds 1 }
        if (!$ready) { throw "$module did not become healthy; inspect .run logs." }
        Write-Host "$module ready on $port"
    }
    Write-Host 'Ten backend processes ready. Start Vue with npm run dev --prefix smarthotel-frontend.'
} catch { if ($processes.Count -gt 0) { & (Join-Path $PSScriptRoot 'Stop-Cloud.ps1') };throw }
