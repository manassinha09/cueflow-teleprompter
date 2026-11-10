param(
    [Parameter(Mandatory = $true)][string]$ApkPath,
    [string]$SdkRoot = $env:ANDROID_HOME
)

$ErrorActionPreference = 'Stop'
$cueRoot = Split-Path -Parent $PSScriptRoot
$cueVersion = ConvertFrom-StringData (Get-Content -Raw (Join-Path $cueRoot 'version.properties'))
if (-not $SdkRoot) { throw 'Pass -SdkRoot or set ANDROID_HOME.' }
$cueTools = Get-ChildItem (Join-Path $SdkRoot 'build-tools') -Directory |
    Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' } |
    Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
if (-not $cueTools) { throw 'Android build tools were not found.' }
$cueApk = (Resolve-Path -LiteralPath $ApkPath).Path

$cueSignature = & (Join-Path $cueTools.FullName 'apksigner.bat') verify --verbose --print-certs $cueApk
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
$cueCertificate = ($cueSignature | Select-String '^Signer #1 certificate SHA-256 digest: (.+)$').Matches.Groups[1].Value.Trim()
$cueExpectedCertificate = (Get-Content -Raw (Join-Path $cueRoot 'release-signing-certificate.sha256')).Trim()
if ($cueCertificate -ne $cueExpectedCertificate) { throw 'APK is not signed with the official CueFlow release certificate.' }

$cueBadging = & (Join-Path $cueTools.FullName 'aapt2.exe') dump badging $cueApk
if ($LASTEXITCODE -ne 0) { throw 'Could not read APK metadata.' }
$cuePackage = $cueBadging | Where-Object { $_ -like 'package:*' }
if ($cuePackage -notmatch "name='com.novaflow.cueflow'" -or
    $cuePackage -notmatch "versionCode='$([regex]::Escape($cueVersion.versionCode))'" -or
    $cuePackage -notmatch "versionName='$([regex]::Escape($cueVersion.versionName))'") {
    throw "APK identity/version does not match version.properties: $cuePackage"
}
if ($cueBadging -match '^application-debuggable') { throw 'An official APK must not be debuggable.' }
if ($cueBadging -notcontains "sdkVersion:'29'" -and $cueBadging -notcontains "minSdkVersion:'29'") {
    throw 'Unexpected minimum Android API.'
}
$cueAbis = ($cueBadging | Where-Object { $_ -like 'native-code:*' }) -join ' '
foreach ($cueAbi in @('arm64-v8a', 'armeabi-v7a', 'x86_64')) {
    if ($cueAbis -notmatch [regex]::Escape("'$cueAbi'")) { throw "Missing ABI: $cueAbi" }
}

& (Join-Path $cueTools.FullName 'zipalign.exe') -c -P 16 4 $cueApk
if ($LASTEXITCODE -ne 0) { throw 'APK alignment verification failed.' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$cueZip = [IO.Compression.ZipFile]::OpenRead($cueApk)
try {
    foreach ($cueAsset in @('encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx',
        'decoder-epoch-99-avg-1-chunk-16-left-128.onnx',
        'joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx', 'tokens.txt')) {
        if (-not $cueZip.GetEntry("assets/sherpa-onnx-streaming-zipformer-en-2023-06-26/$cueAsset")) {
            throw "Missing offline speech asset: $cueAsset"
        }
    }
} finally { $cueZip.Dispose() }
Write-Output "Verified CueFlow $($cueVersion.versionName) (version code $($cueVersion.versionCode)): release signature, non-debuggable APK, three ABIs, 16 KB ZIP alignment, and offline speech assets."
Write-Output "SHA-256: $((Get-FileHash -LiteralPath $cueApk -Algorithm SHA256).Hash.ToLowerInvariant())"
