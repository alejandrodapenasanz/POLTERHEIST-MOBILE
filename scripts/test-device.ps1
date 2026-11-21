param(
    [Parameter(Mandatory=$true)][string]$Serial,
    [string]$CastTarget = '',
    [string]$TestMethod = '',
    [ValidateSet('both','image','video')][string]$CastMedia = 'both',
    [ValidateRange(3,90)][int]$HoldSeconds = 10
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$adb = Join-Path $projectRoot '.tools\android-sdk\platform-tools\adb.exe'
if (-not (Test-Path -LiteralPath $adb)) {
    $adbCommand = Get-Command adb -ErrorAction Stop
    $adb = $adbCommand.Source
}
foreach ($package in @('org.polterheist.mobile', 'org.polterheist.mobile.test')) {
    $installed = & $adb -s $Serial shell pm path $package
    if ($LASTEXITCODE -ne 0 -or -not ($installed -match '^package:')) {
        throw "Falta instalar $package en el movil. La APK de pruebas esta en app\build\outputs\apk\androidTest\debug."
    }
}
function Quote-AndroidShell([string]$Value) {
    return "'" + $Value.Replace("'", "'\''") + "'"
}
$command = 'am instrument -w -r -e exportMedia true'
$command += ' -e castMedia ' + $CastMedia + ' -e holdSeconds ' + $HoldSeconds
if ($CastTarget) { $command += ' -e castTarget ' + (Quote-AndroidShell $CastTarget) }
if ($TestMethod) {
    if ($TestMethod -notmatch '^[A-Za-z][A-Za-z0-9_]*$') { throw 'Nombre de prueba no valido.' }
    $command += ' -e class ' + (Quote-AndroidShell ('org.polterheist.mobile.DeviceValidationTest#' + $TestMethod))
}
$command += ' org.polterheist.mobile.test/androidx.test.runner.AndroidJUnitRunner'
$outputDirectory = Join-Path $projectRoot 'artifacts\device-validation'
New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
$result = & $adb -s $Serial shell $command 2>&1 | Tee-Object -FilePath (Join-Path $outputDirectory 'instrumentation-latest.txt')
$result | Write-Output
if ($LASTEXITCODE -ne 0 -or (($result -join "`n") -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|shortMsg=')) {
    throw 'Han fallado pruebas del dispositivo. Consulta artifacts\device-validation\instrumentation-latest.txt.'
}
