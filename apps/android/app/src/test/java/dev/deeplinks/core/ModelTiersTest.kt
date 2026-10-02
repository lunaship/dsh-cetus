package dev.deeplinks.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class ModelTiersTest {

    private val chat = TierModel("deepseek", "chat", "Chat", 64_000, listOf("low", "high"))
    private val reasoner = TierModel("deepseek", "reasoner", "Reasoner", 128_000, listOf("high"))
    private val flash = TierModel("deepseek", "flash", "Flash", 32_000, listOf("low", "medium", "high"))

    @Test
    fun `resolves cheapest default and strongest without hardcoded ids`() {
        val picks = resolveModelTiers(listOf(chat, reasoner, flash), "deepseek", "chat")
        val save = picks.first { it.tier == ModelTier.Save }
        val balanced = picks.first { it.tier == ModelTier.Balanced }
        val power = picks.first { it.tier == ModelTier.Power }
        assertEquals("flash", save.modelId)
        assertEquals("low", save.effort)
        assertEquals("chat", balanced.modelId)
        assertEquals("high", balanced.effort)
        assertEquals("reasoner", power.modelId)
        assertEquals("high", power.effort)
        assertTrue(picks.all { it.enabled })
    }

    @Test
    fun `missing model grays that tier`() {
        val missingDefault = resolveModelTiers(listOf(flash), "deepseek", "absent")
        assertEquals(TierGap.MissingDefault, missingDefault.first { it.tier == ModelTier.Balanced }.gap)
        assertFalse(missingDefault.first { it.tier == ModelTier.Balanced }.enabled)
        assertTrue(missingDefault.first { it.tier == ModelTier.Save }.enabled)
        val empty = resolveModelTiers(emptyList(), "deepseek", "chat")
        assertTrue(empty.all { it.gap == TierGap.EmptyCatalog && !it.enabled })
    }

    @Test
    fun `custom override replaces the derived model`() {
        val custom = mapOf(ModelTier.Save to TierCustom("deepseek", "reasoner", "high"))
        val picks = resolveModelTiers(listOf(chat, reasoner, flash), "deepseek", "chat", custom)
        val save = picks.first { it.tier == ModelTier.Save }
        assertEquals("reasoner", save.modelId)
        assertEquals("high", save.effort)
        assertTrue(save.enabled)
    }

    @Test
    fun `custom model missing from the catalog is disabled`() {
        val custom = mapOf(ModelTier.Power to TierCustom("deepseek", "gone", "high"))
        val power = resolveModelTiers(listOf(flash), null, null, custom).first { it.tier == ModelTier.Power }
        assertEquals(TierGap.CustomMissing, power.gap)
        assertFalse(power.enabled)
    }

    @Test
    fun `price ranks ahead of context window when present`() {
        val cheap = flash.copy(inputPrice = 1.0, contextWindow = 1_000_000)
        val pricey = reasoner.copy(inputPrice = 8.0, contextWindow = 8_000)
        val picks = resolveModelTiers(listOf(pricey, cheap), "deepseek", "reasoner")
        assertEquals("flash", picks.first { it.tier == ModelTier.Save }.modelId)
        assertEquals("reasoner", picks.first { it.tier == ModelTier.Power }.modelId)
    }

    @Test
    fun `unknown effort order stays as the host listed it`() {
        val model = TierModel("p", "m", efforts = listOf("zeta", "alpha"))
        assertEquals(listOf("zeta", "alpha"), orderedEfforts(model.efforts))
        assertEquals("zeta", effortForSlot(model.efforts, ModelTier.Save))
        assertEquals("alpha", effortForSlot(model.efforts, ModelTier.Power))
        assertEquals("alpha", effortForSlot(model.efforts, ModelTier.Balanced))
    }

    @Test
    fun `huge windows stay comparable`() {
        val small = TierModel("p", "s", contextWindow = 1)
        val huge = TierModel("p", "h", contextWindow = Long.MAX_VALUE)
        val picks = resolveModelTiers(listOf(huge, small), "p", "s")
        assertEquals("s", picks.first { it.tier == ModelTier.Save }.modelId)
        assertEquals("h", picks.first { it.tier == ModelTier.Power }.modelId)
    }
}

class BalanceAlertTest {

    @Test
    fun `alert stays off by default and ignores non-ready balances`() {
        val off = BalanceAlertPref()
        assertFalse(balanceBelowThreshold("ready", BigDecimal("1"), off))
        val on = BalanceAlertPref(true, BigDecimal("10"))
        assertFalse(balanceBelowThreshold("signed-out", BigDecimal("1"), on))
        assertFalse(balanceBelowThreshold("failed", BigDecimal("1"), on))
        assertFalse(balanceBelowThreshold("unavailable", BigDecimal("1"), on))
        assertFalse(balanceBelowThreshold("ready", null, on))
    }

    @Test
    fun `ready balance below the threshold is visible`() {
        val on = BalanceAlertPref(true, BigDecimal("10"))
        assertTrue(balanceBelowThreshold("ready", BigDecimal("9.99"), on))
        assertFalse(balanceBelowThreshold("ready", BigDecimal("10"), on))
        assertFalse(balanceBelowThreshold("ready", BigDecimal("10.01"), on))
    }

    @Test
    fun `home fetch is at most once per five minutes`() {
        assertTrue(shouldFetchBalance(null, 1_000))
        assertFalse(shouldFetchBalance(1_000, 1_000 + 60_000))
        assertTrue(shouldFetchBalance(1_000, 1_000 + BALANCE_FETCH_INTERVAL_MS))
    }

    @Test
    fun `amount parser rejects blank and negative`() {
        assertNull(parseAlertAmount("  "))
        assertNull(parseAlertAmount("-1"))
        assertNull(parseAlertAmount("nope"))
        assertEquals(BigDecimal("10.5"), parseAlertAmount(" 10.5 "))
    }
}
