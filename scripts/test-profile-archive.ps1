$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
Push-Location $repo
try {
    if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr' }
    New-Item -ItemType Directory -Force .local\tests | Out-Null
    $json = Get-ChildItem .local\gradle\caches\modules-2\files-2.1\org.json\json\20231013 -Filter '*.jar' -Recurse | Select-Object -First 1 -ExpandProperty FullName
    if (-not $json) { throw 'Build the patch first to resolve the pinned JSON test dependency.' }
    $core = 'extensions\reddit\src\main\java\app\morphe\extension\reddit\patches\archive'
    & "$env:JAVA_HOME\bin\javac.exe" --release 17 -cp $json -d .local\tests "$core\ArchiveEligibility.java" "$core\ArchiveItem.java" "$core\ArchivePostDetails.java" "$core\ArchiveDisplay.java" "$core\ArchiveClient.java" "$core\ArchivePager.java" "$core\ArchiveSnapshot.java" "$core\ArchiveNativeItem.java" "$core\ArchiveMerge.java" tests\profile-archive\ArchiveEligibilityTest.java tests\profile-archive\ArchiveCoreTest.java tests\profile-archive\ArchiveSnapshotTest.java tests\profile-archive\ArchiveMergeTest.java
    if ($LASTEXITCODE) { throw 'Test compilation failed' }
    & "$env:JAVA_HOME\bin\java.exe" -cp .local\tests app.morphe.extension.reddit.patches.archive.ArchiveEligibilityTest
    if ($LASTEXITCODE) { throw 'Eligibility tests failed' }
    & "$env:JAVA_HOME\bin\java.exe" -cp ".local\tests;$json" app.morphe.extension.reddit.patches.archive.ArchiveCoreTest
    if ($LASTEXITCODE) { throw 'Archive core tests failed' }
    & "$env:JAVA_HOME\bin\java.exe" -cp ".local\tests;$json" app.morphe.extension.reddit.patches.archive.ArchiveSnapshotTest
    if ($LASTEXITCODE) { throw 'Archive native-item tests failed' }
    & "$env:JAVA_HOME\bin\java.exe" -cp ".local\tests;$json" app.morphe.extension.reddit.patches.archive.ArchiveMergeTest
    if ($LASTEXITCODE) { throw 'Archive merge tests failed' }
} finally { Pop-Location }
