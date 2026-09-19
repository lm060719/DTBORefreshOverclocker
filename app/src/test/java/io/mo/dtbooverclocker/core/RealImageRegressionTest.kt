package io.mo.dtbooverclocker.core

import io.mo.dtbooverclocker.model.PatchMode
import io.mo.dtbooverclocker.model.PatchStrategy
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/** Opt in with -PsampleDir=<cs directory> -PhostDtc=<host dtc executable>.
 * Uses the user's original and independently boot-tested image, not generated expected values.
 * No firmware blobs or host executables are bundled with the repository/application.
 */
class RealImageRegressionTest {
    @Test fun originalTo144HzMatchesEveryPropertyOfBootTestedImage() {
        val sampleDir = System.getProperty("dtbo.sampleDir", "") ?: ""
        val dtc = System.getProperty("dtbo.dtc", "") ?: ""
        assumeTrue("Local samples and host DTC were not configured", sampleDir.isNotBlank() && dtc.isNotBlank())
        val root = File(sampleDir)
        val original = DtboImageCodec.parse(File(root, "dtbo_b.img"))
        val working = DtboImageCodec.parse(File(root, "dtbo_b_144hz_scaled.img"))
        val work = File(root, "regression").apply { mkdirs() }
        val input = File(work, "original.dtb").apply { writeBytes(original.entries.single().decodedBytes) }
        val dts = File(work, "patched.dts")
        fun runDtc(vararg args: String) {
            val log = File(work, "dtc.log")
            val process = ProcessBuilder(listOf(dtc) + args).redirectErrorStream(true).redirectOutput(log).start()
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                fail("DTC timed out")
            }
            assertEquals(log.readText().takeLast(2000), 0, process.exitValue())
        }
        runDtc("-I", "dtb", "-O", "dts", "-o", dts.absolutePath, input.absolutePath)
        dts.writeText(DtsSanitizer.sanitize(dts.readText()))
        val template = DtsTimingPatcher.analyzeEntry(0, dts).single {
            it.nodePath.contains("qcom,mdss_dsi_o1_42_02_0a_dsc_cmd/") &&
                it.nodePath.endsWith("timing@wqhd_normal_120hz_index_01")
        }
        val patch = DtsTimingPatcher.patch(template, 144, PatchStrategy.BALANCED_BLANKING_TIME, PatchMode.APPEND_NEW)
        dts.writeText(DtsSanitizer.sanitize(patch.text))
        val replacement = File(work, "patched.dtb")
        runDtc("-I", "dts", "-O", "dtb", "-o", replacement.absolutePath, dts.absolutePath)
        val expected = FdtReader.readAllProperties(working.entries.single().decodedBytes)
        val actual = FdtReader.readAllProperties(replacement.readBytes())
        val differences = (expected.keys + actual.keys).filter { key ->
            expected[key]?.contentEquals(actual[key] ?: byteArrayOf()) != true || key !in actual
        }
        assertTrue("Properties differ from boot-tested image: ${differences.take(20)}", differences.isEmpty())
        assertEquals(14691, actual.size)
        val output = File(work, "dtbo_fixed.img")
        DtboImageCodec.rebuild(original, mapOf(0 to replacement.readBytes()), output)
        val parsed = DtboImageCodec.parse(output)
        assertTrue(DtboImageCodec.metadataEquivalent(original.metadata, parsed.metadata))
        assertEquals(working.originalBytes!!.size.toLong(), output.length())
        assertArrayEquals(replacement.readBytes(), parsed.entries.single().decodedBytes)
        AvbImageEnvelope.validate(output.readBytes(), parsed.metadata.totalSize)
        val b = ByteBuffer.wrap(output.readBytes())
        assertEquals(0x41564266, b.getInt(18874304))
        assertEquals(parsed.metadata.totalSize.toLong(), b.getLong(18874304 + 12))
        println("PASS: all ${actual.size} properties match boot-tested 144 Hz image; AVB verified; output=$output")
    }

    @Test fun avbRebuildWithKnownWorkingPayloadReproducesEntireKnownWorkingImage() {
        val sampleDir = System.getProperty("dtbo.sampleDir", "") ?: ""
        assumeTrue("Local samples were not configured", sampleDir.isNotBlank())
        val original = DtboImageCodec.parse(File(sampleDir, "dtbo_b.img"))
        val workingBytes = File(sampleDir, "dtbo_b_144hz_scaled.img").readBytes()
        val working = DtboImageCodec.parse(workingBytes)
        val out = File.createTempFile("golden_avb", ".img")
        try {
            DtboImageCodec.rebuild(original, mapOf(0 to working.entries.single().decodedBytes), out)
            assertArrayEquals("Entire envelope, digest, padding and footer must match", workingBytes, out.readBytes())
        } finally { out.delete() }
    }
}
