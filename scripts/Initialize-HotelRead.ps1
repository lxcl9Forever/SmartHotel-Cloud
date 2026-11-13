param([string]$DependencyHost='192.168.229.131',[int]$Port=13306,[string]$JdbcJar)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
if (!$JdbcJar) { $JdbcJar=Join-Path $projectRoot '.run/tools/mysql-connector-j.jar' }
& (Get-Command java).Source '--class-path' $JdbcJar (Join-Path $PSScriptRoot 'ProvisionHotel.java') $projectRoot $DependencyHost $Port
if ($LASTEXITCODE -ne 0) { throw 'Hotel reader provisioning failed; do not change existing credentials blindly.' }
