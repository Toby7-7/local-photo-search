# Local Photo Search

**Local Photo Search** is an on-device Android app for text-based photo retrieval. It indexes photos from your device gallery using MobileCLIP2-S0 embeddings and lets you search them by entering text — all on the device, no data leaves your phone.

[中文文档](README_zh.md)

## Features

- Real-time text search — English optimal (CLIP BPE tokenizer, non-English text is byte-level tokenized with degraded quality)
- Shows recent indexed photos when the search box is empty
- Results sorted by semantic similarity
- Thumbnail preview with tap-to-open in system gallery
- Configurable score threshold to filter low-relevance results
- Preload toggles for search model and index model
- Pause/resume indexing
- Retry failed photos individually or all at once
- Index rebuild only clears app data, never deletes your photos

## Limitations

- **Language:** The search model uses OpenAI's CLIP BPE tokenizer, which is English-native. Non-English queries (including Chinese) are split into byte-level Unicode tokens, producing degraded embedding quality. Non-English text may still find relevant results due to shared visual concepts, but precision is significantly lower than English.
- **Architecture:** APK targets `arm64-v8a` only. x86/x86_64/armeabi-v7a devices and Android emulators are not supported.
- **Model delivery:** ONNX weight files are not bundled in the APK. Users must manually prepare and push them via adb (see [Model Weights](#model-weights)). This also means the app cannot run immediately after installation.

## Privacy

- No photo upload
- No embedding or vector upload
- No search query upload
- Only reads photos you explicitly authorize via Android's media permission system

## Model Weights

ONNX model weight files are **not** distributed with this repository. You must obtain them separately.

**Required model files:**
```
model.json
image_encoder.onnx
text_encoder.onnx
vocab.json
merges.txt
```

Use the provided script to prepare the model package:
```
python tools/prepare_photo_search_model.py \
    --image-encoder /path/to/image_encoder.onnx \
    --text-encoder /path/to/text_encoder.onnx \
    --output-dir models/mobileclip2-s0
```

Then push to your device:
```
adb shell mkdir -p /sdcard/Android/data/com.photosearch.app/files/models/mobileclip2-s0
adb push models/mobileclip2-s0/. /sdcard/Android/data/com.photosearch.app/files/models/mobileclip2-s0/
```

**Model source:** [apple/MobileCLIP2-S0](https://huggingface.co/apple/MobileCLIP2-S0)
**ONNX export:** [RuteNL/MobileCLIP2-S0-OpenCLIP-ONNX](https://huggingface.co/RuteNL/MobileCLIP2-S0-OpenCLIP-ONNX)
**Tokenizer source:** [openai/clip-vit-base-patch32](https://huggingface.co/openai/clip-vit-base-patch32)

## Build

The APK targets `arm64-v8a` only (64-bit ARM devices).

```powershell
./gradlew :app:testStandardDebugUnitTest
./gradlew :app:testQnnDebugUnitTest
./gradlew :app:assembleStandardDebug
./gradlew :app:assembleQnnDebug
```

Two flavors are available:
- **standard** — uses ONNX Runtime CPU/NNAPI
- **qnn** — uses ONNX Runtime QNN package with HTP/DSP/CPU backends

## Quick Start

```powershell
# 1. Build
./gradlew assembleQnnRelease --no-daemon

# 2. Sign with debug keystore
apksigner sign --ks %USERPROFILE%/.android/debug.keystore --ks-pass pass:android --out app-qnn-signed.apk app/build/outputs/apk/qnn/release/app-qnn-release-unsigned.apk

# 3. Install
adb install -r app-qnn-signed.apk

# 4. Push model files
adb shell mkdir -p /sdcard/Android/data/com.photosearch.app/files/models/mobileclip2-s0
adb push models/mobileclip2-s0/. /sdcard/Android/data/com.photosearch.app/files/models/mobileclip2-s0/
```

After installation, open the app, grant photo permission, and start indexing.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
