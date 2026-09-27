package dev.deeplinks.native

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshS
import dev.deeplinks.core.DshType
import dev.deeplinks.core.Host
import dev.deeplinks.native.ui.DshListActionRow
import dev.deeplinks.native.ui.DshListNote
import dev.deeplinks.native.ui.DshListRetry
import dev.deeplinks.native.ui.DshListRow
import dev.deeplinks.native.ui.DshListSection
import dev.deeplinks.native.ui.DshListTrailing
import dev.deeplinks.native.ui.DshSheet
import dev.deeplinks.native.ui.DshSheetPrimaryButton
import dev.deeplinks.native.ui.DshTextField
import dev.deeplinks.native.util.compactTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val MODEL_ID_PATTERN = Regex("^[\\x21-\\x7E]{1,200}$")
private val LEGAL_API_KEY = Regex("^[\\x21-\\x7E]+$")

/** 与插件 / 桌面同规则的本地预检；插件仍会复核。 */
internal fun apiKeyLooksValid(raw: String): Boolean {
    val value = raw.trim()
    if (value.isEmpty() || !LEGAL_API_KEY.matches(value)) return false
    if (Regex("^[A-Z][A-Z0-9_]*=[^=]").containsMatchIn(value)) return false
    val first = value.first()
    return !((first == '"' || first == '\'' || first == '`') && value.length > 1 && value.endsWith(first))
}

internal fun modelIdLooksValid(raw: String): Boolean = MODEL_ID_PATTERN.matches(raw.trim())

internal fun isPluginTooOld(error: Throwable): Boolean =
    generateSequence(error) { it.cause }.any { it.message.orEmpty().contains("mobile endpoint not found") }

internal fun formatWallets(wallets: List<MobileWallet>): String? {
    if (wallets.isEmpty()) return null
    return wallets.joinToString(" · ") { w ->
        when (w.currency.uppercase()) {
            "CNY" -> "¥${w.balance}"
            "USD" -> "$${w.balance}"
            else -> "${w.balance} ${w.currency}"
        }
    }
}

/** 默认模型的展示名：供应商显示名 · 模型显示名（目录里找不到时退回原始 id）。 */
/** 默认模型行的取值：只放模型名（优先展示名）——「供应商 · 模型」在行尾取值宽度里会被截断。 */
internal fun defaultModelName(groups: List<MobileModelGroup>, provider: String?, model: String?): String? {
    if (model.isNullOrBlank()) return null
    val option = groups.firstOrNull { it.provider == provider }?.models?.firstOrNull { it.id == model }
    return option?.name ?: model
}

