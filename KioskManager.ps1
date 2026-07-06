# =====================================================================
#  LaGuardia Kiosk Manager  -  single-tool GUI for managing the tablets.
#  Crisp text (DPI-aware), live status + tablet info, app picker for Extract.
# =====================================================================

# --- DPI awareness FIRST (before any window) so text isn't blurry on scaled displays ---
Add-Type @"
using System; using System.Runtime.InteropServices;
public static class NativeDpi { [DllImport("user32.dll")] public static extern bool SetProcessDPIAware(); }
"@
try { [NativeDpi]::SetProcessDPIAware() | Out-Null } catch {}

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName Microsoft.VisualBasic
[System.Windows.Forms.Application]::EnableVisualStyles()

# DPI scale factor (1.0 at 96 dpi, 1.5 at 150%, etc.)
$g = [System.Drawing.Graphics]::FromHwnd([IntPtr]::Zero)
$script:scale = $g.DpiX / 96.0
$g.Dispose()
function S([double]$n) { [int][math]::Round($n * $script:scale) }

$root = $PSScriptRoot
if (-not $root) { $root = Split-Path -Parent $MyInvocation.MyCommand.Path }

function Find-Adb {
  $local = Join-Path $root "platform-tools\adb.exe"
  if (Test-Path $local) { return $local }
  $cmd = Get-Command adb -ErrorAction SilentlyContinue
  if ($cmd) { return $cmd.Source }
  return $local
}
$adb = Find-Adb

# palette
$cBg     = [System.Drawing.Color]::FromArgb(15,27,61)
$cPanel  = [System.Drawing.Color]::FromArgb(28,40,80)
$cText   = [System.Drawing.Color]::White
$cGreen  = [System.Drawing.Color]::FromArgb(46,160,67)
$cBlue   = [System.Drawing.Color]::FromArgb(40,110,210)
$cGrey   = [System.Drawing.Color]::FromArgb(70,84,124)

# ---------- background work (separate job, keeps UI responsive) ----------
$ProvisionWork = {
  param($adb, $root)
  function ok($m) { Write-Output ([string]$m) }
  & $adb start-server *> $null
  ok "Looking for the tablet (unlock it + tap 'Allow' if asked)..."
  $ready = $false
  for ($i = 0; $i -lt 90 -and -not $ready; $i++) {
    $list = (& $adb devices | Out-String)
    if ($list -match "(?m)\tdevice\s*$") { $ready = $true; break }
    elseif ($list -match "unauthorized") { ok "  UNAUTHORIZED -> unlock the screen + tap 'Allow'..." }
    elseif ($list -match "offline")      { ok "  offline -> unplug/replug the cable..." }
    else                                  { ok "  no tablet yet -> check the cable + USB debugging..." }
    Start-Sleep -Seconds 2
  }
  if (-not $ready) { ok "Gave up waiting for an authorized tablet."; return }
  ok "Device authorized."
  $kiosk = Join-Path $root "kioskapp\build\kiosk-signed.apk"
  if (-not (Test-Path $kiosk)) { ok "ERROR: kiosk app not found at $kiosk"; return }
  $owner = (& $adb shell dpm list-owners | Out-String)
  if ($owner -match "com\.laguardia\.kiosk") {
    ok "This tablet is ALREADY a kiosk -> updating the app only."
    & $adb shell settings put global verifier_verify_adb_installs 0 *> $null
    ok "Installing latest kiosk app..."
    & $adb install -r $kiosk 2>&1 | ForEach-Object { ok $_ }
    & $adb shell am start -n com.laguardia.kiosk/.KioskActivity *> $null
    ok "DONE - app updated, still a locked kiosk."
    return
  }
  if ($owner -notmatch "no owners") { ok "STOPPED - tablet has a different owner. Factory reset it first."; return }
  $accts = (& $adb shell dumpsys account | Select-String "Accounts:\s*\d" | Out-String)
  if ($accts -notmatch "Accounts:\s*0") { ok "STOPPED - a Google account is signed in. Remove it, then retry."; return }
  ok "Fresh tablet -> full setup."
  & $adb shell settings put global verifier_verify_adb_installs 0 *> $null
  & $adb shell settings put global package_verifier_enable 0 *> $null
  ok "Installing kiosk app..."
  & $adb install -r $kiosk 2>&1 | ForEach-Object { ok $_ }
  $apksDir = Join-Path $root "apks"
  Get-ChildItem $apksDir -Directory -ErrorAction SilentlyContinue | ForEach-Object {
    $parts = (Get-ChildItem $_.FullName -Filter *.apk -File | ForEach-Object { $_.FullName })
    if ($parts) { ok ("Installing " + $_.Name + " (" + $parts.Count + " part(s))..."); & $adb install-multiple -r $parts 2>&1 | ForEach-Object { ok $_ } }
  }
  Get-ChildItem $apksDir -Filter *.apk -File -ErrorAction SilentlyContinue | ForEach-Object {
    ok ("Installing " + $_.Name + "..."); & $adb install -r $_.FullName 2>&1 | ForEach-Object { ok $_ }
  }
  if (-not (Get-ChildItem $apksDir -Recurse -Filter *.apk -ErrorAction SilentlyContinue)) { ok "NOTE: apks\ is empty - Viber/Messenger NOT installed. Use Extract first." }
  ok "Setting device owner..."
  & $adb shell dpm set-device-owner com.laguardia.kiosk/.AdminReceiver 2>&1 | ForEach-Object { ok $_ }
  & $adb shell am start -n com.laguardia.kiosk/.KioskActivity *> $null
  ok "DONE - the tablet is now a locked kiosk."
}
$ExtractWork = {
  param($adb, $root, $pkg)
  function ok($m) { Write-Output ([string]$m) }
  $paths = (& $adb shell pm path $pkg) -split "`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ -like "package:*" } | ForEach-Object { $_.Substring(8).Trim() }
  if (-not $paths) { ok "Not installed on this tablet: $pkg"; return }
  $dest = Join-Path $root "apks\$pkg"
  if (Test-Path $dest) { Get-ChildItem $dest -File | Remove-Item -Force -ErrorAction SilentlyContinue }
  New-Item -ItemType Directory $dest -Force | Out-Null
  ok "Extracting $pkg ($($paths.Count) part(s)) - this can take a minute..."
  foreach ($p in $paths) { ok "  pulling $p"; & $adb pull $p $dest *> $null }
  ok "DONE - saved into apks\$pkg"
}

