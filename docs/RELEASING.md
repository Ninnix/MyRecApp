# Releasing MyRec

Release 0.1.2 targets ARM64 devices running Android 9 or later. GitHub releases
contain a signed APK, a signed Android App Bundle and SHA-256 checksums. Build
products and private signing material stay outside Git.

For a local development build:

```sh
./tools/build.sh :app:assembleDebug
```

The APK is `app/build/outputs/apk/debug/app-debug.apk`. Gradle uses its debug key,
or `.build/debug.keystore` when that file exists. The alternative builder also
keeps its debug key under `.build/`.

For a release, keep the signing key outside the repository. Configure its path
and credentials in an ignored `keystore.properties` at the repository root:

```properties
storeFile=/absolute/path/to/myrec-release.keystore
storePassword=your-private-password
keyAlias=myrec
keyPassword=your-private-password
```

```sh
./tools/build.sh :app:assembleRelease :app:bundleRelease
```

The signed APK is `app/build/outputs/apk/release/app-release.apk`. Without local
signing properties, Gradle produces an unsigned release APK. The signed bundle
is `app/build/outputs/bundle/release/app-release.aab`; use it for Play Console
uploads. An AAB is not directly installable on a phone; use the APK for that.
Verify the signed APK with Android's `apksigner verify --verbose --print-certs`
and confirm its package, version and supported ABI before publishing. Increment `versionCode`
for each release and keep `versionName` in Gradle and the alternative builder
in sync.

Keep the key and a secure backup for future updates. Debug and release builds
have different signing identities. Export projects as ZIP archives before
switching an existing development installation to the release build; Android
cannot install one over the other. Keep keys, passwords and local properties
out of commits and release attachments.

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

Run the affected checks, commit the release source, create the version tag and
push both before publishing. Attach the signed APK and checksum to the matching
GitHub release, together with the signed AAB. The release notes should identify
tested devices and remaining limits without claiming complete iOS parity.
