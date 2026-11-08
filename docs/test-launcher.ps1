# Sertraline local test launcher (pwsh 7).
#
# Two things matter here:
#  * stdin must stay open - a closed stdin makes the server shut down shortly after "Done"
#  * the parent process must outlive the whole test, otherwise the child is reaped
#
# stdout is pumped synchronously into test-console.log (no .NET event handler: script-block
# handlers are invoked on a foreign thread and Windows PowerShell 5.1 throws there).
# Stop the server with RCON "stop"; this script then exits on its own.
param(
    [string]$ServerDir = 'E:\MC\tra\Ets'
)

$ErrorActionPreference = 'Stop'
$logPath = Join-Path $ServerDir 'test-console.log'
Remove-Item $logPath -ErrorAction SilentlyContinue

$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = 'E:\jdk-25\bin\java.exe'
$psi.Arguments = '-Xms2G -Xmx2G -Dfile.encoding=UTF-8 -Dnet.kyori.ansi.colorLevel=truecolor -jar leaf-1.21.4-525.jar nogui'
$psi.WorkingDirectory = $ServerDir
$psi.UseShellExecute = $false
$psi.RedirectStandardInput = $true
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true
$psi.StandardOutputEncoding = [System.Text.Encoding]::UTF8
$psi.StandardErrorEncoding = [System.Text.Encoding]::UTF8

$proc = New-Object System.Diagnostics.Process
$proc.StartInfo = $psi
[void]$proc.Start()

Write-Output "launcher: pid=$($proc.Id)"

$writer = New-Object System.IO.StreamWriter($logPath, $false, (New-Object System.Text.UTF8Encoding($false)))
$writer.AutoFlush = $true

try {
    while (-not $proc.HasExited) {
        $line = $proc.StandardOutput.ReadLine()
        if ($null -eq $line) {
            if ($proc.HasExited) { break }
            Start-Sleep -Milliseconds 100
            continue
        }
        $writer.WriteLine($line)
    }
} finally {
    $writer.Flush()
    $writer.Dispose()
}

# Drain whatever stderr produced, for the record.
try {
    $err = $proc.StandardError.ReadToEnd()
    if ($err) { Add-Content -LiteralPath $logPath -Value $err -Encoding utf8 }
} catch { }

[void]$proc.WaitForExit(30000)
Write-Output "launcher: server exited, code=$($proc.ExitCode)"
