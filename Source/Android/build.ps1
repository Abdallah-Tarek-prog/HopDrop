param([switch]$Debug,[switch]$Release,[switch]$Test,[switch]$Install,[switch]$Interop)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$android=Join-Path $root 'Source/Android'
$sdk='G:\Programs\Android\AndroidSdk'
$jdk='C:\Users\LOQ\AppData\Local\Programs\Eclipse Adoptium\jdk-21.0.8.9-hotspot'
$env:JAVA_HOME=$jdk
$env:PATH=(Join-Path $jdk 'bin')+';'+$env:PATH
$aapt=Join-Path $sdk 'build-tools/36.0.0/aapt2.exe'
$d8=Join-Path $sdk 'build-tools/36.0.0/d8.bat'
$align=Join-Path $sdk 'build-tools/36.0.0/zipalign.exe'
$sign=Join-Path $sdk 'build-tools/36.0.0/apksigner.bat'
$platform=Join-Path $sdk 'platforms/android-36/android.jar'
$javac=Join-Path $jdk 'bin/javac.exe'
$java=Join-Path $jdk 'bin/java.exe'
$jar=Join-Path $jdk 'bin/jar.exe'
$zxing=Join-Path $android 'libs/core-3.5.4.jar'
$out=Join-Path $android 'out'
New-Item -ItemType Directory -Force $out | Out-Null
Push-Location $root
if($Test -or $Interop){
    $classes=Join-Path $out ('jvm-classes-'+[guid]::NewGuid().ToString('N'));New-Item -ItemType Directory -Force $classes | Out-Null
    $classesRel='Source/Android/out/'+(Split-Path $classes -Leaf)
    $core=Get-ChildItem (Join-Path $android 'src/com/hop/drop/core') -Filter '*.java' | ForEach-Object FullName
    $tests=Get-ChildItem (Join-Path $android 'tests') -Filter '*.java' | ForEach-Object FullName
    $argsFile=Join-Path $out 'jvm-javac-args.txt'
    @($core+$tests | ForEach-Object { '"'+$_.Replace('\','/')+'"' }) | Set-Content $argsFile -Encoding Ascii
    & $javac -encoding UTF-8 -d $classesRel '@Source/Android/out/jvm-javac-args.txt'
    if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
    if($Test){
        & $java -cp $classes com.hop.drop.tests.CoreTests;if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
        $testRun=Join-Path $out ("jvm/run-"+[guid]::NewGuid().ToString('N'))
        foreach($n in @('a','b')){
            $dir=Join-Path $testRun $n;New-Item -ItemType Directory -Force $dir | Out-Null
            $identity=Join-Path $dir 'identity.p12'
            if(-not (Test-Path $identity)){& (Join-Path $jdk 'bin/keytool.exe') -genkeypair -alias identity -keyalg EC -groupname secp256r1 -validity 10000 -dname "CN=HopDrop $n" -storetype PKCS12 -keystore $identity -storepass android -keypass android -noprompt;if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}}
        }
        & $java -cp $classes com.hop.drop.tests.PeerTests $testRun;exit $LASTEXITCODE
    }
    if($Interop){& (Join-Path $android 'interop.ps1') -Java $java -Classes $classes -Root $root;exit $LASTEXITCODE}
}
if(-not $Debug -and -not $Release){throw 'Choose -Debug, -Release, -Test, or -Interop'}
if($Debug -and $Release){throw 'Choose one build mode'}
$mode=if($Debug){'debug'}else{'release'}
$scratch=Join-Path $out ($mode+'-'+[guid]::NewGuid().ToString('N'))
$scratchRel='Source/Android/out/'+(Split-Path $scratch -Leaf)
New-Item -ItemType Directory -Force (Join-Path $scratch 'res'),(Join-Path $scratch 'flat'),(Join-Path $scratch 'gen'),(Join-Path $scratch 'classes'),(Join-Path $scratch 'dex') | Out-Null
Copy-Item (Join-Path $android 'res/*') (Join-Path $scratch 'res') -Recurse -Force
$manifest=Join-Path $scratch 'AndroidManifest.xml';Copy-Item (Join-Path $android 'AndroidManifest.xml') $manifest -Force
if($Debug){
    (Get-Content $manifest -Raw).Replace('com.hop.drop.sharedtext','com.hop.drop.dev.sharedtext') | Set-Content $manifest -Encoding UTF8
    '<resources><string name="app_name">HopDrop Dev</string></resources>' | Set-Content (Join-Path $scratch 'res/values/strings.xml') -Encoding UTF8
}
$buildConfig=Join-Path $scratch 'gen/BuildConfig.java'
@("package com.hop.drop;","public final class BuildConfig { public static final boolean DEBUG = $($Debug.ToString().ToLowerInvariant()); }") | Set-Content $buildConfig -Encoding Ascii
& $aapt compile --dir (Join-Path $scratch 'res') -o (Join-Path $scratch 'flat')
if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
$flat=Get-ChildItem (Join-Path $scratch 'flat') -Filter '*.flat' | Sort-Object { if($_.Name -like 'values-night*'){2}elseif($_.Name -like 'values_*'){0}else{1} },Name | ForEach-Object FullName
$base=Join-Path $scratch 'base.apk'
$args=@('link','--auto-add-overlay','--manifest',$manifest,'-I',$platform,'-o',$base,'--java',(Join-Path $scratch 'gen'),'--custom-package','com.hop.drop')
if($Debug){$args+=@('--rename-manifest-package','com.hop.drop.dev')}
foreach($f in $flat){$args+=@('-R',$f)}
& $aapt @args
if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
$sources=Get-ChildItem (Join-Path $android 'src') -Filter '*.java' -Recurse | ForEach-Object FullName
$rjava=Get-ChildItem (Join-Path $scratch 'gen') -Filter 'R.java' -Recurse | ForEach-Object FullName
$androidArgs=Join-Path $scratch 'javac-args.txt'
@($sources+$rjava+@($buildConfig) | ForEach-Object { '"'+$_.Replace('\','/')+'"' }) | Set-Content $androidArgs -Encoding Ascii
& $javac -encoding UTF-8 -source 8 -target 8 -classpath "$platform;Source/Android/libs/core-3.5.4.jar" -d "$scratchRel/classes" "@$scratchRel/javac-args.txt"
if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
$classesJar=Join-Path $scratch 'classes.jar'
& $jar cf $classesJar -C (Join-Path $scratch 'classes') .
if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
& $d8 --min-api 26 --lib $platform --output (Join-Path $scratch 'dex') $classesJar $zxing
if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
Copy-Item $base (Join-Path $scratch 'unsigned.apk') -Force
& $jar uf (Join-Path $scratch 'unsigned.apk') -C (Join-Path $scratch 'dex') classes.dex
if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
$aligned=Join-Path $scratch 'aligned.apk';& $align -f 4 (Join-Path $scratch 'unsigned.apk') $aligned
if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
if($Debug){
    $keys=Join-Path $android '.keys';New-Item -ItemType Directory -Force $keys | Out-Null
    $keystore=Join-Path $keys 'debug.p12';if(-not (Test-Path $keystore)){
        & (Join-Path $jdk 'bin/keytool.exe') -genkeypair -alias hopdropdebug -keyalg EC -groupname secp256r1 -validity 10000 -dname 'CN=HopDrop Debug' -storetype PKCS12 -keystore $keystore -storepass android -keypass android -noprompt
        if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}}
    $apk=Join-Path $out 'HopDrop-debug.apk';& $sign sign --v4-signing-enabled false --ks $keystore --ks-pass pass:android --out $apk $aligned
}else{
    $keystore=Join-Path $root 'Private signing key/hopdrop-release.p12'
    $password=Join-Path $root 'Private signing key/signing-password.txt'
    $apk=Join-Path $root 'HopDrop.apk';& $sign sign --v4-signing-enabled false --ks $keystore --ks-pass "file:$password" --out $apk $aligned
}
if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
& $sign verify --verbose $apk;if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
if($Release){& $aapt dump badging $apk}
if($Install){& (Join-Path $sdk 'platform-tools/adb.exe') install -r $apk;if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}}
Write-Host "Built $apk"
