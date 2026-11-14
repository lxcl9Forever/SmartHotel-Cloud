param([ValidateSet('Rehearse','Migrate','ExportRollback')][string]$Action='Rehearse',[string]$DependencyHost='192.168.229.131',[ValidateRange(1,65535)][int]$Port=13306,[string]$JdbcJar)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
if (Test-Path -LiteralPath (Join-Path $projectRoot '.run/processes.json')) { throw 'Stop project writers before full domain migration.' }
if (!$JdbcJar) { $JdbcJar=Join-Path $projectRoot '.run/tools/mysql-connector-j.jar' }
if (!(Test-Path -LiteralPath $JdbcJar)) { throw 'Supply an existing MySQL JDBC jar.' }
$classes=Join-Path $projectRoot '.run/tools/full-domain-migration'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
& (Get-Command javac).Source '-encoding' 'UTF-8' '-d' $classes (Join-Path $PSScriptRoot 'DbSplit.java') (Join-Path $PSScriptRoot 'FullDomainSplit.java') (Join-Path $PSScriptRoot 'FullDomainRollback.java')
if ($LASTEXITCODE -ne 0) { throw 'Full domain migration compilation failed.' }
& (Get-Command java).Source '--class-path' ($classes+';'+$JdbcJar) FullDomainSplit $projectRoot $DependencyHost $Port $Action.ToLowerInvariant()
if ($LASTEXITCODE -ne 0) { throw 'Full domain migration failed; inspect private manifest before retrying. Do not delete existing data.' }
