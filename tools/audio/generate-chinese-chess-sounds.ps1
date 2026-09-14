param(
    [Parameter(Mandatory = $true)]
    [string] $OutputDirectory
)

$sampleRate = 22050
$channels = 1
$bitsPerSample = 16

function Write-WaveFile {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path,
        [Parameter(Mandatory = $true)]
        [double[]] $Samples
    )

    $stream = [System.IO.File]::Create($Path)
    try {
        $writer = [System.IO.BinaryWriter]::new($stream)
        try {
            $dataLength = $Samples.Length * 2
            $writer.Write([System.Text.Encoding]::ASCII.GetBytes('RIFF'))
            $writer.Write([int](36 + $dataLength))
            $writer.Write([System.Text.Encoding]::ASCII.GetBytes('WAVE'))
            $writer.Write([System.Text.Encoding]::ASCII.GetBytes('fmt '))
            $writer.Write([int]16)
            $writer.Write([short]1)
            $writer.Write([short]$channels)
            $writer.Write([int]$sampleRate)
            $writer.Write([int]($sampleRate * $channels * $bitsPerSample / 8))
            $writer.Write([short]($channels * $bitsPerSample / 8))
            $writer.Write([short]$bitsPerSample)
            $writer.Write([System.Text.Encoding]::ASCII.GetBytes('data'))
            $writer.Write([int]$dataLength)
            foreach ($sample in $Samples) {
                $clamped = [Math]::Max(-1.0, [Math]::Min(1.0, $sample))
                $writer.Write([short]($clamped * 32767))
            }
        } finally {
            $writer.Dispose()
        }
    } finally {
        $stream.Dispose()
    }
}

function New-Impact {
    param(
        [double] $DurationSeconds,
        [double] $LowFrequency,
        [double] $HighFrequency,
        [double] $Amplitude
    )

    $count = [int]($sampleRate * $DurationSeconds)
    $samples = [double[]]::new($count)
    for ($index = 0; $index -lt $count; $index++) {
        $time = $index / $sampleRate
        $envelope = [Math]::Exp(-36.0 * $time)
        $low = [Math]::Sin(2.0 * [Math]::PI * $LowFrequency * $time)
        $high = [Math]::Sin(2.0 * [Math]::PI * $HighFrequency * $time)
        $samples[$index] = $Amplitude * $envelope * (0.72 * $low + 0.28 * $high)
    }
    return $samples
}

function New-Melody {
    param(
        [double[]] $Frequencies,
        [double] $ToneSeconds,
        [double] $Amplitude
    )

    $toneSamples = [int]($sampleRate * $ToneSeconds)
    $gapSamples = [int]($sampleRate * 0.018)
    $result = [System.Collections.Generic.List[double]]::new()
    foreach ($frequency in $Frequencies) {
        for ($index = 0; $index -lt $toneSamples; $index++) {
            $time = $index / $sampleRate
            $phase = $index / [double]$toneSamples
            $attack = [Math]::Min(1.0, $phase / 0.08)
            $release = [Math]::Min(1.0, (1.0 - $phase) / 0.22)
            $envelope = $attack * $release
            $fundamental = [Math]::Sin(2.0 * [Math]::PI * $frequency * $time)
            $overtone = [Math]::Sin(4.0 * [Math]::PI * $frequency * $time)
            $result.Add($Amplitude * $envelope * (0.82 * $fundamental + 0.18 * $overtone))
        }
        for ($index = 0; $index -lt $gapSamples; $index++) {
            $result.Add(0.0)
        }
    }
    return $result.ToArray()
}

[System.IO.Directory]::CreateDirectory($OutputDirectory) | Out-Null

Write-WaveFile -Path (Join-Path $OutputDirectory 'chess_move.wav') -Samples (New-Impact 0.11 310.0 720.0 0.78)
Write-WaveFile -Path (Join-Path $OutputDirectory 'chess_capture.wav') -Samples (New-Impact 0.17 155.0 930.0 0.90)
Write-WaveFile -Path (Join-Path $OutputDirectory 'game_victory.wav') -Samples (New-Melody @(523.25, 659.25, 783.99) 0.13 0.58)
Write-WaveFile -Path (Join-Path $OutputDirectory 'game_defeat.wav') -Samples (New-Melody @(392.00, 329.63, 261.63) 0.15 0.50)
Write-WaveFile -Path (Join-Path $OutputDirectory 'game_draw.wav') -Samples (New-Melody @(440.00, 440.00) 0.14 0.44)
