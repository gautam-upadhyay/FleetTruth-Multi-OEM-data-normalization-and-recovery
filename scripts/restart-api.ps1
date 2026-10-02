param([switch]$Build)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$statePath=Join-Path $projectRoot '.runtime/servers.json'
$state=Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
$existing=Get-CimInstance Win32_Process -Filter "ProcessId=$($state.apiPid)" -ErrorAction SilentlyContinue
if ($existing -and $existing.CommandLine -and $existing.CommandLine.Contains($projectRoot)) { Stop-Process -Id $state.apiPid }
if($Build){
    Push-Location (Join-Path $projectRoot 'api')
    try { & mvn.cmd "-Dmaven.repo.local=$projectRoot/.cache/m2" -q verify; if($LASTEXITCODE -ne 0){throw 'Backend verification failed; the API was not restarted.'} } finally { Pop-Location }
}
$apiPort=([uri]$state.apiUrl).Port
$jarPath=Join-Path $projectRoot 'api/target/fleettruth-api-1.0.0.jar'
$runtimeJar=Join-Path $projectRoot ".runtime/fleettruth-$([DateTime]::UtcNow.Ticks).jar"
Copy-Item -LiteralPath $jarPath -Destination $runtimeJar
$jarPath=$runtimeJar
$process=Start-Process -FilePath (Get-Command java.exe).Source -ArgumentList @('-Xmx768m','-jar',"`"$jarPath`"","--server.port=$apiPort",'--management.health.redis.enabled=false') -WorkingDirectory (Join-Path $projectRoot 'api') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $projectRoot '.runtime/api.log') -RedirectStandardError (Join-Path $projectRoot '.runtime/api-error.log') -PassThru
$state.apiPid=$process.Id
$state | ConvertTo-Json | Set-Content -LiteralPath $statePath
$state | ConvertTo-Json
