package io.mo.dtbooverclocker.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModifiedPathIndexTest
{
    @Test
    fun matchesModifiedNodesTheirAncestorsAndDescendantsOnly()
    {
        val index = ModifiedPathIndex(setOf("/soc/panel@0/timing@1"))

        assertTrue(index.isRelated("/soc/panel@0/timing@1"))
        assertTrue(index.isRelated("/soc/panel@0"))
        assertTrue(index.isRelated("/soc"))
        assertTrue(index.isRelated("/"))
        assertTrue(index.isRelated("/soc/panel@0/timing@1/child"))

        assertFalse(index.isRelated("/soc/panel@0/timing@10"))
        assertFalse(index.isRelated("/soc/panel@0/timing@0"))
        assertFalse(index.isRelated("/other"))
    }

    @Test
    fun modifiedRootRelatesEveryNode()
    {
        val index = ModifiedPathIndex(setOf("/"))

        assertTrue(index.isRelated("/"))
        assertTrue(index.isRelated("/any/deep/node"))
    }
}
