package io.mo.dtbooverclocker.core.devicetree

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test

class DeviceTreeNamesTest
{
    @Test
    fun nodeNamesFollowNameAndOptionalUnitAddress()
    {
        listOf("timing@3", "panel@ae94000", "qcom,mdss_dsi_panel", "fragment@0", "a.b+c-d_e", "x@1,2")
            .forEach { assertEquals("应接受：$it", null, DeviceTreeNames.nodeNameError(it)) }

        listOf("", " ", "/", "#address", "a@b@c", "@1", "timing@", "has space", "a{b")
            .forEach { assertNotNull("应拒绝：$it", DeviceTreeNames.nodeNameError(it)) }
    }

    @Test
    fun propertyNamesAllowHashAndQuestionMark()
    {
        listOf("#address-cells", "qcom,mdss-dsi-panel-framerate", "linux,phandle", "a?b")
            .forEach { assertEquals("应接受：$it", null, DeviceTreeNames.propertyNameError(it)) }

        listOf("", "a b", "a=b", "a;b", "a@b")
            .forEach { assertNotNull("应拒绝：$it", DeviceTreeNames.propertyNameError(it)) }
    }

    @Test
    fun editorUsesTheSharedNodeNameRule()
    {
        val source = "/ {\n    child {\n    };\n};"
        assertThrows(IllegalArgumentException::class.java) {
            DeviceTreeEditor.buildAddNodeChange(0, source, "/", "#bad")
        }
        assertThrows(IllegalArgumentException::class.java) {
            DeviceTreeEditor.buildRenameNodeChange(0, source, "/child", "a@b@c")
        }
    }
}
