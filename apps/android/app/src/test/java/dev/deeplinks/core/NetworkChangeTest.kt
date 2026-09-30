package dev.deeplinks.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** S5：默认网络回调的处置判定。 */
class NetworkChangeTest {

    @Test
    fun `首次回调只登记不清池`() {
        assertEquals(
            NetworkChangeAction.None,
            networkChangeAction(prevHandle = null, newHandle = 42, prevAddrs = emptySet(), newAddrs = emptySet()),
        )
    }

    @Test
    fun `onLost 清池`() {
        assertEquals(
            NetworkChangeAction.ResetPool,
            networkChangeAction(prevHandle = 42, newHandle = null, prevAddrs = emptySet(), newAddrs = emptySet()),
        )
    }

    @Test
    fun `默认网络切换清池`() {
        assertEquals(
            NetworkChangeAction.ResetPool,
            networkChangeAction(prevHandle = 1, newHandle = 2, prevAddrs = emptySet(), newAddrs = emptySet()),
        )
    }

    @Test
    fun `同一张网 IP 变化只作废选路`() {
        assertEquals(
            NetworkChangeAction.InvalidateRoutes,
            networkChangeAction(
                prevHandle = 7,
                newHandle = 7,
                prevAddrs = setOf("10.0.0.2"),
                newAddrs = setOf("10.0.0.9"),
            ),
        )
    }

    @Test
    fun `同一张网地址没变什么都不做`() {
        assertEquals(
            NetworkChangeAction.None,
            networkChangeAction(
                prevHandle = 7,
                newHandle = 7,
                prevAddrs = setOf("10.0.0.2"),
                newAddrs = setOf("10.0.0.2"),
            ),
        )
    }
}
