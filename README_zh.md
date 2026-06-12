# 本地搜图

**本地搜图**是一个本机运行的 Android 照片文本检索 App。它读取手机相册中已授权的图片，用 MobileCLIP2-S0 为照片建立本地向量索引，然后可以直接输入文字搜索照片。

所有索引、向量和搜索都在手机本地完成；App 不上传原图、不上传向量、也不依赖云端检索服务。

[English Documentation](README.md)

## 主要功能

- 输入文字实时搜索本机照片（英语效果最佳，非英语文本经过 byte-level 分解后语义精度下降）
- 清空搜索词后显示最近已索引照片
- 结果按相似度从高到低排列
- 支持连续滚动加载更多结果
- 点击缩略图可用系统相册打开原图
- 索引过程中也可以搜索已完成索引的照片并查看缩略图
- 可设置置信度阈值，过滤低分结果
- 可开关搜索模型和索引模型预加载
- 支持暂停和继续索引
- 失败照片可查看原因，并支持单张或全部重试
- 重建索引只删除本 App 的索引和向量文件，不删除手机照片

## 已知限制

- **搜索语言：** 底层使用 OpenAI CLIP BPE tokenizer，**英语搜索效果最佳**。中文等非英语输入在 tokenizer 内部会被拆解为 byte 级 Unicode 单元，语义精度大幅下降。因视觉概念跨语言共享，部分非英语搜索仍能命中相关结果，但精度远不及英语。
- **CPU 架构：** APK 仅打包 `arm64-v8a` 原生库，不支持 x86/x86_64/armeabi-v7a 设备及 Android 模拟器。
- **模型加载：** ONNX 权重文件不内置在 APK 中，需用户自行准备并通过 adb 推送（见[模型权重](#模型权重)），安装后无法立即使用。

## 隐私

- 不上传照片
- 不上传向量和 embedding
- 不上传搜索 query
- 只读取用户通过 Android 媒体权限明确授权的照片

## 模型权重

ONNX 模型权重文件**不随仓库分发**，需自行获取。

**必需文件：**
```
model.json
image_encoder.onnx
text_encoder.onnx
vocab.json
merges.txt
```

使用脚本准备模型包：
```bash
python tools/prepare_photo_search_model.py \
    --image-encoder /path/to/image_encoder.onnx \
    --text-encoder /path/to/text_encoder.onnx \
    --output-dir models/mobileclip2-s0
```

推送到手机：
```bash
adb shell mkdir -p /sdcard/Android/data/com.photosearch.app/files/models/mobileclip2-s0
adb push models/mobileclip2-s0/. /sdcard/Android/data/com.photosearch.app/files/models/mobileclip2-s0/
```

**模型来源：** [apple/MobileCLIP2-S0](https://huggingface.co/apple/MobileCLIP2-S0)
**ONNX 导出：** [RuteNL/MobileCLIP2-S0-OpenCLIP-ONNX](https://huggingface.co/RuteNL/MobileCLIP2-S0-OpenCLIP-ONNX)
**Tokenizer 来源：** [openai/clip-vit-base-patch32](https://huggingface.co/openai/clip-vit-base-patch32)

## 构建

APK 仅包含 `arm64-v8a` native 库（不含 x86、x86_64、armeabi-v7a），仅适用于 64 位 ARM 设备。

两个 flavor：
- **standard** — 使用 ONNX Runtime CPU/NNAPI
- **qnn** — 使用 ONNX Runtime QNN 包（HTP/DSP/CPU）

```bash
./gradlew :app:testStandardDebugUnitTest
./gradlew :app:testQnnDebugUnitTest
./gradlew :app:assembleStandardDebug
./gradlew :app:assembleQnnDebug
```

## 快速开始

```bash
# 1. 构建
./gradlew assembleQnnRelease --no-daemon

# 2. 用 debug keystore 签名
apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android --out app-qnn-signed.apk app/build/outputs/apk/qnn/release/app-qnn-release-unsigned.apk

# 3. 安装
adb install -r app-qnn-signed.apk

# 4. 推送模型文件
adb shell mkdir -p /sdcard/Android/data/com.photosearch.app/files/models/mobileclip2-s0
adb push models/mobileclip2-s0/. /sdcard/Android/data/com.photosearch.app/files/models/mobileclip2-s0/
```

安装后打开 App，授权照片访问并开始索引即可使用。

## 许可证

Apache License 2.0 — 见 [LICENSE](LICENSE)。
