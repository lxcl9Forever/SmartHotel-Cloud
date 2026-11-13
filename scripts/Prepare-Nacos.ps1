param([Parameter(Mandatory=$true)][string]$VmAddress)
$ErrorActionPreference = 'Stop'
$address = $null
if (![System.Net.IPAddress]::TryParse($VmAddress, [ref]$address) -or
    $address.AddressFamily -ne [System.Net.Sockets.AddressFamily]::InterNetwork -or
    [System.Net.IPAddress]::IsLoopback($address) -or $VmAddress -eq '0.0.0.0') {
    throw 'VmAddress must be the reachable IPv4 address of the Ubuntu VM.'
}
$projectRoot = Split-Path $PSScriptRoot -Parent
$runDir = Join-Path $projectRoot '.run'
New-Item -ItemType Directory -Path $runDir -Force | Out-Null
$serverEnv = Join-Path $runDir 'nacos-server.env'
function New-NacosSecret {
    $bytes = New-Object byte[] 36
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return [Convert]::ToBase64String($bytes)
}
if (!(Test-Path -LiteralPath $serverEnv)) {
    $lines = @(
        ('NACOS_AUTH_TOKEN=' + (New-NacosSecret))
        ('NACOS_AUTH_IDENTITY_KEY=smarthotel-cloud-node')
        ('NACOS_AUTH_IDENTITY_VALUE=' + (New-NacosSecret))
    )
    [System.IO.File]::WriteAllLines($serverEnv, $lines, (New-Object System.Text.UTF8Encoding($false)))
}
$saved = [System.IO.File]::ReadAllLines($serverEnv)
$values = @{}
foreach ($line in $saved) {
    if ($line -match '^([A-Z][A-Z0-9_]*)=(.*)$') { $values[$Matches[1]] = $Matches[2] }
}
foreach ($key in @('NACOS_AUTH_TOKEN','NACOS_AUTH_IDENTITY_KEY','NACOS_AUTH_IDENTITY_VALUE')) {
    if (!$values.ContainsKey($key) -or [string]::IsNullOrWhiteSpace($values[$key])) {
        throw "Missing $key in .run/nacos-server.env. Existing keys will not be replaced."
    }
}
if ([Convert]::FromBase64String($values['NACOS_AUTH_TOKEN']).Length -lt 32) { throw 'Nacos token secret must contain at least 32 decoded bytes.' }
$bundle = Join-Path $runDir ('nacos-package-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $bundle | Out-Null
Copy-Item -LiteralPath (Join-Path $projectRoot 'deploy/compose.nacos.yml') -Destination (Join-Path $bundle 'compose.yml')
$lines = @('NACOS_BIND_ADDRESS=' + $VmAddress)
foreach ($key in @('NACOS_AUTH_TOKEN','NACOS_AUTH_IDENTITY_KEY','NACOS_AUTH_IDENTITY_VALUE')) { $lines += $key + '=' + $values[$key] }
[System.IO.File]::WriteAllLines((Join-Path $bundle '.env'), $lines, (New-Object System.Text.UTF8Encoding($false)))
Add-Type -AssemblyName System.IO.Compression.FileSystem
$tempZip = Join-Path $runDir ('nacos-' + [Guid]::NewGuid().ToString('N') + '.zip')
[System.IO.Compression.ZipFile]::CreateFromDirectory($bundle, $tempZip)
$archive = Join-Path $runDir 'smarthotel-cloud-nacos.zip'
Move-Item -LiteralPath $tempZip -Destination $archive -Force
Write-Host "Prepared: $archive"
Write-Host "Console: http://${VmAddress}:8088/index.html"
Write-Host 'Initialize the nacos administrator password in the console. Do not publish this archive.'
