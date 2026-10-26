# Blanket Ambient Sound Player for Android TV (TV Edition)

This tool installs and configures Blanket, the ambient noise and nature sound player, customized with a TV-first remote interface for Android TV.

Based on Rafael Mardojai's [Blanket](https://github.com/rafaelmardojai/blanket) and the Android port by [vinberg88/blanketforandroid](https://github.com/vinberg88/blanketforandroid) (licensed under GPL-3.0).

> **Separation Notice:**
> CleanYourTV contains zero GPL code. Blanket runs as an independent companion APK (`com.vinberg88.blanket`).

## Features

- **Ambient Sounds**: Rain, Storm, Wind, Waves, Stream, Birds, Summer Night, Train, Boat, City, Coffee Shop, Fireplace, White Noise, Pink Noise.
- **TV-Optimized Remote Navigation**: Rebuilt with high-contrast glowing TV focus rings (3dp solid white border and 1.05x scale), eliminating all lost-cursor issues.
- **Dedicated Volume Steppers**: Active sounds display a remote-friendly `◀  [━━━━●━━━━] 50%  ▶` stepper. D-pad Left/Right adjusts volume in 10% steps.
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

## Remote Control & Navigation Guide

| Action | Remote Button | Behavior |
|---|---|---|
| **Toggle Sound ON / OFF** | **OK / Center** (on Sound Card) | Instantly turns the selected sound ON or OFF. |
| **Favorite Sound** | **Hold OK** (on Sound Card) | Toggles the gold favorite star in the top-right corner. |
| **Navigate Grid** | **D-Pad Up / Down / Left / Right** | Moves seamlessly between sound cards across the 5-column TV grid. |
| **Adjust Volume** | **D-Pad Down** (from active card) -> **Left / Right** | Lands on the volume stepper. Left decreases volume by 10%; Right increases by 10%. |
| **Return to Card** | **D-Pad Up** (from volume stepper) | Moves remote cursor back to the sound card. |
| **Top Controls** | **D-Pad Up** (from top row) | Navigates to Presets menu ("Default", "Nature", "Focus", "Sleep") and More menu. |
| **Master Controls** | **D-Pad Down** (from bottom row) | Navigates to master Mute and Play / Pause all sounds button. |
| **Background Audio** | **Home** | Blanket continues playing ambient audio in the background while you return to CleanYourTV. |
