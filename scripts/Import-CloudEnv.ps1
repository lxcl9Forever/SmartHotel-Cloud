$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$envFile = Join-Path $projectRoot '.env'
if (!(Test-Path -LiteralPath $envFile)) { throw 'Run scripts/Initialize-LocalEnv.ps1 first.' }
foreach ($line in [System.IO.File]::ReadAllLines($envFile)) {
    if ($line.Trim().Length -eq 0 -or $line.TrimStart().StartsWith('#')) { continue }
    if ($line -notmatch '^([A-Z][A-Z0-9_]*)=(.*)$') { throw 'Invalid .env syntax: expected NAME=value.' }
    $envName = $Matches[1]; $envValue = $Matches[2]
    if ($envValue.StartsWith('replace-with')) { throw "Set $envName before starting." }
    [Environment]::SetEnvironmentVariable($envName, $envValue, 'Process')
}
