# Contributing

Check the behavior a change can affect. A camera-control change needs a real
input check; a change to audio order, source clocks or caching needs a processing
correctness check. Include the commands, device, Android version and failures
in the pull request.

Read [AGENTS.md](AGENTS.md) before changing code. Application code is Kotlin or
C11. External inference libraries keep their own implementations and are called
through C APIs. Keep dependencies to the minimum required.

## Build and checks

```sh
python3 scripts/prepare-runtimes.py
python3 scripts/prepare-person.py
./scripts/build.sh :app:testEditor :app:assembleDebug :app:assembleDebugAndroidTest
ANDROID_SERIAL=SERIAL python3 scripts/test-device.py
```

See [BUILD.md](docs/BUILD.md) for tools and [TESTING.md](docs/TESTING.md) for
choosing a device suite. Use a dedicated test device, keep it unlocked during UI
checks, and leave MyRec in the foreground. The runner installs both debug APKs
without clearing app storage. Test project metadata uses isolated cache folders.

## Processing comparisons

Keep the same input, runtime revision, model, thread count and thermal conditions
for before/after measurements. Separate cold loading from warm processing.
Verify frame timing, audio duration and actual output as well as speed. A short
fixture does not establish quality on every video or phone.

## Reporting a problem

Include reproduction steps, expected behavior, phone and Android version, camera
or microphone route, project shape, clip speed and relevant effects. Use short
synthetic media where possible.

Do not attach private recordings, transcripts, device dumps, credentials or
signing keys. Build products, source references and local experiments stay in
ignored folders. Update the privacy notes when storage, permissions or network
requests change.
