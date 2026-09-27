package dev.deeplinks.native

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshS
import dev.deeplinks.core.DshType
import dev.deeplinks.core.Host
import dev.deeplinks.core.dshRipple
import dev.deeplinks.native.ui.DshSheetGrabber
import dev.deeplinks.native.ui.DshTag
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
internal fun defaultModelLabel(groups: List<MobileModelGroup>, provider: String?, model: String?): String? {
    if (model.isNullOrBlank()) return null
    val group = groups.firstOrNull { it.provider == provider }
    val option = group?.models?.firstOrNull { it.id == model }
    return listOfNotNull(group?.displayName ?: provider, option?.name ?: model).joinToString(" · ")
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
    val defaultLabel = defaultModelLabel(llmGroups, appSettings.defaultModelProvider, appSettings.defaultModel)
    DshSettingsGroup {
        ModelsValueRow(
            title = s.defaultModelSetting,
            description = s.defaultModelSettingDesc,
            value = when {
                savingNs == "agent-default-model" -> s.saving
                else -> defaultLabel ?: s.noneSelected
            },
            error = saveErrors["agent-default-model"],
            onClick = if (host != null) ({ showDefaultPicker = true }) else null,
        )
    }

    // ── 余额 ──
    SettingsSection(
        s.sectionBalance,
        actionLabel = if (host == null) s.addDevice else s.refreshBalance,
        actionEnabled = host == null || !(balanceLoading || directoryLoading),
        onAction = { if (host == null) onOpenDevices() else reloadEpoch += 1 },
    )
    DshSettingsGroup {
        val b = balance
        when {
            host == null -> ModelsNote(s.balanceUnavailable)
            b == null && balanceLoading -> ModelsNote(s.querying)
            b == null -> ModelsNote(balanceError ?: s.loadFailed, error = true)
            b.status == "ready" -> {
                ModelsValueRow(title = s.balanceTopUp, value = formatWallets(b.wallets) ?: "—")
                DshSettingsDivider()
                ModelsValueRow(title = s.balanceBonus, value = formatWallets(b.bonusWallets) ?: "—")
            }
            b.status == "signed-out" -> ModelsNote(s.balanceSignedOut)
            b.status == "failed" -> ModelsNote(s.balanceFailed)
            else -> ModelsNote(s.balanceHostTooOld)
        }
    }

    // ── 供应商 ──
    val dir = directory
    val canWrite = host != null && dir?.writable == true && !unsupported
    SettingsSection(
        s.sectionProviders,
        actionLabel = if (canWrite) s.addProvider else null,
        actionEnabled = busy == null,
        onAction = if (canWrite) ({ sheetError = null; sheet = ModelsSheet.AddProvider }) else null,
    )
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
    when {
        host == null -> ModelsNote(s.notConnectedCannotSave)
        unsupported -> ModelsNote(s.pluginTooOld)
        dir == null && directoryLoading -> ModelsNote(s.loadingModelList)
        dir == null && directoryError != null -> SettingsLoadRetry(message = directoryError ?: s.loadFailed, onRetry = { reloadEpoch += 1 })
        rows.isEmpty() && !llmLoading -> ModelsNote(llmError ?: s.noAvailableModels)
        else -> Unit
    }
    rows.forEach { row ->
        ProviderCard(
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
        Spacer(Modifier.height(8.dp))
    }
    if (!unsupported && host != null) {
        ModelsNote(s.customProviderDesktopHint)
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
        SettingsConfirmDialog(
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

@Composable
private fun ModelsNote(text: String, error: Boolean = false) {
    Text(
        text,
        color = if (error) Dsh.error else Dsh.labelTertiary,
        style = DshType.caption,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

/** 标题 + 右侧取值的设置行；onClick 为空时只读。 */
@Composable
private fun ModelsValueRow(
    title: String,
    value: String,
    description: String? = null,
    error: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val s = DshS
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val base = Modifier
        .fillMaxWidth()
        .heightIn(min = 52.dp)
        .clip(RoundedCornerShape(DshRadius.md))
        .background(if (onClick != null && pressed) Dsh.pressed else Color.Transparent)
    Row(
        modifier = (if (onClick != null) base.clickable(interactionSource = interaction, indication = dshRipple(), onClick = onClick) else base)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Dsh.labelPrimary, style = DshType.body)
            if (description != null) {
                Spacer(Modifier.height(2.dp))
                Text(description, color = Dsh.labelTertiary, style = DshType.caption)
            }
            if (error != null) {
                Spacer(Modifier.height(2.dp))
                Text(s.saveFailedWithMessage.format(error), color = Dsh.error, style = DshType.caption)
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            color = if (onClick != null) Dsh.labelSecondary else Dsh.labelPrimary,
            style = DshType.body,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (onClick != null) {
            Spacer(Modifier.width(4.dp))
            Icon(ChevronRightOutline14, contentDescription = null, tint = Dsh.labelTertiary, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun ProviderCard(
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DshRadius.lg))
            .background(Dsh.bgSubtle),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clickable(onClick = onToggle)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (dotColor != null) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                        .semantics { contentDescription = if (keyConfigured || row.kind == "account") s.apiKeyConfigured else s.apiKeyMissing },
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(
                row.displayName,
                color = Dsh.labelPrimary,
                style = DshType.bodyStrong,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            when {
                row.kind == "account" -> ProviderTag(s.providerAccountTag)
                row.custom -> ProviderTag(s.providerCustomTag)
            }
            if (!row.active) ProviderTag(s.providerInactiveTag)
            Spacer(Modifier.weight(1f))
            Text(s.providerModelCount.format(row.models.size), color = Dsh.labelTertiary, style = DshType.caption)
            Spacer(Modifier.width(6.dp))
            Icon(
                if (expanded) ChevronDownOutline14 else ChevronRightOutline14,
                contentDescription = null,
                tint = Dsh.labelTertiary,
                modifier = Modifier.size(16.dp),
            )
        }
        if (!expanded) return@Column
        HorizontalDivider(color = Dsh.borderSubtle, thickness = 0.5.dp)
        if (row.kind == "api") {
            val cred = row.credential
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(s.apiKey, color = Dsh.labelPrimary, style = DshType.body)
                    Text(
                        when {
                            cred?.writable == false -> s.apiKeyReadOnly
                            keyConfigured -> listOfNotNull(s.apiKeyConfigured, row.keyRef).joinToString(" · ")
                            else -> s.apiKeyMissing
                        },
                        color = if (keyConfigured || cred?.writable == false) Dsh.labelTertiary else Dsh.error,
                        style = DshType.caption,
                    )
                }
                if (writable && cred?.writable != false) {
                    ProviderAction(
                        label = if (keyConfigured) s.replaceApiKey else s.setApiKey,
                        enabled = busy == null,
                        onClick = onSetKey,
                    )
                }
            }
            HorizontalDivider(color = Dsh.borderSubtle, thickness = 0.5.dp, modifier = Modifier.padding(start = 14.dp))
        } else if (row.kind == "account") {
            Text(
                s.accountProviderHint,
                color = Dsh.labelTertiary,
                style = DshType.caption,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
        val canRemove = writable && row.modelsEditable && row.models.size > 1 && busy == null
        row.models.forEach { model ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        model.name ?: model.id,
                        color = Dsh.labelSecondary,
                        style = DshType.body,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (model.name != null && model.name != model.id) {
                        Text(model.id, color = Dsh.labelTertiary, style = DshType.microRelaxed, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                model.contextWindow?.let {
                    Text(s.contextSize.format(compactTokens(it)), color = Dsh.labelTertiary, style = DshType.caption)
                }
                if (writable && row.modelsEditable) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .alpha(if (canRemove) 1f else 0.35f)
                            .clickable(enabled = canRemove) { onRemoveModel(model) }
                            .semantics {
                                role = Role.Button
                                contentDescription = "${s.removeModel} ${model.name ?: model.id}"
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(TrashOutline16, contentDescription = null, tint = Dsh.labelTertiary, modifier = Modifier.size(16.dp))
                    }
                } else {
                    Spacer(Modifier.width(10.dp))
                }
            }
        }
        when {
            row.kind != "api" -> Spacer(Modifier.height(6.dp))
            !row.modelsEditable -> Text(
                s.modelsInheritedHint,
                color = Dsh.labelTertiary,
                style = DshType.caption,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
            writable -> Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ProviderAction(label = s.addModel, icon = PlusOutline16, enabled = busy == null, onClick = onAddModel)
                if (row.canDiscover) {
                    ProviderAction(
                        label = if (busy == "discover:${row.provider}") s.fetchingModels else s.fetchModels,
                        icon = RefreshOutline14,
                        enabled = busy == null,
                        onClick = onDiscover,
                    )
                }
            }
        }
        if (error != null) {
            Text(
                error,
                color = Dsh.error,
                style = DshType.caption,
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp),
            )
        }
    }
}

@Composable
private fun ProviderTag(text: String) {
    Spacer(Modifier.width(6.dp))
    DshTag(text = text, color = Dsh.bgCard, contentColor = Dsh.labelTertiary)
}

@Composable
private fun ProviderAction(
    label: String,
    enabled: Boolean,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(DshRadius.sm))
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { role = Role.Button }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (enabled) Dsh.brand400 else Dsh.labelTertiary
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(label, color = tint, style = DshType.label, fontWeight = FontWeight(500))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelsSheetFrame(
    title: String,
    subtitle: String? = null,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Dsh.bgCard,
        contentColor = Dsh.labelPrimary,
        shape = DshSheetShape,
        scrimColor = Dsh.bgOverlay,
        dragHandle = null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
        ) {
            DshSheetGrabber()
            Text(title, color = Dsh.labelPrimary, style = DshType.headline, fontWeight = FontWeight(600))
            if (subtitle != null) {
                Spacer(Modifier.height(4.dp))
                Text(subtitle, color = Dsh.labelTertiary, style = DshType.caption)
            }
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
private fun SheetPrimaryButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(DshRadius.md))
            .background(if (enabled) Dsh.brand400 else Dsh.bgSubtle)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = label
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (enabled) Dsh.onBrand else Dsh.labelTertiary, style = DshType.body, fontWeight = FontWeight(500))
    }
}

@Composable
private fun SheetError(error: String?) {
    if (error == null) return
    Spacer(Modifier.height(8.dp))
    Text(error, color = Dsh.error, style = DshType.caption)
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
    ModelsSheetFrame(title = title, subtitle = listOfNotNull(s.apiKey, keyRef).joinToString(" · "), onDismiss = onDismiss) {
        DshTextField(
            value = key,
            onValueChange = { key = it },
            placeholder = s.apiKeyPlaceholder,
            visualTransformation = PasswordVisualTransformation(),
            isError = key.isNotEmpty() && !valid,
            contentDescription = s.apiKey,
        )
        Spacer(Modifier.height(8.dp))
        Text(s.apiKeyStoredOnHost, color = Dsh.labelTertiary, style = DshType.caption)
        SheetError(error)
        Spacer(Modifier.height(14.dp))
        SheetPrimaryButton(label = if (busy) s.saving else s.save, enabled = valid && !busy) { onSubmit(key.trim()) }
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
    ModelsSheetFrame(title = s.addModel, subtitle = title, onDismiss = onDismiss) {
        DshTextField(
            value = id,
            onValueChange = { id = it },
            label = s.modelIdLabel,
            placeholder = "glm-5.3",
            isError = id.isNotEmpty() && !idValid,
            errorText = s.modelIdInvalid,
        )
        Spacer(Modifier.height(10.dp))
        DshTextField(value = name, onValueChange = { name = it }, label = s.modelNameLabel)
        Spacer(Modifier.height(10.dp))
        DshTextField(
            value = context,
            onValueChange = { next -> context = next.filter { it.isDigit() }.take(9) },
            label = s.modelContextLabel,
            placeholder = "128000",
        )
        SheetError(error)
        Spacer(Modifier.height(14.dp))
        SheetPrimaryButton(label = if (busy) s.saving else s.add, enabled = idValid && !busy) {
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
    ModelsSheetFrame(title = s.discoverTitle, subtitle = title, onDismiss = onDismiss) {
        if (candidates.isEmpty()) {
            Text(s.discoverEmpty, color = Dsh.labelTertiary, style = DshType.body, modifier = Modifier.padding(vertical = 16.dp))
            return@ModelsSheetFrame
        }
        if (candidates.size > 8) {
            SheetSearchField(value = query, onValueChange = { query = it }, placeholder = s.searchModelProvider)
            Spacer(Modifier.height(8.dp))
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 380.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            visible.forEach { model ->
                val checked = model.id in picked
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(DshRadius.md))
                        .clickable { picked = if (checked) picked - model.id else picked + model.id }
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = null,
                        colors = CheckboxDefaults.colors(
                            checkedColor = Dsh.brand400,
                            uncheckedColor = Dsh.labelTertiary,
                            checkmarkColor = Dsh.onBrand,
                        ),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(model.name ?: model.id, color = Dsh.labelPrimary, style = DshType.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (model.name != null && model.name != model.id) {
                            Text(model.id, color = Dsh.labelTertiary, style = DshType.microRelaxed, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    model.contextWindow?.let {
                        Text(s.contextSize.format(compactTokens(it)), color = Dsh.labelTertiary, style = DshType.caption)
                    }
                }
            }
        }
        SheetError(error)
        Spacer(Modifier.height(14.dp))
        SheetPrimaryButton(
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
    ModelsSheetFrame(
        title = choice?.displayName ?: s.addProvider,
        subtitle = if (choice == null) s.customProviderDesktopHint else null,
        onDismiss = onDismiss,
    ) {
        if (choice == null) {
            if (addable.isEmpty()) {
                Text(s.addProviderEmpty, color = Dsh.labelTertiary, style = DshType.body, modifier = Modifier.padding(vertical = 16.dp))
                return@ModelsSheetFrame
            }
            val visible = if (query.isBlank()) addable else addable.filter {
                it.displayName.contains(query, ignoreCase = true) || it.provider.contains(query, ignoreCase = true)
            }
            if (addable.size > 8) {
                SheetSearchField(value = query, onValueChange = { query = it }, placeholder = s.searchModelProvider)
                Spacer(Modifier.height(8.dp))
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                visible.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(DshRadius.md))
                            .clickable { selected = option }
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(option.displayName, color = Dsh.labelPrimary, style = DshType.body, modifier = Modifier.weight(1f))
                        if (option.displayName != option.provider) {
                            Text(option.provider, color = Dsh.labelTertiary, style = DshType.caption)
                        }
                        Spacer(Modifier.width(4.dp))
                        Icon(ChevronRightOutline14, contentDescription = null, tint = Dsh.labelTertiary, modifier = Modifier.size(16.dp))
                    }
                }
            }
            return@ModelsSheetFrame
        }
        DshTextField(
            value = key,
            onValueChange = { key = it },
            label = s.addProviderKeyOptional,
            placeholder = s.apiKeyPlaceholder,
            visualTransformation = PasswordVisualTransformation(),
            isError = !keyOk,
        )
        Spacer(Modifier.height(8.dp))
        Text(s.apiKeyStoredOnHost, color = Dsh.labelTertiary, style = DshType.caption)
        SheetError(error)
        Spacer(Modifier.height(14.dp))
        SheetPrimaryButton(label = if (busy) s.saving else s.add, enabled = keyOk && !busy) {
            onSubmit(choice.provider, key.trim().ifBlank { null })
        }
    }
}
