# LaGuardia Reporting Tablet — Kiosk Kit

Turns a Lenovo Tab (Android) into a **locked, tamper-proof kiosk** for employee reporting. The tablet only runs the apps you allow (default: Viber, Messenger, Camera, Gallery, Files) — no settings, no other apps, no way out. A custom **device-owner** app using Android **lock task** mode. 100% free, no cloud, no subscription, no third-party dependence.

---

## Features

- 🔒 **Lock Task Mode** — Android device-owner lockdown; employees can't exit, install apps, or access settings
- 🖥️ **One-Click Setup** — double-click `KioskManager.bat` to provision a fresh tablet from a PC
- 📱 **On-Tablet Admin** — manage apps, PINs, Google accounts, and maintenance mode directly on the device (no PC needed)
- 🔄 **Key Rotation** — two PINs: servicing (`246810`) for quick Wi-Fi fixes, master (`911911`) for full admin access
- 📦 **App Extraction** — pull Play Store apps off one tablet and sideload them onto others
- 👤 **Single Google Account Lock** — restrict the tablet to one employee Gmail account
- 🛠️ **Maintenance Mode** — pause the kiosk to clear app data, remove apps, or re-register Viber
- ⬆️ **Update Detection** — Kiosk Manager auto-detects existing installations and updates without wiping

---

## Demo

https://github.com/jasperaviles54/kiosk-setup/raw/main/Demo.mp4

> *The video shows a fresh tablet being set up with one click from KioskManager, then locked into kiosk mode. If the video doesn't play above, [click here to download](https://github.com/jasperaviles54/kiosk-setup/raw/main/Demo.mp4).*

---

## Tech Stack

| Layer | Technology |
|---|---|
| **Android App** | Java (compiled with Android SDK build-tools 35) |
| **Lock Mechanism** | Android DevicePolicyManager + Lock Task Mode |
| **PC Management** | PowerShell (KioskManager.ps1) |
| **USB Communication** | ADB (Android Debug Bridge) |
| **APK Signing** | Self-signed keystore (`kiosk.jks`) |
| **Target Device** | Lenovo Tab (Android) |

---

## Architecture

```
PC (Windows)                          Tablet (Android)
  │                                     │
  ├── KioskManager.bat                  ├── Kiosk App (device-owner)
  │     └── KioskManager.ps1           │     ├── Lock Task Mode
  │           ├── Set up / Update       │     ├── App whitelist
  │           ├── Extract app           │     ├── PIN-protected admin panel
  │           └── Disconnect            │     ├── Google account lock
  │                                     │     └── Maintenance mode
  └── ADB (USB) ──────────────────────►│
        ├── Install kiosk APK          └── Allowed apps
        ├── Set device-owner                ├── Viber
        ├── Sideload Viber/Messenger        ├── Messenger
        └── Disable Play Protect            ├── Camera
                                            ├── Gallery
                                            └── Files
```

---

## Project Structure

```
kiosk-setup/
├── KioskManager.bat        # Entry point — double-click to launch
├── KioskManager.ps1        # PC-side management app (setup, extract, disconnect)
├── build-app.ps1           # Rebuild the kiosk APK after editing source (dev only)
├── Demo.mp4                # Setup demo video
├── README.md               # This file
├── kioskapp/
│   ├── AndroidManifest.xml # App permissions and device-admin declarations
│   ├── src/                # Java source code (KioskActivity, AdminReceiver, etc.)
│   ├── res/                # Android resources (layouts, drawables, strings)
│   └── build/
│       ├── kiosk-signed.apk  # Pre-built signed APK (ready to deploy)
│       └── kiosk.jks         # Signing keystore
├── apks/                   # Extracted app installers (Viber, Messenger)
│   ├── com.viber.voip/     # Viber split-APKs (populated via Extract app)
│   └── com.facebook.orca/  # Messenger split-APKs
├── platform-tools/         # adb.exe (Android Debug Bridge)
└── backup-scripts/         # Legacy standalone scripts (superseded by KioskManager)
```

---

## Setup Guide

