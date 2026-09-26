package io.mo.dtbooverclocker.core.devicetree

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceTreeDiffTest
{
    @Test
    fun deleteNodeShowsDeIndentedSourcePreview()
    {
        val change = DeleteNodeChange(0, "/panel/timing@1", "\t\ttiming@1 {\n\t\t\trate = <120>;\n\t\t};")

        assertEquals(
            listOf(
                "Entry 0  /panel/timing@1",
                "- 删除节点及其整个子树",
                "- timing@1 {",
                "- \trate = <120>;",
                "- };"
            ),
            DeviceTreeDiff.render(change).lines()
        )
    }

    @Test
    fun longClonedSubtreeIsTruncatedWithLineCount()
    {
        val source = (listOf("copy {") + (1..20).map { "    p$it;" } + listOf("};")).joinToString("\n")
        val change = CloneNodeChange(0, "/orig", "/copy", source)

        val lines = DeviceTreeDiff.render(change).lines()

        assertEquals("+ 克隆自 /orig", lines[1])
        assertEquals("+ copy {", lines[2])
        assertEquals("+ … 共 22 行", lines.last())
        assertTrue(lines.size == 2 + 8 + 1)
    }
}
