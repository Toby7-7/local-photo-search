import argparse
import json
import os
import gzip
import shutil
import urllib.request
from pathlib import Path


TOKENIZER_REPO = "openai/clip-vit-base-patch32"
OPENAI_BPE_URL = "https://raw.githubusercontent.com/openai/CLIP/main/clip/bpe_simple_vocab_16e6.txt.gz"


def main() -> None:
    parser = argparse.ArgumentParser(description="Prepare model files for Local Photo Search")
    parser.add_argument("--image-encoder", required=True, help="Path to MobileCLIP2-S0 image_encoder.onnx")
    parser.add_argument("--text-encoder", required=True, help="Path to MobileCLIP2-S0 text_encoder.onnx")
    parser.add_argument("--output-dir", required=True, help="Output directory for model files")
    parser.add_argument("--vocab", help="Path to vocab.json (optional, auto-download if missing)")
    parser.add_argument("--merges", help="Path to merges.txt (optional, auto-download if missing)")
    args = parser.parse_args()

    out = Path(args.output_dir)
    out.mkdir(parents=True, exist_ok=True)

    # Copy encoder files
    shutil.copy2(args.image_encoder, out / "image_encoder.onnx")
    shutil.copy2(args.text_encoder, out / "text_encoder.onnx")

    # Tokenizer files
    if args.vocab and args.merges:
        shutil.copy2(args.vocab, out / "vocab.json")
        shutil.copy2(args.merges, out / "merges.txt")
    else:
        prepare_tokenizer_files(out)

    # Write model.json config
    config = {
        "id": "mobileclip2-s0",
        "displayName": "MobileCLIP2-S0",
        "family": "MobileCLIP2",
        "version": "photo-search-s0-v2-mobileclip-preprocess",
        "embeddingDim": 512,
        "imageEncoder": {
            "fileName": "image_encoder.onnx",
            "inputName": "pixel_values",
            "outputName": "image_embeds",
            "inputWidth": 256,
            "inputHeight": 256,
            "channels": 3,
            "layout": "NCHW",
            "mean": [0.0, 0.0, 0.0],
            "std": [1.0, 1.0, 1.0],
            "outputL2Normalize": True,
        },
        "textEncoder": {
            "fileName": "text_encoder.onnx",
            "inputIdsName": "input_ids",
            "attentionMaskName": None,
            "outputName": "text_embeds",
            "inputDataType": "INT64",
            "outputL2Normalize": True,
            "tokenizer": {
                "type": "clip_bpe",
                "vocabFileName": "vocab.json",
                "mergesFileName": "merges.txt",
                "maxLength": 77,
                "bosToken": "<|startoftext|>",
                "eosToken": "<|endoftext|>",
                "padTokenId": 0,
            },
        },
        "notes": "Prepared for the Photo Search app. Push these files to Android/data/com.photosearch.app/files/models/mobileclip2-s0/.",
    }
    (out / "model.json").write_text(json.dumps(config, indent=2, ensure_ascii=False))

    print(f"Model prepared at {out}")
    print("adb push example:")
    print(f"adb push {out} /sdcard/Android/data/com.photosearch.app/files/models/mobileclip2-s0")


def prepare_tokenizer_files(out: Path) -> None:
    os.environ.setdefault("HF_HUB_DOWNLOAD_TIMEOUT", "30")

    try:
        from huggingface_hub import hf_hub_download

        vocab = Path(hf_hub_download(TOKENIZER_REPO, "vocab.json"))
        merges = Path(hf_hub_download(TOKENIZER_REPO, "merges.txt"))
        shutil.copy2(vocab, out / "vocab.json")
        shutil.copy2(merges, out / "merges.txt")
        return
    except Exception:
        pass

    gz_path = out / "bpe_simple_vocab_16e6.txt.gz"
    if not gz_path.exists():
        urllib.request.urlretrieve(OPENAI_BPE_URL, gz_path)
    convert_openai_bpe(gz_path, out / "vocab.json", out / "merges.txt")


def convert_openai_bpe(gz_path: Path, vocab_path: Path, merges_path: Path) -> None:
    lines = gzip.open(gz_path, "rt", encoding="utf-8").read().splitlines()
    merges = [line for line in lines[1 : 49152 - 256 - 2 + 1] if line.strip()]
    encoder_tokens = list(bytes_to_unicode().values())
    encoder_tokens = encoder_tokens + [token + "</w>" for token in encoder_tokens]
    for merge in merges:
        encoder_tokens.append("".join(merge.split()))
    encoder_tokens.extend(["<|startoftext|>", "<|endoftext|>"])
    vocab = {token: index for index, token in enumerate(encoder_tokens)}
    vocab_path.write_text(json.dumps(vocab, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    merges_path.write_text("#version: 0.2\n" + "\n".join(merges) + "\n", encoding="utf-8")


def bytes_to_unicode() -> dict[int, str]:
    bs = list(range(ord("!"), ord("~") + 1))
    bs += list(range(ord("¡"), ord("ÿ") + 1))
    bs += list(range(ord("®"), ord("ÿ") + 1))
    cs = bs[:]
    n = 0
    for b in range(256):
        if b not in bs:
            bs.append(b)
            cs.append(256 + n)
            n += 1
    return dict(zip(bs, [chr(n) for n in cs]))


if __name__ == "__main__":
    main()
