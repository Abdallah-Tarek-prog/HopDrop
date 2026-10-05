param([switch]$Debug,[switch]$Release,[switch]$Test,[switch]$Install,[switch]$Interop)
# HopDrop for Android.
#   -Test     the phone's transfer engine, checked on the desktop JVM (no Android SDK needed)
#   -Interop  the phone's engine against the Windows engine (needs the .NET SDK)
#   -Debug    debug app "HopDrop Dev" (com.hop.drop.dev, installs next to the real app)
#   -Release  the release app, signed with the key in "Private signing key/", copied to HopDrop.apk at the repo root
#   -Install  also install it on the phone connected over adb
# Needs JDK 17+ (JAVA_HOME, or an installed Temurin/Microsoft/Oracle JDK) and, for -Debug/-Release, the Android SDK
# (ANDROID_HOME, or Android Studio's default %LOCALAPPDATA%\Android\Sdk). Gradle downloads everything else.
# Native tools (javac, Gradle) write notes and progress to stderr. With 'Stop', Windows PowerShell 5 would treat that
# as a failure, so every native call is checked through its exit code instead.
$ErrorActionPreference='Continue'
$root=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$android=Join-Path $root 'Source/Android'

function Find-Jdk {
    if($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin/javac.exe'))){return $env:JAVA_HOME}
    $places=@("$env:LOCALAPPDATA\Programs\Eclipse Adoptium","$env:ProgramFiles\Eclipse Adoptium","$env:ProgramFiles\Microsoft","$env:ProgramFiles\Java")
    $found=foreach($place in $places){if(Test-Path $place){Get-ChildItem $place -Directory | Where-Object { Test-Path (Join-Path $_.FullName 'bin/javac.exe') }}}
    $best=$found | Where-Object { $_.Name -match '(\d+)' -and [int]$Matches[1] -ge 17 } | Sort-Object { [int]([regex]::Match($_.Name,'\d+').Value) } -Descending | Select-Object -First 1
    if(-not $best){throw 'JDK 17 or newer is needed: install one (for example Eclipse Temurin 21) or set JAVA_HOME.'}
    return $best.FullName
}
$env:JAVA_HOME=Find-Jdk
$env:PATH=(Join-Path $env:JAVA_HOME 'bin')+';'+$env:PATH
$javac=Join-Path $env:JAVA_HOME 'bin/javac.exe'
$java=Join-Path $env:JAVA_HOME 'bin/java.exe'
$out=Join-Path $android 'out'
New-Item -ItemType Directory -Force $out | Out-Null

if($Test -or $Interop){
    Push-Location $root
    try{
        $classes=Join-Path $out ('jvm-classes-'+[guid]::NewGuid().ToString('N'));New-Item -ItemType Directory -Force $classes | Out-Null
        $classesRel='Source/Android/out/'+(Split-Path $classes -Leaf)
        $core=Get-ChildItem (Join-Path $android 'src/com/hop/drop/core') -Filter '*.java' | ForEach-Object FullName
        $tests=Get-ChildItem (Join-Path $android 'tests') -Filter '*.java' | ForEach-Object FullName
        $argsFile=Join-Path $out 'jvm-javac-args.txt'
        @($core+$tests | ForEach-Object { '"'+$_.Replace('\','/')+'"' }) | Set-Content $argsFile -Encoding Ascii
        & $javac -encoding UTF-8 -d $classesRel '@Source/Android/out/jvm-javac-args.txt' 2>&1 | ForEach-Object { "$_" }
        if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
        if($Test){
            & $java -cp $classes com.hop.drop.tests.CoreTests;if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
            $testRun=Join-Path $out ("jvm/run-"+[guid]::NewGuid().ToString('N'))
            foreach($n in @('a','b')){
                $dir=Join-Path $testRun $n;New-Item -ItemType Directory -Force $dir | Out-Null
                $identity=Join-Path $dir 'identity.p12'
                & (Join-Path $env:JAVA_HOME 'bin/keytool.exe') -genkeypair -alias identity -keyalg EC -groupname secp256r1 -validity 10000 -dname "CN=HopDrop $n" -storetype PKCS12 -keystore $identity -storepass android -keypass android -noprompt
                if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
            }
            & $java -cp $classes com.hop.drop.tests.PeerTests $testRun;exit $LASTEXITCODE
        }
        & (Join-Path $android 'interop.ps1') -Java $java -Classes $classes -Root $root;exit $LASTEXITCODE
    }finally{Pop-Location}
}

if(-not $Debug -and -not $Release){throw 'Choose -Debug, -Release, -Test or -Interop'}
if($Debug -and $Release){throw 'Choose one of -Debug and -Release'}

# Where the Android SDK is: local.properties (not in git), else ANDROID_HOME, else Android Studio's default folder.
$properties=Join-Path $android 'local.properties'
if(-not (Test-Path $properties)){
    $sdk=if($env:ANDROID_HOME){$env:ANDROID_HOME}elseif($env:ANDROID_SDK_ROOT){$env:ANDROID_SDK_ROOT}else{Join-Path $env:LOCALAPPDATA 'Android\Sdk'}
    if(-not (Test-Path $sdk)){throw "No Android SDK found. Install Android Studio, or set ANDROID_HOME."}
    [IO.File]::WriteAllText($properties,'sdk.dir='+($sdk -replace '\\','/' -replace ':','\:')+"`n")
}
$sdk=((Get-Content $properties | Where-Object { $_ -like 'sdk.dir=*' }) -replace '^sdk.dir=','' -replace '\\:',':')

$task=if($Debug){'assembleDebug'}else{'assembleRelease'}
& (Join-Path $android 'gradlew.bat') -p $android $task --console=plain 2>&1 | ForEach-Object { "$_" }
if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
if($Debug){
    $apk=Join-Path $android 'build/outputs/apk/debug/HopDrop-debug.apk'
}else{
    $apk=Join-Path $root 'HopDrop.apk'
    Copy-Item (Join-Path $android 'build/outputs/apk/release/HopDrop-release.apk') $apk -Force
}
$adb=Join-Path $sdk 'platform-tools/adb.exe'
if(-not (Test-Path $adb)){$adb='adb'}
if($Install){& $adb install -r $apk;if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}}
Write-Host "Built $apk ($([math]::Round((Get-Item $apk).Length/1MB,1)) MB)"
