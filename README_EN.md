**English** | [简体中文](README.md)

# DTBO Refresh Overclocker

Version 1.1.1 preserves partition padding and embedded AVB footers, recalculates unsigned
AVB hash descriptors, and rejects signed or unsupported layouts instead of discarding them.
Command modes with an MDP transfer budget now scale that budget inversely with refresh rate;
the balanced strategy keeps their porches unchanged. Vendor dynamic/idle modes cannot be used
as generic overclock templates: select a normal mode on the same panel. New modes are appended
without shifting existing mode order. Packaging verifies AVB, decoded entry bytes and timing parameters.

For the supplied `o1_42_02_0a_dsc_cmd` sample, select `timing@wqhd_normal_120hz_index_01`
from the original image, append a 144 Hz mode using the balanced strategy. The expected clock
is 1632000000, VFP/VBP are 16/24, and MDP transfer time is 6083 microseconds.
These settings are specific to this sample, not a recommendation for other panels.

Optional real-image regression: run `gradlew.bat testDebugUnitTest assembleDebug
"-PsampleDir=E:/path/to/cs" "-PhostDtc=E:/path/to/dtc.exe"` with `dtbo_b.img` and
`dtbo_b_144hz_scaled.img` in that directory. It compares all DT properties and tests
byte-for-byte envelope reconstruction. Firmware and host tools are not bundled; device boot
testing is still required.

**DTBO Refresh Overclocker** is an advanced display refresh rate overclocking and timing tuning tool tailored for Android devices. By accurately deconstructing, recalculating, and safely rebuilding the Device Tree Blob Overlay (DTBO) partition, it allows users to unlock higher refresh rates and optimize display timings beyond factory limitations.

The application features a dual-mode architecture supporting both **No-Root (SAF offline processing)** and **Root (direct active slot flashing)** workflows. It is powered by a proprietary pure-Kotlin DTBO codec, multi-tier active panel auto-detection, continuous staging with batch rebuild, and an enterprise-grade Triple Safety Guard system.

---

## 🌟 Key Features

### 1. Pure Kotlin DTBO Codec (Zero Python / No mkdtboimg Required)
- **Full Specification Support**: Fully supports AOSP DTBO table versions **v0, v1, and the latest 2026 v2 specification** (including the v2 64-byte entry header layout).
- **Lossless Metadata Preservation**: Faithfully preserves original image metadata including `magic`, `header_size`, `entry_size`, `page_size`, `version`, `flags`, entry order, entry IDs, hardware revision (`rev`), and all custom fields.
- **Comprehensive Compression Support**: Pure Kotlin decoding and assembly for uncompressed entries, zlib entries, gzip entries, and **LZ4 Frame** entries. Untouched entries retain their exact binary payload without recompression loss.
- **Post-Build Decompile Verification**: After rebuilding, images undergo an independent decoding and secondary decompile cycle via DTC to guarantee that the generated DTB/DTS properties precisely match targets before any export or flashing.

### 2. Active Display Panel Auto-Detection (Active Panel Detector)
- **Multi-Tier Hardware Recognition Algorithm**:
  1. **Tier 1 - Kernel Boot Arguments**: Inspects `/proc/cmdline` for `msm_drm.dsi_display` and `androidboot.panel` parameters.
  2. **Tier 2 - Xiaomi / Redmi Display Drivers**: Queries `/sys/class/mi_display/disp-DSI-0/panel_info` and corresponding sysfs nodes.
  3. **Tier 3 - Other Vendor Nodes**: Inspects OPPO/OnePlus `oplus_display/panel_name`, touch drivers (`touchpanel/panel_name`), and LCD display driver sysfs nodes.
- **Smart Candidate Highlighting**: When a DTBO contains multiple panel timings (e.g. multi-sourced panels across device revisions), it automatically identifies which physical panel is actively driving the current display and highlights the matched candidate nodes in the UI.

