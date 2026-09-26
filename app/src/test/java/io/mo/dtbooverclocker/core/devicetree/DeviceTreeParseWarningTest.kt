package io.mo.dtbooverclocker.core.devicetree

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceTreeParseWarningTest
{
    @Test
    fun dtcStyleOutputProducesNoWarnings()
    {
        val source = """
            /dts-v1/;
            /plugin/;

            / {
            	fragment@0 {
            		target = <0xffffffff>;

            		__overlay__ {
            			compatible = "qcom,panel";
            			qcom,list = <0x01 0x02
            				0x03>;
            			enabled;
            		};
            	};

            	__fixups__ {
            		panel = "/fragment@0:target:0";
            	};
            };
        """.trimIndent()

        assertEquals(emptyList<DeviceTreeParseWarning>(), DeviceTreeParser.parse(0, source).warnings)
    }

    @Test
    fun reportsDirectivesUnrecognisedHeadersAndDroppedProperties()
    {
        val source = """
            / {
                node {
                    /delete-property/ old-prop;
                    /delete-node/ gone;
                    lbl: labelled-prop = <1>;
                    multi: line = <1
                        2>;
                };
                &extra {
                    value = <2>;
                };
            };
        """.trimIndent()

        val warnings = DeviceTreeParser.parse(0, source).warnings

        assertEquals(listOf(3, 4, 5, 6, 9), warnings.map { it.lineNumber })
        assertTrue(warnings[0].reason.contains("指令"))
        assertTrue(warnings[1].reason.contains("指令"))
        assertTrue(warnings[2].reason.contains("属性"))
        assertTrue(warnings[3].reason.contains("属性"))
        assertTrue(warnings[4].reason.contains("节点头"))
    }
}
