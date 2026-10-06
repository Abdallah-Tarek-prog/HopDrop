param(
    [switch]$Test,
    [switch]$E2E,
    [switch]$DesktopE2E,
    [switch]$Publish,
    [switch]$Store,
    [switch]$Install,
    [switch]$Isolated
)
$ErrorActionPreference = 'Stop'
$root = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$solution = Join-Path $PSScriptRoot 'HopDrop.sln'
$cliProject = Join-Path $PSScriptRoot 'HopDrop.Cli/HopDrop.Cli.csproj'
$desktopProject = Join-Path $PSScriptRoot 'HopDrop.Desktop/HopDrop.Desktop.csproj'
$cliDll = Join-Path $PSScriptRoot 'HopDrop.Cli/bin/Release/net10.0/HopDrop.Cli.dll'
if ($Isolated) {
    $scratch = Join-Path $root '.scratch'
    $env:DOTNET_CLI_HOME = Join-Path $scratch 'dotnet'
    $env:NUGET_PACKAGES = Join-Path $scratch 'nuget'
    $env:TEMP = Join-Path $scratch 'tmp'
    $env:TMP = $env:TEMP
    $env:APPDATA = Join-Path $scratch 'profile/Roaming'
    $env:LOCALAPPDATA = Join-Path $scratch 'profile/Local'
    $env:USERPROFILE = Join-Path $scratch 'profile'
    $env:DOTNET_CLI_TELEMETRY_OPTOUT = '1'
    New-Item -ItemType Directory -Force -Path $env:DOTNET_CLI_HOME,$env:NUGET_PACKAGES,$env:TEMP,$env:APPDATA,$env:LOCALAPPDATA | Out-Null
}
function Restore-Target([string]$target, [string]$runtime) {
    $arguments = @('restore', $target, '-p:NuGetAudit=false', '-m:1')
    if ($runtime) { $arguments += @('-r', $runtime) }
    $feed = Join-Path $root '.scratch/nuget-feed'
    if ($Isolated -and (Test-Path (Join-Path $feed 'wpf-ui.4.3.0.nupkg'))) { $arguments += @('--source', $feed) }
    & dotnet @arguments
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
if ($Publish) {
    # For PCs (x64) and ARM laptops (arm64): a portable HopDrop.exe in "Windows App", and an installer with automatic
    # updates (Velopack) in "Windows App/Installer". Release the installer files on GitHub Releases for updates to reach users.
    $version = ([xml](Get-Content $desktopProject)).Project.PropertyGroup.Version | Where-Object { $_ } | Select-Object -First 1
    $portable = Join-Path $root 'Windows App'
    $installer = Join-Path $portable 'Installer'
    $icon = Join-Path $PSScriptRoot 'HopDrop.Desktop/Assets/HopDrop.ico'
    New-Item -ItemType Directory -Force -Path $portable, $installer | Out-Null
    foreach ($runtime in 'win-x64', 'win-arm64') {
        Restore-Target $desktopProject $runtime
        $common = @('-c', 'Release', '-r', $runtime, '--self-contained', 'true', '--no-restore', '-m:1', '-p:PublishTrimmed=false', '-p:DebugType=None', '-p:DebugSymbols=false')
        $single = Join-Path $PSScriptRoot "out/portable-$runtime"
        & dotnet publish $desktopProject @common -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -p:EnableCompressionInSingleFile=true -o $single
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
        $name = if ($runtime -eq 'win-x64') { 'HopDrop.exe' } else { 'HopDrop-arm64.exe' }
        Copy-Item (Join-Path $single 'HopDrop.exe') (Join-Path $portable $name) -Force
        $folder = Join-Path $PSScriptRoot "out/install-$runtime"
        if (Test-Path $folder) { Remove-Item -Recurse -Force $folder }
        & dotnet publish $desktopProject @common -p:PublishSingleFile=false -o $folder
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
        $channel = if ($runtime -eq 'win-x64') { 'win' } else { 'win-arm64' }
        & vpk pack --packId HopDrop --packVersion $version --packTitle HopDrop --packDir $folder --mainExe HopDrop.exe --icon $icon `
            --runtime $runtime --channel $channel --outputDir $installer
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
        Write-Host "PASS Publish $runtime`: $name ($([math]::Round((Get-Item (Join-Path $portable $name)).Length / 1MB, 1)) MiB) and the $channel installer"
    }
    exit 0
}
if ($Store) {
    # Microsoft Store package (MSIX) for PCs and ARM laptops, plus one .msixbundle holding both for Partner Center, in
    # "Windows App/Store". Store/identity.json has the identity Partner Center gave the app.
    # The Store signs the package itself, so nothing is signed here. -Install also installs the x64 build on this PC for
    # testing (needs Windows' Developer Mode; it replaces an earlier test install).
    $version = ([xml](Get-Content $desktopProject)).Project.PropertyGroup.Version | Where-Object { $_ } | Select-Object -First 1
    $msixVersion = "$version.0"
    $kits = ${env:ProgramFiles(x86)} + '/Windows Kits/10/bin'
    $sdk = Get-ChildItem $kits -Directory | Where-Object { $_.Name -match '^10\.' -and (Test-Path (Join-Path $_.FullName 'x64\makeappx.exe')) } |
        Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
    if (-not $sdk) { throw 'The Windows SDK (makeappx.exe) is needed for -Store.' }
    $makeappx = Join-Path $sdk.FullName 'x64\makeappx.exe'; $makepri = Join-Path $sdk.FullName 'x64\makepri.exe'
    $identity = Get-Content -Raw (Join-Path $PSScriptRoot 'Store/identity.json') | ConvertFrom-Json
    $template = Get-Content -Raw (Join-Path $PSScriptRoot 'Store/AppxManifest.xml')
    $output = Join-Path $root 'Windows App/Store'
    $bundleDir = Join-Path $PSScriptRoot 'out/store-bundle'
    foreach ($folder in $output, $bundleDir) { if (Test-Path $folder) { Remove-Item -Recurse -Force $folder }; New-Item -ItemType Directory -Force $folder | Out-Null }
    $assets = Join-Path $PSScriptRoot 'out/store-assets'
    & powershell -NoProfile -File (Join-Path $PSScriptRoot 'Store/make-assets.ps1') -Out $assets
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    # Test installs get their own identity and live outside the repository, so they never clash with the Store version
    # and keep working when the repository folder moves.
    $testName = 'HopDrop.LocalTest'
    # Not under AppData: Windows refuses to register a package folder from there.
    $testFolder = Join-Path $env:USERPROFILE 'HopDrop Store test'
    if ($Install) { Get-AppxPackage -Name $testName | Remove-AppxPackage }
    foreach ($runtime in 'win-x64', 'win-arm64') {
        $arch = $runtime.Substring(4)
        Restore-Target $desktopProject $runtime
        $layout = Join-Path $PSScriptRoot "out/store-$arch"
        if (Test-Path $layout) { Remove-Item -Recurse -Force $layout }
        & dotnet publish $desktopProject -c Release -r $runtime --self-contained true --no-restore -m:1 -p:PublishTrimmed=false -p:DebugType=None -p:DebugSymbols=false -p:PublishSingleFile=false -o $layout
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
        $manifest = $template.Replace('{Name}', $identity.Name).Replace('{Publisher}', [System.Security.SecurityElement]::Escape($identity.Publisher)).
            Replace('{PublisherDisplayName}', [System.Security.SecurityElement]::Escape($identity.PublisherDisplayName)).Replace('{Version}', $msixVersion).Replace('{Arch}', $arch)
        [System.IO.File]::WriteAllText((Join-Path $layout 'AppxManifest.xml'), $manifest, [System.Text.UTF8Encoding]::new($false))
        Copy-Item $assets (Join-Path $layout 'Assets') -Recurse
        # resources.pri tells Windows which logo file fits each size and display scaling. Index only the logos.
        $pri = Join-Path $PSScriptRoot "out/store-pri-$arch"
        if (Test-Path $pri) { Remove-Item -Recurse -Force $pri }
        New-Item -ItemType Directory -Force $pri | Out-Null
        Copy-Item $assets (Join-Path $pri 'Assets') -Recurse
        Copy-Item (Join-Path $layout 'AppxManifest.xml') $pri
        & $makepri createconfig /cf (Join-Path $pri 'priconfig.xml') /dq en-US /pv 10.0.0 /o | Out-Null
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
        & $makepri new /pr $pri /cf (Join-Path $pri 'priconfig.xml') /mn (Join-Path $pri 'AppxManifest.xml') /of (Join-Path $layout 'resources.pri') /o | Out-Null
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
        $msix = Join-Path $bundleDir "HopDrop_$msixVersion`_$arch.msix"
        & $makeappx pack /o /d $layout /p $msix | Out-Null
        if ($LASTEXITCODE -ne 0) { & $makeappx pack /o /d $layout /p $msix; exit $LASTEXITCODE }
        Copy-Item $msix $output
        Write-Host "PASS Store package $arch`: $(Split-Path $msix -Leaf) ($([math]::Round((Get-Item $msix).Length / 1MB, 1)) MiB)"
    }
    $bundle = Join-Path $output "HopDrop_$msixVersion.msixbundle"
    & $makeappx bundle /o /d $bundleDir /p $bundle /bv $msixVersion | Out-Null
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    Write-Host "PASS Store bundle: $(Split-Path $bundle -Leaf) ($([math]::Round((Get-Item $bundle).Length / 1MB, 1)) MiB) - upload this one to Partner Center"
    if ($Install) {
        if (Test-Path $testFolder) { Remove-Item -Recurse -Force $testFolder }
        Copy-Item (Join-Path $PSScriptRoot 'out/store-x64') $testFolder -Recurse
        $manifest = $template.Replace('{Name}', $testName).Replace('{Publisher}', 'CN=HopDrop Local Test').
            Replace('{PublisherDisplayName}', 'HopDrop test').Replace('{Version}', $msixVersion).Replace('{Arch}', 'x64')
        [System.IO.File]::WriteAllText((Join-Path $testFolder 'AppxManifest.xml'), $manifest, [System.Text.UTF8Encoding]::new($false))
        # resources.pri is indexed by package name, so the test copy needs its own.
        $pri = Join-Path $PSScriptRoot 'out/store-pri-test'
        if (Test-Path $pri) { Remove-Item -Recurse -Force $pri }
        New-Item -ItemType Directory -Force $pri | Out-Null
        Copy-Item $assets (Join-Path $pri 'Assets') -Recurse
        Copy-Item (Join-Path $testFolder 'AppxManifest.xml') $pri
        & $makepri createconfig /cf (Join-Path $pri 'priconfig.xml') /dq en-US /pv 10.0.0 /o | Out-Null
        & $makepri new /pr $pri /cf (Join-Path $pri 'priconfig.xml') /mn (Join-Path $pri 'AppxManifest.xml') /of (Join-Path $testFolder 'resources.pri') /o | Out-Null
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
        Add-AppxPackage -Register (Join-Path $testFolder 'AppxManifest.xml')
        Write-Host "PASS Installed the x64 Store build on this PC for testing ($testName, from $testFolder)"
    }
    exit 0
}
if ($Test) {
    Restore-Target $solution ''
    & dotnet build $solution -c Release --no-restore -m:1
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & dotnet test $solution -c Release --no-build -m:1 --logger 'console;verbosity=normal'
    exit $LASTEXITCODE
}
if ($DesktopE2E) {
    Restore-Target $solution ''
    & dotnet build $solution -c Release --no-restore -m:1
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & powershell -NoProfile -File (Join-Path $PSScriptRoot 'DesktopE2E.ps1')
    exit $LASTEXITCODE
}
if (-not $E2E) {
    Restore-Target $solution ''
    & dotnet build $solution -c Release --no-restore -m:1
    exit $LASTEXITCODE
}
Restore-Target $cliProject ''
& dotnet build $cliProject -c Release --no-restore
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$run = Join-Path $PSScriptRoot ('.e2e/' + [guid]::NewGuid().ToString('N'))
$cliRun = Join-Path $run 'cli'
New-Item -ItemType Directory -Force -Path $cliRun | Out-Null
Copy-Item -Path (Join-Path (Split-Path $cliDll) '*') -Destination $cliRun -Recurse
$cliDll = Join-Path $cliRun 'HopDrop.Cli.dll'
$aData = Join-Path $run 'a/data'; $bData = Join-Path $run 'b/data'
$aReceive = Join-Path $run 'a/receive'; $bReceive = Join-Path $run 'b/receive'
$aInput = Join-Path $run 'a/input'; $bInput = Join-Path $run 'b/input'
New-Item -ItemType Directory -Force -Path $aData,$bData,$aReceive,$bReceive,$aInput,$bInput | Out-Null

function Free-Port {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    $listener.Start()
    $number = ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port
    $listener.Stop()
    return $number
}
$aPort = Free-Port; $bPort = Free-Port
while ($bPort -eq $aPort) { $bPort = Free-Port }
function Args-For([string]$data, [string]$receive, [int]$port, [string]$name, [string[]]$command) {
    return @('--data', $data, '--receive-dir', $receive, '--port', [string]$port, '--name', $name) + $command
}
function New-Process([string[]]$arguments) {
    $info = [System.Diagnostics.ProcessStartInfo]::new('dotnet')
    $info.UseShellExecute = $false; $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true; $info.RedirectStandardError = $true
    $info.Arguments = ((@($cliDll) + $arguments) | ForEach-Object { '"' + $_.Replace('"', '\"') + '"' }) -join ' '
    $process = [System.Diagnostics.Process]::new(); $process.StartInfo = $info
    if (-not $process.Start()) { throw 'Could not start CLI' }
    return $process
}
function Run-Cli([string[]]$arguments) {
    $process = New-Process $arguments
    try {
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(180000)) { throw 'CLI timed out' }
        $result = $stdout.GetAwaiter().GetResult().Trim()
        $errorText = $stderr.GetAwaiter().GetResult().Trim()
        if ($process.ExitCode -ne 0) { throw "CLI exit $($process.ExitCode): $errorText $result" }
        return $result
    }
    finally { if (-not $process.HasExited) { $process.Kill() }; $process.Dispose() }
}
function Start-Server([string[]]$arguments) {
    $process = New-Process $arguments
    $history = [System.Collections.Generic.List[string]]::new()
    return @{ Process = $process; History = $history; OutTask = $process.StandardOutput.ReadLineAsync(); ErrTask = $process.StandardError.ReadLineAsync() }
}
function Wait-Line($server, [string]$pattern) {
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    while ([DateTime]::UtcNow -lt $deadline) {
        foreach ($side in @('Out', 'Err')) {
            $key = $side + 'Task'
            $task = $server[$key]
            if ($null -ne $task -and $task.IsCompleted) {
                $line = $task.GetAwaiter().GetResult()
                if ($null -ne $line) {
                    $server.History.Add($line)
                    if ($line -like 'Pairing URI:*') { Write-Host 'Pairing URI ready' } else { Write-Host $line }
                    $server[$key] = $(if ($side -eq 'Out') { $server.Process.StandardOutput.ReadLineAsync() } else { $server.Process.StandardError.ReadLineAsync() })
                }
                else { $server[$key] = $null }
            }
        }
        foreach ($entry in $server.History) { if ($entry -match $pattern) { return $Matches[1] } }
        if ($server.Process.HasExited) { throw "Server exited while waiting for $pattern" }
        Start-Sleep -Milliseconds 100
    }
    throw "Timed out waiting for $pattern"
}
function Stop-Server($server) {
    if ($null -eq $server) { return }
    if (-not $server.Process.HasExited) { $server.Process.Kill(); $server.Process.WaitForExit(5000) | Out-Null }
    $server.Process.Dispose()
}
function Check-File([string]$from, [string]$to) {
    if (-not (Test-Path -LiteralPath $to)) { throw "Missing received file: $to" }
    $a = (Get-FileHash -Algorithm SHA256 -LiteralPath $from).Hash
    $b = (Get-FileHash -Algorithm SHA256 -LiteralPath $to).Hash
    if ($a -ne $b) { throw "Hash mismatch: $to" }
}
function Make-Input([string]$folder) {
    [System.IO.File]::WriteAllText((Join-Path $folder 'small.txt'), 'HopDrop Stage 1')
    [System.IO.File]::WriteAllBytes((Join-Path $folder 'empty.bin'), [byte[]]::new(0))
    [System.IO.File]::WriteAllText((Join-Path $folder 'تقرير 📁.txt'), 'Unicode')
    $path = Join-Path $folder 'random.bin'
    $stream = [System.IO.File]::Create($path)
    $random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $buffer = [byte[]]::new(1048576)
        for ($i = 0; $i -lt 50; $i++) { $random.GetBytes($buffer); $stream.Write($buffer, 0, $buffer.Length) }
    }
    finally { $random.Dispose(); $stream.Dispose() }
}
$a = $null; $b = $null
try {
    $a = Start-Server (Args-For $aData $aReceive $aPort 'Peer A' @('serve', '--show-qr', '--qr-address', '127.0.0.1', '--auto-confirm-sas'))
    $b = Start-Server (Args-For $bData $bReceive $bPort 'Peer B' @('serve', '--auto-confirm-sas'))
    $aId = Wait-Line $a 'Listening on .*\(([0-9a-f]{64})\)'
    $bId = Wait-Line $b 'Listening on .*\(([0-9a-f]{64})\)'
    $uri = Wait-Line $a 'Pairing URI: (hopdrop://\S+)'
    $qrResult = Run-Cli (Args-For $bData $bReceive $bPort 'Peer B' @('pair-qr', $uri))
    if ($qrResult -notmatch 'Paired by QR') { throw 'QR pairing failed' }
    Write-Host 'QR pairing passed'
    Run-Cli (Args-For $bData $bReceive $bPort 'Peer B' @('unpair', $aId)) | Out-Null
    $sasResult = Run-Cli (Args-For $bData $bReceive $bPort 'Peer B' @('pair-sas', "127.0.0.1:$aPort", '--auto-confirm-sas'))
    if ($sasResult -notmatch 'Paired by number match: (\d{3} \d{3})') { throw 'SAS pairing failed' }
    $clientCode = $Matches[1]
    $serverCode = Wait-Line $a 'Pairing code with .*: (\d{3} \d{3})'
    if ($clientCode -ne $serverCode) { throw 'SAS codes differ' }
    Write-Host "SAS pairing passed: $clientCode"
    Make-Input $aInput; Make-Input $bInput
    $names = @('small.txt', 'empty.bin', 'تقرير 📁.txt', 'random.bin')
    $aFiles = @($names | ForEach-Object { Join-Path $aInput $_ })
    $bFiles = @($names | ForEach-Object { Join-Path $bInput $_ })
    Run-Cli (Args-For $aData $aReceive $aPort 'Peer A' (@('send', "127.0.0.1:$bPort") + $aFiles)) | Out-Null
    Run-Cli (Args-For $bData $bReceive $bPort 'Peer B' (@('send', "127.0.0.1:$aPort") + $bFiles)) | Out-Null
    foreach ($name in $names) { Check-File (Join-Path $aInput $name) (Join-Path $bReceive $name); Check-File (Join-Path $bInput $name) (Join-Path $aReceive $name) }
    Write-Host 'Both transfer directions passed SHA-256 checks'
    Stop-Server $a; Stop-Server $b; $a = $null; $b = $null
    $a = Start-Server (Args-For $aData $aReceive $aPort 'Peer A' @('serve', '--auto-confirm-sas'))
    $b = Start-Server (Args-For $bData $bReceive $bPort 'Peer B' @('serve', '--auto-confirm-sas'))
    Wait-Line $a 'Listening on .*\(([0-9a-f]{64})\)' | Out-Null
    Wait-Line $b 'Listening on .*\(([0-9a-f]{64})\)' | Out-Null
    Run-Cli (Args-For $aData $aReceive $aPort 'Peer A' @('send', "127.0.0.1:$bPort", (Join-Path $aInput 'small.txt'))) | Out-Null
    Run-Cli (Args-For $bData $bReceive $bPort 'Peer B' @('send', "127.0.0.1:$aPort", (Join-Path $bInput 'small.txt'))) | Out-Null
    Check-File (Join-Path $aInput 'small.txt') (Join-Path $bReceive 'small (1).txt')
    Check-File (Join-Path $bInput 'small.txt') (Join-Path $aReceive 'small (1).txt')
    Write-Host 'Remembered pairing after restart passed'
    Write-Host "E2E passed; scratch: $run"
    exit 0
}
catch {
    foreach ($server in @($a, $b)) {
        if ($null -ne $server) {
            foreach ($key in @('OutTask', 'ErrTask')) {
                if ($null -ne $server[$key] -and $server[$key].IsCompleted) {
                    $line = $server[$key].GetAwaiter().GetResult()
                    if ($line -like 'Pairing URI:*') { Write-Host 'Pairing URI ready' } else { Write-Host $line }
                }
            }
        }
    }
    Write-Host $_.ScriptStackTrace; Write-Error $_; exit 1
}
finally { Stop-Server $a; Stop-Server $b }