# ---------- window ----------
$form = New-Object System.Windows.Forms.Form
$form.Text = "LaGuardia Kiosk Manager"
$form.ClientSize = New-Object System.Drawing.Size((S 560),(S 530))
$form.StartPosition = "CenterScreen"
$form.FormBorderStyle = "FixedSingle"
$form.MaximizeBox = $false
$form.BackColor = $cBg
$form.Font = New-Object System.Drawing.Font("Segoe UI",10)

function Add-Label($text,$x,$y,$w,$h,$size,$bold,$fore) {
  $l = New-Object System.Windows.Forms.Label
  $l.Text = $text
  $l.Location = New-Object System.Drawing.Point((S $x),(S $y))
  $l.Size = New-Object System.Drawing.Size((S $w),(S $h))
  $st = if ($bold) { [System.Drawing.FontStyle]::Bold } else { [System.Drawing.FontStyle]::Regular }
  $l.Font = New-Object System.Drawing.Font("Segoe UI",$size,$st)
  $l.ForeColor = $fore
  $form.Controls.Add($l)
  return $l
}

Add-Label "Kiosk Manager" 20 12 300 34 16 $true $cText | Out-Null

$statusBox = Add-Label "" 20 54 520 32 11 $true $cText
$statusBox.BackColor = $cPanel
$statusBox.TextAlign = "MiddleLeft"

Add-Label "This tablet" 20 96 200 20 9 $true ([System.Drawing.Color]::Silver) | Out-Null
$info = New-Object System.Windows.Forms.TextBox
$info.Multiline = $true; $info.ReadOnly = $true; $info.BorderStyle = "None"
$info.Location = New-Object System.Drawing.Point((S 20),(S 118))
$info.Size = New-Object System.Drawing.Size((S 520),(S 86))
$info.BackColor = $cPanel; $info.ForeColor = $cText
$info.Font = New-Object System.Drawing.Font("Consolas",9)
$form.Controls.Add($info)

function New-KButton($text,$x,$y,$w,$back,$fore) {
  $b = New-Object System.Windows.Forms.Button
  $b.Text = $text
  $b.Location = New-Object System.Drawing.Point((S $x),(S $y))
  $b.Size = New-Object System.Drawing.Size((S $w),(S 44))
  $b.Font = New-Object System.Drawing.Font("Segoe UI",10,[System.Drawing.FontStyle]::Bold)
  $b.BackColor = $back; $b.ForeColor = $fore
  $b.FlatStyle = "Flat"; $b.FlatAppearance.BorderSize = 0
  $form.Controls.Add($b)
  return $b
}
$btnSetup      = New-KButton "Set up / Update tablet" 20  220 255 $cGreen $cText
$btnExtract    = New-KButton "Extract app..."         285 220 255 $cBlue  $cText
$btnRefresh    = New-KButton "Refresh"                20  272 255 $cGrey  $cText
$btnDisconnect = New-KButton "Disconnect (safe)"      285 272 255 $cGrey  $cText