### 3. Flexible Timing Patch Modes
- **Overwrite Existing**: Directly replaces the selected timing mode (e.g., 60Hz or 120Hz) with the target refresh rate (e.g., 144Hz).
- **Append New Mode**: Completely preserves all factory timing modes, cloning the selected mode as a base template to insert a new sibling timing node. Features intelligent node name generation (detecting `@<num>`, `-<num>`, `_<num>`, or `_${hz}hz` patterns) for non-destructive overclocking.
- **Delete Mode**: Removes unwanted or problematic refresh rate modes. Includes safeguards against deleting the last remaining mode of a panel and automatically redirects `native-mode` references in the device tree to prevent bootloops and blank screens.

### 4. Scientific Timing Calculation Strategies
- **Balanced Blanking Time (Recommended / Default)**:
  - Governed by the physical display timing relationship: $\text{Refresh} \propto \frac{\text{Pixel Clock}}{\text{VTotal}}$.
  - Simultaneously scales the Pixel Clock and vertical porches (VFP/VBP) to preserve constant blanking duration while leaving horizontal timings and vertical sync pulse width (`vsync-len`) unchanged.
  - Enforces clock scaling ratio safety boundaries ($0.50\times \sim 3.00\times$) for superior stability and color fidelity.
- **Pixel Clock Only**:
  - Keeps porches unchanged and scales the recognized pixel clock property proportionally by $\frac{\text{Target Hz}}{\text{Current Hz}}$.
- **Framerate Only**:
  - Alters only the `panel-framerate` property without adjusting clocks. Due to high variation across display drivers, this mode is **restricted to offline export and prohibited from direct in-app flashing**.
- **Custom Timing**:
  - Designed for experienced tuners: allows manual fine-tuning of Pixel Clock (Hz/MHz), Vertical Front Porch (VFP), Vertical Back Porch (VBP), Horizontal Front Porch (HFP), and Horizontal Back Porch (HBP).

### 5. Real-Time Timing Preview & Geometry Diagram
- **Timing Geometry Chart**: Renders a clear visual representation of the active display area, front porch, back porch, and sync pulse ratios.
- **Overclock Preview Card**: Offers side-by-side comparison of Pixel Clock, H-Total, V-Total, refresh rate, and estimated MIPI DSI bandwidth before applying changes.

### 6. Continuous Staging & Batch Rebuild Workflow
- Apply multiple modifications, additions, or deletions across different panels and refresh rates into a **Staged Changes list**.
- Reset workspace back to its original clean state at any time.
- Clicking **Package Staged** triggers a batch compilation of all modified DTB entries and a single-pass rebuild of the final DTBO image via the pure-Kotlin builder.

### 7. Timeline Backup & One-Click Safe Rollback
- **Dual Backup Modes**:
  - **Automated Backup**: Automatically extracts and registers a complete partition backup before any physical flash.
  - **Manual Backup**: Allows users to take on-demand snapshots of the active DTBO partition with custom descriptions.
- **Multi-Dimensional Metadata**: Tracks timestamp, Android release, system build ID, device model, active slot, block device path, file size, recorded MD5, and SHA-256 hashes.
- **One-Click MD5 Verification**: Rapidly verify local backup file integrity against recorded checksums to detect file corruption or tampering.
- **One-Click Physical Rollback**: Restore any historical backup directly back to the physical partition, backed by a 5-second countdown and post-flash read-back validation.
- **Export & Management**: Export backup images to the public `Download` folder or a custom location, and delete individual backups.

### 8. Triple Safety Guard System
For direct physical flashing, the app enforces an industry-leading three-tier defense mechanism:
1. **Tier 1: Mandatory Physical Backup with Dual-Hash Verification**:
   - Reads original partition data using `dd`.
   - Persists the backup internally and externally to public storage, verifying SHA-256 and MD5 hashes at each step.
2. **Tier 2: Pre-Generated Offline Recovery Rescue ZIP**:
   - Packages the verified original backup into a standard Recovery-flashable Rescue ZIP before any partition write. Even if the device cannot boot into the OS, users can flash the rescue ZIP via TWRP/OrangeFox.
