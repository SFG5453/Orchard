# Publishing NPU add-ons

Run **Publish Android NPU add-ons** from GitHub Actions with **Run workflow**.
It is manual only; mobile tags and canary pushes do not publish NPU packages.

The workflow builds the reduced ONNX Runtime 1.31.0 with QAIRT 2.51.0 and
NDK 28.2.13676358, then builds APKs for Hexagon V68, V69, V73, V75, V79 and V81.
The native runtime is cached by toolchain version, build script and operator config.
All compilation uses the detected CPU count.

APKs use Orchard's release signing key, which the app requires before loading
an add-on. The workflow uses the same repository secrets as Android and desktop releases:

- `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`
- `R2_ACCOUNT_ID`, `R2_BUCKET`, `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`

The R2 bucket must serve `https://depot.sfg545.dev` and permit writes under
`android/npu/`. Each APK is uploaded to a checksum-based URL. After all six uploads
succeed, the workflow publishes `android/npu/npu-addons.json`, the URL Orchard checks.
Existing APK objects are retained so an in-progress download remains valid.
The signed packages are also kept as the `android-npu-addons` workflow artifact for seven days.

Bump `VERSION_CODE` in `build.sh` when the runner, runtime, SDK or models change.
If the runner ABI changes, also bump `NpuAddon.MIN_VERSION_CODE` in the app.
Keep the workflow's pinned dependency versions and runtime cache key aligned with the build scripts.

To validate an existing package directory without uploading:

```sh
python3 scripts/release/publish_npu.py mobile/android/npu-addon/build --dry-run
python3 -m unittest discover -s scripts/release -p 'test_publish_npu.py'
```
