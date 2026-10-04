package dev.deeplinks.native

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * I3.6 共享请求状态用例（testdata/request-state-cases.json）逐条跑 Android 真实实现。
 * 语义来源是本包的 RequestStateTest；同一份用例由 iOS `RequestStateContractTests` 消费。
 */
class RequestStateSharedCasesTest {
    @Test
    fun `共享请求状态用例与 Android 实现一致`() {
        val cases = JSONObject(sharedCasesFile().readText()).getJSONArray("cases")
        assertTrue("用例清单太短，可能没读到文件", cases.length() >= 30)
        for (i in 0 until cases.length()) {
            runCase(cases.getJSONObject(i))
        }
    }

    private fun runCase(item: JSONObject) {
        val name = item.getString("name")
        when (item.getString("op")) {
            "approvalUiStatus" ->
                assertEquals(name, item.getString("expectStatus"), approvalUiStatus(item.rawString("outcome")))
            "mergeStatus" ->
                assertEquals(
                    name,
                    item.rawString("expectStatus"),
                    mergeRequestStatus(item.rawString("current"), item.rawString("incoming")),
                )
            "mergeOutcome" ->
                assertEquals(
                    name,
                    item.rawString("expectOutcome"),
                    mergeRequestOutcome(item.rawString("current"), item.rawString("incoming"), item.rawString("status")),
                )
            "coalesce" -> assertMessages(name, item, coalesceRequestMessages(item.messageList("messages")))
            "applySnapshot" ->
                assertMessages(
                    name,
                    item,
                    applyRequestSnapshotToMessages(
                        item.messageList("messages"),
                        parseSessionRequestSnapshot(item.getJSONObject("snapshot")),
                    ),
                )
            "mergeSnapshot" ->
                assertMessages(
                    name,
                    item,
                    mergeMessagesWithRequestSnapshot(
                        item.messageList("messages"),
                        parseSessionRequestSnapshot(item.getJSONObject("snapshot")),
                    ),
                )
            "parseSnapshot" ->
                assertParsedSnapshot(name, item, parseSessionRequestSnapshot(item.getJSONObject("snapshot")))
            else -> error("未知 op ${item.getString("op")}")
        }
    }

    /** 期望里显式写出的字段逐个断言（含 null 期望）；没写的字段不比较。 */
    private fun assertMessages(name: String, item: JSONObject, actual: List<MobileMessage>) {
        val expected = item.getJSONArray("expectMessages")
        assertEquals("$name：条数一致", expected.length(), actual.size)
        for (i in 0 until expected.length()) {
            val exp = expected.getJSONObject(i)
            val got = actual[i]
            for (key in exp.keys()) {
                when (key) {
                    "id" -> assertEquals(name, exp.optString(key), got.id)
                    "role" -> assertEquals(name, exp.optString(key), got.role)
                    "text" -> assertEquals(name, exp.optString(key), got.text)
                    "type" -> assertEquals(name, exp.optString(key), got.type)
                    "toolName" -> assertEquals(name, exp.rawString(key), got.toolName)
                    "approvalId" -> assertEquals(name, exp.rawString(key), got.approvalId)
                    "callId" -> assertEquals(name, exp.rawString(key), got.callId)
                    "questionRpcId" -> assertEquals(name, exp.rawString(key), got.questionRpcId)
                    "questionHeader" -> assertEquals(name, exp.rawString(key), got.questionHeader)
                    "questionPayloadJson" -> assertEquals(name, exp.rawString(key), got.questionPayloadJson)
                    "requestStatus" -> assertEquals(name, exp.rawString(key), got.requestStatus)
                    "outcome" -> assertEquals(name, exp.rawString(key), got.outcome)
                    "questionOptions" -> {
                        val options = exp.getJSONArray(key)
                        assertEquals(name, (0 until options.length()).map { options.optString(it) }, got.questionOptions)
                    }
                    "takenOverByPhone" -> assertEquals(name, exp.optBoolean(key), got.takenOverByPhone)
                    "questionPayloadJsonContains" ->
                        assertTrue("$name：$key", got.questionPayloadJson?.contains(exp.optString(key)) == true)
                    else -> error("未知期望字段 $key（$name）")
                }
            }
        }
    }

    private fun assertParsedSnapshot(name: String, item: JSONObject, snapshot: SessionRequestSnapshot) {
        val expectedApprovals = item.optJSONArray("expectApprovals") ?: JSONArray()
        assertEquals("$name：approvals 条数", expectedApprovals.length(), snapshot.approvals.size)
        for (i in 0 until expectedApprovals.length()) {
            val exp = expectedApprovals.getJSONObject(i)
            val record = snapshot.approvals[i]
            if (exp.has("approvalId")) assertEquals(name, exp.rawString("approvalId"), record.id)
            if (exp.has("status")) assertEquals(name, exp.rawString("status"), record.status)
            if (exp.has("outcome")) assertEquals(name, exp.rawString("outcome"), record.outcome)
            if (exp.has("toolName")) assertEquals(name, exp.rawString("toolName"), record.toolName)
            if (exp.has("callId")) assertEquals(name, exp.rawString("callId"), record.callId)
        }
        val expectedQuestions = item.optJSONArray("expectQuestions") ?: JSONArray()
        assertEquals("$name：questions 条数", expectedQuestions.length(), snapshot.questions.size)
        for (i in 0 until expectedQuestions.length()) {
            val exp = expectedQuestions.getJSONObject(i)
            val record = snapshot.questions[i]
            if (exp.has("rpcId")) assertEquals(name, exp.rawString("rpcId"), record.id)
            if (exp.has("status")) assertEquals(name, exp.rawString("status"), record.status)
            if (exp.has("questionPayloadJsonContains")) {
                assertTrue(
                    "$name：questions[$i] 题目内容应保留",
                    record.questionsJson?.contains(exp.getString("questionPayloadJsonContains")) == true,
                )
            }
        }
    }

    /** 输入消息：字段名与共享用例一致；缺省值对齐 Android 用例手写时的默认（type=text 等）。 */
    private fun JSONObject.messageList(key: String): List<MobileMessage> {
        val array = getJSONArray(key)
        return (0 until array.length()).map { index ->
            val m = array.getJSONObject(index)
            MobileMessage(
                id = m.optString("id"),
                role = m.optString("role"),
                text = m.optString("text"),
                type = if (m.has("type")) m.optString("type") else "text",
                toolName = m.rawString("toolName"),
                approvalId = m.rawString("approvalId"),
                callId = m.rawString("callId"),
                questionRpcId = m.rawString("questionRpcId"),
                questionOptions = m.optJSONArray("questionOptions")?.let { options ->
                    (0 until options.length()).map { options.optString(it) }
                } ?: emptyList(),
                questionHeader = m.rawString("questionHeader"),
                questionPayloadJson = m.rawString("questionPayloadJson"),
                takenOverByPhone = m.optBoolean("takenOverByPhone"),
                requestStatus = m.rawString("requestStatus"),
                outcome = m.rawString("outcome"),
            )
        }
    }

    /** 缺键与 JSON null 都是没有值；空白串原样保留（approvalUiStatus 的空串用例依赖这一点）。 */
    private fun JSONObject.rawString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key)

    private fun sharedCasesFile(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "testdata/request-state-cases.json")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("找不到 testdata/request-state-cases.json")
    }
}
