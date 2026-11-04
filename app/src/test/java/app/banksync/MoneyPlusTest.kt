package app.banksync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoneyPlusTest {
    @Test
    fun aMoneyPlusBonusIsMoneyPlus() {
        assertTrue(MoneyPlus.matches("ShopeePay Money+ Bonus Your bonus is in! Tap now to view more details."))
        assertTrue(MoneyPlus.matches("ShopeePay Your Money + earnings of RM0.05 are in"))
    }

    @Test
    fun otherShopeeNotificationsAreNot() {
        assertFalse(MoneyPlus.matches("ShopeePay ShopeePay money received! Sample Person has transferred RM5.00 to your ShopeePay wallet via DuitNow."))
        assertFalse(MoneyPlus.matches("Shopee Flash sale promo starts tonight"))
        assertFalse(MoneyPlus.matches("Send Money via DuitNow Successful Your RM5.00 transfer to ***** via DuitNow is successful."))
    }

    @Test
    fun onlyTheShopeeApp() {
        assertTrue(MoneyPlus.isShopee("Shopee"))
        assertTrue(MoneyPlus.isShopee("Shopee MY"))
        assertFalse(MoneyPlus.isShopee("Sample Bank"))
    }
}
