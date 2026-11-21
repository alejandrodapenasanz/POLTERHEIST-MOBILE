$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
& (Join-Path $PSScriptRoot 'build.ps1')
if (Get-Command node -ErrorAction SilentlyContinue) {
    & node (Join-Path $PSScriptRoot 'test-receiver.cjs')
    if ($LASTEXITCODE -ne 0) { throw 'Fallo la validacion del receptor de navegador.' }
}
$artifactDirectory = Join-Path $projectRoot 'artifacts'
New-Item -ItemType Directory -Force -Path $artifactDirectory | Out-Null
$apk = Join-Path $artifactDirectory 'POLTERHEIST-Mobile-0.1.0-debug.apk'
Copy-Item -LiteralPath (Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk') -Destination $apk -Force
$hash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
[System.IO.File]::WriteAllText(($apk + '.sha256'), ($hash + '  ' + (Split-Path -Leaf $apk) + "`n"), [System.Text.Encoding]::ASCII)
Write-Output "APK: $apk"
Write-Output "SHA-256: $hash"
