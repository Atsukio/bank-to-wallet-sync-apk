package app.banksync

import java.text.Normalizer

/** Type-to-filter for the category pickers. Pure, unit-tested. */
object CategorySearch {
    private val marks = Regex("""\p{M}+""")

    /** Lower case without accents, so "cafe" finds "Café". */
    private fun fold(s: String) = marks.replace(Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD), "")

    /**
     * The labels containing every word of [query], anywhere ("food" finds "Groceries · Food & Drinks",
     * "gro dri" too). Labels starting with the query come first, then the rest in their original order.
     */
    fun filter(labels: List<String>, query: String): List<Int> {
        val q = fold(query.trim())
        if (q.isEmpty()) return labels.indices.toList()
        val words = q.split(Regex("""\s+"""))
        val hits = labels.indices.filter { i -> fold(labels[i]).let { l -> words.all { it in l } } }
        return hits.sortedBy { if (fold(labels[it]).startsWith(q)) 0 else 1 }
    }
}
