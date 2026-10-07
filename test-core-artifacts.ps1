# Exercise the actual Gradle verifier in a disposable fixture, never the originals.
$ErrorActionPreference = 'Stop'
$androidRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$workspaceRoot = Split-Path -Parent $androidRoot
$fixtureRoot = Join-Path ([IO.Path]::GetTempPath()) ('libreroute-artifact-check-' + [guid]::NewGuid().ToString('N'))
$fixtureAndroid = Join-Path $fixtureRoot 'LibreRoute-Android'
$fixtureApp = Join-Path $fixtureAndroid 'app'
New-Item -ItemType Directory -Path $fixtureApp -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $androidRoot 'gradle.properties') -Destination (Join-Path $fixtureAndroid 'gradle.properties')
function Copy-FixtureFile([string]$source) {
    $relative = $source.Substring($workspaceRoot.Length + 1)
    $destination = Join-Path $fixtureRoot $relative
    New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
    Copy-Item -LiteralPath $source -Destination $destination
}
foreach ($item in (Get-ChildItem -LiteralPath (Join-Path $workspaceRoot 'LibreRoute-Core') -Recurse -File)) {
    if ($item.FullName -match '[\\/]\.git[\\/]') { continue }
    if (($item.Extension -eq '.go' -and -not $item.Name.EndsWith('_test.go')) -or
        $item.Name -in @('go.mod', 'go.sum') -or $item.Extension -in @('.ps1', '.sh')) {
        Copy-FixtureFile $item.FullName
    }
}
foreach ($name in @('core-artifacts.ps1', 'build-libreroute-core.ps1', 'app/build.gradle.kts',
    'app/src/main/jniLibs/arm64-v8a/liblibreroute_client.so')) {
    Copy-FixtureFile (Join-Path $androidRoot $name)
}
foreach ($item in (Get-ChildItem -LiteralPath (Join-Path $androidRoot 'app/src/main/assets/install') -File)) {
    Copy-FixtureFile $item.FullName
}
$buildText = [IO.File]::ReadAllText((Join-Path $androidRoot 'app/build.gradle.kts'))
$imports = $buildText.Substring(0, $buildText.IndexOf('plugins {'))
$taskStart = $buildText.IndexOf('tasks.register("verifyCoreArtifacts")')
$taskEnd = $buildText.IndexOf('tasks.named("preBuild")', $taskStart)
[IO.File]::WriteAllText((Join-Path $fixtureApp 'verify.gradle.kts'), $imports + $buildText.Substring($taskStart, $taskEnd - $taskStart))
[IO.File]::WriteAllText((Join-Path $fixtureAndroid 'settings.gradle.kts'), 'rootProject.name = "core-artifact-check"' + "`n" + 'include(":app")' + "`n" + 'project(":app").buildFileName = "verify.gradle.kts"')
$wrapper = Join-Path $androidRoot 'gradlew.bat'
function Assert-Verification([string]$scenario, [string]$expectedError = '') {
    $previousErrorAction = $ErrorActionPreference
    try {
        # Windows PowerShell treats native stderr warnings as ErrorRecords.
        $ErrorActionPreference = 'Continue'
        $output = & $wrapper -p $fixtureAndroid :app:verifyCoreArtifacts --console=plain 2>&1
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previousErrorAction }
    $text = $output | Out-String
    if ($expectedError) {
        if ($code -eq 0 -or $text -notmatch [regex]::Escape($expectedError)) {
            throw "$scenario did not reject the invalid artifact as expected: $text"
        }
    } elseif ($code -ne 0) { throw "$scenario failed: $text" }
    Write-Host "$scenario passed"
}
Assert-Verification 'valid signed source-matched artifacts'
$source = Join-Path $fixtureRoot 'LibreRoute-Core/transport/jitsi/jitsi.go'
$original = [IO.File]::ReadAllBytes($source)
try {
    [IO.File]::AppendAllText($source, "`n// source changed after build`n")
    Assert-Verification 'changed source' 'Stale Core artifact'
} finally { [IO.File]::WriteAllBytes($source, $original) }
$binary = Join-Path $fixtureApp 'src/main/jniLibs/arm64-v8a/liblibreroute_client.so'
$original = [IO.File]::ReadAllBytes($binary)
try {
    $changed = $original.Clone(); $changed[0] = $changed[0] -bxor 1
    [IO.File]::WriteAllBytes($binary, $changed)
    Assert-Verification 'modified native binary' 'differs from its build receipt'
} finally { [IO.File]::WriteAllBytes($binary, $original) }
$manifest = Join-Path $fixtureApp 'src/main/assets/install/manifest-amd64.json'
$original = [IO.File]::ReadAllBytes($manifest)
try {
    $signed = [IO.File]::ReadAllText($manifest) | ConvertFrom-Json
    $first = if ($signed.signature[0] -eq 'A') { 'B' } else { 'A' }
    $signed.signature = $first + $signed.signature.Substring(1)
    [IO.File]::WriteAllText($manifest, ($signed | ConvertTo-Json))
    Assert-Verification 'invalid manifest signature' 'signature mismatch'
} finally { [IO.File]::WriteAllBytes($manifest, $original) }
$receipt = Join-Path $fixtureApp 'src/main/assets/install/core-artifacts.json'
$original = [IO.File]::ReadAllBytes($receipt)
try {
    [IO.File]::WriteAllText($receipt, '{"schema":1,"artifacts":{}}')
    Assert-Verification 'missing build receipt entry' 'Missing build receipt for native_arm64'
} finally { [IO.File]::WriteAllBytes($receipt, $original) }
Write-Host "Artifact checks complete. Fixture: $fixtureRoot"
