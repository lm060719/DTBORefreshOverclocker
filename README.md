# DTBO Refresh Overclocker

Android app implementing a dual-mode DTBO refresh-rate patch workflow:

- **No-root core engine**: SAF import -> private cache -> pure-Kotlin DTBO parse -> DTB/DTS -> timing patch -> DTB -> pure-Kotlin metadata-preserving DTBO rebuild -> SAF export.
- **Root adapter**: detect active slot, extract only the active DTBO partition, require backup + SHA-256 + rescue package before writing only that same partition.
- **Offline deployment exports**: save the patched image, generate a single-slot patched Recovery ZIP, or generate a PC Fastboot bundle. The Root direct-flash rescue ZIP is a separate artifact containing the verified original backup.

## Build stack

- Kotlin 2.4.20
- Jetpack Compose + Material 3
- Compose BOM 2026.08.00
- Android Gradle Plugin 9.4.0
- Gradle 9.6.0
- compileSdk / targetSdk 37
- minSdk 26
- JDK 17

## Source layout

```text
app/src/main/java/io/mo/dtbooverclocker/
├── core/
│   ├── NativeToolExecutor.kt
│   ├── RootDetector.kt
│   ├── SlotDetector.kt
│   ├── DtboImageCodec.kt
│   ├── DtsTimingPatcher.kt
│   ├── DtboPatchEngine.kt
│   └── SafetyGuardManager.kt
├── model/Models.kt
├── ui/MainActivity.kt
├── ui/MainViewModel.kt
└── util/HashUtils.kt
```

`SafetyGuardManager` intentionally contains two different ZIP generators: a patched-image Recovery flashing package for offline/no-root workflows, and an original-image Rescue package that is generated before an in-app physical write.

## Required native tool: only DTC

The app no longer needs `mkdtboimg` or a Python runtime. Provide one legally built Android ARM64 executable:

```text
app/src/main/jniLibs/arm64-v8a/libdtc.so
```

It must be an **AArch64 executable ELF**, even though it uses a `.so` filename. See `app/src/main/jniLibs/arm64-v8a/README.md`.

The app uses legacy JNI packaging / native-library extraction so the executable is installed under `applicationInfo.nativeLibraryDir`. It never copies executable code into `cacheDir`.

## Pure-Kotlin DTBO codec

`DtboImageCodec.kt` replaces the former `libmkdtbo.so` dependency. It implements:

- DTBO magic/header validation
- table versions **0, 1 and 2**
- v0/v1 32-byte entries and v2 64-byte entries
- preservation of `id`, `rev`, `flags`, custom fields, page size, version, entry-table layout, and metadata prefix
- recalculation only of fields that must change (`total_size`, `dt_size`, `dt_offset`)
- uncompressed entries
- zlib entries
- gzip entries
- LZ4 Frame entries, including decoding normal compressed blocks and rebuilding a standards-compliant LZ4 frame
- preservation of untouched entry payload bytes without unnecessary recompression
- post-build reparsing before a patched image can enter the flashing path

The binary layout follows AOSP `system/libufdt/utils/src/mkdtboimg.py`, including the 2026 DTBO v2 64-byte entry format.

## Safety behavior

Direct flashing is deliberately stricter than offline export:

1. It is enabled only for a workspace extracted from the current device's active DTBO partition.
2. The UI enforces a 5-second countdown and semantic confirmation (`target Hz` or uppercase `FLASH`).
3. The current block device is read in full before any write.
4. The backup is SHA-256 verified after internal persistence and again after external persistence.
5. A Recovery rescue ZIP is generated and externally persisted before the write starts.
6. Only `/dev/block/by-name/dtbo`, `dtbo_a`, or `dtbo_b` is accepted by the block-device allowlist.
7. The opposite A/B slot is never written.
8. The patched image is read back from the physical partition and SHA-256 checked; a mismatch triggers an immediate attempt to restore the original backup.
9. `FRAMERATE_ONLY` patches are export-only and cannot use the in-app direct-flash path.

## Timing strategies

### Balanced blanking time (default)

Uses the relationship `refresh ∝ clock / VTotal`. It scales VFP/VBP and clock together while leaving horizontal timing and VSync unchanged. The calculation uses the original clock as a ratio anchor, so it does not assume that every vendor property named `panel-clockrate` is literally a raw pixel clock.

### Pixel clock only

Keeps porch values unchanged and scales the recognized clock property by `targetHz/currentHz`.

### Framerate only

Changes only the framerate property. This is available for offline experimentation but blocked from direct flashing.

If `qcom,mdss-dsi-panel-timings` is present as an opaque PHY byte array, the app detects it but intentionally does not apply a generic byte-wise scaling rule; those bytes are SoC/panel specific.

## DTBO metadata verification

After rebuild the app reparses the generated image with its independent read path and rejects it unless these values still match the source image:

- magic
- header size
- entry size
- entry-table offset
- page size
- DTBO version
- entry count/order
- per-entry id
- rev
- flags / compression format
- all custom fields

`dt_size`, `dt_offset`, and `total_size` are expected to change when the patched DTB or its compressed representation changes.
The target entry is then decompressed again, passed back through `dtc`, and the requested refresh property must be found before the image is accepted.

## Gradle wrapper note

`gradle/wrapper/gradle-wrapper.properties` is included. If your source package does not contain `gradle-wrapper.jar`, run once with a locally installed Gradle:

```bash
gradle wrapper --gradle-version 9.6.0
```

Then use `./gradlew assembleDebug` as usual.
