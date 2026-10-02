param([switch]$Monitoring,[string]$ApiUrl='http://127.0.0.1:8082')
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
if($Monitoring){
    $login=Invoke-RestMethod "$ApiUrl/api/auth/login" -Method Post -ContentType 'application/json' -Body (@{email='admin@fleettruth.demo';password='FleetTruth2026!'}|ConvertTo-Json)
    New-Item -ItemType Directory -Force -Path (Join-Path $root '.runtime') | Out-Null
    [System.IO.File]::WriteAllText((Join-Path $root '.runtime/prometheus-token'),$login.token)
    Write-Output 'Monitoring token created. Local-demo token expires after four hours.'
}else{
    $path=Join-Path $root '.env'
    if(Test-Path -LiteralPath $path){throw '.env already exists; keep its existing credentials.'}
    function New-Secret { $bytes=New-Object byte[] 32; $rng=[Security.Cryptography.RandomNumberGenerator]::Create(); $rng.GetBytes($bytes); $rng.Dispose(); return [Convert]::ToBase64String($bytes) }
    [System.IO.File]::WriteAllLines($path,@("DATABASE_PASSWORD=$(New-Secret)","JWT_SECRET=$(New-Secret)","GRAFANA_PASSWORD=$(New-Secret)"))
    Write-Output 'Generated .env with local-only credentials. It is excluded from version control.'
}