private sealed interface ModelsSheet {
    data class ApiKey(val row: MobileProviderRow) : ModelsSheet
    data class AddModel(val row: MobileProviderRow) : ModelsSheet
    data class Discover(val row: MobileProviderRow, val candidates: List<MobileModelDraft>) : ModelsSheet
    data object AddProvider : ModelsSheet
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelsSettingsPage(
    host: Host?,
    viewModel: SettingsViewModel,
    appSettings: AppSettings,
    llmGroups: List<MobileModelGroup>,
    llmLoading: Boolean,
    llmError: String?,
    onReloadCatalog: () -> Unit,
    savingNs: String?,
    saveErrors: Map<String, String>,
    onSave: (ns: String, patch: org.json.JSONObject, onSuccess: () -> Unit) -> Unit,
    onOpenDevices: () -> Unit,
) {
    val s = DshS
    val client = viewModel.client
    val scope = rememberCoroutineScope()
    var balance by viewModel.balance
    var balanceLoading by viewModel.balanceLoading
    var balanceError by viewModel.balanceError
    var directory by viewModel.providers
    var directoryLoading by viewModel.providersLoading
    var directoryError by viewModel.providersError
    var unsupported by viewModel.providersUnsupported
    var reloadEpoch by remember { mutableStateOf(0) }
    var expanded by viewModel.expandedProviders
    var sheet by remember { mutableStateOf<ModelsSheet?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var sheetError by remember { mutableStateOf<String?>(null) }
    var rowErrors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var pendingRemoval by remember { mutableStateOf<Pair<MobileProviderRow, MobileModelOption>?>(null) }
    var showDefaultPicker by remember { mutableStateOf(false) }
    val locale = if (appSettings.language.startsWith("en")) "en-US" else "zh-CN"

    LaunchedEffect(host, reloadEpoch) {
        if (host == null || client == null) {
            balanceLoading = false
            directoryLoading = false
            return@LaunchedEffect
        }
        balanceLoading = true
        directoryLoading = true
        launch(Dispatchers.IO) {
            val result = runCatching { client.getBalance(locale) }
            withContext(Dispatchers.Main) {
                result.onSuccess { balance = it; balanceError = null }
                    .onFailure { e ->
                        balanceError = if (isPluginTooOld(e)) s.pluginTooOld else e.message?.takeIf { it.isNotBlank() } ?: s.loadFailed
                    }
                balanceLoading = false
            }
        }
        launch(Dispatchers.IO) {
            val result = runCatching { client.getProviders() }
            withContext(Dispatchers.Main) {
                result.onSuccess { directory = it; directoryError = null; unsupported = false }
                    .onFailure { e ->
                        if (isPluginTooOld(e)) unsupported = true
                        else directoryError = e.message?.takeIf { it.isNotBlank() } ?: s.loadFailed
                    }
                directoryLoading = false
            }
        }
    }

    /** 在 IO 上跑一次写操作；成功刷新目录与模型目录，失败把原因留给 sheet 或所在卡片。 */
    fun runWrite(key: String, provider: String?, inSheet: Boolean, op: (MobileApiClient) -> MobileProviderDirectory) {
        val c = client ?: return
        if (busy != null) return
        busy = key
        sheetError = null
        provider?.let { rowErrors = rowErrors - it }
        scope.launch(Dispatchers.IO) {
            val result = runCatching { op(c) }
            withContext(Dispatchers.Main) {
                busy = null
                result.onSuccess {
                    directory = it
                    sheet = null
                    onReloadCatalog()
                }.onFailure { e ->
                    val message = if (isPluginTooOld(e)) s.pluginTooOld else e.message?.takeIf { it.isNotBlank() } ?: s.saveFailed
                    if (inSheet) sheetError = message
                    else if (provider != null) rowErrors = rowErrors + (provider to message)
                }
            }
        }
    }

    fun discover(row: MobileProviderRow) {
        val c = client ?: return
        if (busy != null) return
        busy = "discover:${row.provider}"
        rowErrors = rowErrors - row.provider
        scope.launch(Dispatchers.IO) {
            val result = runCatching { c.discoverProviderModels(row.provider) }
            withContext(Dispatchers.Main) {
                busy = null
                result.onSuccess { found ->
                    val known = row.models.map { it.id }.toSet()
                    sheetError = null
                    sheet = ModelsSheet.Discover(row, found.filter { it.id !in known })
                }.onFailure { e ->
                    rowErrors = rowErrors + (row.provider to (e.message?.takeIf { it.isNotBlank() } ?: s.loadFailed))
                }
            }
        }
    }

    // ── 默认模型 ──
    val defaultLabel = defaultModelName(llmGroups, appSettings.defaultModelProvider, appSettings.defaultModel)
    DshListSection(footer = s.defaultModelSettingDesc) {
        DshListRow(
            title = s.defaultModelSetting,
            icon = SparkleOutline16,
            value = when {
                savingNs == "agent-default-model" -> s.saving
                else -> defaultLabel ?: s.noneSelected
            },
            error = saveErrors["agent-default-model"],
            onClick = if (host != null) ({ showDefaultPicker = true }) else null,
        )
    }

    // ── 余额 ──
    DshListSection(
        header = s.sectionBalance,
        headerAction = when {
            host == null -> s.addDevice
            unsupported -> null
            else -> s.refreshBalance
        },
        headerActionEnabled = host == null || !(balanceLoading || directoryLoading),
        onHeaderAction = { if (host == null) onOpenDevices() else reloadEpoch += 1 },
    ) {
        val b = balance
        when {
            host == null -> DshListNote(s.balanceUnavailable)
            // 插件过旧是提示不是故障：与下方供应商分组同一句、同一灰字样式
            unsupported -> DshListNote(s.pluginTooOld)
            b == null && balanceLoading -> DshListNote(s.querying)
            b == null -> DshListNote(balanceError ?: s.loadFailed, error = true)
            b.status == "ready" -> {
                DshListRow(title = s.balanceTopUp, icon = WalletOutline16, value = formatWallets(b.wallets) ?: "—")
                DshListRow(title = s.balanceBonus, icon = GiftOutline16, value = formatWallets(b.bonusWallets) ?: "—")
            }
            b.status == "signed-out" -> DshListNote(s.balanceSignedOut)
            b.status == "failed" -> DshListNote(s.balanceFailed)
            else -> DshListNote(s.balanceHostTooOld)
        }
    }

    // ── 供应商 ──
    val dir = directory
    val canWrite = host != null && dir?.writable == true && !unsupported
    val rows: List<MobileProviderRow> = when {
        unsupported -> llmGroups.map { g ->
            MobileProviderRow(
                provider = g.provider,
                displayName = g.displayName,
                kind = "catalog",
                active = true,
                custom = false,
                keyRef = null,
                credential = null,
                models = g.models,
                modelsEditable = false,
                canDiscover = false,
            )
        }
        else -> dir?.providers.orEmpty()
    }
    val directoryNote: (@Composable () -> Unit)? = when {
        host == null -> ({ DshListNote(s.notConnectedCannotSave) })
        unsupported -> ({ DshListNote(s.pluginTooOld) })
        dir == null && directoryLoading -> ({ DshListNote(s.loadingModelList) })
        dir == null && directoryError != null -> ({ DshListRetry(directoryError ?: s.loadFailed) { reloadEpoch += 1 } })
        rows.isEmpty() && !llmLoading -> ({ DshListNote(llmError ?: s.noAvailableModels) })
        else -> null
    }
    val providersFooter = if (!unsupported && host != null) s.customProviderDesktopHint else null
    // 每个供应商一张卡片；分区标题（带「添加供应商」）挂在第一张上，桌面端提示挂在最后一张下
    val providerCards = rows.size + if (directoryNote != null) 1 else 0
    var cardIndex = 0
    fun nextCard(): Pair<Boolean, Boolean> = Pair(cardIndex == 0, cardIndex == providerCards - 1).also { cardIndex += 1 }
    if (providerCards == 0) {
        DshListSection(
            header = s.sectionProviders,
            headerAction = if (canWrite) s.addProvider else null,
            headerActionEnabled = busy == null,
            onHeaderAction = if (canWrite) ({ sheetError = null; sheet = ModelsSheet.AddProvider }) else null,
            footer = providersFooter,
        ) {}
    }
    if (directoryNote != null) {
        val (first, last) = nextCard()
        DshListSection(
            header = if (first) s.sectionProviders else null,
            footer = if (last) providersFooter else null,
        ) { directoryNote() }
    }
    rows.forEach { row ->
        val (first, last) = nextCard()
        DshListSection(
            header = if (first) s.sectionProviders else null,
            headerAction = if (first && canWrite) s.addProvider else null,
            headerActionEnabled = busy == null,
            onHeaderAction = if (first && canWrite) ({ sheetError = null; sheet = ModelsSheet.AddProvider }) else null,
            footer = if (last) providersFooter else null,
        ) {
            ProviderRows(
                row = row,
                expanded = expanded.contains(row.provider),
                writable = canWrite,
                busy = busy,
                error = rowErrors[row.provider],
                onToggle = {
                    expanded = if (expanded.contains(row.provider)) expanded - row.provider else expanded + row.provider
                },
                onSetKey = { sheetError = null; sheet = ModelsSheet.ApiKey(row) },
                onAddModel = { sheetError = null; sheet = ModelsSheet.AddModel(row) },
                onDiscover = { discover(row) },
                onRemoveModel = { model -> pendingRemoval = row to model },
            )
        }
    }

    // ── 弹层 ──
    if (showDefaultPicker) {
        val catalog = if (llmGroups.isEmpty() && llmLoading) null else MobileModelCatalog(
            currentProvider = appSettings.defaultModelProvider,
            currentModel = appSettings.defaultModel,
            currentReasoningEffort = appSettings.defaultReasoningEffort,
            groups = llmGroups,
        )
        ModelPickerSheet(
            catalog = catalog,
            loading = llmLoading,
            error = llmError ?: saveErrors["agent-default-model"],
            onRetry = onReloadCatalog,
            onDismiss = { showDefaultPicker = false },
            onSelect = { provider, model, effort ->
                val patch = org.json.JSONObject().put("provider", provider).put("model", model)
                if (!effort.isNullOrBlank()) patch.put("reasoningEffort", effort)
                onSave("agent-default-model", patch) {}
            },
        )
    }

    when (val current = sheet) {
        is ModelsSheet.ApiKey -> ApiKeySheet(
            title = current.row.displayName,
            keyRef = current.row.keyRef,
            busy = busy != null,
            error = sheetError,
            onDismiss = { sheet = null },
            onSubmit = { key ->
                runWrite("key:${current.row.provider}", current.row.provider, inSheet = true) {
                    it.setProviderApiKey(current.row.provider, key)
                }
            },
        )
        is ModelsSheet.AddModel -> AddModelSheet(
            title = current.row.displayName,
            existingIds = current.row.models.map { it.id }.toSet(),
            busy = busy != null,
            error = sheetError,
            onDismiss = { sheet = null },
            onSubmit = { draft ->
                runWrite("models:${current.row.provider}", current.row.provider, inSheet = true) {
                    it.editProviderModels(current.row.provider, add = listOf(draft))
                }
            },
        )
        is ModelsSheet.Discover -> DiscoverModelsSheet(
            title = current.row.displayName,
            candidates = current.candidates,
            busy = busy != null,
            error = sheetError,
            onDismiss = { sheet = null },
            onSubmit = { picked ->
                runWrite("models:${current.row.provider}", current.row.provider, inSheet = true) {
                    it.editProviderModels(current.row.provider, add = picked)
                }
            },
        )
        ModelsSheet.AddProvider -> AddProviderSheet(
            addable = dir?.addable.orEmpty(),
            busy = busy != null,
            error = sheetError,
            onDismiss = { sheet = null },
            onSubmit = { provider, key ->
                runWrite("add:$provider", null, inSheet = true) {
                    it.addCatalogProvider(provider, key)
                }
                expanded = expanded + provider
            },
        )
        null -> Unit
    }

    pendingRemoval?.let { (row, model) ->
        DshConfirmDialog(
            title = s.removeModel,
            message = s.removeModelConfirm.format(row.displayName, model.name ?: model.id),
            confirmLabel = s.removeModel,
            danger = true,
            onDismiss = { pendingRemoval = null },
            onConfirm = {
                pendingRemoval = null
                runWrite("models:${row.provider}", row.provider, inSheet = false) {
                    it.editProviderModels(row.provider, remove = listOf(model.id))
                }
            },
        )
    }
}

/**
 * 一个供应商卡片里的行：表头（状态点 · 名称 · 标签 · 模型数，点按展开）+ 展开后的
 * API Key、模型列表与操作行。放在 [DshListSection] 里，分隔线由卡片画。
 */
@Composable
private fun ProviderRows(
    row: MobileProviderRow,
    expanded: Boolean,
    writable: Boolean,
    busy: String?,
    error: String?,
    onToggle: () -> Unit,
    onSetKey: () -> Unit,
    onAddModel: () -> Unit,
    onDiscover: () -> Unit,
    onRemoveModel: (MobileModelOption) -> Unit,
) {
    val s = DshS
    val keyConfigured = row.credential?.configured == true
    val dotColor = when (row.kind) {
        "api" -> if (keyConfigured) Dsh.success else Dsh.error
        "account" -> Dsh.success
        else -> null
    }
    val tags = buildList {
        when {
            row.kind == "account" -> add(s.providerAccountTag)
            row.custom -> add(s.providerCustomTag)
        }
        if (!row.active) add(s.providerInactiveTag)
    }
    val chevronTurn by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(motionDuration(DshDuration.fast), easing = DshEasing.out),
        label = "providerChevron",
    )
    DshListRow(
        title = row.displayName,
        subtitle = tags.joinToString(" · ").ifBlank { null },
        value = s.providerModelCount.format(row.models.size),
        onClick = onToggle,
        trailing = DshListTrailing.None,
        leading = dotColor?.let { color ->
            {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(color)
                        .semantics { contentDescription = if (keyConfigured || row.kind == "account") s.apiKeyConfigured else s.apiKeyMissing },
                )
            }
        },
        trailingContent = {
            Icon(
                ChevronRightOutline14,
                contentDescription = null,
                tint = Dsh.labelTertiary,
                modifier = Modifier.size(16.dp).rotate(chevronTurn),
            )
        },
    )
    if (!expanded) return

