param([switch]$SkipTests)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if ($SkipTests) { & (Join-Path $PSScriptRoot 'build.ps1') -Tasks @('assembleDebug') }
else { & (Join-Path $PSScriptRoot 'build.ps1') }
if (-not $SkipTests -and (Get-Command node -ErrorAction SilentlyContinue)) {
    & node (Join-Path $PSScriptRoot 'test-receiver.cjs')
    if ($LASTEXITCODE -ne 0) { throw 'Fallo la validacion del receptor de navegador.' }
}
$artifactDirectory = Join-Path $projectRoot 'artifacts'
New-Item -ItemType Directory -Force -Path $artifactDirectory | Out-Null
$metadata = Get-Content -LiteralPath (Join-Path $projectRoot 'app\build\outputs\apk\debug\output-metadata.json') -Raw | ConvertFrom-Json
$version = $metadata.elements[0].versionName
if ($version -notmatch '^\d+\.\d+\.\d+$') { throw 'Version de APK inesperada.' }
$apk = Join-Path $artifactDirectory "POLTERHEIST-Mobile-$version-debug.apk"
Copy-Item -LiteralPath (Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk') -Destination $apk -Force
$hash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
[System.IO.File]::WriteAllText(($apk + '.sha256'), ($hash + '  ' + (Split-Path -Leaf $apk) + "`n"), [System.Text.Encoding]::ASCII)
Write-Output "APK: $apk"
Write-Output "SHA-256: $hash"
