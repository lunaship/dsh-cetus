package dev.deeplinks.native

import dev.deeplinks.native.util.optNullableString
import dev.deeplinks.native.util.optStringOrEmpty
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * org.json 的 optString 会把 JSON null 读成字面量 "null"——曾在模型弹层显示成「step-5-preview Null」。
 * 解析层一律走 optNullableString / optStringOrEmpty。
 */
class JsonNullSafetyTest {

    @Test
    fun jsonNullNeverBecomesTheWordNull() {
        val o = JSONObject("""{"a":null,"b":"  keep  ","c":""}""")
        assertNull(o.optNullableString("a"))
        assertNull(o.optNullableString("missing"))
        assertEquals("keep", o.optNullableString("b"))
        assertEquals("", o.optStringOrEmpty("a"))
        assertEquals("x", o.optStringOrEmpty("missing", "x"))
        // 不修剪：消息正文与工具参数里的空白要原样保留
        assertEquals("  keep  ", o.optStringOrEmpty("b"))
    }

    @Test
    fun modelOptionIgnoresNullNameAndEffort() {
        val m = parseModelOption(JSONObject("""{"id":"step-5","name":null,"defaultEffort":null}"""))
        assertEquals("step-5", m.id)
        assertNull(m.name)
        assertNull(m.defaultEffort)
    }
}
