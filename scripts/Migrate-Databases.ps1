param(
    [ValidateSet('Inspect','Rehearse','Migrate','Resume','Verify','Rollback')]
    [string]$Action = 'Inspect',
    [ValidatePattern('^[a-zA-Z0-9][a-zA-Z0-9.-]*$')]
    [string]$DependencyHost = '192.168.229.131',
    [ValidateRange(1,65535)][int]$Port = 13306,
    [string]$JdbcJar
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if (!$JdbcJar) { $JdbcJar = Join-Path $projectRoot '.run/tools/mysql-connector-j.jar' }
if (!(Test-Path -LiteralPath $JdbcJar)) {
    throw 'MySQL JDBC driver not found. Supply -JdbcJar with the mysql-connector-j JAR from your Maven repository.'
}
if ($Action -in @('Rehearse','Migrate','Resume','Rollback')) {
    if (Test-Path -LiteralPath (Join-Path $projectRoot '.run/processes.json')) {
        throw 'Run scripts/Stop-Cloud.ps1 before changing databases.'
    }
}
& (Get-Command java).Source '-Dfile.encoding=UTF-8' '--class-path' $JdbcJar (Join-Path $PSScriptRoot 'DbSplit.java') $projectRoot $DependencyHost $Port $Action.ToLowerInvariant()
if ($LASTEXITCODE -ne 0) { throw 'Database operation failed. The private backup manifest records the last completed phase; do not blindly rerun a migration.' }
