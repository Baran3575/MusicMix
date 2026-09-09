# MusicMix — offline Android DJ mixer

Mix 2 locally-downloaded songs on-device. No network, no account.

## Features
- **Pick 2 songs** from device storage (`audio/*` picker, works with Downloads folder)
- **Auto-detect per track:**
  - BPM (onset-envelope + autocorrelation, 60–180 BPM)
  - Vocal % vs Music % (energy in 300–3400 Hz vocal band vs full spectrum)
- **Per-deck controls:** BPM/tempo slider (pitch-corrected time-stretch via Media3 ExoPlayer),
  Vocal volume, Music volume, Play/Pause
- **Master:** master tempo (both decks stretch to it), equal-power crossfader, Play both / Stop both
- **Save Mix button:** offline render to WAV (`Music/MusicMix/musicmix_<timestamp>.wav`)
  with 3-band DSP split (low <300Hz + high >3400Hz = music, 300–3400Hz = vocal),
  tempo-matched to master BPM, crossfaded and summed.

> Honest limitation: this is DSP approximation, not ML stem separation
> (Demucs/Spleeter). It runs fully offline in a lightweight APK. The `AudioMixer`
> is structured so a future ONNX stem model can replace the 3-band split.

## APK build in GitHub (not local)
1. Push this folder to your repo:
   ```bash
   cd musicmix
   git init -b main
   git remote add origin https://github.com/Baran3575/MusicMix.git
   git add .
   git commit -m "MusicMix android app"
   git push -u origin main --force
   ```
   (If the repo already has a README, clone it first and copy these files in.)
2. Open GitHub → **Actions** tab → **Build APK** run → download
   **musicmix-debug-apk** artifact → install `app-debug.apk` on your phone.
3. Every push rebuilds the APK automatically.

## Project structure
```
settings.gradle.kts / build.gradle.kts / gradle.properties
app/build.gradle.kts (Media3 1.4.1, Compose, minSdk 26, target/compile 34)
app/src/main/AndroidManifest.xml
app/src/main/java/com/baran/musicmix/MainActivity.kt  (UI)
app/src/main/java/com/baran/musicmix/audio/AudioAnalyzer.kt (BPM+vocal detect)
app/src/main/java/com/baran/musicmix/audio/DeckController.kt (ExoPlayer decks)
app/src/main/java/com/baran/musicmix/audio/AudioMixer.kt (WAV export)
.github/workflows/build-apk.yml (cloud APK build)
```

## Permissions
- `READ_MEDIA_AUDIO` (Android 13+) / `READ_EXTERNAL_STORAGE` (older) — to read your downloaded songs.
- `MODIFY_AUDIO_SETTINGS` — playback volume handling.

License: MIT
