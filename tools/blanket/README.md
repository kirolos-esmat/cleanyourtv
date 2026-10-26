# Blanket Ambient Sound Player for Android TV

This tool installs and configures Blanket, the ambient noise and nature sound player, on your Android TV device.

Based on Rafael Mardojai's [Blanket](https://github.com/rafaelmardojai/blanket) and the Android port by [vinberg88/blanketforandroid](https://github.com/vinberg88/blanketforandroid) (licensed under GPL-3.0).

> **Separation Notice:**
> CleanYourTV contains zero GPL code. Blanket runs as an independent companion APK (`com.vinberg88.blanket`).

## Features

- **Ambient Sounds**: Rain, Storm, Wind, Waves, Stream, Birds, Summer Night, Train, Boat, City, Coffee Shop, Fireplace, White Noise, Pink Noise.
- **Mix & Match**: Play multiple sounds simultaneously and adjust each volume slider independently.
- **Background Playback**: Continues playing smoothly in the background while browsing CleanYourTV, watching wallpapers, or running other non-audio apps.
- **100% Offline & Private**: Zero internet permissions requested, zero telemetry, zero advertisements.

## Quick Setup

Run the installer pointing to your TV's IP address:

```bash
TV_HOST=192.168.1.126 ./tools/blanket/install.sh
```

Or pass the target IP directly:

```bash
./tools/blanket/install.sh 192.168.1.126
```

The script will:
1. Fetch the standalone release from `vinberg88/blanketforandroid`.
2. Install the package over ADB.
3. Whitelist Blanket from TV background battery sleep (`dumpsys deviceidle whitelist`).
4. Grant notification permissions for playback controls.
5. Launch Blanket on the TV.

## Verifying Installation

To check if Blanket is installed and running without reinstalling:

```bash
./tools/blanket/install.sh --verify 192.168.1.126
```

## Remote Control & Navigation

- **D-Pad Navigation**: Use remote Up/Down/Left/Right to highlight sound cards and toggle them with `OK / Select`.
- **Volume & Sliders**: Open the controls slider at the bottom or use an on-screen mouse helper to adjust individual sound intensity.
- **Background Audio**: Press `Home` on your remote anytime. The sounds continue to play in the background.
- **Stop / Pause**: Return to Blanket from CleanYourTV Home or App Library and select **Pause all sounds** or **Mute**.
