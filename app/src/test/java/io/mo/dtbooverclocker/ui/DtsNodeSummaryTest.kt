package io.mo.dtbooverclocker.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException

class DtsNodeSummaryTest {
    @Test
    fun indexesNestedPathsAndDirectProperties() {
        val nodes = parseNodeSummaries("""
            /dts-v1/;
            / {
                compatible = "test";
                panel: panel@0 {
                    enabled;
                    timing {
                        clock-frequency = <120000000>;
                    };
                };
            };
        """.trimIndent())
        assertEquals(listOf("/", "/panel@0", "/panel@0/timing"), nodes.map { it.path })
        assertTrue(nodes.all { it.propertyCount == 1 })
    }

    @Test
    fun retainsAllNodesInLargeDeviceTrees() {
        val dts = buildString {
            appendLine("/ {")
            repeat(1200) { appendLine("node@$it {\nvalue = <$it>;\n};") }
            appendLine("};")
        }
        val nodes = parseNodeSummaries(dts)
        assertEquals(1201, nodes.size)
        assertEquals(1, nodes.single { it.path == "/node@1199" }.propertyCount)
    }

    @Test(expected = CancellationException::class)
    fun obsoleteParsingCanBeCancelled() {
        parseNodeSummaries("/ {\n};") { throw CancellationException() }
    }
}
