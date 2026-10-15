# Release APK signing and update compatibility

Android only installs an update when the application ID, signing certificate, and version code are compatible. Aether+ keeps the application ID `io.github.mrmatin0.aether` and increases the base version code for every release, so the critical rule is: **never replace the release certificate**.

## Required GitHub Actions secrets

Release builds must use the private keystore whose certificate signed the existing distributed APKs. Configure these repository Actions secrets:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | Base64-encoded release keystore (`base64 -w0 release.keystore`) |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Release key alias |
| `KEY_PASSWORD` | Private-key password |

The workflow decodes the keystore only inside the runner's temporary directory and passes credentials only to the Gradle signing step. No keystore, password, or private key belongs in Git. If the secrets are missing, the release build must fail; it must never generate a new key or silently use a debug key.

## Preserve existing installations

Before configuring the secrets, verify that the private keystore has the same SHA-256 certificate fingerprint as the previous release APKs. The repository pins the expected fingerprint in `.github/expected-signer.txt` and CI rejects a mismatch before publication. This check is what prevents `INSTALL_FAILED_UPDATE_INCOMPATIBLE` for users upgrading from an existing release.

Also keep all of the following unchanged for an update:

- application ID: `io.github.mrmatin0.aether`
- signing certificate: the existing release certificate
- monotonically increasing `versionCode`

For Aether+ **1.5.5**, the version name is `1.5.5` and the base version code is `20`.

## Local release builds

Create `keystore.properties` from `keystore.properties.example`, or provide `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD` in the environment, then run:

```bash
gradle :app:assembleRelease -PgithubRepo=MrMatin0/Aether
```

Never commit `release.keystore`, `keystore.properties`, or encoded key material. Back up the private keystore securely. Losing it means future updates cannot be installed over the existing app.

## Verify an APK

```bash
apksigner verify --verbose --print-certs app/build/outputs/apk/release/*.apk
adb install -r Aether-1.5.5-arm64-v8a.apk
```

The signer fingerprint must match `.github/expected-signer.txt`, and the install test must be performed without uninstalling the previous version. If either check fails, do not publish the APK.