    if (row.kind == "api") {
        val cred = row.credential
        val canSetKey = writable && cred?.writable != false
        DshListRow(
            title = s.apiKey,
            icon = KeyOutline16,
            iconTint = if (keyConfigured || cred?.writable == false) Dsh.brand400 else Dsh.error,
            subtitle = row.keyRef?.takeIf { keyConfigured },
            value = when {
                cred?.writable == false -> s.apiKeyReadOnly
                !canSetKey -> if (keyConfigured) s.apiKeyConfigured else s.apiKeyMissing
                keyConfigured -> s.replaceApiKey
                else -> s.setApiKey
            },
            enabled = busy == null || !canSetKey,
            onClick = if (canSetKey && busy == null) onSetKey else null,
        )
    } else if (row.kind == "account") {
        DshListNote(s.accountProviderHint)
    }

    val canRemove = writable && row.modelsEditable && row.models.size > 1 && busy == null
    row.models.forEach { model ->
        DshListRow(
            title = model.name ?: model.id,
            subtitle = model.id.takeIf { model.name != null && model.name != model.id },
            value = model.contextWindow?.let { s.contextSize.format(compactTokens(it)) },
            trailingContent = if (writable && row.modelsEditable) {
                { RemoveModelButton(model, enabled = canRemove) { onRemoveModel(model) } }
            } else {
                null
            },
        )
    }

