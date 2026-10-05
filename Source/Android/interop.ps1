param([Parameter(Mandatory=$true)][string]$Java,[Parameter(Mandatory=$true)][string]$Classes,[Parameter(Mandatory=$true)][string]$Root)
$ErrorActionPreference='Stop'
$project=Join-Path $Root 'Source/Windows/HopDrop.Cli/HopDrop.Cli.csproj'
& dotnet build $project -c Release -m:1 --nologo
if($LASTEXITCODE -ne 0){Write-Host 'FAIL Windows CLI build';exit $LASTEXITCODE}
$dll=Join-Path $Root 'Source/Windows/HopDrop.Cli/bin/Release/net10.0/HopDrop.Cli.dll'
$run=Join-Path $Root ('Source/Android/out/interop/run-'+[guid]::NewGuid().ToString('N'))
$data=Join-Path $run 'java';New-Item -ItemType Directory -Force $data | Out-Null
$identity=Join-Path $data 'identity.p12'
$keytool=Join-Path $env:JAVA_HOME 'bin/keytool.exe'
& $keytool -genkeypair -alias identity -keyalg EC -groupname secp256r1 -validity 10000 -dname 'CN=HopDrop Java interop' -storetype PKCS12 -keystore $identity -storepass android -keypass android -noprompt
if($LASTEXITCODE -ne 0){Write-Host 'FAIL Java test identity generation';exit $LASTEXITCODE}
& $Java -cp $Classes com.hop.drop.tests.InteropRunner $run $dll
exit $LASTEXITCODE
