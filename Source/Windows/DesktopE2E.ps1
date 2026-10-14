# Runs one real Desktop peer against the Stage 1 CLI. Data stays under .e2e.
$ErrorActionPreference = 'Stop'
$root = Join-Path $PSScriptRoot '.e2e/desktop-run'
if (Test-Path -LiteralPath $root) { Remove-Item -LiteralPath $root -Recurse -Force }
$desktop = Join-Path $PSScriptRoot 'HopDrop.Desktop/bin/Release/net10.0-windows10.0.19041.0/HopDrop.exe'
$cli = Join-Path $PSScriptRoot 'HopDrop.Cli/bin/Release/net10.0/HopDrop.Cli.dll'
$aData = Join-Path $root 'desktop/data'; $bData = Join-Path $root 'cli/data'
$aReceive = Join-Path $root 'desktop/receive'; $bReceive = Join-Path $root 'cli/receive'
$aInput = Join-Path $root 'desktop/input'; $bInput = Join-Path $root 'cli/input'
$ready = Join-Path $root 'desktop/ready.json'; $result = Join-Path $root 'desktop/send-result.txt'
New-Item -ItemType Directory -Force $aData,$bData,$aReceive,$bReceive,$aInput,$bInput | Out-Null
# AI agent access is off by default; this test copy has it on (Settings → AI agents).
[System.IO.File]::WriteAllText((Join-Path $aData 'desktop.json'), '{"CloseToTray":true,"Theme":"system","Agents":true,"DirectLink":false,"PutReceivedTextOnClipboard":false,"ShareCopiedText":false}')