    when {
        row.kind != "api" -> Unit
        !row.modelsEditable -> DshListNote(s.modelsInheritedHint)
        writable -> {
            DshListActionRow(label = s.addModel, icon = PlusOutline16, enabled = busy == null, onClick = onAddModel)
            if (row.canDiscover) {
                DshListActionRow(
                    label = if (busy == "discover:${row.provider}") s.fetchingModels else s.fetchModels,
                    icon = RefreshOutline14,
                    enabled = busy == null,
                    onClick = onDiscover,
                )
            }
        }
    }
    if (error != null) DshListNote(error, error = true)
}

@Composable
private fun RemoveModelButton(model: MobileModelOption, enabled: Boolean, onClick: () -> Unit) {
    val s = DshS
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .alpha(if (enabled) 1f else 0.35f)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "${s.removeModel} ${model.name ?: model.id}" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(TrashOutline16, contentDescription = null, tint = Dsh.labelTertiary, modifier = Modifier.size(16.dp))
    }
}

/** 面板里的说明小字（卡片外）。 */
@Composable
private fun SheetHint(text: String) {
    Text(
        text,
        color = Dsh.labelTertiary,
        style = DshType.captionRelaxed,
        modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp),
    )
}

@Composable
private fun SheetError(error: String?) {
    if (error == null) return
    Text(
        error,
        color = Dsh.error,
        style = DshType.captionRelaxed,
        modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp),
    )
}

