# Third-party components

Application source is Kotlin and C11. Android framework APIs provide camera, decoding, encoding, audio routing, drawing, and storage. No Android UI or media library is bundled.

* RNNoise v0.1, Xiph.Org Foundation, BSD 3-Clause. Its C runtime and trained denoising network are built from the external source archive. [Source](https://github.com/xiph/rnnoise/tree/v0.1). The redistribution notice is included in the APK assets under `licenses/RNNoise.txt`.
* Qwen-ASR, Salvatore Sanfilippo, MIT, revision `924694251d9e0f18e5d86bbd06aa3ab5f870002d`. The external runtime is called through its C API. [Source](https://github.com/antirez/qwen-asr/tree/924694251d9e0f18e5d86bbd06aa3ab5f870002d). The license is included under `licenses/Qwen-ASR-runtime.txt`.
* Optional Qwen3-ASR-0.6B model weights, Qwen Team, Apache 2.0, Hugging Face revision `5eb144179a02acc5e5ba31e748d22b0cf3e303b0`. [Model card](https://huggingface.co/Qwen/Qwen3-ASR-0.6B). Weights are downloaded separately and are not embedded in the APK.
* Kotlin standard library, JetBrains, Apache 2.0.
* ONNX Runtime 1.31.1, Microsoft, MIT with third-party notices. The ARM64 Android library is used through its C API. [Source](https://github.com/microsoft/onnxruntime). Notices are bundled under `licenses/ONNX-Runtime.txt` and `licenses/ONNX-Runtime-ThirdParty.txt`.
* PPHumanSeg FP32 model from OpenCV Zoo, revision `2027dd2f5a8a5746b5d4964900a0465afc6d3a53`, Apache 2.0. [Source](https://github.com/opencv/opencv_zoo/tree/2027dd2f5a8a5746b5d4964900a0465afc6d3a53/models/human_segmentation_pphumanseg). The bundled model's notice is `licenses/PPHumanSeg.txt`; no OpenCV runtime is included.
* Optional LavaSR v2 learned weights and its ONNX conversion, Apache 2.0. [Author's source](https://github.com/ysharma3501/LavaSR), [ONNX conversion](https://github.com/Topping1/LavaSR-ONNX). Notices are bundled under `licenses/LavaSR.txt` and `licenses/LavaSR-ONNX.txt`; model files are downloaded separately.

* Optional DeepFilterNet3 model, Hendrik Schröter and contributors, MIT/Apache-2.0. [Author's source and models](https://github.com/Rikorose/DeepFilterNet/tree/978576aa8400552a4ce9730838c635aa30db5e61). The pinned public mirror contains the same three ONNX graphs; the MIT notice is bundled under `licenses/DeepFilterNet.txt`.
* Optional VoiceFixer model, Haohe Liu and contributors, MIT. [Source](https://github.com/haoheliu/voicefixer). The [pinned ONNX conversion](https://huggingface.co/TigreGotico/audiosronnx-voicefixer/tree/aa259fa578b9d5f4c76fda3cd967f73542b0e15b) credits [Kazuhito Takahashi's exporter](https://github.com/Kazuhito00/VoiceFixer-ONNX/tree/8ee7c45b84e0a30bb1d2563b8b04231eba327e3d), MIT. Notices are bundled under `licenses/VoiceFixer.txt` and `licenses/VoiceFixer-ONNX.txt`.

The supplied launcher artwork retains its original rights. Reference-derived test fixtures are identified in `app/src/androidTest/assets/README.md` and are not production media assets.

Runtime revisions and file hashes are recorded in `runtimes.json` and `data/models.json`. The application code does not incorporate the external runtime implementation sources.
