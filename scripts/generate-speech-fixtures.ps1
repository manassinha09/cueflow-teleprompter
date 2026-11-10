Add-Type -AssemblyName System.Speech
$cueVoice = New-Object System.Speech.Synthesis.SpeechSynthesizer
$cueFormat = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(48000, [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen, [System.Speech.AudioFormat.AudioChannel]::Mono)
$cueVoice.Rate = 0
$cueRoot = Join-Path (Split-Path -Parent $PSScriptRoot) '.cueflow-local'
New-Item -ItemType Directory -Force -Path $cueRoot | Out-Null
$cuePhrases = @{
    'benchmark' = 'Welcome to our camera test. The script should move as you read aloud. Take a short pause before continuing. When you are ready, stop recording and play back the saved video.'
    'scroll-up' = 'Scroll up two lines.'
    'scroll-down' = 'Scroll down two lines.'
}
foreach ($cueEntry in $cuePhrases.GetEnumerator()) {
    $cueVoice.SetOutputToWaveFile((Join-Path $cueRoot ($cueEntry.Key + '.wav')), $cueFormat)
    $cueVoice.Speak($cueEntry.Value)
    $cueVoice.SetOutputToNull()
}
$cueVoice.Dispose()
