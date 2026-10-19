# Fire TV Clean Home

Strips ads and bloat from an Amazon Fire TV and replaces the home screen with a minimal, Apple TV–style
launcher: a full-screen rotating photo wallpaper, a small clock and greeting, and a frosted dock of
streaming apps. Everything is done over Wi-Fi with `adb`, with no root, and everything can be undone.

Built for and tested on an **Insignia NS-55F501NA22** (Amazon `AFTDCT31`, Fire OS 7.7.0.8 / Android 9).
Other Fire OS 7 TVs should work the same way; package names may differ slightly.

## What you get
- **Home screen** (`launcher/`): greeting, clock and date top-left; **Inputs** and **Settings** pills
  top-right; a frosted dock with Netflix, YouTube, Prime Video, Disney+, Max, Hulu, Apple TV, Peacock,
  Paramount+ and YouTube TV (whichever are installed); press **Down** for all other apps.
  Focus lifts tiles with a soft spring, a sheen and a white ring; rows glide instead of jumping.
- **Wallpapers**: your own photos if you add any, otherwise Bing's photo of the day (fetched at most
  twice a day, newest 16 kept), otherwise built-in generated landscapes. Rotates every 3 minutes.
- **Debloat** (`debloat.sh`): disables Amazon's ad, tracking, promo and extra packages.
- **Undo** (`restore.sh`, `removed-packages.txt`): re-enables everything that was disabled.

---

## 1. Turn on developer mode on the TV
1. **Settings → Device & Software** (called *My Fire TV* on some firmware) **→ About**.
2. Highlight the **first item** (the TV's name) and press **Select 7 times** until it says you're a developer.
3. Back out to **Device & Software → Developer Options**; turn on **ADB Debugging**
   (and *Apps from Unknown Sources*).
4. Note the TV's IP under **About → Network**.

## 2. Connect from your Mac
```bash
brew install --cask android-platform-tools
adb connect <tv-ip>:5555
```
Accept **"Allow USB debugging?"** on the TV (tick *Always allow*), then check with `adb devices`.

- `No route to host` / `Network is unreachable` even though the TV pings → macOS is blocking the app
  you're running adb from: **System Settings → Privacy & Security → Local Network**, enable it.
- `Connection refused` → ADB Debugging isn't on yet (step 1).

The scripts default to `192.168.0.2:5555`; override with `ANDROID_SERIAL=<ip>:5555 ./script.sh`.

## 3. Debloat
```bash
./debloat.sh
```
Disables 25 packages (ads/ACR tracking, sponsored content, shopping, telemetry, promos, Freevee/miniTV,
Amazon Photos/Music, games hub, tutorials, Amazon Kids, Ambient) and logs each to `removed-packages.txt`.

Notes from Fire OS 7.7:
- `pm uninstall --user 0` reports *Success* but Fire OS quietly reinstalls the package, so the script
  falls back to `pm disable-user`, which sticks.
- Seven packages are "protected" and can't be touched: ads deeplinking, client/device metrics,
  customer-support app, log manager, crash manager, usage stats.
- Nothing needed for the system UI, settings, inputs/HDMI, the tuner, the remote/Bluetooth, Wi-Fi,
  audio, the Appstore or setup is touched. Amazon's launcher is **kept** (see below).

Also worth turning off on the TV: **Settings → Preferences → Privacy Settings** (*Device Usage Data*,
*Collect App Usage Data*, *Interest-based Ads*) and **Preferences → Featured Content** (both autoplay options).

## 4. Build and install the launcher
One-time toolchain (about 1 GB):
```bash
brew install openjdk@17 gradle
brew install --cask android-commandlinetools
yes | sdkmanager --sdk_root=/opt/homebrew/share/android-commandlinetools --licenses
sdkmanager --sdk_root=/opt/homebrew/share/android-commandlinetools "platforms;android-34" "build-tools;34.0.0"
echo "sdk.dir=/opt/homebrew/share/android-commandlinetools" > launcher/local.properties
```
Then build, install and enable the Home-button redirect in one go:
```bash
launcher/install.sh
```

## How it works on Fire OS
Fire OS doesn't let you change the default home screen: the Home button always opens Amazon's
launcher, `cmd package set-home-activity` is accepted but ignored, and remote keys aren't delivered
to third-party accessibility services. So:

- **Home button redirect**: an accessibility service ("Home button redirect") watches for Amazon's
  home page coming to the front, whether from the Home button, boot, "Alexa, go home" or Back, and opens this
  launcher instead. Android can hold back launches for up to 5 s right after Home, so the service
  checks the launcher actually appeared and retries if not. Expect a brief flash of Amazon's home.
- **Settings** opens Amazon's settings grid (`MainSettingsActivity`), which the redirect leaves alone.
- **Inputs**: Amazon's Inputs screen and HDMI switching need Amazon-signed permissions, so the
  Inputs pill opens the settings grid and the service clicks its *Inputs* tile for you.
- **Amazon's launcher stays installed.** Settings and Inputs live inside it, and removing it can
  leave the TV needing a factory reset.

## Customising
- **Dock apps and order**: `FAVORITES` in `launcher/app/src/main/java/tv/minimal/home/Apps.kt`.
- **Your own wallpapers** (override Bing): landscape jpg/png/webp, ideally 1920×1080:
  ```bash
  adb push ~/Pictures/tv-wallpapers/. /sdcard/Android/data/tv.minimal.home/files/wallpapers/
  ```
  Remove them again to go back to Bing photos. The only network access the launcher makes is to bing.com for wallpapers.

## Undo
- Re-enable everything: `./restore.sh` (or `./restore.sh <package>` for one).
- Remove the launcher: `adb uninstall tv.minimal.home`. Amazon's home screen takes over again.
- Show Amazon's home temporarily: **Settings → Accessibility → Home button redirect → Off**.
- A **factory reset** (Settings → Device & Software → Reset to Factory Defaults) restores everything.

## Troubleshooting
- **Home goes to Amazon's screen and stays there**: the redirect got switched off. That happens if
  the app is **force-stopped** (Settings → Applications → Home → Force stop). Run `launcher/install.sh`
  or re-enable it under Settings → Accessibility.
- **Something came back after a Fire OS update**: re-run `./debloat.sh`; it only touches the approved list.

## Files
| Path | What |
|---|---|
| `debloat.sh` | Disables the approved packages and logs them |
| `restore.sh` | Re-enables everything in the log |
| `removed-packages.txt` | What was disabled on this TV, and when |
| `packages-before.txt` | Full package list before any changes |
| `launcher/` | Android TV launcher (Kotlin, no libraries); `install.sh` builds + installs |
