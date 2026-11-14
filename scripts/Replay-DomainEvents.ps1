param([Parameter(Mandatory=$true)][ValidateSet('order','review','marketing','payment','stay','statistics')][string]$Domain)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'Import-CloudEnv.ps1')
$ports=@{order=8082;review=8085;marketing=8086;payment=8087;stay=8089;statistics=8090}
Invoke-RestMethod ('http://127.0.0.1:'+$ports[$Domain]+'/internal/v1/domain-events/replay') -Method Post -Headers @{'X-Internal-Token'=$env:INTERNAL_TOKEN} -TimeoutSec 5 | Out-Null
Write-Host 'Dead events returned to the retry queue. Run Get-CloudStatus.ps1 to verify delivery; replay does not fabricate business success.'
