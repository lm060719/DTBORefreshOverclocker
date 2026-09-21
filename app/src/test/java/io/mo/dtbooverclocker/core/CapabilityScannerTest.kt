package io.mo.dtbooverclocker.core

import io.mo.dtbooverclocker.model.CapabilityKind
import io.mo.dtbooverclocker.model.CapabilityStatus
import io.mo.dtbooverclocker.model.DtboBinaryImage
import io.mo.dtbooverclocker.model.DtboMetadata
import io.mo.dtbooverclocker.model.DtboWorkspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CapabilityScannerTest
{
    @Test
    fun classifiesDisplayAndHardwareRelatedCapabilities()
    {
        val root = createTempDir(prefix = "capability_scanner_")
        val dts = File(root, "entry_0.dts").apply {
            writeText(
                """
                /dts-v1/;
                / {
                    panel {
                        qcom,mdss-dsi-hbm-enabled;
                        timing@0 {
                            qcom,mdss-dsi-panel-framerate = <120>;
                            qcom,mdss-dsi-panel-width = <1440>;
                            qcom,mdss-dsi-panel-height = <3200>;
                            qcom,compression-mode = "dsc";
                            qcom,mdss-dsc-version = <0x12>;
                            qcom,mdss-dsc-bit-per-component = <10>;
                            qcom,mdss-dsc-bit-per-pixel = <8>;
                            qcom,mdss-dsc-slice-per-pkt = <2>;
                            qcom,mdss-dsc-slice-width = <720>;
                            qcom,mdss-dsc-slice-height = <20>;
                        };
                    };
                    thermal-zones {
                        skin-thermal { polling-delay = <1000>; };
                    };
                    touchpanel@0 {
                        compatible = "goodix,gt9916";
                    };
                };
                """.trimIndent()
            )
        }
        val candidates = DtsTimingPatcher.analyzeEntry(0, dts)
        val metadata = DtboMetadata(
            magicHex = "0xd7b7ab1e",
            totalSize = 0,
            headerSize = 32,
            entrySize = 32,
            entriesOffset = 32,
            pageSize = 4096,
            version = 0,
            entries = emptyList()
        )
        val workspace = DtboWorkspace(
            rootDir = root,
            inputImage = File(root, "dtbo.img").apply { writeBytes(ByteArray(0)) },
            metadataFile = File(root, "metadata.json").apply { writeText("{}") },
            metadata = metadata,
            binaryImage = DtboBinaryImage(
                metadata = metadata,
                prefixTemplate = ByteArray(0),
                entries = emptyList()
            ),
            extractedEntries = emptyList(),
            dtsFiles = listOf(dts),
            candidates = candidates
        )

        val report = CapabilityScanner.scan(workspace)

        assertEquals(1, report.scannedEntryCount)
        assertEquals(CapabilityStatus.AVAILABLE, report.finding(CapabilityKind.REFRESH_RATE)?.status)
        assertEquals(CapabilityStatus.AVAILABLE, report.finding(CapabilityKind.RESOLUTION)?.status)
        assertEquals(CapabilityStatus.ANALYSIS_ONLY, report.finding(CapabilityKind.DSC)?.status)
        assertEquals(CapabilityStatus.DETECTED, report.finding(CapabilityKind.BRIGHTNESS_HBM)?.status)
        assertEquals(CapabilityStatus.DETECTED, report.finding(CapabilityKind.THERMAL)?.status)
        assertEquals(CapabilityStatus.DETECTED, report.finding(CapabilityKind.TOUCH)?.status)
        assertEquals(CapabilityStatus.NOT_FOUND, report.finding(CapabilityKind.CHARGING)?.status)
        assertEquals(1, report.dscTopologies.size)
        assertTrue(report.nodeCount >= 5)
    }
}
