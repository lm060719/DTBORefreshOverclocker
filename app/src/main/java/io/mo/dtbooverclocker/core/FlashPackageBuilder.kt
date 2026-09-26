package io.mo.dtbooverclocker.core

import io.mo.dtbooverclocker.util.HashUtils
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 生成 Recovery 刷机 Zip 与 PC Fastboot 一键包，不依赖 Android Context，便于在 JVM 上直接校验产物。
 *
 * 脚本在执行写入前都会校验包内镜像的大小与 SHA-256，只写入一个明确的 DTBO 分区。
 */
object FlashPackageBuilder {
    const val PATCHED_IMAGE = "dtbo_patched.img"
    const val BACKUP_IMAGE = "dtbo_backup.img"
    const val UPDATE_BINARY = "META-INF/com/google/android/update-binary"
    const val UPDATER_SCRIPT = "META-INF/com/google/android/updater-script"

    /** Recovery 下 by-name 目录因设备与 Recovery 实现而异，按顺序查找第一个真实存在的块设备。 */
    const val RECOVERY_SEARCH_DIRS =
        "/dev/block/by-name /dev/block/bootdevice/by-name /dev/block/platform/*/by-name /dev/block/platform/*/*/by-name"

    private val partitionPattern = Regex("^dtbo(?:_[ab])?$")

