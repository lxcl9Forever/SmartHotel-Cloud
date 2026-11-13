$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$envFile = Join-Path $projectRoot '.env'
if (Test-Path -LiteralPath $envFile) { throw '.env already exists. Existing secrets will not be overwritten.' }
function New-Secret { $bytes = New-Object byte[] 36; $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create(); try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }; [Convert]::ToBase64String($bytes) }
$lines = @(
    ('MYSQL_PASSWORD=' + (New-Secret))
    ('MYSQL_ROOT_PASSWORD=' + (New-Secret))
    ('REDIS_PASSWORD=' + (New-Secret))
    ('JWT_SECRET=' + (New-Secret))
    ('INTERNAL_TOKEN=' + (New-Secret))
)
[System.IO.File]::WriteAllLines($envFile, $lines, (New-Object System.Text.UTF8Encoding($false)))
Write-Host 'Created .env with independent random development secrets. Do not commit this file.'
