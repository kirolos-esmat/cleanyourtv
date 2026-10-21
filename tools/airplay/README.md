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

Or pass the target device and custom name directly:

```bash
./tools/airplay/install.sh 192.168.1.126 "Living Room TV"
```

The script will:
1. Download the standalone release from `jqssun/android-airplay-server`.
2. Install the package over ADB.
3. Exclude the service from TV background battery sleep (`dumpsys deviceidle whitelist`).
4. Enable overlay permissions and dark mode.
5. Apply safe low-latency preferences and restart the server cleanly.
6. Verify and display the active configuration reported by the receiver.

## Verifying Active Settings

To inspect the receiver's current live configuration without reinstalling:

```bash
./tools/airplay/install.sh --verify 192.168.1.126
```

## Configuration & Lip-Sync Tuning Options

All tuning options can be customized via environment variables:

| Variable | Default | Allowed Values | Description |
|---|---|---|---|
| `DEVICE_NAME` | `Kirolos's Apple TV` | string | AirPlay broadcast name shown in Apple Control Center. |
| `AIRPLAY_LOW_LATENCY` | `true` | `true`, `false` | Enables Oboe low-latency performance mode and API 30+ low-latency codec flag. |
| `AIRPLAY_SCHEDULED_RELEASE` | `false` | `true`, `false` | When `true`, schedules video buffer release to VSYNC timestamps. Upstream defaults to `false` (immediate render). |
| `AIRPLAY_AUDIO_AUTO_BUFFER` | `true` | `true`, `false` | Dynamically adapts audio cushion to network jitter. |
| `AIRPLAY_AUDIO_CUSHION_MS` | `40` | `1`..`1000` | Fixed audio cushion (ms) used when auto-buffering is disabled. |
| `AIRPLAY_AUDIO_LATENCY_MS` | `-1` | `-1`..`1000` | Audio latency offset reported to Apple sender (`-1` = unconfigured upstream standard). |

### Example Custom Tuning:
```bash
# Example: Fixed 30ms cushion on stable 5GHz Wi-Fi with custom device name
TV_HOST=192.168.1.126 \
AIRPLAY_AUDIO_AUTO_BUFFER=false \
AIRPLAY_AUDIO_CUSHION_MS=30 \
./tools/airplay/install.sh
```

## Lip-Sync Diagnostics Guide

If audio and video timing drifts on your specific TV or sound hardware:

1. **Check Live Diagnostics via ADB:**
   ```bash
   adb logcat -s VideoRenderer AudioPlayer Oboe
   ```
2. **Diagnose Timing Discrepancies:**
   - **Audio Late (Lips move before speech):** Often happens when external soundbars or HDMI eARC/ARC introduce hardware audio delay, or when Wi-Fi packet jitter expands the cushion.
     - *Remedy:* On a stable 5GHz network, test `AIRPLAY_AUDIO_AUTO_BUFFER=false` with `AIRPLAY_AUDIO_CUSHION_MS=30` or `40`.
   - **Audio Early (Speech heard before lips move):** Video decoding is falling behind audio.
     - *Remedy:* Keep `AIRPLAY_SCHEDULED_RELEASE=false` so video frames are released immediately upon decoding.
   - **Audio Crackling / Dropouts:** Audio buffer underruns occur when `AIRPLAY_AUDIO_CUSHION_MS` is set too low (e.g. <23 ms, which is less than a single 1024-sample audio frame).
     - *Remedy:* Keep `AIRPLAY_AUDIO_AUTO_BUFFER=true` or raise `AIRPLAY_AUDIO_CUSHION_MS` to `40` or higher.

## Usage

On your iPhone, iPad, or Mac:
1. Ensure you are on the same local Wi-Fi network as the TV.
2. Open **Control Center** -> **Screen Mirroring** (or the AirPlay icon in media apps).
3. Select your TV name from the list.

## Known Limitations

- **DRM Content:** Video streams protected by Apple FairPlay hardware DRM (such as Netflix, Apple TV+, Disney+) cannot be mirrored to third-party receivers. Standard screen mirroring, photos, browser videos, and non-DRM media stream smoothly.
- **Reboot:** On some TV boxes, if the app is killed after a cold reboot, relaunching the app once restores the background listener.
