package com.neveroff.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the ordering of the version strings this project has actually published, plus the
 * dotted/semver style it is moving to. The regression that motivated these: "2.02" parsed as
 * major 2 / minor 2 and so outranked "2.1", which offered 2.1 installs a downgrade.
 */
class UpdateManagerVersionTest {

    private fun assertOlder(older: String, newer: String) {
        assertTrue("$older should sort below $newer", UpdateManager.compareVersions(older, newer) < 0)
        assertTrue("$newer should sort above $older", UpdateManager.compareVersions(newer, older) > 0)
    }

    @Test
    fun `decimal releases sort in publication order`() {
        assertOlder("2.01", "2.02")
        assertOlder("2.02", "2.1")
        assertOlder("2.01", "2.1")
        assertOlder("2.1", "2.2")
    }

    /**
     * 2.11 was chosen over 2.1.1 precisely because the buggy comparator still installed in the
     * field reads it as [2, 11] and so offers it to 2.02 installs, which read themselves as
     * [2, 2]. Every later release has to keep clearing 2.11 — 2.12, 2.13, 3.0. Do not publish a
     * "2.2" after this: it sorts below 2.11 under both the old and the fixed comparator.
     */
    @Test
    fun `2_11 outranks every release published before it`() {
        assertOlder("2.01", "2.11")
        assertOlder("2.02", "2.11")
        assertOlder("2.0.1", "2.11")
        assertOlder("2.0.2", "2.11")
        assertOlder("2.1", "2.11")
        assertOlder("2.11", "2.12")
        assertOlder("2.11", "3.0")
        assertOlder("2.2", "2.11")
    }

    @Test
    fun `dotted releases sort as integers per component`() {
        assertOlder("2.0.1", "2.0.2")
        assertOlder("2.0.2", "2.1")
        assertOlder("2.9", "2.10")
        assertOlder("9.9", "10.0")
        assertOlder("2.1.0", "2.1.1")
    }

    @Test
    fun `missing trailing components count as zero`() {
        assertEquals(0, UpdateManager.compareVersions("2.1", "2.1.0"))
        assertEquals(0, UpdateManager.compareVersions("2", "2.0.0"))
        assertOlder("2.1", "2.1.1")
    }

    @Test
    fun `an install is never offered its own version or an older one`() {
        assertFalse(UpdateManager.isHigherVersion("2.1", "2.1"))
        assertFalse(UpdateManager.isHigherVersion("2.02", "2.1"))
        assertFalse(UpdateManager.isHigherVersion("2.01", "2.1"))
        assertTrue(UpdateManager.isHigherVersion("2.3", "2.1"))
        assertTrue(UpdateManager.isHigherVersion("2.1", "2.02"))
    }

    @Test
    fun `the published table picks 2_1 as latest`() {
        val published = listOf("2.01", "2.02", "2.1")
        val latest = published.maxWithOrNull { a, b -> UpdateManager.compareVersions(a, b) }
        assertEquals("2.1", latest)
    }

    @Test
    fun `garbage components do not throw`() {
        UpdateManager.compareVersions("", "2.1")
        UpdateManager.compareVersions("2.1-beta", "2.1")
        UpdateManager.compareVersions("v2.1", "2.02")
    }
}
