**English** | [简体中文](README.md)

# DTBO Studio

> Active development branch: `chatgpt/device-tree-core-phase1`  
> App version: `1.1.5`

**DTBO Studio** is an Android DTBO (Device Tree Blob Overlay) analysis, editing, validation, and rebuild tool.

This branch has evolved beyond a refresh-rate overclocker. Its current architecture is:

```text
DTBO image
  ↓
DTBO Codec / DTC
  ↓
Structured Device Tree Core
  ├─ Capability Scanner
  ├─ Reference Index
  ├─ Refresh-rate Planner
  ├─ Resolution Planner
  ├─ DSC Topology Analyzer
  └─ Generic node/property editor
  ↓
DeviceTreeChange
  ↓
DeviceTreeTransaction
  ↓
Replay / verification / integrity checks
  ↓
DTBO rebuild / export / controlled flashing
```

The app currently exposes four main tabs:

- **Overview** — image import / Root extraction, transaction queue, packaging and deployment.
- **Modules** — refresh rate, resolution, DSC, Charging and capability scanning.
- **Device Tree** — hierarchical browsing, search, references and generic editing.
- **Settings** — environment, logs, cache, backups and advanced tools.

---

## Current feature status

| Module | Status | Direct Root flash |
| --- | --- | --- |
| Refresh rate | ✅ Writable | ✅ Subject to safety policy |
| Resolution | ✅ Aspect-ratio-preserving downscale | ❌ Export-only |
| DSC | ✅ Topology analysis | ✅ Parameter editing (export only) |
| Generic Device Tree editor | ✅ Node/property editing | ❌ Export-only |
| Capability Scanner | ✅ Automatic scan | N/A |
| Reference index | ✅ label/path/local/external fixups | N/A |
| Brightness / HBM | 🔎 Detection only | Not writable yet |
| Thermal | 🔎 Detection only | Not writable yet |
| Charging | ✅ Parameter detection and validation | ✅ Parameter editing (export only) |
| Touch | 🔎 Detection only | Not writable yet |

A capability being detected does **not** mean that DTBO Studio considers it safe to modify.

---

## Device Tree Core

The structured Device Tree layer supports:

- typed properties: Boolean, String, String List, U32, U64, Cells, Byte Array, Phandle and Unknown;
- add / edit / delete property operations;
- typed value editing and raw DTS inspection;
- add / clone / rename / delete node operations;
- clone guards for labels, `phandle`, and `linux,phandle`;
- reversible `DeviceTreeChange` operations.

The Device Tree UI provides hierarchical browsing, search scopes, node details and staged-operation inspection.

---

## Reference index

The reference index understands:

- `&label`
- `&{/absolute/path}`
- `/__symbols__`
- `/__local_fixups__`
- `/__fixups__`
- explicit `phandle / linux,phandle`

It exposes outgoing references, incoming references, unresolved internal references, and external fixups.

Label and path references are parsed with a handwritten scanner rather than Java/Android regex, avoiding the Android `PatternSyntaxException` difference that previously degraded reference indexing.

---

## Refresh-rate module

The production mutation path is:

```text
TimingParameterCalculator
  ↓
TimingDeviceTreePlanner
  ↓
DeviceTreeChange[]
  ↓
DeviceTreeEditor replay
  ↓
verifyReplay
  ↓
DeviceTreeTransaction
```

Supported modes:

- overwrite an existing timing;
- append a new sibling timing;
- delete an existing timing.

Supported strategies:

- Balanced Blanking Time;
- Pixel Clock Only;
- Framerate Only;
- Custom.

The planner validates node-set changes, template isolation, target properties, `native-mode` remapping, and MDP transfer-time handling when present.

`DtsTimingPatcher.analyzeEntry()` is still used for timing-candidate discovery. The old `DtsTimingPatcher.patch()` path is retained only as a regression oracle and is not the normal production writer.

---

## Resolution module

The current resolution planner intentionally supports only **aspect-ratio-preserving downscaling**.

It can update proven coupled fields such as:

- panel width;
- panel height;
- DSC slice width;
- recognized full-width ROI alignment.

It rejects cases where the topology cannot be proven safe, including unsupported aspect-ratio changes, upscaling, invalid DSC divisibility, and unknown ROI layouts.

Resolution transactions are marked `EXPORT_ONLY` and cannot be flashed directly through the app.

---

## DSC Parameter Editor and Topology Analyzer

The DSC module edits version, BPC, BPP, slice width/height, slices per packet, and block prediction on the selected node. Changes are staged as one transaction for undo, packaging, and export.

Staging validates positive dimensions, parameter ranges, panel divisibility, and slices-per-packet divisibility. Missing version and BPC/BPP properties may remain blank. DSC edits are export-only; topology checks do not establish device compatibility.

It extracts and checks:

- DSC version;
- SCR version raw value;
- bits per component;
- bits per pixel;
- block prediction;
- slice width / height;
- slice-per-packet;
- horizontal / vertical slice counts;
- slices per frame;
- ROI alignment.

Full PPS, RC-range, and vendor DSI-command rewriting is not enabled.

---

## Charging editor

Select a DTB entry and charging node, edit recognized current/voltage limits and recharge thresholds, preview changes, and stage one undoable transaction. Existing Qualcomm charger/battery-profile and standard battery parameters are supported, together with SMB5 HVDCP/USB PD disable flags. Inputs in mA/mV preserve µA/µV precision; validation rejects invalid values, conflicting limits and stale snapshots.

