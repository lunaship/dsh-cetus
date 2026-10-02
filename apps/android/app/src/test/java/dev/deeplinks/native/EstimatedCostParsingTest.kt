package dev.deeplinks.native

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 插件只给累计 token：内置表计价时带全谷时 / 全峰时区间，App 显示区间。 */
class EstimatedCostParsingTest {

    @Test
    fun `range is parsed when max exceeds min`() {
        val cost = parseEstimatedCost(
            JSONObject("""{"amount":0.912,"currency":"USD","priceDate":"2026-10-02","source":"builtin","amountMin":0.456,"amountMax":0.912}"""),
        )!!
        assertEquals(0.456, cost.amountMin!!, 1e-9)
        assertEquals(0.912, cost.amountMax!!, 1e-9)
    }

    @Test
    fun `missing or degenerate range falls back to single amount`() {
        val old = parseEstimatedCost(JSONObject("""{"amount":1.0,"currency":"USD"}"""))!!
        assertNull(old.amountMin)
        assertNull(old.amountMax)
        val flat = parseEstimatedCost(JSONObject("""{"amount":1.0,"currency":"USD","amountMin":1.0,"amountMax":1.0}"""))!!
        assertNull(flat.amountMin)
    }
}