$log = New-Object System.Windows.Forms.TextBox
$log.Multiline = $true; $log.ScrollBars = "Vertical"; $log.ReadOnly = $true
$log.Location = New-Object System.Drawing.Point((S 20),(S 332))
$log.Size = New-Object System.Drawing.Size((S 520),(S 185))
$log.BackColor = [System.Drawing.Color]::Black
$log.ForeColor = [System.Drawing.Color]::LightGreen
$log.Font = New-Object System.Drawing.Font("Consolas",9)
$form.Controls.Add($log)

function Write-Log($m) { $log.AppendText((Get-Date -Format "HH:mm:ss") + "  " + $m + "`r`n") }

$script:prevConnected = $false

function Update-Info {
  try {
    $devs = (& $adb devices 2>$null | Out-String)
    if ($devs -notmatch "(?m)\tdevice\s*$") { $info.Text = "  (no tablet connected)"; return }
    $model = (& $adb shell getprop ro.product.model 2>$null | Out-String).Trim()
    $ver   = (& $adb shell getprop ro.build.version.release 2>$null | Out-String).Trim()
    $owner = (& $adb shell dpm list-owners 2>$null | Out-String)
    if ($owner -match "laguardia\.kiosk") { $kioskTxt = "YES (device owner / locked)" }
    elseif ($owner -match "no owners")    { $kioskTxt = "no - fresh / unmanaged" }
    else                                   { $kioskTxt = "managed by another owner" }
    $appVer = ""
    if ($owner -match "laguardia\.kiosk") {
      $appVer = ((& $adb shell dumpsys package com.laguardia.kiosk 2>$null | Select-String "versionName" | Select-Object -First 1) -replace ".*versionName=","").Trim()
    }
    $acc = ((& $adb shell dumpsys account 2>$null | Select-String "Accounts:\s*\d" | Select-Object -First 1) -replace ".*Accounts:\s*","").Trim()
    $lines = @()
    $lines += ("Model:    " + $model + "    Android " + $ver)
    $lines += ("Kiosk:    " + $kioskTxt + $(if ($appVer) { "   (app v" + $appVer + ")" } else { "" }))
    $lines += ("Google account(s) on device: " + $acc)
    $info.Text = ($lines -join "`r`n")
  } catch { $info.Text = "  (could not read tablet info)" }
}

function Update-Status {
  try {
    $devs = (& $adb devices 2>$null | Out-String)
    $connected = $false
    if ($devs -match "unauthorized") {
      $statusBox.Text = "  Tablet: UNAUTHORIZED  --  unlock it + tap 'Allow'"; $statusBox.ForeColor = [System.Drawing.Color]::Gold
    } elseif ($devs -match "(?m)\tdevice\s*$") {
      $connected = $true
      $owner = (& $adb shell dpm list-owners 2>$null | Out-String)
      if ($owner -match "laguardia\.kiosk") { $statusBox.Text = "  Tablet: CONNECTED  --  kiosk (managed/locked)"; $statusBox.ForeColor = [System.Drawing.Color]::LightGreen }
      elseif ($owner -match "no owners")    { $statusBox.Text = "  Tablet: CONNECTED  --  fresh, ready to set up"; $statusBox.ForeColor = [System.Drawing.Color]::DeepSkyBlue }
      else                                   { $statusBox.Text = "  Tablet: CONNECTED  --  managed by another owner"; $statusBox.ForeColor = [System.Drawing.Color]::Gold }
    } else {
      $statusBox.Text = "  Tablet: not connected"; $statusBox.ForeColor = [System.Drawing.Color]::Silver
    }
    if ($connected -and -not $script:prevConnected) { Update-Info }
    elseif (-not $connected) { $info.Text = "  (no tablet connected)" }
    $script:prevConnected = $connected
  } catch { $statusBox.Text = "  (status check error)"; $statusBox.ForeColor = [System.Drawing.Color]::Silver }
}

function Set-Busy($on) {
  $btnSetup.Enabled = -not $on; $btnExtract.Enabled = -not $on; $btnDisconnect.Enabled = -not $on
  if ($on) { $statusBox.Text = "  Working...  (watch the log below)"; $statusBox.ForeColor = [System.Drawing.Color]::Gold }
}

$script:job = $null
$statusTimer = New-Object System.Windows.Forms.Timer
$statusTimer.Interval = 3000
$statusTimer.Add_Tick({ Update-Status })

