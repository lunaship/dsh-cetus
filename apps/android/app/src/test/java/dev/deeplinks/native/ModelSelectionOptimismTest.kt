package dev.deeplinks.native

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * N2：会话内乐观换模型的目录状态转移——成功保持新值，失败回滚到选择前的快照。
 */
class ModelSelectionOptimismTest {

    private fun catalog(current: String) = MobileModelCatalog(
        currentProvider = "p",
        currentModel = current,
        currentReasoningEffort = "low",
    )

    @Test
    fun successKeepsTheOptimisticallySelectedModel() {
        val optimism = ModelSelectionOptimism(catalog("old"))
        val applied = optimism.select("p", "new", "high")
        assertEquals("new", applied?.currentModel)
        assertEquals("high", applied?.currentReasoningEffort)
    }

    @Test
    fun failureRollsBackToThePreSelectionSnapshot() {
        val optimism = ModelSelectionOptimism(catalog("old"))
        optimism.select("p", "new", "high")
        val rolledBack = optimism.rollback()
        assertEquals("old", rolledBack?.currentModel)
        assertEquals("low", rolledBack?.currentReasoningEffort)
    }

    @Test
    fun nullCatalogStaysNull() {
        val optimism = ModelSelectionOptimism(null)
        assertEquals(null, optimism.select("p", "new", null))
        assertEquals(null, optimism.rollback())
    }
}
