package io.mo.dtbooverclocker.core

import io.mo.dtbooverclocker.util.HashUtils
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.random.Random

class FlashPackageBuilderTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun image(name: String, size: Int, seed: Int) =
        tmp.newFile(name).apply { writeBytes(Random(seed).nextBytes(size)) }

    private fun unzip(zip: File): File {
        val dir = tmp.newFolder()
        ZipFile(zip).use { z ->
            z.entries().asSequence().forEach { e -> File(dir, e.name).apply { parentFile.mkdirs() }.writeBytes(z.getInputStream(e).readBytes()) }
        }
        return dir
    }

    private fun run(vararg command: String, dir: File, env: Map<String, String> = emptyMap(), stdin: String = ""): Pair<Int, String> {
        val process = ProcessBuilder(*command).directory(dir).redirectErrorStream(true)
            .apply { environment().putAll(env) }.start()
        process.outputStream.use { it.write(stdin.toByteArray()) }
        val output = process.inputStream.bufferedReader().readText()
        assertTrue("timeout: ${command.toList()}", process.waitFor(60, TimeUnit.SECONDS))
        return process.exitValue() to output
    }

    private fun shAvailable(): Boolean = runCatching {
        run("sh", "-c", "command -v unzip && command -v sha256sum && command -v head", dir = tmp.root).first == 0
    }.getOrDefault(false)

    private val isWindows = System.getProperty("os.name").startsWith("Windows")

    @Test fun recoveryZipHasExpectedLayout() {
        val img = image("p.img", 70_000, 1)
        val zip = FlashPackageBuilder.writeRecoveryZip(tmp.root.resolve("r.zip"), img, FlashPackageBuilder.PATCHED_IMAGE, "dtbo_b", "t", listOf("x"))
        ZipFile(zip).use { z ->
            val binary = z.getInputStream(z.getEntry(FlashPackageBuilder.UPDATE_BINARY)).bufferedReader().readText()
            assertTrue(binary.startsWith("#!/sbin/sh\n"))
            assertFalse("update-binary must use LF", binary.contains('\r'))
            assertTrue(binary.contains("PART=\"dtbo_b\""))
            assertTrue(binary.contains("EXPECTED_SHA256=\"${HashUtils.sha256(img)}\""))
            assertTrue(binary.contains("EXPECTED_SIZE=\"70000\""))
            assertTrue(binary.contains("[ -b \"\$d/\$PART\" ]"))
            assertArrayEquals(img.readBytes(), z.getInputStream(z.getEntry(FlashPackageBuilder.PATCHED_IMAGE)).readBytes())
            assertTrue(z.getEntry(FlashPackageBuilder.UPDATER_SCRIPT) != null)
        }
    }

    @Test fun rejectsNonDtboPartition() {
        val img = image("p.img", 100, 1)
        val failure = runCatching {
            FlashPackageBuilder.writeRecoveryZip(tmp.root.resolve("r.zip"), img, "x.img", "boot_a", "t", emptyList())
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    /** 用普通文件模拟块设备，真实执行 update-binary 验证解包、校验、写入与回读流程。 */
    private fun runUpdateBinary(zip: File, byName: File): Pair<Int, String> {
        val extracted = unzip(zip)
        val script = File(extracted, FlashPackageBuilder.UPDATE_BINARY).readText()
            .replace("SEARCH_DIRS=\"${FlashPackageBuilder.RECOVERY_SEARCH_DIRS}\"", "SEARCH_DIRS=\"/nonexistent ${byName.shPath()}\"")
            .replace("[ -b ", "[ -f ")
            // MSYS 的管道没有 /proc/self/fd，改为直接写到标准输出
            .replace("> /proc/self/fd/\$OUTFD", ">&1")
            .replace("TMP=\"/tmp/dtbo_flash.img\"", "TMP=\"${tmp.root.shPath()}/dtbo_flash.img\"")
        val scriptFile = File(extracted, "update-binary.sh").apply { writeText(script) }
        return run("sh", scriptFile.shPath(), "3", "1", zip.shPath(), dir = extracted)
    }

    @Test fun recoveryUpdateBinaryFlashesTargetAndVerifies() {
        assumeTrue("sh/unzip/sha256sum unavailable", shAvailable())
        val img = image("p.img", 200_000, 2)
        val zip = FlashPackageBuilder.writeRecoveryZip(tmp.root.resolve("r.zip"), img, FlashPackageBuilder.PATCHED_IMAGE, "dtbo_a", "t", emptyList())
        val byName = tmp.newFolder("by-name")
        val partition = File(byName, "dtbo_a").apply { writeBytes(ByteArray(300_000) { 0x5A }) }
        val other = File(byName, "dtbo_b").apply { writeBytes(ByteArray(300_000) { 0x11 }) }

        val (code, output) = runUpdateBinary(zip, byName)

        assertEquals(output, 0, code)
        assertTrue(output, output.contains("ui_print Readback verified"))
        val written = partition.readBytes()
        assertEquals("partition size must not change", 300_000, written.size)
        assertArrayEquals(img.readBytes(), written.copyOf(200_000))
        assertTrue("other slot untouched", other.readBytes().all { it == 0x11.toByte() })
    }

    @Test fun recoveryUpdateBinaryAbortsWhenPartitionMissing() {
        assumeTrue("sh/unzip/sha256sum unavailable", shAvailable())
        val img = image("p.img", 1000, 3)
        val zip = FlashPackageBuilder.writeRecoveryZip(tmp.root.resolve("r.zip"), img, FlashPackageBuilder.BACKUP_IMAGE, "dtbo_a", "t", emptyList())
        val byName = tmp.newFolder("by-name")

        val (code, output) = runUpdateBinary(zip, byName)

        assertEquals(output, 1, code)
        assertTrue(output, output.contains("Block device dtbo_a not found"))
        assertFalse("must not create a regular file in by-name", File(byName, "dtbo_a").exists())
    }

    @Test fun recoveryUpdateBinaryAbortsOnCorruptImage() {
        assumeTrue("sh/unzip/sha256sum unavailable", shAvailable())
        val img = image("p.img", 5000, 4)
        val zip = FlashPackageBuilder.writeRecoveryZip(tmp.root.resolve("r.zip"), img, FlashPackageBuilder.PATCHED_IMAGE, "dtbo_a", "t", emptyList())
        // 同尺寸不同内容：用篡改过的镜像重建 zip，但保留原 update-binary
        val binary = ZipFile(zip).use { z -> z.getInputStream(z.getEntry(FlashPackageBuilder.UPDATE_BINARY)).readBytes() }
        val tampered = tmp.root.resolve("tampered.zip")
        java.util.zip.ZipOutputStream(tampered.outputStream()).use { out ->
            out.putNextEntry(java.util.zip.ZipEntry(FlashPackageBuilder.UPDATE_BINARY)); out.write(binary); out.closeEntry()
            out.putNextEntry(java.util.zip.ZipEntry(FlashPackageBuilder.PATCHED_IMAGE)); out.write(Random(99).nextBytes(5000)); out.closeEntry()
        }
        val byName = tmp.newFolder("by-name")
        val partition = File(byName, "dtbo_a").apply { writeBytes(ByteArray(8000)) }

        val (code, output) = runUpdateBinary(tampered, byName)

        assertEquals(output, 1, code)
        assertTrue(output, output.contains("SHA-256 mismatch"))
        assertTrue("partition untouched", partition.readBytes().all { it == 0.toByte() })
    }

    private fun fastbootFixture(): Triple<File, File, File> {
        val patched = image("patched.img", 50_000, 5)
        val original = image("orig.img", 50_000, 6)
        val zip = FlashPackageBuilder.writeFastbootBundle(tmp.root.resolve("f.zip"), patched, original, "dtbo_b", "a")
        return Triple(zip, patched, original)
    }

    @Test fun fastbootBundleLayout() {
        val (zip, patched, original) = fastbootFixture()
        ZipFile(zip).use { z ->
            val names = z.entries().asSequence().map { it.name }.toSet()
            assertEquals(
                setOf("flash_patched.bat", "flash_patched.sh", "rollback.bat", "rollback.sh", "README.txt", "dtbo_patched.img", "dtbo_backup.img"),
                names
            )
            val bat = z.getInputStream(z.getEntry("flash_patched.bat")).bufferedReader().readText()
            assertFalse("bat must use CRLF", Regex("[^\r]\n").containsMatchIn(bat))
            assertTrue(bat.contains("cd /d \"%~dp0\""))
            assertTrue(bat.contains("set \"SHA=${HashUtils.sha256(patched)}\""))
            val sh = z.getInputStream(z.getEntry("rollback.sh")).bufferedReader().readText()
            assertFalse(sh.contains('\r'))
            assertTrue(sh.contains("SHA=\"${HashUtils.sha256(original)}\""))
            assertTrue(sh.contains("PART=\"dtbo_b\""))
        }
    }

    private fun fakeFastbootSh(dir: File, log: File) = File(dir, "fastboot").apply {
        writeText(
            "#!/bin/sh\n" +
                "if [ \"\$1\" = devices ]; then echo 'SERIAL123\tfastboot'; exit 0; fi\n" +
                "echo \"\$@\" >> '${log.shPath()}'\n" +
                "cp \"\$3\" '${log.parentFile.shPath()}/flashed_'\"\$2\".img\n"
        )
        setExecutable(true)
    }

    @Test fun fastbootShellScriptsFlashTheRightImage() {
        assumeTrue("sh unavailable", shAvailable())
        val (zip, patched, original) = fastbootFixture()
        val dir = unzip(zip)
        val log = tmp.root.resolve("fastboot.log")
        fakeFastbootSh(dir, log)

        val (code, output) = run("sh", File(dir, "flash_patched.sh").shPath(), dir = tmp.root)
        assertEquals(output, 0, code)
        assertEquals("flash dtbo_b dtbo_patched.img", log.readText().trim())
        assertArrayEquals(patched.readBytes(), tmp.root.resolve("flashed_dtbo_b.img").readBytes())

        val (rbCode, rbOutput) = run("sh", File(dir, "rollback.sh").shPath(), dir = tmp.root)
        assertEquals(rbOutput, 0, rbCode)
        assertArrayEquals(original.readBytes(), tmp.root.resolve("flashed_dtbo_b.img").readBytes())
    }

    @Test fun fastbootShellScriptRefusesCorruptImage() {
        assumeTrue("sh unavailable", shAvailable())
        val (zip) = fastbootFixture()
        val dir = unzip(zip)
        File(dir, "dtbo_patched.img").appendBytes(byteArrayOf(1))
        val log = tmp.root.resolve("fastboot.log")
        fakeFastbootSh(dir, log)

        val (code, output) = run("sh", File(dir, "flash_patched.sh").shPath(), dir = tmp.root)
        assertEquals(output, 1, code)
        assertTrue(output, output.contains("does not match"))
        assertFalse(log.exists())
    }

    private fun fakeFastbootBat(binDir: File, log: File) = File(binDir, "fastboot.bat").apply {
        writeText(
            "@echo off\r\n" +
                "if \"%1\"==\"devices\" (echo SERIAL123\tfastboot& exit /b 0)\r\n" +
                "echo %*>>\"${log.absolutePath}\"\r\n" +
                "copy /y \"%3\" \"${log.parentFile.absolutePath}\\flashed_%2.img\" >nul\r\n" +
                "exit /b 0\r\n"
        )
    }

    private fun runBat(bat: File, binDir: File): Pair<Int, String> {
        val path = binDir.absolutePath + File.pathSeparator + System.getenv("SystemRoot") + "\\System32"
        // 从 System32 之类的其他目录启动，确认脚本自己 cd 到包目录；stdin 喂回车跳过 pause
        return run("cmd", "/c", bat.absolutePath, dir = tmp.root, env = mapOf("PATH" to path), stdin = "\r\n")
    }

    @Test fun fastbootBatchScriptsFlashTheRightImage() {
        assumeTrue("Windows only", isWindows)
        val (zip, patched, original) = fastbootFixture()
        val dir = unzip(zip)
        val binDir = tmp.newFolder("bin")
        val log = tmp.root.resolve("fastboot.log")
        fakeFastbootBat(binDir, log)

        val (code, output) = runBat(File(dir, "flash_patched.bat"), binDir)
        assertEquals(output, 0, code)
        assertEquals("flash dtbo_b \"dtbo_patched.img\"", log.readText().trim())
        assertArrayEquals(patched.readBytes(), tmp.root.resolve("flashed_dtbo_b.img").readBytes())

        val (rbCode, rbOutput) = runBat(File(dir, "rollback.bat"), binDir)
        assertEquals(rbOutput, 0, rbCode)
        assertArrayEquals(original.readBytes(), tmp.root.resolve("flashed_dtbo_b.img").readBytes())
    }

    @Test fun fastbootBatchScriptRefusesCorruptImageAndMissingFastboot() {
        assumeTrue("Windows only", isWindows)
        val (zip) = fastbootFixture()
        val dir = unzip(zip)
        val binDir = tmp.newFolder("bin")
        val log = tmp.root.resolve("fastboot.log")

        val (noFbCode, noFbOutput) = runBat(File(dir, "flash_patched.bat"), binDir)
        assertEquals(noFbOutput, 1, noFbCode)
        assertTrue(noFbOutput, noFbOutput.contains("fastboot not found"))

        fakeFastbootBat(binDir, log)
        File(dir, "dtbo_patched.img").appendBytes(byteArrayOf(1))
        val (code, output) = runBat(File(dir, "flash_patched.bat"), binDir)
        assertEquals(output, 1, code)
        assertTrue(output, output.contains("does not match"))
        assertFalse(log.exists())
    }

    @Test fun moduleZipLayout() {
        val img = image("p.img", 4096, 7)
        val zip = FlashPackageBuilder.writeModuleZip(tmp.root.resolve("m.zip"), img, "144 Hz\nline2", 42)
        ZipFile(zip).use { z ->
            val names = z.entries().asSequence().map { it.name }.toSet()
            assertEquals(
                setOf(FlashPackageBuilder.UPDATE_BINARY, FlashPackageBuilder.UPDATER_SCRIPT, "module.prop", "customize.sh", "uninstall.sh", "dtbo_patched.img"),
                names
            )
            assertEquals("#MAGISK\n", z.getInputStream(z.getEntry(FlashPackageBuilder.UPDATER_SCRIPT)).bufferedReader().readText())
            val prop = z.getInputStream(z.getEntry("module.prop")).bufferedReader().readLines()
            assertTrue(prop.contains("id=${FlashPackageBuilder.MODULE_ID}"))
            assertTrue(prop.contains("versionCode=42"))
            assertTrue("prop values must be single line", prop.contains("version=144 Hz line2"))
            names.filter { it.endsWith(".sh") || it.endsWith("update-binary") || it == "module.prop" }.forEach {
                assertFalse("$it must use LF", z.getInputStream(z.getEntry(it)).bufferedReader().readText().contains('\r'))
            }
        }
    }

    /** 模拟设备：by-name 下用普通文件代替块设备，并把模块路径重定向到临时目录。 */
    private inner class ModuleDevice(slotSuffix: String) {
        val root = tmp.newFolder()
        val byName = File(root, "by-name").apply { mkdirs() }
        val installed = File(root, "modules/${FlashPackageBuilder.MODULE_ID}")
        val uninstallLog = File(root, "uninstall.log")
        private val prelude = "getprop() { [ \"\$1\" = ro.boot.slot_suffix ] && echo '$slotSuffix'; }\n"

        fun adapt(script: String) = script
            .replace(FlashPackageBuilder.RECOVERY_SEARCH_DIRS, "/nonexistent ${byName.shPath()}")
            .replace("[ -b ", "[ -f ")
            .replace(FlashPackageBuilder.MODULE_INSTALLED_DIR, installed.shPath())
            .replace(FlashPackageBuilder.MODULE_UNINSTALL_LOG, uninstallLog.shPath())

        /** 按 Magisk / KernelSU install_module 的顺序：先解压到 MODPATH，再 source customize.sh。 */
        fun install(zip: File): Pair<Int, String> {
            val modPath = File(root, "modules_update/${FlashPackageBuilder.MODULE_ID}").apply { deleteRecursively(); mkdirs() }
            ZipFile(zip).use { z ->
                z.entries().asSequence().filterNot { it.name.startsWith("META-INF/") }.forEach { e ->
                    File(modPath, e.name).writeBytes(z.getInputStream(e).readBytes())
                }
            }
            val customize = File(modPath, "customize.sh").apply { writeText(adapt(readText())) }
            val harness = File(root, "install.sh").apply {
                writeText(
                    prelude +
                        "ui_print() { echo \"\$1\"; }\nabort() { echo \"\$1\"; exit 1; }\n" +
                        "MODPATH='${modPath.shPath()}'\nZIPFILE='${zip.shPath()}'\n. '${customize.shPath()}'\n"
                )
            }
            val result = run("sh", harness.shPath(), dir = root)
            if (result.first == 0) {
                installed.deleteRecursively()
                modPath.copyRecursively(installed)
            }
            return result
        }

        fun uninstall(): Pair<Int, String> {
            val script = File(installed, "uninstall.sh").apply { writeText(prelude + adapt(readText())) }
            return run("sh", script.shPath(), dir = root)
        }
    }

    @Test fun moduleInstallWritesActiveSlotAndUninstallRestores() {
        assumeTrue("sh unavailable", shAvailable())
        val device = ModuleDevice("_b")
        val originalBytes = Random(10).nextBytes(64_000)
        val partition = File(device.byName, "dtbo_b").apply { writeBytes(originalBytes) }
        val other = File(device.byName, "dtbo_a").apply { writeBytes(ByteArray(64_000) { 7 }) }
        val patched = image("patched.img", 40_000, 11)

        val (code, output) = device.install(FlashPackageBuilder.writeModuleZip(tmp.root.resolve("m.zip"), patched, "x", 1))

        assertEquals(output, 0, code)
        assertTrue(output, output.contains("Readback verified"))
        assertArrayEquals(patched.readBytes(), partition.readBytes().copyOf(40_000))
        assertArrayEquals("tail of partition preserved", originalBytes.copyOfRange(40_000, 64_000), partition.readBytes().copyOfRange(40_000, 64_000))
        assertTrue("inactive slot untouched", other.readBytes().all { it == 7.toByte() })
        assertArrayEquals(originalBytes, File(device.installed, "dtbo_backup.img").readBytes())

        val (unCode, unOutput) = device.uninstall()
        assertEquals(unOutput, 0, unCode)
        assertArrayEquals(originalBytes, partition.readBytes())
        assertTrue(device.uninstallLog.readText().contains("restored"))
    }

    @Test fun moduleReinstallKeepsOriginalBackup() {
        assumeTrue("sh unavailable", shAvailable())
        val device = ModuleDevice("_a")
        val originalBytes = Random(12).nextBytes(64_000)
        val partition = File(device.byName, "dtbo_a").apply { writeBytes(originalBytes) }

        assertEquals(0, device.install(FlashPackageBuilder.writeModuleZip(tmp.root.resolve("m1.zip"), image("p1.img", 30_000, 13), "v1", 1)).first)
        val second = image("p2.img", 50_000, 14)
        val (code, output) = device.install(FlashPackageBuilder.writeModuleZip(tmp.root.resolve("m2.zip"), second, "v2", 2))

        assertEquals(output, 0, code)
        assertTrue(output, output.contains("Keeping original backup"))
        assertArrayEquals(second.readBytes(), partition.readBytes().copyOf(50_000))
        assertArrayEquals(originalBytes, File(device.installed, "dtbo_backup.img").readBytes())
    }

    @Test fun moduleUninstallLeavesPartitionAloneAfterOta() {
        assumeTrue("sh unavailable", shAvailable())
        val device = ModuleDevice("_a")
        val partition = File(device.byName, "dtbo_a").apply { writeBytes(Random(15).nextBytes(64_000)) }
        assertEquals(0, device.install(FlashPackageBuilder.writeModuleZip(tmp.root.resolve("m.zip"), image("p.img", 30_000, 16), "x", 1)).first)

        val otaBytes = Random(17).nextBytes(64_000)
        partition.writeBytes(otaBytes)
        assertEquals(0, device.uninstall().first)

        assertArrayEquals(otaBytes, partition.readBytes())
        assertTrue(device.uninstallLog.readText().contains("left untouched"))
    }

    @Test fun moduleInstallAbortsWithoutTouchingPartition() {
        assumeTrue("sh unavailable", shAvailable())
        val device = ModuleDevice("_b")
        val patched = image("p.img", 30_000, 18)
        val zip = FlashPackageBuilder.writeModuleZip(tmp.root.resolve("m.zip"), patched, "x", 1)

        val (missingCode, missingOutput) = device.install(zip)
        assertEquals(missingOutput, 1, missingCode)
        assertTrue(missingOutput, missingOutput.contains("dtbo_b not found"))
        assertFalse(File(device.byName, "dtbo_b").exists())

        val originalBytes = Random(19).nextBytes(64_000)
        val partition = File(device.byName, "dtbo_b").apply { writeBytes(originalBytes) }
        val corrupt = tmp.root.resolve("corrupt.zip")
        java.util.zip.ZipOutputStream(corrupt.outputStream()).use { out ->
            ZipFile(zip).use { z ->
                z.entries().asSequence().forEach { e ->
                    out.putNextEntry(java.util.zip.ZipEntry(e.name))
                    out.write(if (e.name == "dtbo_patched.img") Random(20).nextBytes(30_000) else z.getInputStream(e).readBytes())
                    out.closeEntry()
                }
            }
        }
        val (code, output) = device.install(corrupt)
        assertEquals(output, 1, code)
        assertTrue(output, output.contains("SHA-256 mismatch"))
        assertArrayEquals(originalBytes, partition.readBytes())
    }

    /** Git Bash / MSYS 下 sh 需要正斜杠路径。 */
    private fun File.shPath(): String = absolutePath.replace('\\', '/')
}
