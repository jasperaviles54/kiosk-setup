# Rebuilds the kiosk APK from source in kioskapp\.
# Requires: Android SDK build-tools 35 + platform-35, and a JDK (17+).
# Edit the two paths below if your SDK/JDK live elsewhere.
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path

$JDK   = "C:\Program Files\Java\jdk-25\bin"
$SDK   = "C:\Users\user\android-sdk"
$BT    = Join-Path $SDK "build-tools\35.0.0"
$AJAR  = Join-Path $SDK "platforms\android-35\android.jar"
$APP   = Join-Path $root "kioskapp"
$B     = Join-Path $APP "build"

if (-not (Test-Path $AJAR)) { throw "android.jar not found at $AJAR - install platform-35." }
if (-not (Test-Path "$BT\aapt2.exe")) { throw "build-tools 35 not found at $BT." }

# clean
Get-ChildItem "$B\classes" -Recurse -ErrorAction SilentlyContinue | Remove-Item -Recurse -Force -ErrorAction SilentlyContinue
foreach ($f in @("base.apk","aligned.apk","kiosk-signed.apk","res.zip","classes.jar")) { Remove-Item "$B\$f" -Force -ErrorAction SilentlyContinue }
Get-ChildItem "$B\dex" -ErrorAction SilentlyContinue | Remove-Item -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory (Join-Path $B "classes") -Force | Out-Null
New-Item -ItemType Directory (Join-Path $B "dex") -Force | Out-Null

# keystore (create once if missing)
if (-not (Test-Path "$B\kiosk.jks")) {
  & "$JDK\keytool.exe" -genkeypair -keystore "$B\kiosk.jks" -alias kiosk -keyalg RSA -keysize 2048 `
    -validity 10000 -storepass kioskpass -keypass kioskpass -dname "CN=Company Kiosk, O=LaGuardia Security"
}

& "$BT\aapt2.exe" compile --dir "$APP\res" -o "$B\res.zip"
& "$BT\aapt2.exe" link -o "$B\base.apk" -I $AJAR --manifest "$APP\AndroidManifest.xml" -R "$B\res.zip" --min-sdk-version 28 --target-sdk-version 33 --auto-add-overlay
$javas = (Get-ChildItem "$APP\src" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
& "$JDK\javac.exe" --release 11 -classpath $AJAR -d "$B\classes" $javas
& "$JDK\jar.exe" cf "$B\classes.jar" -C "$B\classes" .
& "$BT\d8.bat" --release --lib $AJAR --output "$B\dex" "$B\classes.jar"

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$B\base.apk", 'Update')
try {
  $e = $zip.GetEntry("classes.dex"); if ($e) { $e.Delete() }
  $en = $zip.CreateEntry("classes.dex", 'Optimal'); $s = $en.Open()
  $by = [System.IO.File]::ReadAllBytes("$B\dex\classes.dex"); $s.Write($by,0,$by.Length); $s.Close()
} finally { $zip.Dispose() }

& "$BT\zipalign.exe" -f 4 "$B\base.apk" "$B\aligned.apk"
& "$BT\apksigner.bat" sign --ks "$B\kiosk.jks" --ks-pass pass:kioskpass --ks-key-alias kiosk --key-pass pass:kioskpass --out "$B\kiosk-signed.apk" "$B\aligned.apk"
& "$BT\apksigner.bat" verify "$B\kiosk-signed.apk"
Write-Host "`nBuilt: $B\kiosk-signed.apk"
