package dev.deeplinks.native

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for the typed CommandPalette model.
 * No Android runtime required — verifies filtering/grouping semantics.
 */
class CommandPaletteTest {

    private val insertPlan = PaletteEntry(
        PaletteCommand.Insertable("/plan", "先出执行计划，再动手", "计划"),
        PaletteGroup.AGENT,
    )
    private val newSession = PaletteEntry(
        PaletteCommand.Local("/new-session", "开始一个新会话", LocalKind.NEW_SESSION, "新会话"),
        PaletteGroup.APP,
    )
    private val completeCmd = PaletteEntry(
        PaletteCommand.Completable("/clear", "保留会话，清空上下文", "清除上下文"),
        PaletteGroup.SESSION,
    )
    private val subagent = PaletteEntry(
        PaletteCommand.Insertable("/subagent", "派生一个子智能体", "子智能体"),
        PaletteGroup.AGENT,
    )

    private val sample: List<PaletteEntry> = listOf(insertPlan, newSession, completeCmd, subagent)

    @Test
    fun `只有根 slash 返回全部条目，按组分组并保留组顺序`() {
        val out = filterPalette(sample, "/")
        val groups = out.map { it.first }
        assertEquals(listOf(PaletteGroup.AGENT, PaletteGroup.SESSION, PaletteGroup.APP), groups)
        // 组内保持源顺序：plan 先于 subagent
        assertEquals(listOf(insertPlan, subagent), out.first().second)
        // 空组被丢弃
        assertTrue(filterPalette(listOf(insertPlan, newSession), "/").none { it.first == PaletteGroup.SESSION })
    }

    @Test
    fun `按 trigger 子串大小写不敏感过滤`() {
        val out = filterPalette(sample, "/PLA") // 触发 /plan
        assertEquals(1, out.size)
        assertEquals(PaletteGroup.AGENT, out.first().first)
        assertEquals(listOf(insertPlan), out.first().second)
    }

    @Test
    fun `match 命中跨组时各自返回`() {
        val out = filterPalette(sample, "/NEW") // 触发 /new-session (APP)
        assertEquals(listOf(PaletteGroup.APP), out.map { it.first })
        assertEquals(listOf(newSession), out.first().second)
    }

    @Test
    fun `无匹配时返回空列表`() {
        assertEquals(emptyList<Pair<PaletteGroup, List<PaletteEntry>>>(), filterPalette(sample, "/zzz"))
    }

    @Test
    fun `本地动作不会作为服务端命令误投递`() {
        // /new-session 是 LocalKind.NEW_SESSION；不应作为 Completable 出现
        assertTrue(sample.none { (it.command as? PaletteCommand.Completable)?.trigger == "/new-session" })
        assertTrue(sample.any { it.command is PaletteCommand.Local && (it.command as PaletteCommand.Local).kind == LocalKind.NEW_SESSION })
    }

    @Test
    fun `空 source 与空 query 都安全返回空结果`() {
        assertEquals(emptyList<Pair<PaletteGroup, List<PaletteEntry>>>(), filterPalette(emptyList(), ""))
        assertEquals(emptyList<Pair<PaletteGroup, List<PaletteEntry>>>(), filterPalette(emptyList(), "/"))
    }

    @Test
    fun `可插入命令与完整命令按类型可区分`() {
        assertTrue(insertPlan.command is PaletteCommand.Insertable)
        assertTrue(completeCmd.command is PaletteCommand.Completable)
        // Insertable.id 必须以前缀 insert: 开头
        assertEquals("insert:/plan", insertPlan.command.id)
        assertEquals("complete:/clear", completeCmd.command.id)
    }

    @Test
    fun `palette includes feedback insertable`() {
        assertTrue(
            DSH_PALETTE.any {
                val cmd = it.command as? PaletteCommand.Insertable
                cmd?.trigger == "/feedback"
            },
        )
    }

