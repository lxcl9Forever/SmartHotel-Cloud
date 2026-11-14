param([switch]$AsJson)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'Import-CloudEnv.ps1')
$ports=[ordered]@{gateway=8080;user=8081;order=8082;hotel=8083;inventory=8084;review=8085;marketing=8086;payment=8087;stay=8089;statistics=8090}
$results=@()
foreach($name in $ports.Keys){
    $row=[ordered]@{Service=$name;Port=$ports[$name];Health='UNAVAILABLE';EventsPending=$null;EventsDead=$null;WorkflowsPending=$null;OrderClaims=$null;MarketingPending=$null}
    try{
        $health=Invoke-RestMethod ('http://127.0.0.1:'+$ports[$name]+'/actuator/health') -TimeoutSec 3
        $row.Health=$health.status
        if($name -in @('order','review','marketing','payment','stay','statistics')){
            $domain=Invoke-RestMethod ('http://127.0.0.1:'+$ports[$name]+'/internal/v1/domain-events/health') -Headers @{'X-Internal-Token'=$env:INTERNAL_TOKEN} -TimeoutSec 3
            $row.EventsPending=$domain.pending;$row.EventsDead=$domain.dead;$row.WorkflowsPending=$domain.workflowPending
            if($name -eq 'order'){$row.OrderClaims=$domain.authoritativeActive;$row.MarketingPending=$domain.marketingPending}
        }
    }catch{$row.Health='UNAVAILABLE'}
    $results+=[pscustomobject]$row
}
if($AsJson){ConvertTo-Json -InputObject $results}else{$results | Format-Table -AutoSize}
