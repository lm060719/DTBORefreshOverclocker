package io.mo.dtbooverclocker.core

import io.mo.dtbooverclocker.core.devicetree.DeviceTreeParser
import io.mo.dtbooverclocker.model.DscIssueSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DscTopologyAnalyzerTest
{
    @Test
    fun buildsCompleteQualcommDscTopology()
    {
        val text = """
            /dts-v1/;
            / {
                panel {
                    timing@0 {
                        qcom,mdss-dsi-panel-framerate = <120>;
                        qcom,mdss-dsi-panel-width = <1440>;
                        qcom,mdss-dsi-panel-height = <3200>;
                        qcom,compression-mode = "dsc";
                        qcom,mdss-dsc-version = <0x12>;
                        qcom,mdss-dsc-bit-per-component = <10>;
                        qcom,mdss-dsc-bit-per-pixel = <8>;
                        qcom,mdss-dsc-block-prediction-enable;
                        qcom,mdss-dsc-slice-per-pkt = <2>;
                        qcom,mdss-dsc-slice-width = <720>;
                        qcom,mdss-dsc-slice-height = <20>;
                        qcom,panel-roi-alignment = <1440 20 1440 20 1440 20>;
                    };
                };
            };
        """.trimIndent()

        val topologies = DscTopologyAnalyzer.analyze(
            listOf(DeviceTreeParser.parse(0, text))
        )

        assertEquals(1, topologies.size)
        val topology = topologies.single()
        assertEquals("1.2", topology.versionDisplay)
        assertEquals(120, topology.refreshHz)
        assertEquals(1440, topology.panelWidth)
        assertEquals(3200, topology.panelHeight)
        assertEquals(720, topology.sliceWidth)
        assertEquals(20, topology.sliceHeight)
        assertEquals(2, topology.horizontalSliceCount)
        assertEquals(160, topology.verticalSliceCount)
        assertEquals(320, topology.slicesPerFrame)
        assertEquals(2, topology.slicePerPacket)
        assertEquals(10, topology.bitsPerComponent)
        assertEquals(8, topology.bitsPerPixel)
        assertTrue(topology.blockPredictionEnabled)
        assertFalse(topology.hasErrors)
        assertTrue(topology.issues.any { it.severity == DscIssueSeverity.INFO })
    }

    @Test
    fun reportsBrokenSliceTopologyInsteadOfSilentlyAcceptingIt()
    {
        val text = """
            /dts-v1/;
            / {
                panel {
                    timing@0 {
                        qcom,mdss-dsi-panel-width = <1440>;
                        qcom,mdss-dsi-panel-height = <3200>;
                        qcom,compression-mode = "dsc";
                        qcom,mdss-dsc-slice-per-pkt = <3>;
                        qcom,mdss-dsc-slice-width = <700>;
                        qcom,mdss-dsc-slice-height = <20>;
                    };
                };
            };
        """.trimIndent()

        val topology = DscTopologyAnalyzer.analyze(
            listOf(DeviceTreeParser.parse(0, text))
        ).single()

        assertTrue(topology.hasErrors)
        assertTrue(topology.issues.any { it.message.contains("不能被 slice-width") })
    }
}
