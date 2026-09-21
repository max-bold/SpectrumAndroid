param([string[]]$Tasks = @('assembleDebug', 'testDebugUnitTest'))
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$localJava = Get-ChildItem -LiteralPath "$projectRoot/.tools/java" -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
if ($localJava) { $env:JAVA_HOME = $localJava.FullName }
if (Test-Path -LiteralPath "$projectRoot/.tools/android-sdk") {
    $sdkPath = "$projectRoot/.tools/android-sdk".Replace('\', '/').Replace(':', '\:')
    [System.IO.File]::WriteAllText("$projectRoot/android/local.properties", "sdk.dir=$sdkPath`n")
}
Push-Location "$projectRoot/android"
try { & ./gradlew.bat @Tasks; if ($LASTEXITCODE -ne 0) { throw "Gradle failed: $LASTEXITCODE" } }
finally { Pop-Location }
