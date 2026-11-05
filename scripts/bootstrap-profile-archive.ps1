[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$dependencies = Join-Path $repo '.local\dependencies'
New-Item -ItemType Directory -Force $dependencies | Out-Null

# Public sources at the exact revisions required by upstream v2.0.0. No account token is needed.
$pins = [ordered]@{
    'morphe-patches-gradle-plugin' = '9847822595ec8b42058461c556a54f5df83f2147'
    'morphe-patcher' = '8c7ff9f29d4cf6395fcbe42a4ceddd034d71fecf'
    'morphe-patches-library' = '42edb9710c2b63416e8eddd8096bb6a0adff75dd'
    'smali' = '4220ae75f7faf79ec6158d30dcac2e5409881cdf'
    'ARSCLib' = '10d0708212cb356d6a77eda3b2637aa2749580f0'
}
foreach ($name in $pins.Keys) {
    $path = Join-Path $dependencies $name
    if (-not (Test-Path -LiteralPath $path)) {
        git clone --filter=blob:none --no-checkout "https://github.com/MorpheApp/$name.git" $path
        if ($LASTEXITCODE) { throw "Clone failed: $name" }
        git -C $path checkout --detach $pins[$name]
        if ($LASTEXITCODE) { throw "Checkout failed: $name" }
    }
    $actual = git -C $path rev-parse HEAD
    if ($actual -ne $pins[$name]) { throw "Unexpected revision in $path. Expected $($pins[$name]); found $actual" }
}

# The upstream settings plugin assumes GitHub credentials even for source-only builds.
$plugin = Join-Path $dependencies 'morphe-patches-gradle-plugin\src\main\kotlin\app\morphe\patches\gradle\SettingsPlugin.kt'
$content = [IO.File]::ReadAllText($plugin)
$content = $content.Replace('.orElse(System.getenv("GITHUB_ACTOR")).get()', '.orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull')
$content = $content.Replace('.orElse(System.getenv("GITHUB_TOKEN")).get()', '.orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull')
[IO.File]::WriteAllText($plugin, $content)

# JitPack coordinates are case sensitive.
$settings = Join-Path $dependencies 'morphe-patcher\settings.gradle.kts'
[IO.File]::WriteAllText($settings, [IO.File]::ReadAllText($settings).Replace(
    'com.github.MorpheApp:arsclib', 'com.github.MorpheApp:ARSCLib'))

# Keep nested composite builds on the same optional Patcher compatibility-test checkout.
$librarySettings = Join-Path $dependencies 'morphe-patches-library\settings.gradle.kts'
[IO.File]::WriteAllText($librarySettings, [IO.File]::ReadAllText($librarySettings).Replace(
    'val libDir = file("../$libraryPath")',
    'val libDir = providers.gradleProperty("morphePatcherPath").orNull?.let { file(it) } ?: file("../$libraryPath")'))

# Git on Windows may check symlinks out as small text files. ANTLR needs their actual contents.
$smali = (Resolve-Path (Join-Path $dependencies 'smali')).Path
foreach ($entry in (git -C $smali ls-files -s)) {
    if ($entry -notmatch '^120000\s+\S+\s+\d+\s+(.+)$') { continue }
    $relative = $Matches[1]
    $destination = Join-Path $smali $relative
    if ((Get-Item -LiteralPath $destination).LinkType) { continue }
    $link = (git -C $smali show "HEAD:$relative").Trim()
    $source = [IO.Path]::GetFullPath((Join-Path (Split-Path $destination) $link))
    if (-not $source.StartsWith($smali + [IO.Path]::DirectorySeparatorChar)) {
        throw "Source link escaped the pinned checkout: $source"
    }
    Copy-Item -LiteralPath $source -Destination $destination
}
Write-Host 'Pinned source dependencies are ready.'