function Free-Port {
    do {
        $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
        $listener.Start(); $port = ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port; $listener.Stop()
    } while ($port -eq 7410)
    return $port
}
function Start-Hidden([string]$file, [string[]]$arguments, [bool]$capture = $false) {
    $info = [System.Diagnostics.ProcessStartInfo]::new($file)
    $info.UseShellExecute = $false; $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $capture; $info.RedirectStandardError = $capture
    # The CLI writes UTF-8 when its output is captured (symbols and non-Latin text must come back intact).
    if ($capture) { $info.StandardOutputEncoding = [System.Text.UTF8Encoding]::new($false) }
    $info.Arguments = ($arguments | ForEach-Object { '"' + $_.Replace('"', '\"') + '"' }) -join ' '
    $process = [System.Diagnostics.Process]::new(); $process.StartInfo = $info
    if (-not $process.Start()) { throw "Could not start $file" }
    return $process
}
function Wait-File([string]$path, [System.Diagnostics.Process]$process) {
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    while ([DateTime]::UtcNow -lt $deadline) {
        if (Test-Path -LiteralPath $path) { return (Get-Content -Raw -LiteralPath $path) }
        if (Test-Path -LiteralPath ($path + '.error')) { throw (Get-Content -Raw -LiteralPath ($path + '.error')) }
        if ($process.HasExited) { throw "Process exited before writing $path (exit $($process.ExitCode))" }
        Start-Sleep -Milliseconds 100
    }
    throw "Timed out waiting for $path"
}
function Check-File([string]$source, [string]$destination) {
    if (-not (Test-Path -LiteralPath $destination)) { throw "Missing received file: $destination" }
    if ((Get-FileHash -Algorithm SHA256 -LiteralPath $source).Hash -ne (Get-FileHash -Algorithm SHA256 -LiteralPath $destination).Hash) {
        throw "Hash mismatch: $destination"
    }
}
function Run-Cli([string[]]$command) {
    $arguments = @($cli, '--data', $bData, '--receive-dir', $bReceive, '--port', [string]$bPort, '--name', 'CLI peer') + $command
    $output = & dotnet @arguments 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) { throw "CLI failed: $output" }
    return $output.Trim()
}
function Run-SecondCli([string[]]$command) {
    $arguments = @($cli, '--data', $cData, '--receive-dir', $cReceive, '--port', [string]$cPort, '--name', 'Second CLI peer') + $command
    $output = & dotnet @arguments 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) { throw "Second CLI failed: $output" }
    return $output.Trim()
}
function Desktop-Send([string]$target, [string[]]$files) {
    $status = Desktop-Command (@('--e2e-send', $target) + $files)
    if (-not $status.StartsWith('PASS')) { throw "Desktop send failed: $status" }
}
function Desktop-Command([string[]]$command) {
    if (Test-Path -LiteralPath $result) { Remove-Item -LiteralPath $result }
    $forward = Start-Hidden $desktop (@('--data', $aData) + $command + @('--e2e-result', $result))
    try { if (-not $forward.WaitForExit(15000)) { throw 'Desktop argument forwarding timed out' } }
    finally { if (-not $forward.HasExited) { $forward.Kill() }; $forward.Dispose() }
    return Wait-File $result $desktopProcess
}
function Set-DesktopPeerPort([string]$id, [int]$port) {
    $path = Join-Path $aData 'devices.json'
    # Windows PowerShell 5.1's ConvertFrom-Json hands back a JSON list as one object: unroll it into its devices.
    $devices = @((Get-Content -Raw $path | ConvertFrom-Json) | ForEach-Object { $_ })
    $device = $devices | Where-Object Id -eq $id | Select-Object -First 1
    if ($null -eq $device) { throw "Desktop has no paired device $id" }
    $device.Port = $port
    [System.IO.File]::WriteAllText($path, (ConvertTo-Json -InputObject $devices -Depth 10))
}
function Wait-Received([string]$source, [string]$destination) {
    $deadline = [DateTime]::UtcNow.AddSeconds(45)
    while ([DateTime]::UtcNow -lt $deadline) {
        if (Test-Path -LiteralPath $destination) { Check-File $source $destination; return }
        Start-Sleep -Milliseconds 200
    }
    throw "Timed out waiting for $destination"
}
function Wait-CliLine([System.Diagnostics.Process]$process, [string]$pattern) {
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    while ([DateTime]::UtcNow -lt $deadline) {
        $line = $process.StandardOutput.ReadLineAsync()
        if (-not $line.Wait([Math]::Max(1, [int]($deadline - [DateTime]::UtcNow).TotalMilliseconds))) { break }
        if ($null -eq $line.Result) { break }
        if ($line.Result -match $pattern) { return $line.Result }
    }
    throw "CLI did not print $pattern"
}
function Agent-Cli([string[]]$command) {
    $process = Start-Hidden $desktop (@('agent') + $command + @('--data', $aData)) $true
    $output = $process.StandardOutput.ReadToEndAsync()
    if (-not $process.WaitForExit(120000)) { $process.Kill(); throw 'agent command timed out' }
    $text = $output.Result; $process.Dispose()
    return $text | ConvertFrom-Json
}
# A minimal MCP client over the server's standard input/output. Replies can come back in any order; match them by id.
$script:mcpReplies = @{}
function Mcp-Send([System.Diagnostics.Process]$server, [hashtable]$message) {
    $server.StandardInput.WriteLine(($message | ConvertTo-Json -Depth 10 -Compress)); $server.StandardInput.Flush()
}
function Mcp-Reply([System.Diagnostics.Process]$server, [int]$id, [int]$seconds = 60) {
    $deadline = [DateTime]::UtcNow.AddSeconds($seconds)
    while (-not $script:mcpReplies.ContainsKey($id)) {
        $left = [int]($deadline - [DateTime]::UtcNow).TotalMilliseconds
        if ($left -le 0) { throw "No MCP reply for request $id" }
        if ($null -eq $script:mcpLine) { $script:mcpLine = $server.StandardOutput.ReadLineAsync() }
        if (-not $script:mcpLine.Wait($left)) { throw "No MCP reply for request $id" }
        $text = $script:mcpLine.Result; $script:mcpLine = $null
        if ($null -eq $text) { throw 'The MCP server closed its output' }
        $reply = $text | ConvertFrom-Json
        $script:mcpReplies[[int]$reply.id] = $reply
    }
    return $script:mcpReplies[$id]
}
function Tool-Text($reply) {
    if ($null -ne $reply.error) { throw "MCP error: $($reply.error.message)" }
    if ($reply.result.isError) { throw "Tool error: $($reply.result.content[0].text)" }
    return $reply.result.content[0].text | ConvertFrom-Json
}
$aPort = Free-Port; $bPort = Free-Port; while ($bPort -eq $aPort) { $bPort = Free-Port }
$cPort = Free-Port; while ($cPort -in @($aPort, $bPort)) { $cPort = Free-Port }
$cData = Join-Path $root 'second-cli/data'; $cReceive = Join-Path $root 'second-cli/receive'
New-Item -ItemType Directory -Force $cData,$cReceive | Out-Null
$desktopProcess = $null; $cliProcess = $null; $secondCliProcess = $null
$scenario = 'Desktop listener and isolated data'
try {
    $desktopProcess = Start-Hidden $desktop @('--data', $aData, '--receive-dir', $aReceive, '--port', [string]$aPort,
        '--e2e-serve', $ready, '--e2e-auto-confirm', '--e2e-link', 'DIRECT-HopDrop-TEST,secretpassphrase1,192.168.137.1')
    $status = Wait-File $ready $desktopProcess | ConvertFrom-Json
    if ($status.Port -ne $aPort) { throw 'Desktop listened on the wrong port' }
    Write-Host 'PASS Desktop listener and isolated data'

    $scenario = 'first run opens Devices'
    if ($status.FirstPage -ne 'devices' -or -not $status.HasPairingCode) { throw "First page was $($status.FirstPage); pairing code: $($status.HasPairingCode)" }
    Write-Host 'PASS first run opens Devices with pairing code'

    $scenario = 'direct link in pairing QR'
    if ($status.Qr -notmatch 'ws=DIRECT-HopDrop-TEST' -or $status.Qr -notmatch 'wk=secretpassphrase1' -or $status.Qr -notmatch 'wa=192.168.137.1') { throw 'QR has no test direct link' }
    Write-Host 'PASS direct link in pairing QR (no Wi-Fi Direct started)'

    $cliProcess = Start-Hidden 'dotnet' @($cli, '--data', $bData, '--receive-dir', $bReceive, '--port', [string]$bPort,
        '--name', 'CLI peer', '--auto-confirm-sas', 'serve') $true
    $line = Wait-CliLine $cliProcess 'Listening on .*\(([0-9a-f]{64})\)'
    $cliId = [regex]::Match($line, '\(([0-9a-f]{64})\)').Groups[1].Value

    $scenario = 'Desktop QR pairing with CLI'
    $qr = Run-Cli @('pair-qr', $status.Qr)
    if ($qr -notmatch 'Paired by QR') { throw "QR pairing failed: $qr" }
    Set-DesktopPeerPort $cliId $bPort
    Write-Host 'PASS Desktop QR pairing with CLI'

    $scenario = 'single-instance named-pipe argument forwarding'
    $probeFile = Join-Path $aInput 'pipe-probe.txt'
    [System.IO.File]::WriteAllText($probeFile, 'pipe probe')
    $forward = Start-Hidden $desktop @('--data', $aData, '--e2e-send', ('a' * 64), $probeFile, '--e2e-result', $result)
    try { if (-not $forward.WaitForExit(15000)) { throw 'Desktop argument forwarding timed out' } }
    finally { if (-not $forward.HasExited) { $forward.Kill() }; $forward.Dispose() }
    $forwardResult = Wait-File $result $desktopProcess
    if ($forwardResult -notmatch '^FAIL .*not paired') { throw "Unexpected pipe result: $forwardResult" }
    Write-Host 'PASS single-instance named-pipe argument forwarding'

    $aFile = Join-Path $aInput 'from-desktop.txt'; $bFile = Join-Path $bInput 'from-cli.txt'
    [System.IO.File]::WriteAllText($aFile, 'Desktop to CLI')
    [System.IO.File]::WriteAllText($bFile, 'CLI to Desktop')
    $empty = Join-Path $bInput 'empty.bin'; [System.IO.File]::WriteAllBytes($empty, [byte[]]::new(0))
    $scenario = 'CLI to Desktop transfer'
    Run-Cli (@('send', "127.0.0.1:$aPort", $bFile, $empty)) | Out-Null
    Check-File $bFile (Join-Path $aReceive 'from-cli.txt'); Check-File $empty (Join-Path $aReceive 'empty.bin')
    Write-Host 'PASS CLI to Desktop transfer (SHA-256, zero-byte file)'

    $scenario = 'Desktop to CLI transfer'
    Desktop-Send "127.0.0.1:$bPort" @($aFile)
    Check-File $aFile (Join-Path $bReceive 'from-desktop.txt')
    Write-Host 'PASS Desktop to CLI transfer (SHA-256)'

    $scenario = 'text both ways without touching the clipboard'
    $linkText = 'https://example.com/' + [char]0x00FC + 'ber?x=' + [char]0x2713
    Run-Cli @('text', $status.Id, $linkText, '--clip') | Out-Null
    $history = Get-Content -Raw (Join-Path $aData 'history.json') | ConvertFrom-Json
    if (@($history | Where-Object { $_.Text -eq $linkText -and $_.Direction -eq 'received' }).Count -ne 1) { throw 'Desktop did not record the received link' }
    $outgoingText = 'Hello ' + [char]0x2713 + ' ' + [char]0x3053 + [char]0x3093 + [char]0x306B + [char]0x3061 + [char]0x306F
    if ((Desktop-Command @('--e2e-text', $cliId, $outgoingText)) -ne 'PASS text') { throw 'Desktop text hook failed' }
    Wait-CliLine $cliProcess ('Text from .*' + [regex]::Escape($outgoingText)) | Out-Null
    Write-Host 'PASS text both ways (link, Unicode, no clipboard)'

    $scenario = "send when it's back"
    $cliProcess.Kill(); $cliProcess.WaitForExit(5000) | Out-Null; $cliProcess.Dispose(); $cliProcess = $null
    $queuedFile = Join-Path $aInput 'queued.txt'; [System.IO.File]::WriteAllText($queuedFile, 'send after reconnect')
    $queued = Desktop-Command @('--e2e-send', $cliId, '--e2e-queue', $queuedFile)
    if ($queued -ne 'QUEUED') { throw "Expected QUEUED: $queued" }
    $cliProcess = Start-Hidden 'dotnet' @($cli, '--data', $bData, '--receive-dir', $bReceive, '--port', [string]$bPort,
        '--name', 'CLI peer', '--auto-confirm-sas', 'serve') $true
    Wait-CliLine $cliProcess 'Listening on' | Out-Null
    $contact = Join-Path $bInput 'queue-contact.txt'; [System.IO.File]::WriteAllText($contact, 'contact')
    Run-Cli @('send', "127.0.0.1:$aPort", $contact) | Out-Null
    Wait-Received $queuedFile (Join-Path $bReceive 'queued.txt')
    $deadline = [DateTime]::UtcNow.AddSeconds(10)
    while ((Get-Content -Raw (Join-Path $aData 'queue.json')).Trim() -ne '[]' -and [DateTime]::UtcNow -lt $deadline) { Start-Sleep -Milliseconds 100 }
    if ((Get-Content -Raw (Join-Path $aData 'queue.json')).Trim() -ne '[]') { throw 'Queue was not cleared after delivery' }
    Write-Host "PASS send when it's back (SHA-256, automatic retry, empty queue)"

    $scenario = 'cancel a waiting send'
    $cliProcess.Kill(); $cliProcess.WaitForExit(5000) | Out-Null; $cliProcess.Dispose(); $cliProcess = $null
    $cancelFile = Join-Path $aInput 'cancel-me.txt'; [System.IO.File]::WriteAllText($cancelFile, 'do not send')
    if ((Desktop-Command @('--e2e-send', $cliId, '--e2e-queue', $cancelFile)) -ne 'QUEUED') { throw 'Cancel probe was not queued' }
    if ((Desktop-Command @('--e2e-cancel', $cliId)) -ne 'PASS cancelled') { throw 'Cancel hook failed' }
    if ((Get-Content -Raw (Join-Path $aData 'queue.json')).Trim() -ne '[]') { throw 'Cancelled send is still queued' }
    $cliProcess = Start-Hidden 'dotnet' @($cli, '--data', $bData, '--receive-dir', $bReceive, '--port', [string]$bPort,
        '--name', 'CLI peer', '--auto-confirm-sas', 'serve') $true
    Wait-CliLine $cliProcess 'Listening on' | Out-Null
    Run-Cli @('send', "127.0.0.1:$aPort", $contact) | Out-Null
    if (Test-Path -LiteralPath (Join-Path $bReceive 'cancel-me.txt')) { throw 'Cancelled file was sent' }
    Write-Host 'PASS cancel a waiting send'

    $scenario = 'Desktop number-match pairing with CLI'
    Run-Cli @('unpair', $status.Id) | Out-Null
    $sas = Run-Cli @('pair-sas', "127.0.0.1:$aPort", '--auto-confirm-sas')
    if ($sas -match 'Paired by number match: (\d{3} \d{3})') { $clientCode = $Matches[1] }
    else { throw "Number-match pairing failed: $sas" }
    $desktopCode = Wait-File ($ready + '.sas') $desktopProcess
    if ($clientCode -ne $desktopCode) { throw "Number-match codes differ: $clientCode / $desktopCode" }
    Set-DesktopPeerPort $cliId $bPort
    Write-Host 'PASS Desktop number-match pairing with CLI'

    $scenario = 'both directions after number match'
    Run-Cli @('send', "127.0.0.1:$aPort", $bFile) | Out-Null
    Desktop-Send "127.0.0.1:$bPort" @($aFile)
    Check-File $bFile (Join-Path $aReceive 'from-cli (1).txt')
    Check-File $aFile (Join-Path $bReceive 'from-desktop (1).txt')
    Write-Host 'PASS both directions after number match (SHA-256)'

    $scenario = 'paired CLI learns direct link from hello'
    $learned = Run-Cli @('devices')
    if ($learned -notmatch 'link=DIRECT-HopDrop-TEST \(192\.168\.137\.1\)') { throw "CLI did not save the link from hello: $learned" }
    Write-Host 'PASS paired CLI learns direct link from hello'

    $scenario = 'send to several devices'
    $secondCliProcess = Start-Hidden 'dotnet' @($cli, '--data', $cData, '--receive-dir', $cReceive, '--port', [string]$cPort,
        '--name', 'Second CLI peer', '--auto-confirm-sas', 'serve') $true
    $secondLine = Wait-CliLine $secondCliProcess 'Listening on .*\(([0-9a-f]{64})\)'
    $secondId = [regex]::Match($secondLine, '\(([0-9a-f]{64})\)').Groups[1].Value
    $secondQr = Desktop-Command @('--e2e-qr')
    if ((Run-SecondCli @('pair-qr', $secondQr)) -notmatch 'Paired by QR') { throw 'Second CLI could not pair' }
    Set-DesktopPeerPort $secondId $cPort
    $multiFile = Join-Path $aInput 'to-both.txt'; [System.IO.File]::WriteAllText($multiFile, 'same file to both')
    if ((Desktop-Command @('--e2e-multi', "$cliId,$secondId", $multiFile)) -ne 'PASS 2') { throw 'Desktop multi-send failed' }
    Check-File $multiFile (Join-Path $bReceive 'to-both.txt')
    Check-File $multiFile (Join-Path $cReceive 'to-both.txt')
    Write-Host 'PASS send to several devices (SHA-256 each)'

    $scenario = 'AI agents over MCP'
    $info = [System.Diagnostics.ProcessStartInfo]::new($desktop)
    $info.UseShellExecute = $false; $info.CreateNoWindow = $true
    $info.RedirectStandardInput = $true; $info.RedirectStandardOutput = $true
    $info.Arguments = '--mcp --data "' + $aData + '"'
    $server = [System.Diagnostics.Process]::Start($info)
    $server.StandardInput.AutoFlush = $true
    try {
        Mcp-Send $server @{ jsonrpc = '2.0'; id = 1; method = 'initialize'; params = @{ protocolVersion = '2025-06-18'; capabilities = @{}; clientInfo = @{ name = 'e2e'; version = '1' } } }
        $init = Mcp-Reply $server 1
        if ($init.result.protocolVersion -ne '2025-06-18' -or $init.result.serverInfo.name -ne 'hopdrop') { throw "Bad initialize reply: $($init | ConvertTo-Json -Depth 6 -Compress)" }
        Mcp-Send $server @{ jsonrpc = '2.0'; method = 'notifications/initialized' }
        Mcp-Send $server @{ jsonrpc = '2.0'; id = 2; method = 'tools/list' }
        $names = (Mcp-Reply $server 2).result.tools | ForEach-Object name
        foreach ($tool in 'list_devices', 'send_files', 'transfer_status', 'recent_transfers', 'wait_for_files') { if ($names -notcontains $tool) { throw "Missing tool $tool" } }
        Mcp-Send $server @{ jsonrpc = '2.0'; id = 3; method = 'tools/call'; params = @{ name = 'list_devices'; arguments = @{} } }
        $devices = Tool-Text (Mcp-Reply $server 3)
        if (@($devices.devices | Where-Object name -eq 'CLI peer').Count -ne 1) { throw "list_devices doesn't show the CLI peer: $($devices | ConvertTo-Json -Depth 6 -Compress)" }
        $folder = Join-Path $aInput 'Agent folder'; New-Item -ItemType Directory -Force (Join-Path $folder 'sub') | Out-Null
        [System.IO.File]::WriteAllText((Join-Path $folder 'top.txt'), 'top'); [System.IO.File]::WriteAllText((Join-Path $folder 'sub/inner.txt'), 'inner')
        Mcp-Send $server @{ jsonrpc = '2.0'; id = 4; method = 'tools/call'; params = @{ name = 'send_files'; arguments = @{ device = 'CLI peer'; paths = @($folder, $aFile); wait_seconds = 60 } } }
        $sent = Tool-Text (Mcp-Reply $server 4 90)
        if ($sent.status -ne 'sent' -or $sent.files -ne 3) { throw "send_files: $($sent | ConvertTo-Json -Compress)" }
        Check-File (Join-Path $folder 'sub/inner.txt') (Join-Path $bReceive 'Agent folder/sub/inner.txt')
        Check-File (Join-Path $folder 'top.txt') (Join-Path $bReceive 'Agent folder/top.txt')
        Mcp-Send $server @{ jsonrpc = '2.0'; id = 5; method = 'tools/call'; params = @{ name = 'send_files'; arguments = @{ device = 'Nobody'; paths = @($aFile) } } }
        $wrong = Mcp-Reply $server 5
        if (-not $wrong.result.isError -or $wrong.result.content[0].text -notmatch 'No paired device called') { throw 'An unknown device should give a clear error' }
        # wait_for_files runs while the CLI peer sends; a ping still gets answered meanwhile.
        Mcp-Send $server @{ jsonrpc = '2.0'; id = 6; method = 'tools/call'; params = @{ name = 'wait_for_files'; arguments = @{ timeout_seconds = 60 } } }
        Start-Sleep -Milliseconds 500
        Mcp-Send $server @{ jsonrpc = '2.0'; id = 7; method = 'ping' }
        $null = Mcp-Reply $server 7 10
        $agentInbound = Join-Path $bInput 'for-the-agent.txt'; [System.IO.File]::WriteAllText($agentInbound, 'agent inbound')
        Run-Cli @('send', "127.0.0.1:$aPort", $agentInbound) | Out-Null
        $arrived = Tool-Text (Mcp-Reply $server 6 90)
        if ($arrived.status -ne 'received' -or @($arrived.files)[0] -ne (Join-Path $aReceive 'for-the-agent.txt')) { throw "wait_for_files: $($arrived | ConvertTo-Json -Compress)" }
        Mcp-Send $server @{ jsonrpc = '2.0'; id = 8; method = 'tools/call'; params = @{ name = 'recent_transfers'; arguments = @{ limit = 2; direction = 'received' } } }
        $recent = Tool-Text (Mcp-Reply $server 8)
        if (@($recent.transfers)[0].files[0] -ne (Join-Path $aReceive 'for-the-agent.txt')) { throw "recent_transfers: $($recent | ConvertTo-Json -Depth 6 -Compress)" }
    }
    finally { $server.StandardInput.Close(); if (-not $server.WaitForExit(5000)) { $server.Kill() }; $server.Dispose() }
    Write-Host 'PASS AI agents over MCP (devices, folder send, clear errors, wait for files, recent)'

    $scenario = 'AI agents from the command line'
    $cliFile = Join-Path $aInput 'agent-cli.txt'; [System.IO.File]::WriteAllText($cliFile, 'agent cli')
    $reply = Agent-Cli @('send', '--to', 'CLI peer', $cliFile)
    if (-not $reply.ok -or $reply.status -ne 'sent') { throw "agent send: $($reply | ConvertTo-Json -Compress)" }
    Check-File $cliFile (Join-Path $bReceive 'agent-cli.txt')
    $listed = Agent-Cli @('devices')
    if (-not $listed.ok -or @($listed.devices).Count -lt 1) { throw "agent devices: $($listed | ConvertTo-Json -Compress)" }
    Write-Host 'PASS AI agents from the command line (send, devices)'
    Write-Host "PASS DesktopE2E; data: $root"
    exit 0
}
catch {
    Write-Host "FAIL $scenario`: $($_.Exception.Message)"
    Write-Host "Data: $root"
    exit 1
}
finally {
    foreach ($temporary in @($ready, ($ready + '.sas'))) {
        if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary }
    }
    foreach ($process in @($desktopProcess, $cliProcess, $secondCliProcess)) {
        if ($null -ne $process) {
            if (-not $process.HasExited) { $process.Kill(); $process.WaitForExit(5000) | Out-Null }
            $process.Dispose()
        }
    }
}