@Composable
private fun ApiKeySheet(
    title: String,
    keyRef: String?,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    val s = DshS
    var key by remember { mutableStateOf("") }
    val valid = apiKeyLooksValid(key)
    DshSheet(
        onDismiss = onDismiss,
        title = title,
        subtitle = listOfNotNull(s.apiKey, keyRef).joinToString(" · "),
        skipPartiallyExpanded = true,
    ) {
        Spacer(Modifier.height(12.dp))
        DshTextField(
            value = key,
            onValueChange = { key = it },
            placeholder = s.apiKeyPlaceholder,
            visualTransformation = PasswordVisualTransformation(),
            isError = key.isNotEmpty() && !valid,
            contentDescription = s.apiKey,
        )
        SheetHint(s.apiKeyStoredOnHost)
        SheetError(error)
        DshSheetPrimaryButton(label = if (busy) s.saving else s.save, enabled = valid && !busy) { onSubmit(key.trim()) }
    }
}

@Composable
private fun AddModelSheet(
    title: String,
    existingIds: Set<String>,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSubmit: (MobileModelDraft) -> Unit,
) {
    val s = DshS
    var id by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var context by remember { mutableStateOf("") }
    val idValid = modelIdLooksValid(id) && id.trim() !in existingIds
    DshSheet(onDismiss = onDismiss, title = s.addModel, subtitle = title, skipPartiallyExpanded = true) {
        Spacer(Modifier.height(12.dp))
        DshTextField(
            value = id,
            onValueChange = { id = it },
            label = s.modelIdLabel,
            placeholder = "glm-5.3",
            isError = id.isNotEmpty() && !idValid,
            errorText = s.modelIdInvalid,
        )
        Spacer(Modifier.height(12.dp))
        DshTextField(value = name, onValueChange = { name = it }, label = s.modelNameLabel)
        Spacer(Modifier.height(12.dp))
        DshTextField(
            value = context,
            onValueChange = { next -> context = next.filter { it.isDigit() }.take(9) },
            label = s.modelContextLabel,
            placeholder = "128000",
        )
        SheetError(error)
        DshSheetPrimaryButton(label = if (busy) s.saving else s.add, enabled = idValid && !busy) {
            onSubmit(
                MobileModelDraft(
                    id = id.trim(),
                    name = name.trim().ifBlank { null },
                    contextWindow = context.toLongOrNull()?.takeIf { it > 0 },
                ),
            )
        }
    }
}

