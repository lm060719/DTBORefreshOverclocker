package io.mo.dtbooverclocker.core.devicetree

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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

    @Test
    fun addNodeAndInverseDeleteIt()
    {
        val add = DeviceTreeEditor.buildAddNodeChange(
            entryIndex = 0,
            text = sample,
            parentNodePath = "/",
            nodeName = "touch@1"
        )
        val changed = DeviceTreeEditor.apply(sample, add)
        assertTrue(changed.contains("touch@1 {"))
        assertTrue(DeviceTreeParser.parse(0, changed).findNode("/touch@1") != null)

        val restored = DeviceTreeEditor.apply(changed, add.inverse())
        assertTrue(DeviceTreeParser.parse(0, restored).findNode("/touch@1") == null)
    }

    @Test
    fun cloneNodeCopiesPropertiesAndCanUndo()
    {
        val clone = DeviceTreeEditor.buildCloneNodeChange(
            entryIndex = 0,
            text = sample,
            sourceNodePath = "/panel@0",
            newNodeName = "panel@1"
        )
        val changed = DeviceTreeEditor.apply(sample, clone)
        val cloned = DeviceTreeParser.parse(0, changed).findNode("/panel@1")
        requireNotNull(cloned)
        assertEquals("<0x78>", cloned.properties.first { it.name == "rate" }.rawValue)
        assertTrue(cloned.properties.any { it.name == "enabled" })

        val restored = DeviceTreeEditor.apply(changed, clone.inverse())
        assertTrue(DeviceTreeParser.parse(0, restored).findNode("/panel@1") == null)
    }

    @Test
    fun renameNodeChangesPathAndInverseRestoresIt()
    {
        val rename = DeviceTreeEditor.buildRenameNodeChange(
            entryIndex = 0,
            text = sample,
            nodePath = "/panel@0",
            newNodeName = "panel@1"
        )
        val changed = DeviceTreeEditor.apply(sample, rename)
        val changedDocument = DeviceTreeParser.parse(0, changed)
        assertTrue(changedDocument.findNode("/panel@0") == null)
        assertTrue(changedDocument.findNode("/panel@1") != null)

        val restored = DeviceTreeEditor.apply(changed, rename.inverse())
        val restoredDocument = DeviceTreeParser.parse(0, restored)
        assertTrue(restoredDocument.findNode("/panel@0") != null)
        assertTrue(restoredDocument.findNode("/panel@1") == null)
    }

    @Test
    fun deleteNodeAndInverseRestoreSubtree()
    {
        val delete = DeviceTreeEditor.buildDeleteNodeChange(
            entryIndex = 0,
            text = sample,
            nodePath = "/panel@0"
        )
        val changed = DeviceTreeEditor.apply(sample, delete)
        assertTrue(DeviceTreeParser.parse(0, changed).findNode("/panel@0") == null)

        val restored = DeviceTreeEditor.apply(changed, delete.inverse())
        val panel = DeviceTreeParser.parse(0, restored).findNode("/panel@0")
        requireNotNull(panel)
        assertEquals(2, panel.propertyCount)
    }

    @Test
    fun cloneRejectsSubtreeWithLabel()
    {
        val labeled = """
            /dts-v1/;
            / {
                panel: panel@0 {
                    rate = <0x78>;
                };
            };
        """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            DeviceTreeEditor.buildCloneNodeChange(
                entryIndex = 0,
                text = labeled,
                sourceNodePath = "/panel@0",
                newNodeName = "panel@1"
            )
        }
    }



    @Test
    fun cloneCanStripOnlyRootLabelForControlledFeatureModules()
    {
        val labeled = """
            /dts-v1/;
            / {
                timing0: timing@0 {
                    rate = <0x78>;
                };
            };
        """.trimIndent()

        val clone = DeviceTreeEditor.buildCloneNodeChange(
            entryIndex = 0,
            text = labeled,
            sourceNodePath = "/timing@0",
            newNodeName = "timing@1",
            stripRootLabel = true
        )
        val changed = DeviceTreeEditor.apply(labeled, clone)
        val document = DeviceTreeParser.parse(0, changed)
        val original = requireNotNull(document.findNode("/timing@0"))
        val cloned = requireNotNull(document.findNode("/timing@1"))

        assertEquals("timing0", original.label)
        assertEquals(null, cloned.label)
        assertEquals("<0x78>", cloned.properties.first { it.name == "rate" }.rawValue)
    }

    @Test
    fun stripRootLabelStillRejectsPhandleOrChildIdentity()
    {
        val phandled = """
            /dts-v1/;
            / {
                timing0: timing@0 {
                    phandle = <0x10>;
                    rate = <0x78>;
                };
            };
        """.trimIndent()
        assertThrows(IllegalArgumentException::class.java) {
            DeviceTreeEditor.buildCloneNodeChange(
                entryIndex = 0,
                text = phandled,
                sourceNodePath = "/timing@0",
                newNodeName = "timing@1",
                stripRootLabel = true
            )
        }

        val childLabel = """
            /dts-v1/;
            / {
                timing0: timing@0 {
                    rate = <0x78>;
                    child0: child@0 {
                        value = <1>;
                    };
                };
            };
        """.trimIndent()
        assertThrows(IllegalArgumentException::class.java) {
            DeviceTreeEditor.buildCloneNodeChange(
                entryIndex = 0,
                text = childLabel,
                sourceNodePath = "/timing@0",
                newNodeName = "timing@1",
                stripRootLabel = true
            )
        }
    }


    @Test
    fun cloneRejectsSubtreeWithExplicitPhandle()
    {
        val phandled = """
            /dts-v1/;
            / {
                panel@0 {
                    phandle = <0x10>;
                    rate = <0x78>;
                };
            };
        """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            DeviceTreeEditor.buildCloneNodeChange(
                entryIndex = 0,
                text = phandled,
                sourceNodePath = "/panel@0",
                newNodeName = "panel@1"
            )
        }
    }


    @Test
    fun rootCannotBeDeletedOrRenamed()
    {
        assertThrows(IllegalArgumentException::class.java) {
            DeviceTreeEditor.buildDeleteNodeChange(0, sample, "/")
        }
        assertThrows(IllegalArgumentException::class.java) {
            DeviceTreeEditor.buildRenameNodeChange(0, sample, "/", "root2")
        }
    }

}
