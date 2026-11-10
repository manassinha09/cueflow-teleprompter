# CueFlow - Teleprompter

I started CueFlow because I needed a free, open-source teleprompter with no ads to record YouTube videos in 4K. I wanted the script to scroll as I spoke so I could focus on the take. The goal is to give creators as much control as their phone supports, so they can get more out of the camera they already have.

CueFlow combines an offline English teleprompter with a built-in Android camera. It follows your speech as you record and saves the video without the script overlay.

## Downloads

| Channel | Availability |
| --- | --- |
| Google Play | The store link will appear here when CueFlow is published. |
| [GitHub Releases](https://github.com/manassinha09/cueflow-teleprompter/releases) | APK builds will appear here when they’re published. |
| [GitHub Actions](https://github.com/manassinha09/cueflow-teleprompter/actions/workflows/build-apk.yml) | Download `cueflow-debug-apk` from the **Artifacts** section of a successful run. |

## What CueFlow can do

- **Follow your script as you speak.** The prompter highlights the latest matched word, pauses when you pause, and handles repeated or skipped lines. Buttons and voice commands let you move two lines at a time.
- **Record at the quality your phone supports.** Choose from 4K (3840 × 2160), 2K (2560 × 1440), 1080p, or 720p at 24, 30, or 60 fps, depending on the selected camera and available video encoder.
- **Frame your shot in portrait or landscape.** The full video frame stays visible behind the script and controls. Videos use H.264 or HEVC with AAC audio and are saved to `Movies/CueFlow`.

## Supported devices

CueFlow supports **Android 10 (API 29) and later** on devices with a camera and microphone. The APK supports ARM64 (`arm64-v8a`), ARMv7 (`armeabi-v7a`), and x86-64 (`x86_64`).

**Settings → Video quality** lists the combinations the selected camera and an installed encoder can support. CueFlow checks individual lenses where Android exposes them. A label such as **up to 30 fps** indicates a variable frame rate. If a preset fails when a take starts, CueFlow tries a compatible setting and shows the actual quality when it saves. Front-camera 4K recording has been verified on the Pixel 8 Pro.

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

## Device testing

### Automated checks

Run `.\scripts\generate-speech-fixtures.ps1` with Windows PowerShell to generate mono 48 kHz PCM16 WAV fixtures in `.cueflow-local`. Push the files to the device with ADB and pass their device paths as instrumentation arguments. Tests that use speech fixtures skip when the required paths are absent.

| Test | What to run |
| --- | --- |
| `WhisperPerformanceTest` | Pass `benchmarkAudio` to measure Whisper decode performance. Import a Whisper model in CueFlow first. |
| `SpeechNavigationTest` | Pass `benchmarkAudio` for script following or `commandAudioPath` for voice commands. For commands, `commandLines` accepts `2` or `-2` and defaults to `2`. |
| `FrontCamera4kTest#recordsFront4k30ThroughTheApp` | Checks a front-camera 4K30 clip on the Pixel 8 Pro (API 35 or later). Pass `benchmarkAudio` and import a Whisper model to benchmark decoding during recording. |
| `FrontCamera4kTest#recordsAdvertisedMaximumThroughTheApp` | Pass `-e compatCamera front` or `-e compatCamera back` to check consecutive takes at the highest supported resolution. Add `-e compatMaximum fps` to test the highest frame rate instead. |
| `PrompterScrollTest` | Checks that the final line reaches the reading edge and that manual rewind is retained in both viewport sizes. |

`SpeechNavigationTest` and `recordsFront4k30ThroughTheApp` use Zipformer by default. To test either with Whisper, import a model first and pass `-e speechEngine whisper`.

### Manual recording check

1. In **Settings → Orientation**, try **Auto**, **Portrait**, and **Landscape**. Check that the full frame stays visible under the script and controls.
2. Record a take while reading a script. Pause, repeat a sentence, and skip a line. Watch the yellow highlight to check that the prompter pauses and resumes in the right place.
3. Play the saved clip. Check audio sync and confirm that the script overlay stays out of the video.
4. On the Pixel 8 Pro, compare the same scene with Pixel Camera at matching settings and with Video Boost off. Try 4K/30 fps and 1080p/30 fps if both are available.

## License

CueFlow’s original source code is licensed under the [Apache License 2.0](LICENSE). Third-party components retain their own licenses. See the [bundled license notices](app/src/main/assets/licenses) and the [sherpa-onnx setup script](scripts/prepare-sherpa.ps1), which fetches notices for its dependencies.
