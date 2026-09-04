# Emulator platform signing

For `system-images;android-28;default;x86`, run:

```bash
./scripts/fetch-aosp-test-platform-keys.sh
```

The script fetches the public AOSP Android 9 **test** platform key from
`platform/build` tag `android-9.0.0_r1`.

`build-platform-apk.sh` verifies the certificate against the connected emulator's
`framework-res.apk` before installation. If the fingerprints do not match, use the
actual platform keys for that image:

```bash
PLATFORM_KEY_DIR=/path/to/keys ./scripts/build-platform-apk.sh
```

Never use the AOSP test key for a production image.
