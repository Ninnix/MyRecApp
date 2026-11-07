# Releasing YouRec

The repository currently provides debug builds for development. There is no
published release or configured production signing key.

```sh
./tools/build.sh :app:assembleDebug
```

The APK is `app/build/outputs/apk/debug/app-debug.apk`. Gradle uses its debug key,
or `.build/debug.keystore` when that local key exists. The alternative build
also writes its debug key under `.build/`.

For production distribution, configure an upload key outside the repository
and sign the release APK/AAB with Android's signing tools. Keep signing files,
passwords and local properties out of commits. Preserve the signing identity
when updating an installed app.

Run the affected checks in [TESTING.md](TESTING.md), verify bundled licenses and
model checksums, and review permission/privacy changes before distributing a
build. Repository visibility remains private; changing it or publishing a
release is a separate action.
