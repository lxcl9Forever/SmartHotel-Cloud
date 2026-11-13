param(
    [Parameter(Mandatory=$true)]
    [string]$VmAddress
)
$ErrorActionPreference = 'Stop'
$address = $null
if (![System.Net.IPAddress]::TryParse($VmAddress, [ref]$address) -or
    $address.AddressFamily -ne [System.Net.Sockets.AddressFamily]::InterNetwork -or
    [System.Net.IPAddress]::IsLoopback($address) -or $VmAddress -eq '0.0.0.0') {
    throw 'VmAddress must be the reachable IPv4 address of your Ubuntu VM.'
}
$projectRoot = Split-Path $PSScriptRoot -Parent
if (!(Test-Path -LiteralPath (Join-Path $projectRoot '.env'))) {
    & (Join-Path $PSScriptRoot 'Initialize-LocalEnv.ps1')
}
$values = @{}
foreach ($line in [System.IO.File]::ReadAllLines((Join-Path $projectRoot '.env'))) {
    if ($line -match '^([A-Z][A-Z0-9_]*)=(.*)$') { $values[$Matches[1]] = $Matches[2] }
}
$keys = @('MYSQL_PASSWORD','MYSQL_ROOT_PASSWORD','REDIS_PASSWORD')
foreach ($key in $keys) {
    if (!$values.ContainsKey($key) -or [string]::IsNullOrWhiteSpace($values[$key]) -or
        $values[$key].StartsWith('replace-with')) { throw "Set $key in .env before packaging." }
}
# A fresh directory prevents stale scripts/SQL from entering a rebuilt archive.
$runDir = Join-Path $projectRoot '.run'
New-Item -ItemType Directory -Path $runDir -Force | Out-Null
$bundleDir = Join-Path $runDir ('ubuntu-dependencies-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $bundleDir | Out-Null
Copy-Item -LiteralPath (Join-Path $projectRoot 'deploy/compose.ubuntu.yml') -Destination (Join-Path $bundleDir 'compose.yml')
Copy-Item -LiteralPath (Join-Path $projectRoot 'database') -Destination $bundleDir -Recurse
$lines = @('DEPENDENCY_BIND_ADDRESS=' + $VmAddress)
foreach ($key in $keys) { $lines += $key + '=' + $values[$key] }
[System.IO.File]::WriteAllLines((Join-Path $bundleDir '.env'), $lines, (New-Object System.Text.UTF8Encoding($false)))
$archive = Join-Path $runDir 'smarthotel-cloud-ubuntu.zip'
# ZipFile includes the hidden .env; no JWT or internal-service key is sent to Ubuntu.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$temporaryArchive = Join-Path $runDir ('ubuntu-' + [Guid]::NewGuid().ToString('N') + '.zip')
[System.IO.Compression.ZipFile]::CreateFromDirectory($bundleDir, $temporaryArchive)
Move-Item -LiteralPath $temporaryArchive -Destination $archive -Force
Write-Host "Prepared: $archive"
Write-Host "Dependencies will bind to ${VmAddress}:13306 and ${VmAddress}:16379."
Write-Host 'The archive contains database credentials. Do not upload it to GitHub.'
