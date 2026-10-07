param(
    [string]$GoExe = 'go',
    [string]$NdkRoot = '',
    [string]$InstallTrustKeyFile = ''
)

$ErrorActionPreference = 'Stop'
$androidRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$workspaceRoot = Split-Path -Parent $androidRoot
$goRoot = Join-Path $workspaceRoot 'LibreRoute-Core'
. (Join-Path $androidRoot 'core-artifacts.ps1')
if (-not $InstallTrustKeyFile) {
    $InstallTrustKeyFile = Join-Path $env:USERPROFILE '.libreroute/install_trust.key.pub'
}
$jniRoot = Join-Path $androidRoot 'app\src\main\jniLibs'
$buildFlags = '-s -w -checklinkname=0'
if ($InstallTrustKeyFile) {
    $installPublicKey = (Get-Content -LiteralPath $InstallTrustKeyFile -Raw).Trim()
    if ($installPublicKey -notmatch '^[A-Za-z0-9_-]{43}$') { throw 'Invalid installation public key' }
    $buildFlags += " -X libreroute/admin.InstallTrustKey=$installPublicKey"
}

if ([string]::IsNullOrWhiteSpace($NdkRoot)) {
    $sdkRoot = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { $env:ANDROID_HOME }
    if ($sdkRoot) {
        $ndkParent = Join-Path $sdkRoot 'ndk'
        if (Test-Path -LiteralPath $ndkParent) {
            $NdkRoot = (Get-ChildItem -LiteralPath $ndkParent -Directory | Sort-Object Name -Descending | Select-Object -First 1).FullName
        }
    }
}
if ([string]::IsNullOrWhiteSpace($NdkRoot) -or -not (Test-Path -LiteralPath $NdkRoot)) {
    throw 'Android NDK not found. Install an NDK or pass -NdkRoot explicitly.'
}

$ndkHost = Join-Path $NdkRoot 'toolchains\llvm\prebuilt\windows-x86_64'
$clang = Join-Path $ndkHost 'bin\clang.exe'
$sysroot = Join-Path $ndkHost 'sysroot'
if (-not (Test-Path -LiteralPath $clang) -or -not (Test-Path -LiteralPath $sysroot)) {
    throw "Android NDK compiler/sysroot not found under $NdkRoot"
}

if (-not (Test-Path -LiteralPath (Join-Path $goRoot 'go.mod'))) {
    throw "LibreRoute Go source is missing: $goRoot"
}
if (-not (Test-Path -LiteralPath $jniRoot)) {
    throw "Android jniLibs directory is missing: $jniRoot"
}

$targets = @(
    @{ Abi = 'arm64-v8a'; Arch = 'arm64' }
)
$stageRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("libreroute-client-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $stageRoot | Out-Null

$previous = @{
    GOOS = $env:GOOS
    GOARCH = $env:GOARCH
    CGO_ENABLED = $env:CGO_ENABLED
    CC = $env:CC
}
$sourceFingerprint = Get-CoreSourceFingerprint $workspaceRoot

try {
    Push-Location $goRoot
    try {
        foreach ($target in $targets) {
            $stageAbi = Join-Path $stageRoot $target.Abi
            New-Item -ItemType Directory -Path $stageAbi | Out-Null
            $output = Join-Path $stageAbi 'liblibreroute_client.so'
            $env:GOOS = 'android'
            $env:GOARCH = $target.Arch
            $env:CGO_ENABLED = '1'
            $env:CC = '"' + $clang + '" --target=aarch64-linux-android26 --sysroot=' + $sysroot
            Write-Host "Building LibreRoute Android client for $($target.Abi)..."
            & $GoExe build -buildmode=pie -trimpath -ldflags $buildFlags -o $output .
            if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $output)) {
                throw "Go build failed for $($target.Abi); existing Android binaries were not changed"
            }
        }
    } finally {
        Pop-Location
    }

    if ((Get-CoreSourceFingerprint $workspaceRoot) -ne $sourceFingerprint) {
        throw 'Core sources changed during the build; Android binaries were not replaced'
    }
    foreach ($target in $targets) {
        $source = Join-Path (Join-Path $stageRoot $target.Abi) 'liblibreroute_client.so'
        $destination = Join-Path (Join-Path $jniRoot $target.Abi) 'liblibreroute_client.so'
        Copy-Item -LiteralPath $source -Destination $destination
        Write-Host "Installed $destination"
    }
    Write-CoreArtifactReceipt $workspaceRoot $sourceFingerprint @{
        native_arm64 = (Join-Path $jniRoot 'arm64-v8a/liblibreroute_client.so')
    } $installPublicKey
} finally {
    $env:GOOS = $previous.GOOS
    $env:GOARCH = $previous.GOARCH
    $env:CGO_ENABLED = $previous.CGO_ENABLED
    $env:CC = $previous.CC
}
