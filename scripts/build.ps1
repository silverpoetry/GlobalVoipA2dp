param(
    [string]$JavaHome = ''
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot

if (-not [string]::IsNullOrWhiteSpace($JavaHome)) {
    if (-not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin\java.exe'))) {
        throw "JDK 17 not found: $JavaHome"
    }
    $env:JAVA_HOME = $JavaHome
    $env:Path = "$JavaHome\bin;$env:Path"
}

& java -version
if ($LASTEXITCODE -ne 0) {
    throw 'A working JDK 17 is required on PATH or through -JavaHome.'
}

Push-Location $projectRoot
try {
    & .\gradlew.bat clean testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease --stacktrace
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle build failed with exit code $LASTEXITCODE"
    }
} finally {
    Pop-Location
}
