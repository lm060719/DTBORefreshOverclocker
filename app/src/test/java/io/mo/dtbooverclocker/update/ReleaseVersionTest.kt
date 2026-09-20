package io.mo.dtbooverclocker.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseVersionTest {
    private fun compare(left: String, right: String): Int =
        requireNotNull(ReleaseVersion.parse(left)).compareTo(requireNotNull(ReleaseVersion.parse(right)))

    @Test
    fun newerReleasesCompareNumerically() {
        assertTrue(compare("v1.1.3", "1.1.2") > 0)
        assertTrue(compare("1.10.0", "1.9.9") > 0)
        assertTrue(compare("2.0.0", "1.99.99") > 0)
        assertTrue(compare("1.1.2", "1.2.0") < 0)
    }

    @Test
    fun prefixesMetadataAndTrailingZerosDoNotTriggerUpdate() {
        assertEquals(0, compare("v1.1.2", "1.1.2"))
        assertEquals(0, compare(" V1.1.2+release.4 ", "1.1.2+local"))
        assertEquals(0, compare("1.2", "1.2.0"))
    }

    @Test
    fun stableVersionIsNewerThanItsPrerelease() {
        assertTrue(compare("1.2.0", "1.2.0-rc.1") > 0)
        assertTrue(compare("1.2.0-beta.10", "1.2.0-beta.2") > 0)
        assertTrue(compare("1.2.0-alpha", "1.2.0-beta") < 0)
        assertTrue(compare("1.2.0-rc", "1.2.0-rc.1") < 0)
    }

    @Test
    fun invalidTagsAreNotSilentlyReportedAsUpToDate() {
        listOf("", "latest", "release-1.2.0", "1..2", "1.2.3-", "1.2.3+").forEach {
            assertNull(it, ReleaseVersion.parse(it))
        }
    }
}
