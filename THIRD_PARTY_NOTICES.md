# Third-party components

Application source is Kotlin and C11. Android framework APIs provide camera, decoding, encoding, audio routing, drawing, and storage. No Android UI or media library is bundled.

* RNNoise v0.1, Xiph.Org Foundation, BSD 3-Clause. Its C runtime and trained denoising network are built from the external source archive. [Source](https://github.com/xiph/rnnoise/tree/v0.1). The redistribution notice is included in the APK assets under `licenses/RNNoise.txt`.
* Qwen-ASR, Salvatore Sanfilippo, MIT, revision `924694251d9e0f18e5d86bbd06aa3ab5f870002d`. The external runtime is called through its C API. [Source](https://github.com/antirez/qwen-asr/tree/924694251d9e0f18e5d86bbd06aa3ab5f870002d). The license is included under `licenses/Qwen-ASR-runtime.txt`.
* Optional Qwen3-ASR-0.6B model weights, Qwen Team, Apache 2.0, Hugging Face revision `5eb144179a02acc5e5ba31e748d22b0cf3e303b0`. [Model card](https://huggingface.co/Qwen/Qwen3-ASR-0.6B). Weights are downloaded separately and are not embedded in the APK.
* Kotlin standard library, JetBrains, Apache 2.0.
* ONNX Runtime 1.31.1, Microsoft, MIT with third-party notices. The ARM64 Android library is used through its C API. [Source](https://github.com/microsoft/onnxruntime). Notices are bundled under `licenses/ONNX-Runtime.txt` and `licenses/ONNX-Runtime-ThirdParty.txt`.
* PPHumanSeg FP32 model from OpenCV Zoo, revision `2027dd2f5a8a5746b5d4964900a0465afc6d3a53`, Apache 2.0. [Source](https://github.com/opencv/opencv_zoo/tree/2027dd2f5a8a5746b5d4964900a0465afc6d3a53/models/human_segmentation_pphumanseg). The bundled model's notice is `licenses/PPHumanSeg.txt`; no OpenCV runtime is included.
* Optional LavaSR V1 model and its ONNX conversion, Apache 2.0. [Author's source](https://github.com/ysharma3501/LavaSR), [ONNX conversion](https://github.com/Topping1/LavaSR-ONNX). Notices are bundled under `licenses/LavaSR.txt` and `licenses/LavaSR-ONNX.txt`; model files are downloaded separately.

The supplied launcher artwork retains its original rights. Reference-derived test fixtures are identified in `app/src/androidTest/assets/README.md` and are not production media assets.

Runtime revisions and file hashes are recorded in `runtimes.json` and `data/models.json`. The application code does not incorporate the external runtime implementation sources.