Xiaomi MCA buck, quick, wireless and reverse charging configurations are also supported. Wired/wireless thermal matrices are grouped by channel, with descending limits checked within each channel. Large forms use groups and pages while preserving drafts; charge-pump ratio arrays are edited independently of thermal ordering.

Anonymous QTI battery charger overlays are resolved through external fixups. Recognized thermal-current tables support per-level editing in mA, preserving array length and validating descending order. Changes use the shared packaging and export workflow, with direct Root flashing disabled. Unknown vendor properties, other thermal/JEITA tables and references are preserved. See [supported bindings and scope](docs/charging.md).

## Capability Scanner

After a DTBO workspace is loaded, a background scan classifies:

- Refresh Rate
- Resolution
- DSC
- Brightness / HBM
- Thermal
- Charging
- Touch

A missing capability in the current DTBO does not mean the device lacks it; configuration may live in the base DTB, `vendor_boot`, `vendor_dlkm`, another overlay, or driver code.

---

## Unified DeviceTreeTransaction layer

Refresh-rate, resolution, and generic Device Tree edits now share one logical transaction model.

Each `DeviceTreeTransaction` contains:

- kind;
- summary;
- underlying `DeviceTreeChange[]`;
- risk level;
- `directFlashAllowed`;
- warnings;
- affected DTB entries.

Transaction kinds:

- `REFRESH_RATE`
- `RESOLUTION`
- `GENERIC_EDIT`

Risk levels:

- `TRUSTED`
- `CAUTION`
- `EXPORT_ONLY`

The Overview tab shows transaction and operation counts and can atomically undo the most recent transaction by replaying inverse operations in reverse order.

---

## DTBO codec and rebuild

`DtboImageCodec` is a pure Kotlin/JVM DTBO table parser and rebuilder.

Supported table versions:

- v0
- v1
- v2

Supported entry compression:

- none
- zlib
- gzip
- LZ4 Frame

The builder preserves entry metadata and untouched payloads where possible, recalculates required offsets/sizes, then parses and validates the rebuilt image again.

Python and `mkdtboimg` are not required.

DTS/DTB compilation still uses the bundled ARM64 DTC executable:

```text
app/src/main/jniLibs/arm64-v8a/libdtc.so
```

Despite the extension, it is used as an AArch64 ELF executable rather than a normal JNI shared library.

---

## Root and safety boundaries

The app supports:

- SAF local-image import;
- Root extraction from the active DTBO partition;
- active-panel detection;
- backup and hash verification;
- Recovery rescue/deployment ZIPs;
- PC Fastboot bundles;
- controlled direct Root flashing.

Direct Root flashing is permitted only when every staged transaction has `directFlashAllowed == true`.

Resolution and generic Device Tree transactions are currently export-only.

---

## Source layout

```text
app/src/main/java/io/mo/dtbooverclocker/
├── core/
│   ├── ActivePanelDetector.kt
│   ├── CapabilityScanner.kt
│   ├── DscTopologyAnalyzer.kt
│   ├── DtboImageCodec.kt
│   ├── DtboPatchEngine.kt
│   ├── DtsTimingPatcher.kt
│   ├── ResolutionPlanner.kt
│   ├── TimingDeviceTreePlanner.kt
│   ├── TimingParameterCalculator.kt
│   ├── BackupManager.kt
│   ├── SafetyGuardManager.kt
│   └── devicetree/
│       ├── DeviceTreeModels.kt
│       ├── DeviceTreeParser.kt
│       ├── DeviceTreeEditor.kt
│       ├── DeviceTreeChange.kt
│       ├── DeviceTreeDiff.kt
│       ├── DeviceTreeReferenceIndex.kt
│       ├── DeviceTreeTransaction.kt
│       ├── DeviceTreeValueCodec.kt
│       └── DtsNumericValueCodec.kt
├── model/
├── ui/
└── util/
```

---

## Build configuration

| Item | Version |
| --- | --- |
| App | 1.1.5 |
| Kotlin | 2.4.20 |
| Android Gradle Plugin | 9.3.0 |
| Gradle Wrapper | 9.6.1 |
| JDK | 17 |
| compileSdk | 37 |
| targetSdk | 37 |
| minSdk | 26 |
| Compose BOM | 2026.08.00 |

Debug build:

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

Windows:

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

---

## GitHub Actions artifacts

Workflow:

`DTBO Studio UI validation`

For pushes to `chatgpt/**`, the workflow runs unit tests, assembles the debug APK, packages the source tree, and uploads artifacts.

Find artifacts at:

```text
Repository
  → Actions
  → DTBO Studio UI validation
  → choose a run
  → Artifacts
```

Artifacts:

- `DTBOStudio_Debug_APK` — contains `app-debug.apk`, retained for 14 days by the workflow.
- `DTBOStudio_Source` — source archive for that run.

---

## Known boundaries

1. Timing-candidate discovery still relies on `DtsTimingPatcher.analyzeEntry()`.
2. DSC supports core parameter editing for export; PPS, RC ranges, and vendor commands are not synchronized automatically.
3. Resolution editing is limited to topology-validated proportional downscaling.
4. Charging supports recognized parameter editing for export; Thermal / Touch / HBM are detection-only.
5. Generic Device Tree edits and resolution transactions are not eligible for direct Root flashing.
6. Successful compile/rebuild validation does not replace real-device boot and stability testing.

---

## License

Released under the [GNU General Public License v3.0](LICENSE).
