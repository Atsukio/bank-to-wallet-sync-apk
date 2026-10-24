package app.banksync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangelogTest {
    @Test
    fun newestFirstWithUniqueCodes() {
        val codes = Changelog.releases.map { it.code }
        assertEquals(codes.sortedDescending(), codes)
        assertEquals(codes.size, codes.toSet().size)
        assertTrue(Changelog.releases.all { it.changes.isNotEmpty() })
    }

    @Test
    fun sinceSkipsSeenAndNotYetInstalled() {
        assertEquals(listOf(2), Changelog.since(1, 2).map { it.code })
        assertEquals(listOf(2, 1), Changelog.since(0, 2).map { it.code })
        assertTrue(Changelog.since(2, 2).isEmpty())
        // An entry written ahead of the build that ships it isn't shown yet.
        assertEquals(listOf(1), Changelog.since(0, 1).map { it.code })
    }

    @Test
    fun formatListsEachChange() {
        val text = Changelog.format(listOf(Release(9, "9.0", "1 Jan 2030", listOf("One", "Two"))))
        assertEquals("9.0 · 1 Jan 2030\n• One\n• Two", text)
    }
}
