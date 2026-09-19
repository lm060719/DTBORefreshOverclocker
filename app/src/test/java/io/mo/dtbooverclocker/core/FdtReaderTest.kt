package io.mo.dtbooverclocker.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FdtReaderTest {

    @Test
    fun testParseFdtProperties() {
        val dtbFile = File("C:/Users/limo2/.gemini/antigravity/brain/fb6f5904-53aa-4848-838b-baad97c72e24/scratch/orig.dtb")
        if (!dtbFile.exists()) return

        val bytes = dtbFile.readBytes()
        val props = FdtReader.readAllProperties(bytes)
        assertTrue(props.size > 1000)

        // Check key properties exist
        val voltPara = props.entries.firstOrNull { it.key.endsWith("volt_para1") }
        assertNotNull(voltPara)
        assertTrue(voltPara!!.value.isNotEmpty())
    }
}

