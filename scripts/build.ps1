param([string[]]$Tasks = @('assembleDebug', 'testDebugUnitTest', 'lintDebug'))
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$previousJava = $env:JAVA_HOME
$previousDebug = $env:DEBUG
try {
    $env:DEBUG = ''
    if (-not $env:JAVA_HOME) {
        $siblingJdk = Join-Path (Split-Path -Parent $projectRoot) 'POLTERHEIST\.cache\tv_control\android\tools\jdk'
        $jdk = Get-ChildItem -LiteralPath $siblingJdk -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($jdk) { $env:JAVA_HOME = $jdk.FullName }
    }
    if (-not $env:JAVA_HOME -and -not (Get-Command java -ErrorAction SilentlyContinue)) {
        throw 'Instala JDK 17 y configura JAVA_HOME, o abre el proyecto en Android Studio.'
    }
    Push-Location $projectRoot
    try {
        & .\gradlew.bat @Tasks --console=plain
        if ($LASTEXITCODE -ne 0) { throw "Gradle termino con codigo $LASTEXITCODE" }
    } finally { Pop-Location }
} finally { $env:JAVA_HOME = $previousJava; $env:DEBUG = $previousDebug }
