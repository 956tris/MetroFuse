param([string]$Output = (Join-Path $PSScriptRoot 'fixture.wav'))
$taskSamples = 11025
$taskRate = 44100
$taskStream = [System.IO.File]::Create($Output)
$taskWriter = [System.IO.BinaryWriter]::new($taskStream)
try {
    $taskWriter.Write([System.Text.Encoding]::ASCII.GetBytes('RIFF'))
    $taskWriter.Write([int](36 + $taskSamples * 2))
    $taskWriter.Write([System.Text.Encoding]::ASCII.GetBytes('WAVEfmt '))
    $taskWriter.Write([int]16)
    $taskWriter.Write([short]1)
    $taskWriter.Write([short]1)
    $taskWriter.Write([int]$taskRate)
    $taskWriter.Write([int]($taskRate * 2))
    $taskWriter.Write([short]2)
    $taskWriter.Write([short]16)
    $taskWriter.Write([System.Text.Encoding]::ASCII.GetBytes('data'))
    $taskWriter.Write([int]($taskSamples * 2))
    for ($taskIndex = 0; $taskIndex -lt $taskSamples; $taskIndex++) {
        $taskWriter.Write([short](4000 * [Math]::Sin(2 * [Math]::PI * 440 * $taskIndex / $taskRate)))
    }
} finally {
    $taskWriter.Dispose()
}
Get-Item -LiteralPath $Output | Select-Object FullName,Length