3. **Tier 3: Strict Whitelisting, Single-Slot Isolation & Auto-Rollback**:
   - **Strict Allowlist**: Only `/dev/block/by-name/dtbo`, `dtbo_a`, or `dtbo_b` can be targeted.
   - **Single-Slot Isolation**: Operates strictly on the active A/B slot; never touches the opposite slot.
   - **Semantic Confirmation**: Enforces a 5-second countdown and requires typing a confirmation token (such as target Hz or `FLASH`).
   - **Post-Flash Read-Back Verification**: Reads back partition data immediately after writing and checks SHA-256. If a mismatch is detected, the app immediately attempts to restore the original backup.

### 9. Multi-Format Deployment Exports
- **Patched DTBO Image (`.img`)**: For manual Fastboot flashing or image archiving.
- **Recovery Flashable ZIP (`.zip`)**: Ready-to-flash package with cross-platform installer scripts and safety checks for custom recoveries.
- **PC Fastboot Bundle (`.zip`)**: Contains the patched image, Windows batch script (`flash-windows.bat`), Linux shell script (`flash-linux.sh`), and step-by-step instructions.
- **Recovery Rescue ZIP (`.zip`)**: Offline rescue package containing the pristine factory backup.

### 10. Real-Time Terminal Echo & Diagnostics
- **Live Terminal Echo**: Streams detailed outputs from DTC compilation, codec operations, partition writing, and hash checks.
- **One-Click Copy & Log Export**: Easily copy console logs, export diagnostic log files, or clear log caches.

---

## 🏗️ Source Code Layout

```text
app/src/main/java/io/mo/dtbooverclocker/
├── core/
│   ├── ActivePanelDetector.kt       # Multi-tier hardware screen identification & matching
│   ├── BackupManager.kt             # Timeline backup manager, JSON manifest, MD5 verify & rollback
│   ├── DtboImageCodec.kt            # Pure Kotlin DTBO v0/v1/v2 codec & compression handler
│   ├── DtboPatchEngine.kt           # Workspace manager, DTS analysis, staged changes & batch rebuild
│   ├── DtsTimingPatcher.kt          # DTS syntax tree analysis, timing strategies, add/delete nodes
│   ├── NativeToolExecutor.kt        # JNI native DTC executable execution wrapper
│   ├── RootDetector.kt              # Root access & shell executor
│   ├── SafetyGuardManager.kt        # Triple safety guard, rescue/fastboot bundle creation & direct flash
│   └── SlotDetector.kt              # A/B slot & DTBO block device path detector
├── model/
│   ├── BackupModels.kt              # Backup record & verification state models
│   └── Models.kt                    # Timing candidate, workspace, patch report & config models
├── ui/
│   ├── MainActivity.kt              # Main UI, staged changes workflow & navigation
│   ├── MainViewModel.kt             # Core state machine & coroutine dispatcher
│   ├── RollbackScreen.kt            # Timeline backup list, MD5 verification & rollback UI
│   ├── SettingsScreen.kt            # Environment diagnostics, storage clean, logs & safety settings
│   ├── AboutScreen.kt               # App information, repository links & open-source notices
│   └── components/
│       ├── DisclaimerDialog.kt      # Disclaimer & high-risk warning dialog
│       ├── OverclockPreviewCard.kt  # Pre/post overclock timing electrical parameter comparison
│       ├── TimingCandidateSelector.kt # Multi-dimensional candidate selector with active panel highlighting
│       ├── TimingGeometryChart.kt   # Screen timing blanking geometry structure chart
│       └── TimingUtils.kt           # Timing node naming & panel identifier utilities
└── util/
    ├── AppLogger.kt                 # Application ring log buffer & file persistence
    ├── HashUtils.kt                 # MD5 & SHA-256 cryptographic utility
    └── StorageUtils.kt              # Storage size formatting & filesystem utilities
```

---

## ⚙️ Native Tool Requirements

The app requires no Python runtime or `mkdtboimg`. Only **one** properly built Android ARM64 native executable is needed:

```text
app/src/main/jniLibs/arm64-v8a/libdtc.so
```

