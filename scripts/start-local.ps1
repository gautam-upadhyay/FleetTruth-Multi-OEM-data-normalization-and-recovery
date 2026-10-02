param([switch]$SkipBuild, [int]$ApiPort=8080, [int]$WebPort=5173)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$runtimePath = Join-Path $projectRoot '.runtime'
New-Item -ItemType Directory -Path $runtimePath -Force | Out-Null
function Find-FreePort([int]$Preferred) {
    $occupied = [System.Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners().Port
    while ($occupied -contains $Preferred) { $Preferred++ }
    return $Preferred
}
$ApiPort = Find-FreePort $ApiPort
$WebPort = Find-FreePort $WebPort
if (-not $SkipBuild) {
    Push-Location (Join-Path $projectRoot 'api')
    try { & mvn.cmd "-Dmaven.repo.local=$projectRoot/.cache/m2" -q package; if ($LASTEXITCODE -ne 0) { throw 'Backend build failed' } } finally { Pop-Location }
    Push-Location (Join-Path $projectRoot 'web')
    try { & npm.cmd ci --no-fund; if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency installation failed' }; & npm.cmd run build; if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed' } } finally { Pop-Location }
}
$jarPath = Join-Path $projectRoot 'api/target/fleettruth-api-1.0.0.jar'
if (-not (Test-Path -LiteralPath $jarPath)) { throw 'Build the backend first or run without -SkipBuild.' }
$runtimeJar=Join-Path $runtimePath "fleettruth-$([DateTime]::UtcNow.Ticks).jar"
Copy-Item -LiteralPath $jarPath -Destination $runtimeJar
$jarPath=$runtimeJar
$javaPath = (Get-Command java.exe).Source
$nodePath = (Get-Command node.exe).Source
$apiProcess = Start-Process -FilePath $javaPath -ArgumentList @('-Xmx768m','-jar',"`"$jarPath`"","--server.port=$ApiPort",'--management.health.redis.enabled=false') -WorkingDirectory (Join-Path $projectRoot 'api') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $runtimePath 'api.log') -RedirectStandardError (Join-Path $runtimePath 'api-error.log') -PassThru
$env:VITE_API_TARGET = "http://127.0.0.1:$ApiPort"
$vitePath = Join-Path $projectRoot 'web/node_modules/vite/bin/vite.js'
$webProcess = Start-Process -FilePath $nodePath -ArgumentList @("`"$vitePath`"",'--host','127.0.0.1','--port',"$WebPort",'--strictPort') -WorkingDirectory (Join-Path $projectRoot 'web') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $runtimePath 'web.log') -RedirectStandardError (Join-Path $runtimePath 'web-error.log') -PassThru
$state = @{ apiPid=$apiProcess.Id; webPid=$webProcess.Id; apiUrl="http://127.0.0.1:$ApiPort"; webUrl="http://127.0.0.1:$WebPort"; startedAt=(Get-Date).ToString('o') }
$state | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $runtimePath 'servers.json')
$state | ConvertTo-Json
