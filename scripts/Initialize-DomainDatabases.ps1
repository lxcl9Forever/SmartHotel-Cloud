param(
    [string]$DependencyHost='127.0.0.1',
    [ValidateRange(1,65535)][int]$Port=13306,
    [Parameter(Mandatory=$true)][string]$JdbcJar
)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
if (!(Test-Path -LiteralPath (Join-Path $projectRoot '.env'))) { throw 'Initialize private .env first.' }
if (Select-String -LiteralPath (Join-Path $projectRoot '.env') -Pattern '^DB_LAYOUT=' -Quiet) {
    throw 'A database layout already exists. Do not repeat initialization; inspect its private migration manifests.'
}
$shared=@{DependencyHost=$DependencyHost;Port=$Port;JdbcJar=$JdbcJar}
& (Join-Path $PSScriptRoot 'Migrate-Databases.ps1') @shared -Action Rehearse
& (Join-Path $PSScriptRoot 'Migrate-Databases.ps1') @shared -Action Migrate
foreach ($scriptName in @('Initialize-HotelRead.ps1','Upgrade-Inventory.ps1','Upgrade-InventoryRemote.ps1','Upgrade-HotelWrite.ps1','Upgrade-HotelCatalog.ps1','Split-HotelDatabase.ps1','Split-InventoryDatabase.ps1')) {
    Write-Host ('Initializing: '+$scriptName)
    & (Join-Path $PSScriptRoot $scriptName) @shared
}
& (Join-Path $PSScriptRoot 'Split-FullDomains.ps1') @shared -Action Rehearse
& (Join-Path $PSScriptRoot 'Split-FullDomains.ps1') @shared -Action Migrate
& (Join-Path $PSScriptRoot 'Upgrade-PaymentAi.ps1') @shared
Write-Host 'Nine domain databases initialized. Preserve private backups before starting applications.'
