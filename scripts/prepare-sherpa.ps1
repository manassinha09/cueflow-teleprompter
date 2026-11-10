$ErrorActionPreference = 'Stop'
$cueRoot = Split-Path -Parent $PSScriptRoot
$cueCache = Join-Path $cueRoot '.cueflow-local/sherpa'
$cueAssets = Join-Path $cueCache 'assets'
New-Item -ItemType Directory -Force -Path $cueCache, $cueAssets | Out-Null

function Get-CueDependency($Name, $Url, $Sha256) {
    $target = Join-Path $cueCache $Name
    if ((Test-Path -LiteralPath $target) -and (Get-FileHash -LiteralPath $target).Hash -eq $Sha256) {
        return
    }
    $partial = "$target.download"
    try {
        curl.exe -fsSL --retry 2 -o $partial $Url
        if ($LASTEXITCODE -ne 0 -or (Get-FileHash -LiteralPath $partial).Hash -ne $Sha256) {
            throw "Could not verify $Name."
        }
        Move-Item -LiteralPath $partial -Destination $target -Force
    } finally {
        if (Test-Path -LiteralPath $partial) { Remove-Item -LiteralPath $partial }
    }
}

$cueRelease = 'https://github.com/k2-fsa/sherpa-onnx/releases/download'
$cueModel = 'sherpa-onnx-streaming-zipformer-en-2023-06-26'
Get-CueDependency 'sherpa-onnx-2.0.1.aar' "$cueRelease/v2.0.1/sherpa-onnx-2.0.1.aar" 'A8D09D1D32F3EB8520482C6D3D43FAE674681C5B050385967C70A3C2C9BF0EE7'
Get-CueDependency 'LICENSE' 'https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/v2.0.1/LICENSE' 'CFC7749B96F63BD31C3C42B5C471BF756814053E847C10F3EB003417BC523D30'
$cueOrt = 'https://raw.githubusercontent.com/microsoft/onnxruntime/v1.28.2'
Get-CueDependency 'onnxruntime-LICENSE' "$cueOrt/LICENSE" '2F07C72751AED99790B8A4869CF2311DF85A860B22DED05FA22803587A48922C'
Get-CueDependency 'onnxruntime-ThirdPartyNotices.txt' "$cueOrt/ThirdPartyNotices.txt" '0E07B95F3A8D6230037707C5C4A2B554D12C4CB67369669AC255635528FFCEE2'

$cueModelUrl = "https://huggingface.co/csukuangfj/$cueModel/resolve/672fbf1b30579d6585301139bb363f42a0ad4a24"
$cueFiles = @{
    'encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx' = '563FDE436D16CF7607CF408CD6B30909819D03162652EF389C2450CED3F45AC1'
    'decoder-epoch-99-avg-1-chunk-16-left-128.onnx' = '7BF787F90B194B307E5A4AD6A34FADB4E748304C35F78A8D66358A05B13EE6EF'
    'joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx' = 'D944208D660D67C8D72CD2ACAEAC971FA5CEB8C80E76C1968148846FEDD6E297'
    'tokens.txt' = '49E3C2646595FD907228B3C6787069658F67B17377C60AEB8619C4551B2316FB'
}
$cueDestination = Join-Path $cueAssets $cueModel
New-Item -ItemType Directory -Force -Path $cueDestination | Out-Null
foreach ($cueName in $cueFiles.Keys) {
    Get-CueDependency "zipformer-$cueName" "$cueModelUrl/$cueName" $cueFiles[$cueName]
    Copy-Item -LiteralPath (Join-Path $cueCache "zipformer-$cueName") -Destination (Join-Path $cueDestination $cueName)
}
$cueLicenses = Join-Path $cueAssets 'licenses'
New-Item -ItemType Directory -Force -Path $cueLicenses | Out-Null
Copy-Item -LiteralPath (Join-Path $cueCache 'LICENSE') -Destination (Join-Path $cueLicenses 'sherpa-onnx-and-zipformer-apache-2.0.txt')
Copy-Item -LiteralPath (Join-Path $cueCache 'onnxruntime-LICENSE') -Destination (Join-Path $cueLicenses 'onnxruntime-mit.txt')
Copy-Item -LiteralPath (Join-Path $cueCache 'onnxruntime-ThirdPartyNotices.txt') -Destination $cueLicenses
