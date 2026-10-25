package app.banksync

import org.junit.Assert.assertEquals
import org.junit.Test

class CategorySearchTest {
    private val labels = listOf(
        "Groceries · Food & Drinks",
        "Restaurant, fast-food · Food & Drinks",
        "Fuel · Vehicle",
        "Café · Food & Drinks",
        "Food delivery · Food & Drinks",
    )

    private fun names(q: String) = CategorySearch.filter(labels, q).map { labels[it].substringBefore(" ·") }

    @Test
    fun emptyQueryShowsEverythingInOrder() {
        assertEquals(labels.indices.toList(), CategorySearch.filter(labels, "  "))
    }

    @Test
    fun matchesAnywhereIgnoringCase() {
        assertEquals(listOf("Fuel"), names("VEHI"))
        assertEquals(listOf("Groceries"), names("cer"))
    }

    @Test
    fun everyWordMustMatch() {
        assertEquals(listOf("Groceries"), names("gro dri"))
    }

    @Test
    fun labelsStartingWithTheQueryComeFirst() {
        assertEquals(listOf("Food delivery", "Groceries", "Restaurant, fast-food", "Café"), names("food"))
    }

    @Test
    fun accentsDontMatter() {
        assertEquals(listOf("Café"), names("cafe"))
    }
}
