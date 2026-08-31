# Builds the debug APK and installs it on a connected/emulated device over adb.
# Uses a bundled JDK 11 instead of Android Studio's JBR: this machine's newer JDKs
# (17/21) hit a broken AF_UNIX loopback socket in java.nio.Selector on Windows here,
# which crashes the Gradle daemon on startup. JDK 11 predates that code path.
$env:JAVA_HOME = "C:\Users\Suma K\AndroidStudioProjects\jdk11\jdk-11.0.32+9"
$env:ANDROID_HOME = "C:/Users/Suma K/AppData/Local/Android/Sdk"
$env:JAVA_OPTS = "-Djava.net.preferIPv4Stack=true"

Set-Location $PSScriptRoot
.\gradlew.bat assembleDebug --console=plain
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$adb = "$env:ANDROID_HOME/platform-tools/adb.exe"
$apk = "app\build\outputs\apk\debug\app-debug.apk"
& $adb install -r $apk
