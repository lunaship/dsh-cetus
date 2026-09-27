package dev.deeplinks.native

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsSettingsParsingTest {
    @Test
    fun parsesReadyBalanceWallets() {
        val balance = parseBalance(
            JSONObject()
                .put("status", "ready")
                .put("wallets", JSONArray().put(JSONObject().put("currency", "CNY").put("balance", "12.50")))
                .put("bonusWallets", JSONArray().put(JSONObject().put("currency", "").put("balance", "1"))),
        )
        assertEquals("ready", balance.status)
        assertEquals(listOf(MobileWallet("CNY", "12.50")), balance.wallets)
        assertTrue("没有币种的钱包丢弃", balance.bonusWallets.isEmpty())
        assertEquals("¥12.50", formatWallets(balance.wallets))
        assertNull(formatWallets(emptyList()))
    }

    @Test
    fun missingBalanceStatusMeansUnavailable() {
        assertEquals("unavailable", parseBalance(JSONObject()).status)
    }

    @Test
    fun parsesProviderDirectory() {
        val dir = parseProviderDirectory(
            JSONObject()
                .put("writable", true)
                .put(
                    "providers",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("provider", "zai")
                                .put("displayName", "Z.ai")
                                .put("kind", "api")
                                .put("active", true)
                                .put("keyRef", "ZAI_API_KEY")
                                .put("credential", JSONObject().put("configured", true).put("writable", true).put("source", JSONObject.NULL))
                                .put("models", JSONArray().put(JSONObject().put("id", "glm-5.3").put("name", "GLM-5.3").put("contextWindow", 1000000)))
                                .put("modelsEditable", true)
                                .put("canDiscover", true),
                        )
                        .put(JSONObject().put("displayName", "no id")),
                )
                .put("addable", JSONArray().put(JSONObject().put("provider", "openai").put("displayName", "OpenAI"))),
        )
        assertTrue(dir.writable)
        assertEquals(1, dir.providers.size)
        val zai = dir.providers.single()
        assertEquals(MobileProviderCredential(configured = true, writable = true, source = null), zai.credential)
        assertEquals("ZAI_API_KEY", zai.keyRef)
        assertEquals(1_000_000L, zai.models.single().contextWindow)
        assertTrue(zai.modelsEditable)
        assertEquals(listOf(MobileAddableProvider("openai", "OpenAI")), dir.addable)
    }

    @Test
    fun modelDraftJsonOmitsEmptyFields() {
        val json = MobileModelDraft(id = "glm-6", name = " ", contextWindow = 0, inputModalities = listOf("text")).toJson()
        assertEquals("glm-6", json.getString("id"))
        assertFalse(json.has("name"))
        assertFalse(json.has("contextWindow"))
        assertEquals("text", json.getJSONArray("inputModalities").getString(0))
    }

    @Test
    fun apiKeyPrecheckMatchesDesktopRules() {
        assertTrue(apiKeyLooksValid("sk-abc123"))
        assertTrue(apiKeyLooksValid("  sk-abc  "))
        assertTrue(apiKeyLooksValid("ABCD=="))
        assertFalse(apiKeyLooksValid(""))
        assertFalse(apiKeyLooksValid("sk abc"))
        assertFalse(apiKeyLooksValid("OPENAI_API_KEY=sk-1"))
        assertFalse(apiKeyLooksValid("\"sk-1\""))
        assertTrue(modelIdLooksValid("deepseek-v4"))
        assertFalse(modelIdLooksValid("has space"))
    }

    @Test
    fun detectsOutdatedPluginFrom404() {
        assertTrue(isPluginTooOld(IllegalStateException("x", IllegalStateException("mobile endpoint not found"))))
        assertFalse(isPluginTooOld(IllegalStateException("mobile API unavailable")))
    }

    @Test
    fun defaultModelLabelPrefersDisplayNames() {
        val groups = listOf(
            MobileModelGroup(provider = "stepfun", displayName = "StepFun", models = listOf(MobileModelOption("step-5", "Step 5", null, null))),
        )
        assertEquals("StepFun · Step 5", defaultModelLabel(groups, "stepfun", "step-5"))
        assertEquals("zai · glm-9", defaultModelLabel(groups, "zai", "glm-9"))
        assertNull(defaultModelLabel(groups, "zai", null))
    }
}
