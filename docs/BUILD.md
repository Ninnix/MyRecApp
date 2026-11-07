# Building YouRec

Run commands from the repository root. The repository is private; cloning needs
GitHub access to `Ninnix/YouRec`.

## Tools and source

Use JDK 17+, Android SDK 37, build tools 36.0.0, NDK 28.2.13676358, CMake 3.31.6,
Python 3.12 and curl. The Gradle wrapper pins Gradle 9.6.0.

```sh
git clone https://github.com/Ninnix/YouRec.git
cd YouRec
python3 tools/prepare-runtimes.py
python3 tools/prepare-person.py
./tools/build.sh :app:assembleDebug :app:assembleDebugAndroidTest :app:testEditor
```

Runtime archives and ONNX Runtime are fetched into `.build/runtime-sources/`.
Their exact sources and checksums are in [runtimes.json](../runtimes.json).
Application sources remain Kotlin and C11; external runtime internals are not
copied into application sources. No local reference screenshots or experiment
folders are needed to build.

`prepare-person.py` verifies or restores the bundled segmentation model using
[data/models.json](../data/models.json). Caption and Studio weights are optional
and are not needed to compile the app.

## SDK paths

Set `JAVA_HOME` and `ANDROID_HOME` as needed. `tools/build.sh` also recognizes the
usual Android Studio JDK location on Linux. Machine-specific SDK paths belong
in the ignored `local.properties`:

```properties
sdk.dir=/absolute/path/to/Android/Sdk
```

For an NDK outside the SDK, add
`-PyourecNdkPath=/absolute/path/to/android-ndk` to the build command.

The current native target is `arm64-v8a`, with Android 9 as the minimum version.
The packaged inference backend is CPU execution; no manufacturer SDK is needed.

## Install

```sh
adb -s SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
```

Open YouRec and grant camera/microphone access when recording. See
[TESTING.md](TESTING.md) before running instrumentation on a phone.

## Alternative compiler script

```sh
python3 tools/build.py
```

This uses the installed SDK/NDK and cached Kotlin compiler without a Gradle
daemon. Prepare the runtimes first. Its APK is
`.build/manual/yourec-debug.apk`; it uses the same Kotlin module name and assets
as the Gradle build. This path needs the compiler versions already in the
Gradle cache, so use the wrapper for a first build.

Signing and distribution are described in [RELEASING.md](RELEASING.md).
