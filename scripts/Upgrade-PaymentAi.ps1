param([string]$DependencyHost='192.168.229.131',[ValidateRange(1,65535)][int]$Port=13306,[Parameter(Mandatory=$true)][string]$JdbcJar)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
if(Test-Path (Join-Path $root '.run/processes.json')){throw 'Stop Java services before payment metadata upgrade.'}
$classes=Join-Path $root '.run/tools/payment-upgrade';New-Item -ItemType Directory -Force $classes | Out-Null
& (Get-Command javac).Source -encoding UTF-8 -d $classes (Join-Path $PSScriptRoot 'DbSplit.java') (Join-Path $PSScriptRoot 'FullDomainSplit.java') (Join-Path $PSScriptRoot 'FullDomainRollback.java') (Join-Path $PSScriptRoot 'PaymentFeatureUpgrade.java')
if($LASTEXITCODE -ne 0){throw 'Payment upgrade compilation failed'}
& (Get-Command java).Source -cp ($classes+';'+$JdbcJar) PaymentFeatureUpgrade $root $DependencyHost $Port
if($LASTEXITCODE -ne 0){throw 'Payment upgrade failed; inspect private manifest and retain existing data.'}