$pollTimer = New-Object System.Windows.Forms.Timer
$pollTimer.Interval = 700
$pollTimer.Add_Tick({
  if ($script:job) {
    $out = Receive-Job $script:job 2>&1
    foreach ($line in $out) { if ($line -ne $null -and ("$line").Trim() -ne "") { Write-Log ("$line") } }
    if ($script:job.State -ne 'Running') {
      $pollTimer.Stop(); Remove-Job $script:job -Force -ErrorAction SilentlyContinue; $script:job = $null
      Set-Busy $false; $statusTimer.Start(); Update-Status; Update-Info
    }
  }
})

function Start-Work($block, $argList) {
  if ($script:job) { Write-Log "A task is already running - please wait."; return }
  Set-Busy $true; $statusTimer.Stop()
  $script:job = Start-Job -ScriptBlock $block -ArgumentList $argList
  $pollTimer.Start()
}

function Show-PackagePicker {
  $devs = (& $adb devices 2>$null | Out-String)
  if ($devs -notmatch "(?m)\tdevice\s*$") { [System.Windows.Forms.MessageBox]::Show("Connect an authorized tablet first.","Extract") | Out-Null; return $null }
  $pkgs = & $adb shell pm list packages -3 2>$null | ForEach-Object { ($_ -replace "package:","").Trim() } | Where-Object { $_ } | Sort-Object
  $pf = New-Object System.Windows.Forms.Form
  $pf.Text = "Pick an app to extract"; $pf.ClientSize = New-Object System.Drawing.Size((S 380),(S 360))
  $pf.StartPosition = "CenterParent"; $pf.FormBorderStyle = "FixedDialog"; $pf.MaximizeBox = $false; $pf.MinimizeBox = $false
  $pf.Font = New-Object System.Drawing.Font("Segoe UI",10)
  $lb = New-Object System.Windows.Forms.ListBox
  $lb.Location = New-Object System.Drawing.Point((S 12),(S 12)); $lb.Size = New-Object System.Drawing.Size((S 356),(S 286))
  $lb.Font = New-Object System.Drawing.Font("Consolas",9)
  foreach ($p in $pkgs) { [void]$lb.Items.Add($p) }
  $vi = $lb.Items.IndexOf("com.viber.voip"); if ($vi -ge 0) { $lb.SelectedIndex = $vi } elseif ($lb.Items.Count -gt 0) { $lb.SelectedIndex = 0 }
  $ok = New-Object System.Windows.Forms.Button; $ok.Text = "Extract"; $ok.Location = New-Object System.Drawing.Point((S 196),(S 312)); $ok.Size = New-Object System.Drawing.Size((S 84),(S 32)); $ok.DialogResult = "OK"
  $cancel = New-Object System.Windows.Forms.Button; $cancel.Text = "Cancel"; $cancel.Location = New-Object System.Drawing.Point((S 286),(S 312)); $cancel.Size = New-Object System.Drawing.Size((S 84),(S 32)); $cancel.DialogResult = "Cancel"
  $pf.Controls.Add($lb); $pf.Controls.Add($ok); $pf.Controls.Add($cancel)
  $pf.AcceptButton = $ok; $pf.CancelButton = $cancel
  $res = $pf.ShowDialog($form)
  if ($res -eq "OK" -and $lb.SelectedItem) { return [string]$lb.SelectedItem } else { return $null }
}

$btnSetup.Add_Click({ Write-Log "=== Set up / Update tablet ==="; Start-Work $ProvisionWork @($adb,$root) })
$btnExtract.Add_Click({ $pkg = Show-PackagePicker; if ($pkg) { Write-Log "=== Extract $pkg ==="; Start-Work $ExtractWork @($adb,$root,$pkg) } })
$btnRefresh.Add_Click({ Update-Status; Update-Info; Write-Log "Status refreshed." })
$btnDisconnect.Add_Click({ try { & $adb kill-server 2>$null } catch {}; Write-Log "ADB stopped - safe to unplug."; $statusBox.Text = "  Tablet: disconnected (ADB stopped)"; $statusBox.ForeColor = [System.Drawing.Color]::Silver; $info.Text = "  (no tablet connected)"; $script:prevConnected = $false })

$form.Add_FormClosing({ if ($script:job) { Stop-Job $script:job -ErrorAction SilentlyContinue; Remove-Job $script:job -Force -ErrorAction SilentlyContinue } })

Write-Log "Kiosk Manager ready."
Update-Status
$statusTimer.Start()
[void]$form.ShowDialog()