    fun writeRecoveryZip(
        output: File,
        image: File,
        imageEntryName: String,
        partition: String,
        title: String,
        readme: List<String>
    ): File {
        requirePartition(partition)
        require(image.isFile && image.length() >= 32) { "DTBO 镜像无效：${image.absolutePath}" }
        val sha256 = HashUtils.sha256(image)

        output.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(output)).use { zip ->
            zip.putTextEntry(
                UPDATE_BINARY,
                recoveryUpdateBinary(title, partition, imageEntryName, image.length(), sha256)
            )
            zip.putTextEntry(UPDATER_SCRIPT, "#DTBO Refresh Overclocker: update-binary handles flashing\n")
            zip.putTextEntry(
                "README.txt",
                (readme + listOf(
                    "Target partition: $partition",
                    "Image: $imageEntryName (${image.length()} bytes)",
                    "SHA-256: $sha256",
                    "Requires a custom recovery (TWRP / OrangeFox etc.) that runs shell update-binary ZIPs.",
                    "Stock recovery rejects unsigned ZIPs."
                )).joinToString("\n", postfix = "\n")
            )
            zip.putFileEntry(imageEntryName, image)
        }
        require(output.isFile && output.length() > 0L) { "Recovery Zip 生成失败" }
        return output
    }

    fun writeFastbootBundle(
        output: File,
        patchedImage: File,
        originalImage: File?,
        partition: String,
        oppositeSlot: String?
    ): File {
        requirePartition(partition)
        require(patchedImage.isFile && patchedImage.length() >= 32) { "修补镜像无效" }
        originalImage?.let { require(it.isFile && it.length() >= 32) { "原始镜像无效：${it.absolutePath}" } }

        val patchedSha = HashUtils.sha256(patchedImage)
        val backupSha = originalImage?.let(HashUtils::sha256)

        output.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(output)).use { zip ->
            zip.putTextEntry("flash_patched.bat", fastbootBat("flashing patched DTBO", partition, PATCHED_IMAGE, patchedSha))
            zip.putTextEntry("flash_patched.sh", fastbootSh("flashing patched DTBO", partition, PATCHED_IMAGE, patchedSha))
            zip.putFileEntry(PATCHED_IMAGE, patchedImage)
            if (originalImage != null && backupSha != null) {
                zip.putTextEntry("rollback.bat", fastbootBat("restoring original DTBO", partition, BACKUP_IMAGE, backupSha))
                zip.putTextEntry("rollback.sh", fastbootSh("restoring original DTBO", partition, BACKUP_IMAGE, backupSha))
                zip.putFileEntry(BACKUP_IMAGE, originalImage)
            }
            zip.putTextEntry("README.txt", buildString {
                appendLine("DTBO Refresh Overclocker - PC Fastboot bundle")
                appendLine("Target partition: $partition (this bundle never flashes the other slot)")
                appendLine("$PATCHED_IMAGE SHA-256: $patchedSha")
                backupSha?.let { appendLine("$BACKUP_IMAGE SHA-256: $it (the image this workspace was loaded from)") }
                appendLine()
                appendLine("1. Extract the whole zip into one folder.")
                appendLine("2. Reboot the phone into bootloader (adb reboot bootloader) and connect it.")
                appendLine("3. Windows: double-click flash_patched.bat.  Linux/macOS: sh flash_patched.sh")
                appendLine("   fastboot must be in PATH, or put fastboot(.exe) into this folder.")
                appendLine("4. Check the output, then run: fastboot reboot")
                if (backupSha != null) appendLine("Rollback: rollback.bat / sh rollback.sh")
                oppositeSlot?.let { appendLine("Emergency: boot the other slot with fastboot --set-active=$it") }
                appendLine("The bootloader must be unlocked; a modified DTBO no longer matches the vbmeta hash.")
            }.replace("\n", "\r\n"))
        }
        require(output.isFile && output.length() > 0L) { "Fastboot 一键包生成失败" }
        return output
    }

    fun recoveryUpdateBinary(
        title: String,
        partition: String,
        imageEntryName: String,
        imageSize: Long,
        sha256: String
    ): String = """
        |#!/sbin/sh
        |# DTBO Refresh Overclocker - $title
        |OUTFD="${'$'}2"
        |ZIPFILE="${'$'}3"
        |PART="$partition"
        |IMG="$imageEntryName"
        |EXPECTED_SIZE="$imageSize"
        |EXPECTED_SHA256="$sha256"
        |SEARCH_DIRS="$RECOVERY_SEARCH_DIRS"
        |TMP="/tmp/dtbo_flash.img"
        |
        |ui_print() {
        |  echo "ui_print ${'$'}1" > /proc/self/fd/${'$'}OUTFD
        |  echo "ui_print" > /proc/self/fd/${'$'}OUTFD
        |}
        |abort() {
        |  ui_print "! ${'$'}1"
        |  rm -f "${'$'}TMP" "${'$'}TMP.rb"
        |  exit 1
        |}
        |sha256_of() {
        |  sha256sum "${'$'}1" 2>/dev/null | cut -d' ' -f1
        |}
        |
        |ui_print "DTBO Refresh Overclocker"
        |ui_print "$title"
        |
        |TARGET=""
        |for d in ${'$'}SEARCH_DIRS; do
        |  if [ -b "${'$'}d/${'$'}PART" ]; then TARGET="${'$'}d/${'$'}PART"; break; fi
        |done
        |[ -n "${'$'}TARGET" ] || abort "Block device ${'$'}PART not found"
        |ui_print "Target: ${'$'}TARGET"
        |
        |rm -f "${'$'}TMP" "${'$'}TMP.rb"
        |unzip -p "${'$'}ZIPFILE" "${'$'}IMG" > "${'$'}TMP" || abort "Failed to extract ${'$'}IMG"
        |SIZE=${'$'}(wc -c < "${'$'}TMP" | tr -d ' ')
        |[ "${'$'}SIZE" = "${'$'}EXPECTED_SIZE" ] || abort "Size mismatch: ${'$'}SIZE != ${'$'}EXPECTED_SIZE"
        |
        |HAVE_SHA=0
        |command -v sha256sum >/dev/null 2>&1 && HAVE_SHA=1
        |if [ "${'$'}HAVE_SHA" = 1 ]; then
        |  [ "${'$'}(sha256_of "${'$'}TMP")" = "${'$'}EXPECTED_SHA256" ] || abort "SHA-256 mismatch, zip is corrupted"
        |else
        |  ui_print "sha256sum unavailable, hash check skipped"
        |fi
        |
        |if command -v blockdev >/dev/null 2>&1; then
        |  PSIZE=${'$'}(blockdev --getsize64 "${'$'}TARGET" 2>/dev/null)
        |  if [ -n "${'$'}PSIZE" ] && [ "${'$'}SIZE" -gt "${'$'}PSIZE" ]; then
        |    abort "Image (${'$'}SIZE) larger than partition (${'$'}PSIZE)"
        |  fi
        |fi
        |
        |ui_print "Writing ${'$'}SIZE bytes..."
        |dd if="${'$'}TMP" of="${'$'}TARGET" bs=4M conv=notrunc,fsync 2>/dev/null || dd if="${'$'}TMP" of="${'$'}TARGET" bs=4M || abort "Write failed"
        |sync
        |
        |if [ "${'$'}HAVE_SHA" = 1 ] && head -c "${'$'}SIZE" "${'$'}TARGET" > "${'$'}TMP.rb" 2>/dev/null && [ "${'$'}(wc -c < "${'$'}TMP.rb" | tr -d ' ')" = "${'$'}SIZE" ]; then
        |  [ "${'$'}(sha256_of "${'$'}TMP.rb")" = "${'$'}EXPECTED_SHA256" ] || abort "Readback mismatch! Restore your backup before rebooting"
        |  ui_print "Readback verified"
        |else
        |  ui_print "Readback check skipped"
        |fi
        |rm -f "${'$'}TMP" "${'$'}TMP.rb"
        |ui_print "Done. SHA-256: ${'$'}EXPECTED_SHA256"
        |exit 0
        |""".trimMargin()

    fun fastbootBat(action: String, partition: String, image: String, sha256: String): String = """
        |@echo off
        |setlocal
        |cd /d "%~dp0"
        |set "PART=$partition"
        |set "IMG=$image"
        |set "SHA=$sha256"
        |echo DTBO Refresh Overclocker - $action to %PART% ONLY
        |if not exist "%IMG%" goto noimage
        |set "FB=fastboot"
        |if exist "%~dp0fastboot.exe" set "FB=%~dp0fastboot.exe"
        |if not "%FB%"=="fastboot" goto havefb
        |where fastboot >nul 2>nul || goto nofastboot
        |:havefb
        |certutil -hashfile "%IMG%" SHA256 | findstr /i /x /c:"%SHA%" >nul || goto badhash
        |"%FB%" devices | findstr /i /c:"fastboot" >nul || goto nodevice
        |call "%FB%" flash %PART% "%IMG%"
        |if errorlevel 1 goto fail
        |echo Flash complete. Check the output above, then run: fastboot reboot
        |pause
        |exit /b 0
        |:noimage
        |echo [ERROR] %IMG% not found. Extract the whole zip into one folder first.
        |goto fail
        |:nofastboot
        |echo [ERROR] fastboot not found. Add platform-tools to PATH or copy fastboot.exe here.
        |goto fail
        |:badhash
        |echo [ERROR] SHA-256 of %IMG% does not match, the file is corrupted.
        |goto fail
        |:nodevice
        |echo [ERROR] No device in fastboot mode. Run: adb reboot bootloader
        |goto fail
        |:fail
        |echo Flash aborted. DO NOT flash the opposite slot.
        |pause
        |exit /b 1
        |""".trimMargin().replace("\n", "\r\n")

    fun fastbootSh(action: String, partition: String, image: String, sha256: String): String = """
        |#!/bin/sh
        |set -eu
        |cd "${'$'}(dirname "${'$'}0")"
        |PART="$partition"
        |IMG="$image"
        |SHA="$sha256"
        |fail() { echo "[ERROR] ${'$'}1" >&2; echo "Flash aborted. DO NOT flash the opposite slot." >&2; exit 1; }
        |echo "DTBO Refresh Overclocker - $action to ${'$'}PART ONLY"
        |[ -f "${'$'}IMG" ] || fail "${'$'}IMG not found. Extract the whole zip into one folder first."
        |if [ -x ./fastboot ]; then FB=./fastboot
        |elif command -v fastboot >/dev/null 2>&1; then FB=fastboot
        |else fail "fastboot not found. Install platform-tools or copy fastboot here."
        |fi
        |if command -v sha256sum >/dev/null 2>&1; then ACTUAL=${'$'}(sha256sum "${'$'}IMG" | cut -d' ' -f1)
        |elif command -v shasum >/dev/null 2>&1; then ACTUAL=${'$'}(shasum -a 256 "${'$'}IMG" | cut -d' ' -f1)
        |else fail "sha256sum/shasum not found, cannot verify ${'$'}IMG"
        |fi
        |[ "${'$'}ACTUAL" = "${'$'}SHA" ] || fail "SHA-256 of ${'$'}IMG does not match, the file is corrupted."
        |"${'$'}FB" devices | grep -qi fastboot || fail "No device in fastboot mode. Run: adb reboot bootloader"
        |"${'$'}FB" flash "${'$'}PART" "${'$'}IMG" || fail "fastboot flash failed"
        |echo "Flash complete. Check the output above, then run: fastboot reboot"
        |""".trimMargin()

    /**
     * KernelSU / Magisk / APatch 通用模块。DTBO 由 bootloader 直接读取，模块的 overlay 机制覆盖不到，
     * 因此由 customize.sh 在安装时写入当前活跃槽位，并把原分区备份进模块目录；
     * uninstall.sh 在模块被移除后、确认分区仍是本模块写入的内容时，把备份写回。
     */
    fun writeModuleZip(
        output: File,
        patchedImage: File,
        summary: String,
        versionCode: Int
    ): File {
        require(patchedImage.isFile && patchedImage.length() >= 32) { "修补镜像无效" }
        val sha256 = HashUtils.sha256(patchedImage)
        val oneLine = summary.replace(Regex("\\s+"), " ").trim().take(120)

        output.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(output)).use { zip ->
            zip.putTextEntry(UPDATE_BINARY, MAGISK_MODULE_INSTALLER)
            zip.putTextEntry(UPDATER_SCRIPT, "#MAGISK\n")
            zip.putTextEntry(
                "module.prop",
                """
                |id=$MODULE_ID
                |name=DTBO Refresh Overclocker
                |version=${oneLine.ifBlank { "patched DTBO" }}
                |versionCode=$versionCode
                |author=DTBO Refresh Overclocker
                |description=安装时把修补后的 DTBO（SHA-256 ${sha256.take(12)}…）写入当前槽位，重启后生效。移除模块并重启会自动写回原 DTBO，再重启一次恢复生效。
                |""".trimMargin()
            )
            zip.putTextEntry("customize.sh", moduleCustomizeScript(patchedImage.length(), sha256))
            zip.putTextEntry("uninstall.sh", moduleUninstallScript())
            zip.putFileEntry(PATCHED_IMAGE, patchedImage)
        }
        require(output.isFile && output.length() > 0L) { "模块 Zip 生成失败" }
        return output
    }

    const val MODULE_ID = "dtbo_refresh_overclocker"
    const val MODULE_STATE = "dtbo_state"
    const val MODULE_INSTALLED_DIR = "/data/adb/modules/$MODULE_ID"
    const val MODULE_UNINSTALL_LOG = "/data/adb/dtbo_refresh_overclocker_uninstall.log"

    /** 模块与卸载脚本共用的块设备查找逻辑：只认活跃槽位对应的 dtbo 分区。 */
    private val moduleFindTarget = """
        |SUFFIX=${'$'}(getprop ro.boot.slot_suffix)
        |if [ -z "${'$'}SUFFIX" ]; then
        |  case "${'$'}(getprop ro.boot.slot)" in a) SUFFIX=_a ;; b) SUFFIX=_b ;; esac
        |fi
        |find_target() {
        |  for d in $RECOVERY_SEARCH_DIRS; do
        |    if [ -b "${'$'}d/${'$'}1" ]; then echo "${'$'}d/${'$'}1"; return 0; fi
        |  done
        |  return 1
        |}
        |sha256_of() { sha256sum "${'$'}1" 2>/dev/null | cut -d' ' -f1; }
        |head_sha256() {
        |  head -c "${'$'}2" "${'$'}1" 2>/dev/null | sha256sum 2>/dev/null | cut -d' ' -f1
        |}
        |dd_write() {
        |  dd if="${'$'}1" of="${'$'}2" bs=4M conv=notrunc,fsync 2>/dev/null ||
        |    dd if="${'$'}1" of="${'$'}2" bs=4M conv=notrunc 2>/dev/null ||
        |    dd if="${'$'}1" of="${'$'}2" bs=4M 2>/dev/null
        |}
        |""".trimMargin()

    fun moduleCustomizeScript(imageSize: Long, sha256: String): String = """
        |# DTBO Refresh Overclocker - sourced by the Magisk / KernelSU / APatch module installer
        |IMG_SIZE="$imageSize"
        |IMG_SHA256="$sha256"
        |OLD_MODDIR="$MODULE_INSTALLED_DIR"
        |$moduleFindTarget
        |PART="dtbo${'$'}SUFFIX"
        |TARGET=${'$'}(find_target "${'$'}PART") || abort "! Block device ${'$'}PART not found"
        |ui_print "- Target: ${'$'}TARGET"
        |
        |SRC="${'$'}MODPATH/$PATCHED_IMAGE"
        |[ -f "${'$'}SRC" ] || unzip -o "${'$'}ZIPFILE" "$PATCHED_IMAGE" -d "${'$'}MODPATH" >&2 || abort "! Failed to extract $PATCHED_IMAGE"
        |[ "${'$'}(wc -c < "${'$'}SRC" | tr -d ' ')" = "${'$'}IMG_SIZE" ] || abort "! Image size mismatch, zip is corrupted"
        |command -v sha256sum >/dev/null 2>&1 || abort "! sha256sum not available"
        |[ "${'$'}(sha256_of "${'$'}SRC")" = "${'$'}IMG_SHA256" ] || abort "! SHA-256 mismatch, zip is corrupted"
        |
        |PSIZE=${'$'}(blockdev --getsize64 "${'$'}TARGET" 2>/dev/null)
        |if [ -n "${'$'}PSIZE" ] && [ "${'$'}IMG_SIZE" -gt "${'$'}PSIZE" ]; then
        |  abort "! Image (${'$'}IMG_SIZE) larger than partition (${'$'}PSIZE)"
        |fi
        |
        |# 重装 / 升级时，如果分区当前仍是旧模块写入的内容，沿用旧模块保存的原厂备份，避免把修补镜像当成原厂备份
        |BACKUP="${'$'}MODPATH/$BACKUP_IMAGE"
        |OLD_PART=""; OLD_SIZE=""; OLD_SHA=""
        |[ -f "${'$'}OLD_MODDIR/$MODULE_STATE" ] && . "${'$'}OLD_MODDIR/$MODULE_STATE"
        |if [ "${'$'}OLD_PART" = "${'$'}PART" ] && [ -f "${'$'}OLD_MODDIR/$BACKUP_IMAGE" ] &&
        |   [ -n "${'$'}OLD_SIZE" ] && [ "${'$'}(head_sha256 "${'$'}TARGET" "${'$'}OLD_SIZE")" = "${'$'}OLD_SHA" ]; then
        |  cp -f "${'$'}OLD_MODDIR/$BACKUP_IMAGE" "${'$'}BACKUP" || abort "! Failed to keep previous backup"
        |  ui_print "- Keeping original backup from previous install"
        |else
        |  dd if="${'$'}TARGET" of="${'$'}BACKUP" bs=4M 2>/dev/null || abort "! Failed to back up ${'$'}PART"
        |  ui_print "- Backed up ${'$'}PART to module folder"
        |fi
        |[ -s "${'$'}BACKUP" ] || abort "! Backup is empty"
        |
        |ui_print "- Writing ${'$'}IMG_SIZE bytes to ${'$'}PART"
        |dd_write "${'$'}SRC" "${'$'}TARGET" || abort "! Write failed, partition unchanged or partially written. Restore $BACKUP_IMAGE before rebooting"
        |sync
        |if [ "${'$'}(head_sha256 "${'$'}TARGET" "${'$'}IMG_SIZE")" != "${'$'}IMG_SHA256" ]; then
        |  ui_print "! Readback mismatch, restoring original DTBO"
        |  dd_write "${'$'}BACKUP" "${'$'}TARGET" && sync
        |  abort "! Install failed, original DTBO restored"
        |fi
        |{
        |  echo "OLD_PART=${'$'}PART"
        |  echo "OLD_SIZE=${'$'}IMG_SIZE"
        |  echo "OLD_SHA=${'$'}IMG_SHA256"
        |} > "${'$'}MODPATH/$MODULE_STATE"
        |ui_print "- Readback verified, reboot to apply"
        |ui_print "- Remove this module and reboot to restore the original DTBO"
        |""".trimMargin()

    fun moduleUninstallScript(): String = """
        |#!/system/bin/sh
        |# DTBO Refresh Overclocker - runs after the module is removed; restores the original DTBO
        |MODDIR=${'$'}{0%/*}
        |LOG="$MODULE_UNINSTALL_LOG"
        |$moduleFindTarget
        |OLD_PART=""; OLD_SIZE=""; OLD_SHA=""
        |[ -f "${'$'}MODDIR/$MODULE_STATE" ] && . "${'$'}MODDIR/$MODULE_STATE"
        |[ -n "${'$'}OLD_PART" ] && [ -s "${'$'}MODDIR/$BACKUP_IMAGE" ] || { echo "no state or backup, skipped" > "${'$'}LOG"; exit 0; }
        |TARGET=${'$'}(find_target "${'$'}OLD_PART") || { echo "${'$'}OLD_PART not found" > "${'$'}LOG"; exit 0; }
        |# 分区已被 OTA 或其他方式改写时不动它
        |if [ "${'$'}(head_sha256 "${'$'}TARGET" "${'$'}OLD_SIZE")" != "${'$'}OLD_SHA" ]; then
        |  echo "${'$'}TARGET no longer holds the patched image, left untouched" > "${'$'}LOG"
        |  exit 0
        |fi
        |if dd_write "${'$'}MODDIR/$BACKUP_IMAGE" "${'$'}TARGET"; then
        |  sync
        |  echo "restored ${'$'}TARGET from backup" > "${'$'}LOG"
        |else
        |  echo "restore of ${'$'}TARGET FAILED" > "${'$'}LOG"
        |fi
        |""".trimMargin()

    /** Magisk 官方 module_installer.sh；KernelSU / APatch 使用各自的安装器，会忽略此文件。 */
    private val MAGISK_MODULE_INSTALLER = """
        |#!/sbin/sh
        |umask 022
        |ui_print() { echo "${'$'}1"; }
        |require_new_magisk() {
        |  ui_print "*******************************"
        |  ui_print " Please install Magisk v20.4+! "
        |  ui_print "*******************************"
        |  exit 1
        |}
        |OUTFD=${'$'}2
        |ZIPFILE=${'$'}3
        |mount /data 2>/dev/null
        |[ -f /data/adb/magisk/util_functions.sh ] || require_new_magisk
        |. /data/adb/magisk/util_functions.sh
        |[ ${'$'}MAGISK_VER_CODE -lt 20400 ] && require_new_magisk
        |install_module
        |exit 0
        |""".trimMargin()

    private fun requirePartition(partition: String) {
        require(partitionPattern.matches(partition)) { "拒绝打包非 DTBO 分区：$partition" }
    }

    private fun ZipOutputStream.putTextEntry(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun ZipOutputStream.putFileEntry(name: String, file: File) {
        putNextEntry(ZipEntry(name))
        file.inputStream().use { it.copyTo(this) }
        closeEntry()
    }
}
