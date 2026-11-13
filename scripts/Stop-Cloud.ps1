$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$pidFile = Join-Path $projectRoot '.run/processes.json'
if (!(Test-Path -LiteralPath $pidFile)) { Write-Host 'No project process record exists.'; return }
# Windows PowerShell 5.1 may emit the JSON array as one pipeline object.
# Assign first, then enumerate explicitly so each iteration is one process record.
$parsed = ConvertFrom-Json -InputObject (Get-Content -Raw -LiteralPath $pidFile)
$items = @()
foreach ($entry in $parsed) { $items += $entry }
[array]::Reverse($items)
foreach ($item in $items) {
    $proc = Get-Process -Id $item.Id -ErrorAction SilentlyContinue
    if ($null -eq $proc) { continue }
    # Match creation time as well as PID; never stop a process that reused an old PID.
    $expectedProcessName = if ($item.Module -eq 'smarthotel-frontend') { 'node' } else { 'java' }
    if ($proc.ProcessName -eq $expectedProcessName -and $proc.StartTime.ToUniversalTime().Ticks -eq [long]$item.StartTime) {
        Stop-Process -Id $proc.Id
        Write-Host "Stopped $($item.Module)"
    } else { throw "PID $($item.Id) no longer belongs to this run; refusing to stop it." }
}
Remove-Item -LiteralPath $pidFile
Write-Host 'Application processes stopped. Docker data is retained. Use docker compose stop mysql redis to stop dependencies.'
