package com.fograin.launcher.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateServiceTest {

    @Test
    fun newerVersionsAreDetected() {
        assertTrue(isNewerVersion("0.1.1", "0.1.0"))
        assertTrue(isNewerVersion("0.2.0", "0.1.9"))
        assertTrue(isNewerVersion("1.0.0", "0.9.9"))
        assertTrue(isNewerVersion("v0.2.0", "0.1.0"))
        assertFalse(isNewerVersion("0.1.0", "0.1.0"))
        assertFalse(isNewerVersion("0.1.0", "0.2.0"))
    }

    @Test
    fun prereleasesSortBeforeTheirRelease() {
        assertFalse(isNewerVersion("0.2.0-rc.1", "0.2.0"))
        assertTrue(isNewerVersion("0.2.0", "0.2.0-rc.1"))
        assertTrue(isNewerVersion("0.2.0-rc.2", "0.2.0-rc.1"))
        assertTrue(isNewerVersion("0.2.0-rc.10", "0.2.0-rc.9"))
        assertTrue(isNewerVersion("0.2.0-rc.1", "0.2.0-beta.1"))
    }

    @Test
    fun malformedVersionsNeverPrompt() {
        assertFalse(isNewerVersion("nightly", "0.1.0"))
        assertFalse(isNewerVersion("0.2", "0.1.0"))
        assertFalse(isNewerVersion("0.2.0", "unknown"))
        assertNull(parseVersion("v1"))
    }

    @Test
    fun latestReleasePayloadIsParsed() {
        val release = parseLatestRelease(
            """
            {
              "tag_name": "v0.2.0",
              "html_url": "https://github.com/crystelf/fograin-launcher/releases/tag/v0.2.0",
              "name": "Fograin Launcher 0.2.0",
              "body": "notes",
              "draft": false,
              "prerelease": false
            }
            """.trimIndent(),
        )
        assertEquals("0.2.0", release?.version)
        assertEquals("Fograin Launcher 0.2.0", release?.title)
        assertEquals(
            "https://github.com/crystelf/fograin-launcher/releases/tag/v0.2.0",
            release?.url,
        )
    }

    @Test
    fun draftsAndPrereleasesAreIgnored() {
        assertNull(parseLatestRelease("""{"tag_name":"v0.2.0","draft":true}"""))
        assertNull(parseLatestRelease("""{"tag_name":"v0.2.0","prerelease":true}"""))
        assertNull(parseLatestRelease("not json"))
    }
}