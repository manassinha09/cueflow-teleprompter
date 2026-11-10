# CueFlow - Teleprompter

I started CueFlow because I needed a free, open-source teleprompter with no ads to record YouTube videos in 4K. I wanted the script to scroll as I spoke so I could focus on the take. The goal is to give creators as much control as their phone supports, so they can get more out of the camera they already have.

CueFlow combines an offline English teleprompter with a built-in Android camera. It follows your speech as you record and saves the video without the script overlay.

## Downloads

| Channel | Availability |
| --- | --- |
| Google Play | The store link will appear here when CueFlow is published. |
| [GitHub Releases](https://github.com/manassinha09/cueflow-teleprompter/releases/latest) | Official signed [0.1.0 APK](https://github.com/manassinha09/cueflow-teleprompter/releases/download/v0.1.0/cueflow-0.1.0.apk) and [SHA-256 checksum](https://github.com/manassinha09/cueflow-teleprompter/releases/download/v0.1.0/cueflow-0.1.0.apk.sha256). |
| [GitHub Actions](https://github.com/manassinha09/cueflow-teleprompter/actions/workflows/build-apk.yml) | Branch and PR builds provide `cueflow-debug-apk` for development. Matching version tags build, verify, and publish official signed releases. |

Version **0.1.0** is the first official release (Android version code **2**). Older debug/preview builds use a different signing certificate and cannot be updated in place. Save any needed scripts/settings before removing a preview installation. Future official releases will use the same release key and support normal updates.

## What CueFlow can do

- **Follow your script as you speak.** The prompter highlights the latest matched word, pauses when you pause, and handles repeated or skipped lines. Buttons and voice commands let you move two lines at a time.
- **Record at the quality your phone supports.** Choose from 4K (3840 × 2160), 2K (2560 × 1440), 1080p, or 720p, depending on the selected camera and available video encoder. 4K is capped at 30 fps; lower resolutions support up to 60 fps. Advertised 24 fps presets are also available.
- **Frame your shot in portrait or landscape.** The full video frame stays visible behind the script and controls. Videos use H.264 or HEVC with AAC audio and are saved to `Movies/CueFlow`.

## Supported devices

CueFlow supports **Android 10 (API 29) and later** on devices with a camera and microphone. The APK supports ARM64 (`arm64-v8a`), ARMv7 (`armeabi-v7a`), and x86-64 (`x86_64`).

**Settings → Video quality** lists the combinations the selected camera and an installed encoder can support, and identifies whether the front or rear camera is selected. Discovery uses standard camera metadata, recording profiles, exposed physical lenses, and available vendor metadata without a phone-model allowlist. When metadata omits a preset, CueFlow tests the camera and hardware encoder together and checks the actual dimensions and delivered frame rate before adding it. Fixed 60 fps modes are also checked for real 60 fps output. The 4K30 and lower-resolution 60 fps limits apply to every discovery path.

The first camera opening can take a few extra seconds while these silent checks run. No audio or videos are saved during detection. Successful checks are cached for that camera, preview configuration, encoder, and firmware; failed checks can be retried after five minutes. Firmware changes trigger fresh checks. This improves automatic compatibility, but cannot unlock modes a manufacturer prevents third-party apps from using.

A label such as **up to 30 fps** indicates a variable frame rate. If a preset fails when a take starts, CueFlow tries a compatible setting and shows the actual quality when it saves. Front-camera 4K recording has been verified on the Pixel 8 Pro and Motorola Edge 60 Fusion. On the Edge 60 Fusion, 1080p60 is available on the rear camera; the front camera delivers 30 fps.

## Speech following

**English Zipformer** runs offline through sherpa-onnx and is bundled and selected by default. It processes microphone audio as you speak. To use Whisper instead, open **Settings → Speech model → Import Whisper model…** and choose a compatible Whisper.cpp GGML model such as `tiny.en` or `base.en`. CueFlow loads the selected model before opening the camera and reuses it across takes.

Both engines receive a 16 kHz copy of the microphone audio. The recording keeps the selected input rate, with 48 kHz preferred and 44.1 kHz or 16 kHz used when needed. Whisper uses three CPU threads and reduces to two under moderate or higher thermal pressure. Zipformer uses two threads.

## Technologies used

| Area | Technology and version |
| --- | --- |
| App UI | Kotlin, Jetpack Compose, and Material 3. Compose compiler plugin 2.2.10 and Compose BOM 2026.02.01. |
| Camera and recording | Android Camera2, MediaCodec, MediaMuxer, and AndroidX Camera Viewfinder 1.6.2. |
| Offline speech recognition | [sherpa-onnx 2.0.1](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v2.0.1) with an [English Zipformer model](https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26), or Whisper.cpp 1.9.5 through C++ and JNI. |
| Markdown scripts | CommonMark 0.32.0. |
| Build | Gradle 9.6.0, Android Gradle Plugin 9.5.1, and a JDK 25 Gradle daemon. |

## Build from source

Open the project in Android Studio and install Android SDK 37, NDK 28.2.13676358, and CMake 3.22.1. CueFlow compiles and targets API 37, with API 29 as the minimum for installing the app.

From the project root, run these commands in PowerShell:

```powershell
.\scripts\prepare-sherpa.ps1
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
```

The setup script downloads and verifies the pinned sherpa-onnx Android library and Zipformer model. CMake fetches Whisper.cpp and verifies its archive checksum. The debug APK is written to `app/build/outputs/apk/debug/`.

### Official releases

`version.properties` is the shared source for the APK version and GitHub release tag. Increase `versionCode` for every official release, set `versionName`, and add the matching file under `release-notes/`. Push an annotated `v<versionName>` tag from the committed source to publish it. CI rejects tags that do not exactly match the app version.

Tagged builds require four GitHub Actions secrets: `CUEFLOW_KEYSTORE_BASE64`, `CUEFLOW_KEYSTORE_PASSWORD`, `CUEFLOW_KEY_ALIAS`, and `CUEFLOW_KEY_PASSWORD`. The keystore is restored only for the release job and removed afterward. For local signed builds, set the same password and alias variables plus `CUEFLOW_KEYSTORE_FILE` pointing to the private keystore, then run:

```powershell
.\gradlew.bat --no-configuration-cache :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
.\scripts\verify-release.ps1 -ApkPath app/build/outputs/apk/release/app-release.apk -SdkRoot $env:ANDROID_HOME
```

Release packaging fails when signing is not configured. Verification checks the APK version, non-debuggable manifest, official signing-certificate fingerprint, supported ABIs, ZIP alignment, and bundled speech model. GitHub Actions publishes the verified universal APK and checksum as a stable release only after these checks pass. The public certificate fingerprint is tracked in `release-signing-certificate.sha256`; private keys and passwords must never be committed. Keep a secure backup of the release keystore and its passwords, and reuse that key for future updates.

The 0.1.0 release disables partial Kotlin/AndroidX optimization because it caused a startup `IllegalAccessError` in `ProcessLifecycleInitializer`. The APK is still signed and non-debuggable, and native speech libraries use their release build configuration. Run the release smoke test before enabling code optimization again.

## Device testing

### Automated checks

Run `.\scripts\generate-speech-fixtures.ps1` with Windows PowerShell to generate mono 48 kHz PCM16 WAV fixtures in `.cueflow-local`. Push the files to the device with ADB and pass their device paths as instrumentation arguments. Tests that use speech fixtures skip when the required paths are absent.

| Test | What to run |
| --- | --- |
| `WhisperPerformanceTest` | Pass `benchmarkAudio` to measure Whisper decode performance. Import a Whisper model in CueFlow first. |
| `SpeechNavigationTest` | Pass `benchmarkAudio` for script following or `commandAudioPath` for voice commands. For commands, `commandLines` accepts `2` or `-2` and defaults to `2`. |
| `FrontCamera4kTest#recordsFront4k30ThroughTheApp` | Checks a front-camera 4K30 clip on the Pixel 8 Pro (API 35 or later). Pass `benchmarkAudio` and import a Whisper model to benchmark decoding during recording. |
| `FrontCamera4kTest#recordsAdvertisedMaximumThroughTheApp` | Pass `-e compatCamera front` or `-e compatCamera back` to check consecutive takes at the highest supported resolution. Add `-e compatMaximum fps` to test the highest frame rate instead, or `-e compatQuality 4K -e compatFps 30` to require an exact preset. Checks menu selection, audio, measured frame rate, decoding, and preview recovery. Close other camera apps first. |
| `CameraCapabilitiesTest#reportCameraCapabilities` | Logs public camera IDs, physical lenses, stream sizes and durations, frame-rate ranges, recording profiles, Motorola video configurations, and hardware encoder limits. |
| Generic discovery regression | Add `-e compatGenericDiscovery true` to `recordsAdvertisedMaximumThroughTheApp` to omit vendor metadata and recording profiles, bypass cached results, and discover modes from actual encoded frames. `-e compatAbsentFps 60` also verifies rejection of false 60 fps support. |
| `FrontCamera4kTest#cameraDiscoverySurvivesActivityRecreation` | Clears only the capability-check cache, recreates the activity during detection, then verifies recovery and another cached restart. |
| `ReleaseSmokeTest#recordsWithBundledSpeechAndDecodesTheSavedVideo` | Pass `-e releaseSmoke true` on a clean test device with the official APK and a test APK signed with the same certificate. Checks bundled Zipformer, video/audio recording, decoding, and activity recreation against the signed release. |
| `CameraCapabilitiesTest#probeExplicitRecordingMode` | Opt-in diagnostic: pass `-e probeCamera 1 -e probeQuality 4K -e probeFps 30` to test a mode even if absent from standard metadata. Measures delivered frames; accepting a session or declaring an encoder frame rate alone does not prove support. Does not save a recording. |
| `PrompterScrollTest` | Checks that the final line reaches the reading edge and that manual rewind is retained in both viewport sizes. |

`SpeechNavigationTest` and `recordsFront4k30ThroughTheApp` use Zipformer by default. To test either with Whisper, import a model first and pass `-e speechEngine whisper`.

### Manual recording check

1. In **Settings → Orientation**, try **Auto**, **Portrait**, and **Landscape**. Check that the full frame stays visible under the script and controls.
2. Record a take while reading a script. Pause, repeat a sentence, and skip a line. Watch the yellow highlight to check that the prompter pauses and resumes in the right place.
3. Play the saved clip. Check audio sync and confirm that the script overlay stays out of the video.
4. On the Pixel 8 Pro, compare the same scene with Pixel Camera at matching settings and with Video Boost off. Try 4K/30 fps and 1080p/30 fps if both are available.

## License

CueFlow’s original source code is licensed under the [Apache License 2.0](LICENSE). Third-party components retain their own licenses. See the [bundled license notices](app/src/main/assets/licenses) and the [sherpa-onnx setup script](scripts/prepare-sherpa.ps1), which fetches notices for its dependencies.
