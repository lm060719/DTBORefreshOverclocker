**English** | [简体中文](README.md)

# DTBO Studio

An Android DTBO (Device Tree Blob Overlay) viewer, editor and rebuilder: import an image, detect display and charging device-tree nodes, stage edits, then verify and repackage it for export. Current version `1.1.5`.

## Features

| Module | Capability | Root flash |
| --- | --- | --- |
| Refresh rate | Edit, add or delete timing modes; Balanced / Pixel Clock only / Framerate only / Custom | ✅ |
| Charging | Edit recognised current, voltage and thermal-table parameters, see [Charging notes](docs/charging.md) | ✅ |
| Device tree | Browse, search, references; add/edit/delete properties, add/clone/rename/delete nodes | ✅ |

Every edit goes into a transaction queue, where it can be undone or packaged together with others. Export formats: DTBO image, Recovery flashable ZIP, PC Fastboot bundle, KernelSU / Magisk / APatch module (flashes the active slot on install; removing the module and rebooting restores the original DTBO; can also be installed from the app via "flash as module").

## Workflow

1. **Import**: pick a local `dtbo.img`, or extract the active slot's partition with Root.
2. **Detect**: timing modes and charging nodes are scanned and grouped by panel. When extracted with Root, the app reads `androidboot.dtbo_idx` and the kernel command line to mark the panel and DTB this device actually uses.
3. **Edit**: stage changes from the module pages or the device-tree page.
4. **Package**: modified DTBs are recompiled, undeclared properties are checked for changes, metadata is verified, then the image is rebuilt.
5. **Export / flash**: export for offline verification, or Root-flash (the original partition is backed up first).

## Tested images

| Device | Import & detection | Edit & export |
| --- | --- | --- |
| OnePlus (8 DTBs) | ✅ | ✅ |
| Realme (6 DTBs) | ✅ | ✅ |
| Meizu 21 (40 DTBs) | ✅ | ✅ |
| Xiaomi 15 Ultra (1 DTB) | ✅ | ✅ |

## Before you start

- **AVB**: packaging updates the DTBO hash automatically; for signed images the vbmeta authentication digest is updated too and the original signature data is kept.
- **Leftover partition data**: if a partition image without AVB has stale data after the DTBO (common on Realme), import shows a warning and packaging zero-fills that area.
- **Multiple DTBs**: the same panel usually exists in every DTB, but the bootloader loads only one. Edit the DTB this device actually uses; the panel list marks it as "本机生效" (active on this device).
- **Non-production panels**: Qualcomm reference and simulation panels show "非量产屏节点，改后不生效" (non-production panel node; changes have no effect).
- **Command-mode panels**: on some panels the refresh rate is set by commands sent to the display driver IC, and all modes share the same clock and porches. Changing only the timing usually does not raise the real refresh rate; confirm with a frame-rate tool after flashing.
- **Modes without clockrate**: the Qualcomm driver derives the link clock from the timing, so no clock property is written for these modes.

## Risks

Modifying a DTBO can cause a black screen, display faults or a device that won't boot. Before flashing, make sure the bootloader is unlocked, the original DTBO is backed up, and a Fastboot or Recovery recovery path is ready.

## Build

Requires JDK 17.

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

On Windows use `.\gradlew.bat`. `app/src/main/jniLibs/arm64-v8a/libdtc.so` in the APK is an ARM64 DTC executable used to convert between DTS and DTB.

Pushes to `chatgpt/**` branches run the unit tests in GitHub Actions and upload a Debug APK.

### Real-image tests (optional)

Firmware images are not included in the repository. Put samples in per-device folders and provide a `dtc` that runs on your machine:

```bash
./gradlew testDebugUnitTest --tests "*DeviceWorkflowVerificationTest*" \
  -PworkflowSampleDir=<sample dir> -PhostDtc=<dtc path> [-PtestMaxHeap=256m]
```

`-PtestMaxHeap=256m` reproduces Android's default per-app heap limit.

## License

[GNU General Public License v3.0](LICENSE)
