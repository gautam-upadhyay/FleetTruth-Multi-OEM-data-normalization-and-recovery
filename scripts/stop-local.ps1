$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$statePath=Join-Path $projectRoot '.runtime/servers.json'
if (-not (Test-Path -LiteralPath $statePath)) { Write-Output 'No recorded local processes.'; exit }
$state=Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
foreach ($processId in @($state.apiPid,$state.webPid)) {
    $process=Get-CimInstance Win32_Process -Filter "ProcessId=$processId" -ErrorAction SilentlyContinue
    if ($process -and $process.CommandLine -and $process.CommandLine.Contains($projectRoot)) { Stop-Process -Id $processId -ErrorAction SilentlyContinue }
}
Write-Output 'Stopped the recorded FleetTruth processes whose command line matched this project.'
