# CueFlow

CueFlow is an offline Android teleprompter with a built-in camera. It records H.264 or HEVC video with AAC audio to `Movies/CueFlow` and follows English scripts with Whisper.cpp or streaming Zipformer through sherpa-onnx. The APK supports Android 10 and later on ARM64, ARMv7, and x86-64 devices with a camera and microphone.

**Settings → Video quality** offers 4K (3840 × 2160), 2K (2560 × 1440), 1080p, and 720p at 24, 30, or 60 fps. The picker shows the combinations supported by the selected camera and an installed video encoder. CueFlow also checks the individual physical lenses exposed through Camera2 and verifies their preview and recording streams before offering additional presets. A label such as **up to 30 fps** means the camera exposes a variable frame-rate range. If a camera or encoder rejects a preset at the start of a take, CueFlow tries another compatible preset and shows the actual quality when it saves. The Pixel 8 Pro keeps its verified physical front-camera 4K path.

Use the Android Studio JDK, Android SDK 37, NDK 28.2.13676358, and CMake 3.22.1. The native build retrieves Whisper.cpp v1.9.5 and checks its archive hash. To prepare the bundled Zipformer model, run these commands in PowerShell from the project directory:

```powershell
.\scripts\prepare-sherpa.ps1
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
```

The sherpa setup script downloads and verifies the pinned [Android AAR](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v2.0.1) and [English Zipformer model](https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26).

**Sherpa-ONNX · English Zipformer** is bundled and selected by default. To use Whisper, select **Settings → Speech model → Import Whisper model…** and choose a compatible Whisper.cpp GGML model file such as `tiny.en` or `base.en`. CueFlow prepares the selected model on a dedicated loading screen before opening the camera, then reuses it for successive takes. Switching models in Settings shows a loading indicator and keeps the camera open. Both engines work offline and support the same two-line scroll commands. Zipformer processes microphone audio incrementally and flushes the remaining audio when recording stops.

Whisper inference runs on a worker at normal scheduling priority with three CPU threads, reducing to two if Android reports moderate or greater thermal pressure. Zipformer uses two CPU threads. CueFlow prefers 48 kHz microphone input and can use 44.1 kHz or 16 kHz if necessary. A stateful low-pass filter converts the recognition copy to 16 kHz while recorded audio keeps the selected input rate. The script matcher normalizes common contractions and numbers, tolerates limited recognition errors, and holds on ambiguous repeated passages. The prompter includes space after the final line so it can stay at the top of either viewport. Two-line buttons and voice commands reset the reading position for rereading.

Run `scripts/generate-speech-fixtures.ps1` with Windows PowerShell to generate mono 48 kHz PCM16 WAV fixtures in `.cueflow-local`. Device tests accept paths to these files after pushing them with ADB. `WhisperPerformanceTest` uses `benchmarkAudio`; `SpeechNavigationTest` uses `benchmarkAudio` and/or `commandAudioPath` plus `commandLines` (`2` or `-2`). Supplying `benchmarkAudio` to `FrontCamera4kTest#recordsFront4k30ThroughTheApp` benchmarks the same audio while recording and checks the saved clip before deleting it. `PrompterScrollTest` checks that the final line reaches the reading edge and that manual rewind is retained in both viewport sizes. Speech fixture tests skip when their path arguments are absent.

`SpeechNavigationTest` and `FrontCamera4kTest#recordsFront4k30ThroughTheApp` use Zipformer by default. Import a Whisper model in CueFlow before running the Whisper benchmark or passing `-e speechEngine whisper` to exercise Whisper in either test.

`FrontCamera4kTest#recordsAdvertisedMaximumThroughTheApp` takes `-e compatCamera front` or `-e compatCamera back` and checks that CueFlow saves consecutive takes with audio and video at the highest supported preset resolution. Add `-e compatMaximum fps` to exercise the highest supported preset frame rate instead.

On the Pixel 8 Pro, select **Auto**, **Portrait**, or **Landscape** under **Settings → Orientation**. The preview shows the whole video frame with CueFlow’s script and controls layered over it. Record the same scene in CueFlow and Pixel Camera at matching settings, with Video Boost off. Try 4K/30 fps and 1080p/30 fps if both are available. In CueFlow, read a script out loud, pause, repeat a sentence, and skip a line. Watch the yellow highlight on the latest matched word. Check that the prompter holds and recovers, and play the saved clip to check audio sync and confirm that the text stays out of the video.

## License

CueFlow’s original source code is licensed under the [Apache License 2.0](LICENSE). Third-party components retain their own licenses. See the [bundled license notices](app/src/main/assets/licenses) and the [sherpa-onnx setup script](scripts/prepare-sherpa.ps1), which fetches notices for its dependencies.