### Prerequisites
- Windows PC with PowerShell
- USB data cable
- Lenovo Tab (Android) — factory reset, **no Google account added**
- [Android Platform Tools](https://developer.android.com/tools/releases/platform-tools) — download and extract into the `platform-tools/` folder (provides `adb.exe`)

### Prepare the Apps (one-time)

Viber and Messenger must be the **Google Play** builds (Huawei AppGallery builds fail with "HMS Core is not installed").

1. On one tablet, add a Google account (Admin panel → **Add Google account**) and install **Viber** and **Messenger** from the Play Store
2. Connect that tablet, open **Kiosk Manager**, click **Extract app**, enter `com.viber.voip` (repeat for `com.facebook.orca`)
3. From then on, **Set up / Update tablet** installs those exact builds on all other tablets — no Play Store needed per device

> `apks/` ships empty on purpose. If empty, setup builds the kiosk but skips Viber/Messenger — fill it with **Extract app** first.

### Set Up a New Tablet

**On the tablet:**
1. **Factory reset** (Settings → System → Reset, or recovery mode)
2. In first-time setup: connect **Wi-Fi**, but **skip the Google account** (critical — an account blocks the lockdown)
3. Settings → About → tap **Build number 7×** → Developer options → **USB debugging ON**
4. Plug into the PC with a data USB cable

**On the PC:**
5. Open **Kiosk Manager** and click **Set up / Update tablet**
6. On the tablet, tick *"Always allow from this computer"* and tap **Allow**
7. Setup installs everything and locks the tablet automatically

> To unplug: click **Disconnect (safe)** first, then remove the cable.

---

## On-Tablet Administration

No computer needed for day-to-day management. Tap the **"Company Tablet" title 7 times** on the kiosk home screen:

| PIN | Access Level | What You Can Do |
|---|---|---|
| `246810` | Servicing | Opens Android Settings briefly (e.g., Wi-Fi). Re-locks on reboot |
| `911911` | Master | Full Admin Settings panel (see below) |

### Admin Settings (Master PIN)

- **App Management** — tick/untick which apps show in the kiosk, then Save & Apply
- **Change PINs** — update both servicing and master PINs
- **Open Android Settings** — access full device settings
- **Employee Google Account** — Unlock / Add / Lock (single Gmail only)
- **Maintenance Mode** — pause the kiosk to clear app data, manage apps, or uninstall. Tap **Re-lock kiosk** (or reboot) when done
- **Remove Management** — fully unlock the device for reconfiguring or retiring

> Change the default PINs after handover.

---

## Changing Allowed Apps

**Easiest:** on the tablet via Admin Settings (master PIN → tick apps → Save).

**Adding a new app:**
```
platform-tools\adb.exe install -r yourapp.apk
```
Then tick it in Admin Settings.

The default app list lives in `kioskapp/src/.../KioskActivity.java` (`DEFAULT_APPS`) if you want to change the built-in defaults.

---

## Employee Google Account

Lock the tablet to a single employee Google account:

1. Admin panel → **Unlock Google accounts**
2. **Add / sign in a Google account** → sign in the employee's Gmail
3. **Lock to current Google account** → blocks adding any other account

**To change later:** Unlock → Open Android Settings → Passwords & accounts → remove old Gmail → Add new → Lock.

---

## Rebuilding the App

```powershell
# Requires Android SDK build-tools 35 + platform-35 and a JDK
.\build-app.ps1
```

Then deploy: connect the tablet → **Kiosk Manager** → **Set up / Update tablet** (detects existing kiosk and updates without wiping).

---

## Troubleshooting

| Problem | Solution |
|---|---|
| Tablet stuck in kiosk | Master PIN → **Remove Management** → device unlocks, reconfigure |
| Tablet completely unresponsive | Boot to **recovery** (Power + Volume Up) → **Wipe data/factory reset**, then re-provision |
| Viber needs re-registration | Master PIN → **Maintenance mode** → Settings → Apps → Viber → Storage → **Clear data** → **Re-lock kiosk** |
| "HMS Core is not installed" | You installed the Huawei AppGallery build of Viber/Messenger — use Play Store builds instead |

---

## Good to Know

- **PINs** are stored on each tablet; defaults are `246810` (servicing) / `911911` (master)
- The app deliberately does **not** block USB installs, so it stays serviceable. Lock task still prevents employees from installing anything
- The kiosk APK is self-signed; first install needs Play Protect's ADB check off — Kiosk Manager handles this automatically
- Keep a tablet on **Wi-Fi**; Viber/Messenger auto-update via Play Store in the background (hidden from kiosk but still update)
- Signing keystore: `kioskapp/build/kiosk.jks` (password `kioskpass`) — keep it, updates must use the same key
- Factory reset always works because no Google account = no reset lock

---

## Author

**Jasper T. Aviles** — Software Developer

- 🌐 [Portfolio](https://jasperaviles54.github.io/portfolio/)
- 💻 [GitHub](https://github.com/jasperaviles54)
