param([string]$DependencyHost='192.168.229.131',[int]$Port=13306,[string]$JdbcJar)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
if (Test-Path -LiteralPath (Join-Path $projectRoot '.run/processes.json')) { throw 'Run Stop-Cloud.ps1 before database upgrade.' }
if (!$JdbcJar) { $JdbcJar=Join-Path $projectRoot '.run/tools/mysql-connector-j.jar' }
$classes=Join-Path $projectRoot '.run/tools/inventory-remote-migration'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
& (Get-Command javac).Source '-encoding' 'UTF-8' '-d' $classes (Join-Path $PSScriptRoot 'DbSplit.java') (Join-Path $PSScriptRoot 'InventoryRemoteUpgrade.java')
if ($LASTEXITCODE -ne 0) { throw 'Migration tool compilation failed.' }
& (Get-Command java).Source '--class-path' ($classes+';'+$JdbcJar) InventoryRemoteUpgrade $projectRoot $DependencyHost $Port
if ($LASTEXITCODE -ne 0) { throw 'Inventory upgrade failed; inspect private manifest before retrying.' }
