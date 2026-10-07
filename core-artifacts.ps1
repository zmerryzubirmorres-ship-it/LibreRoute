# Shared source receipt for the Android and Linux Core build scripts.
# The receipt detects stale local builds; installation authenticity is checked
# separately against the Ed25519-signed installation manifests.
function Get-CoreSourceFingerprint([string]$WorkspaceRoot) {
    $coreRoot = Join-Path $WorkspaceRoot 'LibreRoute-Core'
    $paths = [System.Collections.Generic.List[string]]::new()
    foreach ($item in (Get-ChildItem -LiteralPath $coreRoot -Recurse -File)) {
        if ($item.FullName -match '[\\/]\.git[\\/]') { continue }
        if (($item.Extension -eq '.go' -and -not $item.Name.EndsWith('_test.go')) -or
            $item.Name -in @('go.mod', 'go.sum') -or $item.Extension -in @('.ps1', '.sh')) {
            $paths.Add($item.FullName.Substring($WorkspaceRoot.Length + 1).Replace('\', '/'))
        }
    }
    foreach ($name in @('core-artifacts.ps1', 'build-libreroute-core.ps1', 'app/build.gradle.kts')) {
        $paths.Add("LibreRoute-Android/$name")
    }
    $ordered = $paths.ToArray()
    [Array]::Sort($ordered, [StringComparer]::Ordinal)
    $records = [System.Text.StringBuilder]::new()
    foreach ($path in $ordered) {
        $hash = (Get-FileHash -LiteralPath (Join-Path $WorkspaceRoot $path) -Algorithm SHA256).Hash.ToLowerInvariant()
        [void]$records.Append($path).Append([char]0).Append($hash).Append("`n")
    }
    $digest = [System.Security.Cryptography.SHA256]::Create()
    try {
        return ([BitConverter]::ToString($digest.ComputeHash([Text.Encoding]::UTF8.GetBytes($records.ToString())))).Replace('-', '').ToLowerInvariant()
    } finally { $digest.Dispose() }
}

function Write-CoreArtifactReceipt([string]$WorkspaceRoot, [string]$SourceFingerprint,
    [hashtable]$Artifacts, [string]$InstallPublicKey) {
    if ($InstallPublicKey -notmatch '^[A-Za-z0-9_-]{43}$') {
        throw 'A valid installation public key is required for the build receipt'
    }
    if ((Get-CoreSourceFingerprint $WorkspaceRoot) -ne $SourceFingerprint) {
        throw 'Core sources changed during the build; rebuild before publishing its receipt'
    }
    $receiptPath = Join-Path $WorkspaceRoot 'LibreRoute-Android/app/src/main/assets/install/core-artifacts.json'
    $entries = @{}
    if (Test-Path -LiteralPath $receiptPath) {
        $old = Get-Content -LiteralPath $receiptPath -Raw -Encoding UTF8 | ConvertFrom-Json
        if ($old.schema -eq 1) {
            foreach ($property in $old.artifacts.PSObject.Properties) { $entries[$property.Name] = $property.Value }
        }
    }
    foreach ($name in $Artifacts.Keys) {
        $entries[$name] = @{
            source_sha256 = $SourceFingerprint
            sha256 = (Get-FileHash -LiteralPath $Artifacts[$name] -Algorithm SHA256).Hash.ToLowerInvariant()
            install_public_key = $InstallPublicKey
        }
    }
    $json = @{ schema = 1; artifacts = $entries } | ConvertTo-Json -Depth 5
    [IO.File]::WriteAllText("$receiptPath.tmp", $json, [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath "$receiptPath.tmp" -Destination $receiptPath -Force
}
