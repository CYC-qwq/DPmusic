param([string]$Mode = 'status', [string]$MatchHex = '')
<#
Controls the media session of the Soda Music client through Windows SMTC.

Written as a .ps1 file rather than an inline -Command for a concrete reason: passing a command line
that contains non-ASCII (the client reports its session name in Chinese) got mangled on the way in,
so the name never matched and the action silently did nothing. Here the name is passed as HEX and
compared byte-wise, and all output is hex-encoded too, so no encoding step can corrupt it.

$Mode: status | pause | play | toggle      (status only READS, never toggles)
$MatchHex: UTF-8 hex of a name fragment to match, e.g. 'e6b1bde6b0b4' for 汽水.
#>
$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch {}

try { Add-Type -AssemblyName System.Runtime.WindowsRuntime -ErrorAction Stop }
catch { Write-Output "ERR=assembly:$($_.Exception.Message)"; exit 1 }

$asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and
    $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]
function Await($op, $type) {
    $m = $asTaskGeneric.MakeGenericMethod($type)
    $t = $m.Invoke($null, @($op))
    if (-not $t.Wait(15000)) { throw 'await timeout' }
    $t.Result
}
function ToHex([string]$s) {
    if ($null -eq $s) { return '' }
    return (([System.Text.Encoding]::UTF8.GetBytes($s) | ForEach-Object { $_.ToString('x2') }) -join '')
}

$mgrType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager,Windows.Media,ContentType=WindowsRuntime]
$mgr = Await ($mgrType::RequestAsync()) $mgrType
$sessions = $mgr.GetSessions()
Write-Output "COUNT=$($sessions.Count)"

$target = $null
foreach ($s in $sessions) {
    $app = $s.SourceAppUserModelId
    $hex = ToHex $app
    $st = '?'
    try { $st = $s.GetPlaybackInfo().PlaybackStatus.ToString() } catch {}
    $title = ''
    try {
        $op = $s.TryGetMediaPropertiesAsync()
        $props = Await $op ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties])
        $title = $props.Title
    } catch { $title = '' }
    Write-Output ("SESS APPHEX=$hex STATUS=$st TITLEHEX=$(ToHex $title)")
    if ($MatchHex -ne '' -and $hex -like "*$MatchHex*") { $target = $s }
}

if ($Mode -eq 'status') { Write-Output 'DONE=status'; exit 0 }
if ($null -eq $target) { Write-Output 'DONE=no-match'; exit 0 }

$ok = $false
switch ($Mode) {
    'pause'  { $ok = Await ($target.TryPauseAsync())  ([bool]) }
    'play'   { $ok = Await ($target.TryPlayAsync())   ([bool]) }
    'toggle' { $ok = Await ($target.TryTogglePlayPauseAsync()) ([bool]) }
}
Start-Sleep -Milliseconds 350
$after = '?'
try { $after = $target.GetPlaybackInfo().PlaybackStatus.ToString() } catch {}
Write-Output "ACTION=$Mode OK=$ok AFTER=$after"
Write-Output 'DONE=ok'
