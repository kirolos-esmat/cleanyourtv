# AirPlay Receiver for Android TV

This tool installs and configures an open-source AirPlay server on your Android TV device.

Based on [jqssun/android-airplay-server](https://github.com/jqssun/android-airplay-server) (licensed under GPL-3.0).

> **Separation Notice:**
> CleanYourTV contains zero GPL code. The AirPlay server runs as an independent companion APK (`io.github.jqssun.airplay`).

## Quick Setup

Run the installer pointing to your TV's IP address:

```bash
TV_HOST=192.168.1.126 ./tools/airplay/install.sh
```

The script will:
1. Download the latest standalone release from `jqssun/android-airplay-server`.
2. Install the package over ADB.
3. Exclude the service from aggressive TV background battery sleeping (`dumpsys deviceidle whitelist`).
4. Initialize the background receiver service.

## Usage

On your iPhone, iPad, or Mac:
1. Ensure you are on the same local Wi-Fi network as the TV.
2. Open **Control Center** -> **Screen Mirroring** (or AirPlay in video apps).
3. Select your TV name from the list.

## Known Limitations

- **DRM Content:** Video streams protected by Apple FairPlay hardware DRM (such as Netflix, Apple TV+, Disney+) cannot be mirrored to third-party receivers. Standard screen mirroring, photos, browser videos, and non-DRM media stream smoothly.
- **Reboot:** On some TV boxes, if the app is killed after a cold reboot, relaunching the app once restores the background listener.
