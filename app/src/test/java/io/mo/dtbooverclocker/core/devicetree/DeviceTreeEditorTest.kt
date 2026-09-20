package io.mo.dtbooverclocker.core.devicetree

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceTreeEditorTest
{
    private val sample = """
        /dts-v1/;
        / {
            panel@0 {
                rate = <0x78>;
                enabled;
            };
        };
    """.trimIndent()

    @Test
    fun setAddDeleteRoundTrip()
    {
        val set = DeviceTreeEditor.buildSetChange(0, sample, "/panel@0", "rate", "<0x90>")
        val afterSet = DeviceTreeEditor.apply(sample, set)
        assertTrue(afterSet.contains("rate = <0x90>;"))
        assertTrue(afterSet.contains("enabled;"))

        val add = DeviceTreeEditor.buildAddChange(0, afterSet, "/panel@0", "vendor,test", "\"ok\"")
        val afterAdd = DeviceTreeEditor.apply(afterSet, add)
        assertTrue(afterAdd.contains("vendor,test = \"ok\";"))

        val delete = DeviceTreeEditor.buildDeleteChange(0, afterAdd, "/panel@0", "enabled")
        val afterDelete = DeviceTreeEditor.apply(afterAdd, delete)
        assertFalse(afterDelete.contains("enabled;"))
    }

    @Test
    fun inverseRestoresSetProperty()
    {
        val set = DeviceTreeEditor.buildSetChange(0, sample, "/panel@0", "rate", "<0x90>")
        val changed = DeviceTreeEditor.apply(sample, set)
        val restored = DeviceTreeEditor.apply(changed, set.inverse())
        assertTrue(restored.contains("rate = <0x78>;"))
    }
}
