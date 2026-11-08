# Gem system runtime test in a single job (server start -> assertions -> stop).
param(
    [string]$ServerDir = 'E:\MC\tra\Ets',
    [string]$NodeDir = 'E:\Sertraline\Sertraline\docs\node',
    [string]$NodeScript = 'bot-gem.js',
    [string]$ResultFile = 'E:\Sertraline\Sertraline\docs\node\gem-result.txt'
)

$ErrorActionPreference = 'Continue'
$consoleLog = Join-Path $ServerDir 'test-console.log'
Remove-Item $consoleLog -ErrorAction SilentlyContinue
Remove-Item $ResultFile -ErrorAction SilentlyContinue

function Write-Result($text) { $text | Out-File -FilePath $ResultFile -Append -Encoding utf8 }

# The bot must be opped for /sertralineitem and /sgem. Add it to ops.json for the run and
# restore the file afterwards, so the server is left exactly as it was found.
$opsPath = Join-Path $ServerDir 'ops.json'
$opsBackup = Join-Path $ServerDir 'ops.json.bak-test'
Copy-Item $opsPath $opsBackup -Force
# Offline UUID of SrTestBot (offline-mode servers derive it from the name).
$botUuid = '5efa652f-d72f-3f0e-b47f-44d7c73ad755'
$ops = Get-Content $opsPath -Raw -Encoding utf8 | ConvertFrom-Json
if (-not ($ops | Where-Object { $_.name -eq 'SrTestBot' })) {
    $ops = @($ops) + [PSCustomObject]@{ uuid = $botUuid; name = 'SrTestBot'; level = 4; bypassesPlayerLimit = $false }
    $ops | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $opsPath -Encoding utf8
    Write-Result 'ops.json: added SrTestBot'
}

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
Write-Result "server pid=$($proc.Id)"

$writer = New-Object System.IO.StreamWriter($consoleLog, $false, (New-Object System.Text.UTF8Encoding($false)))
$writer.AutoFlush = $true

$pump = [PowerShell]::Create()
[void]$pump.AddScript({
    param($proc, $writer)
    while (-not $proc.HasExited) {
        $line = $proc.StandardOutput.ReadLine()
        if ($null -eq $line) { if ($proc.HasExited) { break } else { Start-Sleep -Milliseconds 50; continue } }
        $writer.WriteLine($line)
    }
    $writer.Flush()
}).AddArgument($proc).AddArgument($writer)
$pumpHandle = $pump.BeginInvoke()

$ready = $false
for ($i = 0; $i -lt 120; $i++) {
    Start-Sleep -Seconds 2
    if ($proc.HasExited) { break }
    if ((Test-Path $consoleLog) -and (Select-String -Path $consoleLog -Pattern 'Done \(' -Quiet)) { $ready = $true; break }
}
Write-Result "ready=$ready"
if (-not $ready) {
    Write-Result 'SERVER DID NOT START'
    if (-not $proc.HasExited) { $proc.Kill() }
    exit 1
}

& node (Join-Path $NodeDir $NodeScript) 2>&1 | Tee-Object -FilePath $ResultFile -Append

Write-Result '--- console: gem / error lines ---'
Select-String -Path $consoleLog -Pattern 'gem|Gem|ERROR|Exception' | Select-Object -Last 25 |
    ForEach-Object { Write-Result ($_.Line -replace "`e\[[0-9;]*m", '') }

if (-not $proc.HasExited) {
    try { $proc.StandardInput.WriteLine('stop') } catch { }
    [void]$proc.WaitForExit(180000)
}
Write-Result "server exited, code=$($proc.ExitCode)"
try { [void]$pump.EndInvoke($pumpHandle) } catch { }
$writer.Dispose()

# Restore ops.json.
if (Test-Path $opsBackup) {
    Copy-Item $opsBackup $opsPath -Force
    Remove-Item $opsBackup -Force
    Write-Result 'ops.json: restored'
}
