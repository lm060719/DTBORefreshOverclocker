# ARM64 executable tool placement

Only **one** native executable is required:

- `libdtc.so` — an **Android ARM64 executable ELF** compatible with:
  - `dtc -I dtb -O dts`
  - `dtc -I dts -O dtb`

Despite the `.so` suffix, this file is executed with `ProcessBuilder`; it is not loaded with `System.loadLibrary()`.
The APK name is intentional so Android extracts it under `applicationInfo.nativeLibraryDir`, which avoids executing code copied into writable app storage on Android 10+.

Expected path:

```text
app/src/main/jniLibs/arm64-v8a/libdtc.so
```

A quick host-side check should report an AArch64 executable, for example:

```text
ELF 64-bit LSB pie executable, ARM aarch64 ...
```

Do **not** place an x86_64 desktop `dtc`, shell script, or Python script here.

`libmkdtbo.so` is no longer used. DTBO v0/v1/v2 header parsing, entry extraction, metadata-preserving rebuild, zlib/gzip handling, and LZ4 Frame handling are implemented in Kotlin by `DtboImageCodec.kt`.