    @Test
    fun insertFeedbackCommand_prefixesOnce() {
        assertEquals("/feedback ", insertFeedbackCommand(""))
        assertEquals("/feedback 已有草稿", insertFeedbackCommand("已有草稿"))
        assertEquals("/feedback already", insertFeedbackCommand("/feedback already"))
        assertEquals("  /feedback ", insertFeedbackCommand("  "))
    }

    @Test
    fun searchDegraded_doesNotLookLikeNoMatchAlone() {
        assertEquals(false, searchShowsDegradedHint(SearchUiState.Idle))
        assertEquals(false, searchShowsDegradedHint(SearchUiState.Loading))
        assertEquals(false, searchShowsDegradedHint(SearchUiState.Empty()))
        assertEquals(false, searchShowsDegradedHint(SearchUiState.Error("down")))
        assertEquals(false, searchShowsDegradedHint(SearchUiState.Results("q", 2)))
        assertEquals(true, searchShowsDegradedHint(SearchUiState.Empty(degraded = true)))
        assertEquals(true, searchShowsDegradedHint(SearchUiState.Results("q", 2, degraded = true)))
        assertEquals(true, searchShowsDegradedHint(SearchUiState.Results("q", 0, degraded = true)))
    }

    // ===== 高权限命令：无论从哪个入口都不能绕过确认 =====

    @Test
    fun `高权限预设不再以 Completable 直发形式存在于 palette`() {
        val direct = DSH_PALETTE.mapNotNull { it.command as? PaletteCommand.Completable }
            .map { it.trigger }
            .filter { it.startsWith("/permission") }
        assertTrue("仍然存在可绕过确认的直发权限命令：$direct", direct.isEmpty())
    }

    @Test
    fun `权限只有一个本地入口，带预设参数输入也落到它（走 picker 二次确认）`() {
        val localPermissions = DSH_PALETTE.mapNotNull { it.command as? PaletteCommand.Local }
            .filter { it.kind == LocalKind.OPEN_PERMISSION_PICKER }
        assertEquals(listOf("/permission"), localPermissions.map { it.trigger })
        // 权限相关条目绝不能是可直接提交的服务端命令
        assertTrue(DSH_PALETTE.none { it.command !is PaletteCommand.Local && it.command.trigger.startsWith("/permission") })
        val hit = filterPalette(DSH_PALETTE, DANGER_FULL_ACCESS_COMMAND).flatMap { it.second }.map { it.command }
        assertEquals(localPermissions, hit)
    }

    @Test
    fun `高权限命令识别容忍空白与大小写`() {
        assertEquals(true, isDangerPermissionCommand("/permission danger-full-access"))
        assertEquals(true, isDangerPermissionCommand("  /PERMISSION   Danger-Full-Access  "))
        assertEquals(true, isDangerPermissionCommand("/permission\tdanger-full-access"))
    }

    @Test
    fun `普通权限与其它命令不需要高权限确认`() {
        assertEquals(false, isDangerPermissionCommand("/permission read-only"))
        assertEquals(false, isDangerPermissionCommand("/permission workspace-write"))
        assertEquals(false, isDangerPermissionCommand("/clear"))
        assertEquals(false, isDangerPermissionCommand(""))
        assertEquals(false, isDangerPermissionCommand("请解释一下 /permission danger-full-access 是什么"))
    }

    @Test
    fun `normalizeSlashCommand 压缩空白并小写`() {
        assertEquals("/a b", normalizeSlashCommand("  /A   B "))
        assertEquals("/a b", normalizeSlashCommand("/a\n\tb"))
    }

    @Test
    fun `中文名也能匹配`() {
        assertEquals(listOf(completeCmd), filterPalette(sample, "/上下文").flatMap { it.second })
    }

    @Test
    fun `已带参数的输入仍命中原命令`() {
        assertEquals(listOf(insertPlan), filterPalette(sample, "/plan 重构设置页").flatMap { it.second })
    }

    @Test
    fun `正式面板里每条命令都有独立的中文名`() {
        assertTrue(DSH_PALETTE.all { it.command.title != it.command.trigger })
    }
}
