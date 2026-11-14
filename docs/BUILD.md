# Building MyRec

Run these commands from the repository root. You need JDK 17+, Android SDK 37,
build tools 36.0.0, NDK 28.2.13676358, CMake 3.31.6, Python 3.12, and curl.
The wrapper pins Gradle 9.6.0.

## Get the source and build

The checkout uses this remote:

```sh
git clone https://github.com/Ninnix/MyRecApp.git MyRecApp
cd MyRecApp
python3 tools/prepare-runtimes.py
python3 tools/prepare-person.py
./tools/build.sh :app:assembleDebug :app:assembleDebugAndroidTest :app:testEditor
```

The runtime script downloads the pinned sources and ONNX Runtime into
`.build/runtime-sources/`. [runtimes.json](../runtimes.json) records the sources
and checksums. The person script verifies or restores the bundled segmentation
model from [data/models.json](../data/models.json). Audio and caption downloads
are optional; you do not need them to compile.

The app uses Kotlin and C11. External runtime implementations stay outside the
application sources and are called through C APIs. Local reference screenshots
and experiments are not needed for a build.

## Set SDK paths

Set `JAVA_HOME` and `ANDROID_HOME` if they are not already configured.
`tools/build.sh` also finds the usual Android Studio JDK location on Linux.
Put machine-specific SDK paths in the ignored `local.properties`:

```properties
sdk.dir=/absolute/path/to/Android/Sdk
```

For an NDK outside the SDK, pass
`-PmyrecNdkPath=/absolute/path/to/android-ndk` to the build command.
The app targets ARM64 and needs Android 9 or later. Inference uses the CPU;
there is no manufacturer SDK to install.

## Install

```sh
adb -s SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
```

Open MyRec and grant camera and microphone access when recording. The package
is `org.myrec.app`; it does not replace `org.yourec.app` or copy that app's
private data. Keep the old installation if it contains projects you need.
See [Testing](TESTING.md) before running device checks.

## Build without a Gradle daemon

```sh
python3 tools/build.py
```

This uses the installed SDK/NDK and cached Kotlin compiler. Prepare the runtimes
first. The APK is `.build/manual/myrec-debug.apk`. It uses the same Kotlin module
name and assets as Gradle. Use the wrapper for the first build to populate the
compiler cache.

## Keep the iOS source nearby

```sh
git clone --depth 1 https://github.com/antirez/YouRecApp.git reference/YouRecApp
git -C reference/YouRecApp rev-parse HEAD
```

Skip the clone if the folder already exists. `reference/` is ignored.
Record the exact commit when comparing algorithms or behavior. Signing is
covered in [Releasing](RELEASING.md).
