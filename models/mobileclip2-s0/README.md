# MobileCLIP2-S0 Model Files

This directory is a placeholder for the MobileCLIP2-S0 model files required by the Local Photo Search app.

## Required Files

| File | Description |
|------|-------------|
| `model.json` | Model configuration metadata |
| `image_encoder.onnx` | Image encoder ONNX model |
| `text_encoder.onnx` | Text encoder ONNX model |
| `vocab.json` | CLIP BPE vocabulary (from openai/clip-vit-base-patch32) |
| `merges.txt` | CLIP BPE merge rules (from openai/clip-vit-base-patch32) |

## How to Prepare

Use the provided preparation script:

```bash
python tools/prepare_photo_search_model.py \
    --image-encoder /path/to/mobileclip2-s0/image_encoder.onnx \
    --text-encoder /path/to/mobileclip2-s0/text_encoder.onnx \
    --output-dir models/mobileclip2-s0
```

The script will copy the ONNX files, download or copy tokenizer files (`vocab.json`, `merges.txt`),
and generate a `model.json` configuration.

## Push to Device

After preparation, push the model directory to the device:

```bash
adb push models/mobileclip2-s0 \
    /sdcard/Android/data/com.photosearch.app/files/models/mobileclip2-s0
```

## Distribution

Model weights are **not** distributed with this repository. Users must obtain them from the
original source:

- **MobileCLIP2**: [apple/MobileCLIP2-S0](https://huggingface.co/apple/MobileCLIP2-S0)
  - ONNX export: [RuteNL/MobileCLIP2-S0-OpenCLIP-ONNX](https://huggingface.co/RuteNL/MobileCLIP2-S0-OpenCLIP-ONNX)
  - License: [Apple Sample Code License](https://developer.apple.com/sample-code/license/)
- **Tokenizer**: [openai/clip-vit-base-patch32](https://huggingface.co/openai/clip-vit-base-patch32)
  - License: MIT
