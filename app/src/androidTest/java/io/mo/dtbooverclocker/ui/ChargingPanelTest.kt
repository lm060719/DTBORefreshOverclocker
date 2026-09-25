package io.mo.dtbooverclocker.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import io.mo.dtbooverclocker.core.ChargingAnalyzer
import io.mo.dtbooverclocker.core.devicetree.DeviceTreeParser
import io.mo.dtbooverclocker.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class ChargingPanelTest {
    @get:Rule val compose = createComposeRule()
    private val source = """
        /dts-v1/;
        / {
            charger {
                compatible = "qcom,qpnp-smb5";
                qcom,fcc-max-ua = <3000000>;
            };
            battery {
                constant-charge-current-max-microamp = <2000000>;
            };
        };
    """.trimIndent()
    private val state = mutableStateOf(MainUiState())
    private var staged: Pair<ChargingNode, Map<String, String>>? = null
    private fun report(text: String = source) = CapabilityReport(1, 3, 3, emptyList(),
        ChargingAnalyzer.analyze(listOf(DeviceTreeParser.parse(0, text))))
    private fun show(text: String = source): StateRestorationTester {
        val metadata = DtboMetadata("0xd7b7ab1e", 0, 32, 32, 32, 4096, 0, emptyList())
        state.value = MainUiState(workspace = DtboWorkspace(File("charging-test"), File("input.img"), File("metadata"), metadata,
            DtboBinaryImage(metadata, byteArrayOf(), emptyList()), emptyList(), emptyList(), emptyList()), capabilityReport = report(text))
        return StateRestorationTester(compose).also { restoration ->
            restoration.setContent {
                MaterialTheme {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        ChargingPanel(state.value) { node, inputs -> staged = node to inputs }
                    }
                }
            }
        }
    }
    private fun input(label: String = "最大快充电流（mA）") = compose.onNode(hasSetTextAction() and hasText(label))
    private fun cell(label: String) = compose.onNode(hasSetTextAction() and hasContentDescription(label))
    private fun stage() = compose.onNodeWithText("暂存充电修改（仅导出验证）")

    @Test fun actualPhoneMcaGroupsKeepDraftsAndStageHiddenFields() {
        val registry = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val fixture = File(registry.targetContext.cacheDir, "charging-device.dts")
        org.junit.Assume.assumeTrue("Optional actual-device DTC fixture", fixture.isFile)
        val restore = show(fixture.readText())
        compose.onNodeWithText("394 个可编辑参数 · 6 / 14 个节点 · 仅导出验证").assertExists()
        input("DCP 输入电流（mA）").performScrollTo().performTextReplacement("1450")
        compose.onNodeWithText("下一页").performScrollTo().performClick()
        input("PMIC 浮充电压补偿（mV）").performScrollTo().performTextReplacement("1")
        restore.emulateSavedInstanceStateRestore()
        stage().performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals("1450", staged?.second?.get("in_dcp"))
            assertEquals("1", staged?.second?.get("pmic_fv_compensation"))
        }
        compose.onNodeWithText("切换充电节点（14）").performScrollTo().performClick()
        compose.onNodeWithText("/fragment@45/__overlay__/mca_charger_thermal").performScrollTo().performClick()
        cell("有线温控 · 5 V 输入 · 第 1 档").performScrollTo().performTextReplacement("1950")
        cell("无线温控 · CP 50 W · 第 1 档").performScrollTo().performTextReplacement("8500")
        stage().performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals("1950", staged?.second?.get("wired_thermal[0]"))
            assertEquals("8500", staged?.second?.get("wireless_thermal[6]"))
            assertEquals(300, staged?.second?.size)
        }
        compose.onNodeWithText("Charging 参数编辑").performScrollTo()
        val capture = compose.onRoot().captureToImage().asAndroidBitmap()
        File(registry.targetContext.cacheDir, "charging-mca-panel.png").outputStream().use {
            capture.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun validatesInputAndStagesTheSelectedNode() {
        show()
        val capture = compose.onRoot().captureToImage().asAndroidBitmap()
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        File(context.cacheDir, "charging-panel.png").outputStream().use { capture.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        stage().performScrollTo().assertIsNotEnabled()
        input().performScrollTo().performTextReplacement("2800.123")
        compose.onNodeWithText("• 最大快充电流：3000 mA → 2800.123 mA").performScrollTo().assertIsDisplayed()
        stage().performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals("/charger", staged?.first?.nodePath)
            assertEquals("2800.123", staged?.second?.get("qcom,fcc-max-ua"))
        }
        input().performScrollTo().performTextReplacement("1.0001")
        stage().performScrollTo().assertIsNotEnabled()
    }

    @Test fun draftsSurviveNodeSwitchAndSavedStateRestore() {
        val restoration = show()
        input().performScrollTo().performTextReplacement("2750")
        compose.onNodeWithText("切换充电节点（2）").performScrollTo().performClick()
        compose.onNodeWithText("/battery").performScrollTo().performClick()
        input("恒流充电电流上限（mA）").performScrollTo().performTextReplacement("1850")
        restoration.emulateSavedInstanceStateRestore()
        input("恒流充电电流上限（mA）").assertTextContains("1850")
        compose.onNodeWithText("切换充电节点（2）").performScrollTo().performClick()
        compose.onNodeWithText("/charger").performScrollTo().performClick()
        input().assertTextContains("2750")
    }

    @Test fun rescanResetsStaleInputsAndBusyDisablesWrites() {
        show()
        input().performTextReplacement("2800")
        compose.runOnIdle { state.value = state.value.copy(capabilityReport = report(source.replace("3000000", "2700000"))) }
        input().assertTextContains("2700")
        input().performTextReplacement("2600")
        compose.runOnIdle { state.value = state.value.copy(busy = true) }
        compose.onNodeWithText("最大快充电流（mA）").assertIsNotEnabled()
        stage().performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(busy = false, capabilityScanInProgress = true) }
        stage().assertIsNotEnabled()
    }

    @Test fun resetsDraftWithoutStagingAndExplainsMissingNodes() {
        show()
        input().performTextReplacement("2500")
        compose.onNodeWithText("重置未暂存输入").performScrollTo().performClick()
        input().assertTextContains("3000")
        assertNull(staged)
        compose.runOnIdle { state.value = state.value.copy(capabilityReport = report().copy(chargingNodes = emptyList())) }
        compose.onNodeWithText("当前 DTBO 未发现充电参数。相关配置可能位于基础 DTB、vendor_boot 或电源管理驱动中。").assertExists()
    }

    @Test fun editsReferenceOverlayLevelsAndRejectsInvalidOrder() {
        show("""
            /dts-v1/;
            / {
                charger_therm0 {
                    polling-delay = <0>;
                };
                fragment@22 {
                    target = <0xffffffff>;
                    __overlay__ {
                        qcom,wireless-fw-name = "idt9415.bin";
                        qcom,thermal-mitigation = <3000000 1500000 1000000 500000>;
                        #cooling-cells = <2>;
                    };
                };
                __fixups__ {
                    battery_charger = "/fragment@22:target:0";
                };
            };
        """.trimIndent())
        compose.onNodeWithText("Overlay 目标：&battery_charger").assertExists()
        for ((index, value) in listOf("3000", "1500", "1000", "500").withIndex()) {
            cell("温控限流 · 第 ${index + 1} 档").performScrollTo().assertTextContains(value)
        }
        cell("温控限流 · 第 2 档").performScrollTo().performTextReplacement("3500")
        stage().performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("温控限流必须按档位非递增排列，同一通道的后一档不能大于前一档").assertExists()
        compose.onNodeWithText("红框：数值无效，或高于上一档。同一通道的后一档不能高于前一档。").assertExists()
        cell("温控限流 · 第 2 档").performScrollTo().performTextReplacement("1400")
        stage().performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals("/fragment@22/__overlay__", staged?.first?.nodePath)
            assertEquals("1400", staged?.second?.get("qcom,thermal-mitigation[1]"))
            assertEquals(4, staged?.second?.size)
        }
        compose.onNodeWithText("切换充电节点（2）").performScrollTo().performClick()
        compose.onNodeWithText("/charger_therm0").performScrollTo().performClick()
        compose.onNodeWithText("切换到可编辑节点").performScrollTo().performClick()
        cell("温控限流 · 第 2 档").assertTextContains("1400")
    }
}
