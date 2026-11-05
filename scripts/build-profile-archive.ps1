[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$ApkPath,
    [string]$OutputPath = '.local\output\reddit-archive-unsigned.apk',
    [switch]$Probe
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
Push-Location $repo
try {
    if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr' }
    if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
    $env:GRADLE_USER_HOME = Join-Path $repo '.local\gradle'
    $java = Join-Path $env:JAVA_HOME 'bin\java.exe'
    if (-not (Test-Path $java)) { throw 'Set JAVA_HOME to JDK 21.' }
    $apk = (Resolve-Path -LiteralPath $ApkPath).Path
    $output = if ([IO.Path]::IsPathRooted($OutputPath)) {
        [IO.Path]::GetFullPath($OutputPath)
    } else { [IO.Path]::GetFullPath((Join-Path $repo $OutputPath)) }
    & (Join-Path $PSScriptRoot 'bootstrap-profile-archive.ps1')
    $probeArgument = @()
    if ($Probe) {
        # Resolve Kotlin before compiling the isolated instrumentation fixture.
        & $java -jar gradle\wrapper\gradle-wrapper.jar :extensions:reddit:compileReleaseJavaWithJavac --console=plain --max-workers=4
        if ($LASTEXITCODE) { throw 'Extension compilation failed.' }
        $kotlin = Get-ChildItem (Join-Path $env:GRADLE_USER_HOME 'caches\modules-2\files-2.1\org.jetbrains.kotlin\kotlin-stdlib') -Filter '*.jar' -Recurse |
            Sort-Object FullName | Select-Object -Last 1 -ExpandProperty FullName
        $androidJar = Join-Path $env:ANDROID_HOME 'platforms\android-36\android.jar'
        $probeClasses = [IO.Path]::GetFullPath((Join-Path $repo '.local\probe\classes'))
        $probeRoot = [IO.Path]::GetFullPath((Join-Path $repo '.local\probe')) + [IO.Path]::DirectorySeparatorChar
        if (-not $probeClasses.StartsWith($probeRoot, [StringComparison]::OrdinalIgnoreCase)) { throw 'Probe output escaped workspace.' }
        if (Test-Path -LiteralPath $probeClasses) { Remove-Item -LiteralPath $probeClasses -Recurse -Force }
        New-Item -ItemType Directory -Force $probeClasses, .local\probe\dex | Out-Null
        & "$env:JAVA_HOME\bin\javac.exe" -source 17 -target 17 -cp "$androidJar;$kotlin;extensions/reddit/build/intermediates/javac/release/compileReleaseJavaWithJavac/classes;extensions/shared/library/build/intermediates/javac/release/compileReleaseJavaWithJavac/classes;.local/dependencies/morphe-patches-library/extension-library/build/intermediates/javac/release/compileReleaseJavaWithJavac/classes" -d .local\probe\classes @((Get-ChildItem tests/android -Recurse -Filter *.java).FullName)
        if ($LASTEXITCODE) { throw 'Probe compilation failed.' }
        & "$env:JAVA_HOME\bin\jar.exe" cf .local\probe\probe.jar -C .local\probe\classes .
        if ($LASTEXITCODE) { throw 'Probe packaging failed.' }
        & "$env:ANDROID_HOME\build-tools\36.0.0\d8.bat" --min-api 29 --lib $androidJar --classpath $kotlin --output .local\probe\dex .local\probe\probe.jar
        if ($LASTEXITCODE) { throw 'Probe DEX compilation failed.' }
        $probeArgument = @('-PredditProbeDex=.local/probe/dex/classes.dex')
    }
    & $java -jar gradle\wrapper\gradle-wrapper.jar :patches:validateProfileArchive "-PredditApk=$apk" "-PredditOutput=$output" @probeArgument --console=plain --max-workers=4
    if ($LASTEXITCODE) { throw 'APK patching or verification failed.' }
    Write-Host "Unsigned APK: $output"
} finally { Pop-Location }
