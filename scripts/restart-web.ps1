$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$statePath=Join-Path $root '.runtime/servers.json'
$state=Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
$existing=Get-CimInstance Win32_Process -Filter "ProcessId=$($state.webPid)" -ErrorAction SilentlyContinue
if($existing -and $existing.CommandLine -and $existing.CommandLine.Contains($root)){Stop-Process -Id $state.webPid}
$env:VITE_API_TARGET=$state.apiUrl
$port=([uri]$state.webUrl).Port
$vite=Join-Path $root 'web/node_modules/vite/bin/vite.js'
$process=Start-Process -FilePath (Get-Command node.exe).Source -ArgumentList @("`"$vite`"",'--host','127.0.0.1','--port',"$port",'--strictPort') -WorkingDirectory (Join-Path $root 'web') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $root '.runtime/web.log') -RedirectStandardError (Join-Path $root '.runtime/web-error.log') -PassThru
$state.webPid=$process.Id
$state | ConvertTo-Json | Set-Content -LiteralPath $statePath
$state | ConvertTo-Json
