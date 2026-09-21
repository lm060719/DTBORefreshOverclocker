package io.mo.dtbooverclocker.core

import io.mo.dtbooverclocker.core.devicetree.*
import io.mo.dtbooverclocker.model.DscParameters
import io.mo.dtbooverclocker.model.FeatureModuleKind
import io.mo.dtbooverclocker.ui.MainUiState
import org.junit.Assert.*
import org.junit.Test

class DscPlannerTest
{
    private val parameters = DscParameters(0x12, 10, 8, 720, 20, 2, true)
    private val source = """
        /dts-v1/;
        / {
            panel {
                timing@0 {
                    qcom,compression-mode = "dsc";
                    qcom,mdss-dsi-panel-width = <1440>;
                    qcom,mdss-dsi-panel-height = <3200>;
                    qcom,mdss-dsi-panel-framerate = <120>;
                    qcom,mdss-dsc-version = <0x12>;
                    qcom,mdss-dsc-bit-per-component = <10>;
                    qcom,mdss-dsc-bit-per-pixel = <8>;
                    qcom,mdss-dsc-slice-width = <720>;
                    qcom,mdss-dsc-slice-height = <20>;
                    qcom,mdss-dsc-slice-per-pkt = <2>;
                    qcom,mdss-dsc-block-prediction-enable;
                    qcom,panel-roi-alignment = <1440 40 1440 40 1440 40>;
                    vendor,pps = [00 11 22 33];
                };
                timing@1 { untouched = <123>; };
            };
        };
    """.trimIndent()

    @Test
    fun editsSelectedNodeAndUndoRestoresAllProperties()
    {
        val target = parameters.copy(version = 0x11, bitsPerComponent = 8, bitsPerPixel = 10,
            sliceWidth = 360, sliceHeight = 40, slicePerPacket = 4, blockPredictionEnabled = false)
        val plan = DscPlanner.plan(2, source, "/panel/timing@0", target)
        val updated = DscTopologyAnalyzer.analyze(listOf(DeviceTreeParser.parse(2, plan.replayedText))).single()
        assertEquals(target.version, updated.version)
        assertEquals(target.bitsPerComponent, updated.bitsPerComponent)
        assertEquals(target.bitsPerPixel, updated.bitsPerPixel)
        assertEquals(360, updated.sliceWidth)
        assertEquals(40, updated.sliceHeight)
        assertEquals(4, updated.slicePerPacket)
        assertFalse(updated.blockPredictionEnabled)
        assertEquals(7, plan.transaction.operationCount)
        assertEquals(DeviceTreeTransactionKind.DSC, plan.transaction.kind)
        assertEquals(FeatureModuleKind.DSC, plan.transaction.moduleChange?.module)
        assertEquals(DeviceTreeTransactionRisk.EXPORT_ONLY, plan.transaction.risk)
        assertFalse(plan.transaction.directFlashAllowed)
        assertEquals(setOf(2), plan.transaction.entryIndices)

        val before = properties(source)
        val after = properties(plan.replayedText)
        val modified = plan.transaction.operations.mapNotNull { it.allowedPropertyPath() }.toSet()
        assertEquals(before.filterKeys { it !in modified }, after.filterKeys { it !in modified })
        val restored = plan.transaction.operations.asReversed().fold(plan.replayedText) { text, change ->
            DeviceTreeEditor.apply(text, change.inverse())
        }
        assertEquals(before, properties(restored))

        val state = MainUiState(transactions = listOf(plan.transaction))
        assertEquals(plan.transaction.operations, state.moduleDeviceTreeChanges)
        assertEquals(listOf(plan.transaction.moduleChange), state.moduleStagedChanges)
        assertTrue(state.deviceTreeChanges.isEmpty())
    }

    @Test
    fun addsMissingPropertiesAndBooleanAndCanUndoThem()
    {
        val original = source.lineSequence().filterNot {
            it.contains("qcom,mdss-dsc-version") || it.contains("qcom,mdss-dsc-bit-per-component") ||
                it.contains("qcom,mdss-dsc-block-prediction-enable")
        }.joinToString("\n")
        val plan = DscPlanner.plan(0, original, "/panel/timing@0", parameters)
        assertEquals(3, plan.transaction.operationCount)
        assertTrue(plan.transaction.operations.all { it is AddPropertyChange })
        val restored = plan.transaction.operations.asReversed().fold(plan.replayedText) { text, change ->
            DeviceTreeEditor.apply(text, change.inverse())
        }
        assertEquals(properties(original), properties(restored))
    }

    @Test
    fun rejectsInvalidGeometryAndNumericParameters()
    {
        listOf(
            parameters.copy(sliceWidth = 0), parameters.copy(sliceWidth = -1),
            parameters.copy(sliceWidth = 700), parameters.copy(sliceWidth = Int.MAX_VALUE),
            parameters.copy(sliceHeight = 0), parameters.copy(sliceHeight = 30),
            parameters.copy(slicePerPacket = 0), parameters.copy(slicePerPacket = 3),
            parameters.copy(sliceWidth = 240, slicePerPacket = 4),
            parameters.copy(bitsPerComponent = 5), parameters.copy(bitsPerPixel = 25),
            parameters.copy(version = 256), parameters.copy(version = null)
        ).forEach { invalid ->
            assertThrows(invalid.toString(), IllegalArgumentException::class.java) {
                DscPlanner.plan(0, source, "/panel/timing@0", invalid)
            }
        }
    }

    @Test
    fun rejectsNoOpAndNonDscTargets()
    {
        assertThrows(IllegalArgumentException::class.java) { DscPlanner.plan(0, source, "/panel/timing@0", parameters) }
        assertThrows(IllegalArgumentException::class.java) { DscPlanner.plan(0, source, "/panel/timing@1", parameters) }
        assertThrows(IllegalArgumentException::class.java) { DscPlanner.plan(0, source, "/missing", parameters) }
        assertThrows(IllegalArgumentException::class.java) {
            DscPlanner.plan(0, source.replace("\"dsc\"", "\"none\""), "/panel/timing@0", parameters.copy(sliceHeight = 40))
        }
    }

    @Test
    fun preservesAbsentOptionalProperties()
    {
        val original = source.lineSequence().filterNot {
            it.contains("qcom,mdss-dsc-version") || it.contains("qcom,mdss-dsc-bit-per-")
        }.joinToString("\n")
        val plan = DscPlanner.plan(0, original, "/panel/timing@0", parameters.copy(
            version = null, bitsPerComponent = null, bitsPerPixel = null, sliceHeight = 40))
        assertEquals(1, plan.transaction.operationCount)
        assertFalse(plan.replayedText.contains("qcom,mdss-dsc-version"))
    }

    private fun properties(text: String): Map<String, String?> = DeviceTreeParser.parse(0, text).flatten()
        .flatMap { node -> node.properties.map { "${node.path.trimEnd('/')}/${it.name}" to it.rawValue } }.toMap()
}