> **Note**:
> - Despite the `.so` extension, this file is an **AArch64 ELF executable binary** (not a shared library loaded via `System.loadLibrary()`).
> - The `.so` naming enables Android's native packaging mechanism to extract it into the read-only directory `applicationInfo.nativeLibraryDir`.
> - The application **never** copies executable binaries into writable storage such as `cacheDir`, complying fully with Android 10+ W^X (Write XOR Execute) security policies.
> - See [arm64-v8a/README.md](app/src/main/jniLibs/arm64-v8a/README.md) for details.

---

## 🛠️ Build Stack & Tech Specs

- **Programming Language**: Kotlin 2.4.20
- **UI Framework**: Jetpack Compose + Material 3 (Compose BOM 2026.08.00)
- **Build System**: Android Gradle Plugin 9.4.0 / Gradle 9.6.0
- **SDK Targets**:
  - `compileSdk`: 37
  - `targetSdk`: 37
  - `minSdk`: 26 (Android 8.0+)
- **JDK Requirement**: JDK 17
- **Target Architecture**: `arm64-v8a`

### Build Commands

```bash
# Setup Gradle Wrapper if required
gradle wrapper --gradle-version 9.6.0

# Build Debug APK
./gradlew assembleDebug

# Build Release APK
./gradlew assembleRelease
```

---

## 📖 Usage Guide

### Method 1: No-Root Mode (Safe Offline Tuning)
1. Extract `dtbo.img` from your device's official ROM, recovery update package, or third-party backup.
2. Open the app and select **SAF Local Image Import** under "Image Source".
3. The app parses the DTBO using the pure Kotlin codec and decompiles DTBs to DTS via DTC.
4. Browse the candidate timing nodes, selecting the panel and baseline mode.
5. Set your target refresh rate, choose a timing strategy (recommended: "Balanced Blanking Time"), and review the preview card and geometry chart.
6. Click **Stage Timing Change** (repeat for additional modes if desired).
7. Click **Package Staged** to verify metadata and compile all modified entries.
8. Under Deployment Options, choose your export format:
   - Export `.img` for PC `fastboot flash dtbo dtbo_patched.img`.
   - Export Recovery Flashable ZIP for flashing in custom recovery.
   - Export PC Fastboot Bundle to flash from your computer.

### Method 2: Root Mode (One-Click Extract & Direct Flash)
1. Ensure the device has Root access (KernelSU / APatch / Magisk).
2. Select **Read from Physical DTBO Partition** under "Image Source" (the app automatically detects the active A/B slot).
3. The Active Panel Detector highlights the current physical panel.
4. Configure overclock parameters, stage changes, and click Package Staged.
5. Under Deployment Options, select **Direct Flash to Physical Partition**:
   - A **5-second safety countdown** will begin.
   - A physical partition backup is taken and verified via local/external SHA-256 checksums.
   - An offline Recovery rescue ZIP is pre-generated.
   - Enter the semantic confirmation token to initiate flashing.
   - Post-flash readback verification is executed immediately.
6. Reboot to enjoy your new high refresh rate.

### Method 3: Backup & Emergency Rollback
- Access the rollback timeline via the **clock icon in the top app bar** or through **Settings -> Backup & Rollback Management**.
- Inspect historical backups with device details, timestamps, and checksums.
- Click "Verify MD5" to confirm file integrity.
- Click "Rollback to this Backup", confirm the action, and the image will be safely flashed back with write-back verification.

---

## ⚠️ Disclaimer & Risk Warning

1. **Hardware Stress & Risks**: Modifying DTBO refresh rates constitutes hardware overclocking. Display panels, driver ICs, and ribbon cables may suffer from elevated electrical and thermal stress, potentially leading to ghosting, image retention, burn-in, color degradation, or permanent hardware failure.
2. **Blank Screen & Bootloop Risks**: Applying incompatible timings or editing the wrong panel's default mode can cause the display to remain black upon boot or trigger a bootloop during display driver initialization.
3. **User Responsibility**: Users assume full responsibility for any risks incurred and should possess the skills to recover their device via PC Fastboot or custom recovery. The author and contributors accept no liability for any data loss, bricked devices, or hardware damage resulting from the use of this tool.

---

## 📄 License

This project is licensed under the **[GNU General Public License v3.0 (GPLv3)](LICENSE)**.
