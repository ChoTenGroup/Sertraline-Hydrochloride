# End-to-end runtime test in a single job.
#
# Why one script: the server process is reaped as soon as the job that owns it ends, so
# start -> test -> stop has to happen inside one uninterrupted run.
param(
    [string]$ServerDir = 'E:\MC\tra\Ets',
    [string]$NodeDir = 'E:\Sertraline\Sertraline\docs\node',
    [string]$ResultFile = 'E:\Sertraline\Sertraline\docs\node\e2e-result.txt'
)

$ErrorActionPreference = 'Continue'
$consoleLog = Join-Path $ServerDir 'test-console.log'
Remove-Item $consoleLog -ErrorAction SilentlyContinue
Remove-Item $ResultFile -ErrorAction SilentlyContinue

function Write-Result($text) {
    $text | Out-File -FilePath $ResultFile -Append -Encoding utf8
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

# Pump stdout on a background runspace so the main thread stays free to run the tests.
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

# Wait for the server to finish starting.
$ready = $false
for ($i = 0; $i -lt 120; $i++) {
    Start-Sleep -Seconds 2
    if ($proc.HasExited) { break }
    if ((Test-Path $consoleLog) -and (Select-String -Path $consoleLog -Pattern 'Done \(' -Quiet)) { $ready = $true; break }
}
Write-Result "ready=$ready after ~$($i * 2)s"
if (-not $ready) {
    Write-Result 'SERVER DID NOT START'
    if (-not $proc.HasExited) { $proc.Kill() }
    Write-Result (Get-Content $consoleLog -Tail 25 | Out-String)
    exit 1
}

# Helpers so each assertion fits in one chat line (Minecraft caps chat at 256 chars).
$F = "Java.type('io.github.zzzyyylllty.sertraline.data.SItemFactory').INSTANCE"
$A = "Java.type('io.github.zzzyyylllty.sertraline.api.SertralineItemAPI').INSTANCE"

$tests = @(
    # 1. namespaced parse -> MinecraftSItem, storageKey / shouldAdapt / material
    "sertralineapi evalGraalJs var S=$F,m=S.parse('minecraft::stone');m.getClass().getSimpleName()+'|'+m.getStorageKey()+'|'+m.getShouldAdapt()+'|'+m.getMaterial()",
    # 2. bare id -> ModernSItem; external namespace -> ExternalPluginSItem
    "sertralineapi evalGraalJs var S=$F,a=S.parse('doe_card'),b=S.parse('someplugin::gun');a.getClass().getSimpleName()+'|'+a.getStorageKey()+'|'+b.getClass().getSimpleName()+'|'+b.getStorageKey()+'|'+b.getShouldAdapt()",
    # 3. register a MinecraftSItem through the API
    "sertralineapi evalGraalJs var A=$A,S=$F,i=S.parse('minecraft::diamond');A.register(i,i.getStorageKey());A.getItemCount()",
    # 4. it is retrievable, counted as Minecraft, and namespaced lookup does not fall back
    "sertralineapi evalGraalJs var A=$A;A.isRegistered('minecraft::diamond')+'|'+A.getMinecraftItems().size()+'|'+A.getItem('nonexistent::x')",
    # 5. serialize -> deserialize keeps the concrete type and restores runtime state
    "sertralineapi evalGraalJs var S=$F,j=S.parse('doe_card').serialize(),r=S.deserialize(j);r.getClass().getSimpleName()+'|'+r.getStorageKey()+'|'+r.getShouldAdapt()+'|'+j.length()",
    # 6. minecraft item round-trips back to MinecraftSItem, not ModernSItem
    "sertralineapi evalGraalJs var S=$F,j=S.parse('minecraft::stone').serialize(),r=S.deserialize(j);r.getClass().getSimpleName()+'|'+r.getStorageKey()+'|'+r.getShouldAdapt()",
    # 7. garbage / empty namespace must not throw
    "sertralineapi evalGraalJs var S=$F;S.deserialize('{bad}')+'|'+S.parse('::stone')+'|'+S.parse('not a thing!')",
    # 8. the same checks through the Nashorn path (evalJS)
    "sertralineapi evalJS var S=$F,m=S.parse('minecraft::stone');m.getClass().getSimpleName()+'|'+m.getStorageKey()+'|'+m.getMaterial()"
)

$nodeExe = 'node'
$botScript = Join-Path $NodeDir 'bot-cmd.js'
& $nodeExe $botScript @tests 2>&1 | Tee-Object -FilePath $ResultFile -Append

Write-Result '--- server console (Sertraline lines) ---'
Select-String -Path $consoleLog -Pattern 'ERROR|WARN.*Recipe|Exception' | Select-Object -Last 20 |
    ForEach-Object { Write-Result ($_.Line -replace "`e\[[0-9;]*m", '') }

# Shut down cleanly.
if (-not $proc.HasExited) {
    try { $proc.StandardInput.WriteLine('stop') } catch { }
    [void]$proc.WaitForExit(180000)
}
Write-Result "server exited, code=$($proc.ExitCode)"
try { [void]$pump.EndInvoke($pumpHandle) } catch { }
$writer.Dispose()
