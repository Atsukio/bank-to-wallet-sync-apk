package app.banksync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The demo shows the real parser at work, so its made-up alerts must read the way the demo expects. */
class DemoTest {
    @Test
    fun everyAlertParsesAsIntended() {
        for (a in Demo.alerts) {
            val p = Parser.parse(a.text)
            if (a.status == Store.UNPARSED) {
                assertNull(a.text, p)
            } else {
                assertNotNull(a.text, p)
                assertNotNull("payee of ${a.text}", p!!.payee)
            }
        }
    }

    @Test
    fun onlyTheSalaryIsIncome() {
        val income = Demo.alerts.filter { (Parser.parse(it.text)?.amount ?: 0.0) > 0 }
        assertEquals(listOf("Example Company"), income.map { Parser.parse(it.text)!!.payee })
    }

    @Test
    fun everyAlertHasARuleAndKnownCategory() {
        val categories = Demo.categories.map { it.id }.toSet()
        for (a in Demo.alerts) {
            assertTrue(a.pkg, Demo.rules.any { it.pkg == a.pkg })
            a.categoryId?.let { assertTrue(it, it in categories) }
        }
    }

    @Test
    fun showsEveryKindOfRow() {
        val statuses = Demo.alerts.map { it.status }.toSet()
        for (s in listOf(Store.SYNCED, Store.PENDING, Store.REVIEW, Store.UNPARSED)) assertTrue("status $s", s in statuses)
        // A waiting row without a category shows the "tap to choose one" prompt.
        assertTrue(Demo.alerts.any { it.status == Store.PENDING && it.categoryId == null })
    }
}
