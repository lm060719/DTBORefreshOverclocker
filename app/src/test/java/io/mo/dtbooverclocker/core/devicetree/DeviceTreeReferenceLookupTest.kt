package io.mo.dtbooverclocker.core.devicetree

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceTreeReferenceLookupTest
{
    @Test
    fun reverseLookupsReturnSortedLabelsAndPhandlesPerNode()
    {
        val source = """
            / {
                zeta: alpha: panel {
                    phandle = <0x2>;
                };
                other {
                    linux,phandle = <0x1>;
                };
            };
        """.trimIndent()

        val index = DeviceTreeReferenceIndexer.build(DeviceTreeParser.parse(0, source))

        assertEquals(listOf("alpha", "zeta"), index.labelsOf("/panel"))
        assertEquals(listOf(2L), index.phandlesOf("/panel"))
        assertEquals(listOf(1L), index.phandlesOf("/other"))
        assertEquals(emptyList<String>(), index.labelsOf("/other"))
        assertEquals(emptyList<String>(), index.labelsOf("/missing"))
    }
}
