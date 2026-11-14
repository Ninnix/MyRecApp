# Releasing MyRec

The repository provides debug builds. There is no production signing key or
published MyRec release configured here.

```sh
./tools/build.sh :app:assembleDebug
```

The APK is `app/build/outputs/apk/debug/app-debug.apk`. Gradle uses its debug key,
or `.build/debug.keystore` when that file exists. The alternative builder also
keeps its debug key under `.build/`.

For distribution, create an upload key outside the repository and sign the
release APK or AAB with Android's signing tools. Keep keys, passwords, and local
properties out of commits. Future updates must keep the package and signing
identity of the installed MyRec build.

The package is now `org.myrec.app`. It installs separately from the old
`org.yourec.app` build. There is no automatic project migration. Do not tell
users to uninstall the old app before saving what they need from it.

Run the affected checks in [Testing](TESTING.md), verify model checksums and
notices, and check the app label, export folder, and launcher icon. Include
[LICENCE](../LICENCE) and its APK copy under `licenses/MyRec.txt`, along with
the complete upstream notice under `licenses/YouRec.txt`. YouRec's MIT
grant covers its source and documentation; it reserves the YouRec name and icon.
Use MyRec's own name and hippopotamus artwork. VoiceFixer code and model weights
have separate licenses; see [the notices](../THIRD_PARTY_NOTICES.md).

Renaming the local app does not rename the GitHub repository or publish a release.
The existing remote and repository visibility are unchanged.