@Composable
private fun DiscoverModelsSheet(
    title: String,
    candidates: List<MobileModelDraft>,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSubmit: (List<MobileModelDraft>) -> Unit,
) {
    val s = DshS
    var query by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<Set<String>>(emptySet()) }
    val visible = remember(candidates, query) {
        if (query.isBlank()) candidates
        else candidates.filter { it.id.contains(query, ignoreCase = true) || it.name?.contains(query, ignoreCase = true) == true }
    }
    DshSheet(onDismiss = onDismiss, title = s.discoverTitle, subtitle = title, skipPartiallyExpanded = true) {
        if (candidates.isEmpty()) {
            DshListSection { DshListNote(s.discoverEmpty) }
            return@DshSheet
        }
        if (candidates.size > 8) {
            Spacer(Modifier.height(12.dp))
            SheetSearchField(value = query, onValueChange = { query = it }, placeholder = s.searchModelProvider)
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 400.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            DshListSection {
                visible.forEach { model ->
                    val checked = model.id in picked
                    DshListRow(
                        title = model.name ?: model.id,
                        subtitle = model.id.takeIf { model.name != null && model.name != model.id },
                        value = model.contextWindow?.let { s.contextSize.format(compactTokens(it)) },
                        onClick = { picked = if (checked) picked - model.id else picked + model.id },
                        trailing = DshListTrailing.None,
                        leading = {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = null,
                                colors = CheckboxDefaults.colors(
                                    checkedColor = Dsh.brand400,
                                    uncheckedColor = Dsh.labelTertiary,
                                    checkmarkColor = Dsh.onBrand,
                                ),
                            )
                        },
                    )
                }
            }
        }
        SheetError(error)
        DshSheetPrimaryButton(
            label = if (busy) s.saving else s.addSelectedModels.format(picked.size),
            enabled = picked.isNotEmpty() && !busy,
        ) {
            onSubmit(candidates.filter { it.id in picked })
        }
    }
}

@Composable
private fun AddProviderSheet(
    addable: List<MobileAddableProvider>,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSubmit: (provider: String, apiKey: String?) -> Unit,
) {
    val s = DshS
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<MobileAddableProvider?>(null) }
    var key by remember { mutableStateOf("") }
    val keyOk = key.isBlank() || apiKeyLooksValid(key)
    val choice = selected
    DshSheet(
        onDismiss = onDismiss,
        title = choice?.displayName ?: s.addProvider,
        subtitle = if (choice == null) s.customProviderDesktopHint else null,
        skipPartiallyExpanded = true,
    ) {
        if (choice == null) {
            if (addable.isEmpty()) {
                DshListSection { DshListNote(s.addProviderEmpty) }
                return@DshSheet
            }
            val visible = if (query.isBlank()) addable else addable.filter {
                it.displayName.contains(query, ignoreCase = true) || it.provider.contains(query, ignoreCase = true)
            }
            if (addable.size > 8) {
                Spacer(Modifier.height(12.dp))
                SheetSearchField(value = query, onValueChange = { query = it }, placeholder = s.searchModelProvider)
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                DshListSection {
                    visible.forEach { option ->
                        DshListRow(
                            title = option.displayName,
                            value = option.provider.takeIf { it != option.displayName },
                            onClick = { selected = option },
                        )
                    }
                }
            }
            return@DshSheet
        }
        Spacer(Modifier.height(12.dp))
        DshTextField(
            value = key,
            onValueChange = { key = it },
            label = s.addProviderKeyOptional,
            placeholder = s.apiKeyPlaceholder,
            visualTransformation = PasswordVisualTransformation(),
            isError = !keyOk,
        )
        SheetHint(s.apiKeyStoredOnHost)
        SheetError(error)
        DshSheetPrimaryButton(label = if (busy) s.saving else s.add, enabled = keyOk && !busy) {
            onSubmit(choice.provider, key.trim().ifBlank { null })
        }
    }
}
