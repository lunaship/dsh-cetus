package dev.deeplinks.native

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshS
import dev.deeplinks.core.DshType
import dev.deeplinks.core.L
import dev.deeplinks.core.modelContextWindow
import dev.deeplinks.core.modelSheetContextNote
import dev.deeplinks.core.modelSheetScopeNote
import dev.deeplinks.native.ui.v4.DlBottomSheet
import dev.deeplinks.native.ui.v4.DlListRow
import dev.deeplinks.native.ui.v4.DlRowTrailing
import dev.deeplinks.native.ui.v4.DlSectionHeader
import dev.deeplinks.native.ui.v4.DlSegmented
import dev.deeplinks.native.util.SessionListKind
import dev.deeplinks.native.util.catalogKind
import dev.deeplinks.native.util.compactTokens
import java.util.Locale

/** 模型超过这个数才显示搜索框（5.2 默认不显示）。 */
private const val MODEL_SEARCH_THRESHOLD = 8

/**
 * 5.2 模型与推理：模型单选（名称 + 供应商 · 上下文）+ 推理等级分段 + 「上下文已用 · 只影响这个会话」。
 * 选完不关弹层：目录先乐观更新，单选和分段立刻反映新值。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelPickerSheet(
    catalog: MobileModelCatalog?,
    loading: Boolean = false,
    error: String? = null,
    contextPercent: Int? = null,
    onRetry: () -> Unit = {},
    onDismiss: () -> Unit,
    onSelect: (provider: String, model: String, effort: String?) -> Unit,
) {
    DlBottomSheet(onDismissRequest = onDismiss, title = L.modelAndEffort) {
        ModelPickerContent(catalog, loading, error, contextPercent, onRetry, onSelect)
    }
}

/** 弹层正文，截图直接画这一块。 */
@Composable
internal fun ModelPickerContent(
    catalog: MobileModelCatalog?,
    loading: Boolean,
    error: String?,
    contextPercent: Int?,
    onRetry: () -> Unit,
    onSelect: (provider: String, model: String, effort: String?) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val currentOption = remember(catalog) {
        catalog?.groups?.asSequence()?.flatMap { g -> g.models.asSequence().map { g to it } }
            ?.firstOrNull { (g, m) -> isCurrentModel(catalog, g, m) }
    }
    val currentEffort = catalog?.currentReasoningEffort ?: currentOption?.second?.defaultEffort
    val efforts = currentOption?.second?.reasoningEfforts.orEmpty()
    val modelCount = catalog?.groups?.sumOf { it.models.size } ?: 0
    val filteredGroups = remember(catalog, query) { filterModelGroups(catalog?.groups.orEmpty(), query) }

    if (modelCount > MODEL_SEARCH_THRESHOLD) {
        Column(Modifier.padding(horizontal = DshSpace.s16, vertical = DshSpace.s4)) {
            SheetSearchField(value = query, onValueChange = { query = it }, placeholder = L.searchModelProvider)
        }
    }
    DlSectionHeader(L.model)
    when (catalogKind(
        hasItems = catalog?.groups?.isNotEmpty() == true,
        initialLoad = loading && catalog == null,
        hasError = error != null && catalog == null,
    )) {
        SessionListKind.Loading -> DlListRow(title = L.loadingModelList, enabled = false)
        SessionListKind.Error -> DlListRow(
            title = L.loadModelListFailed,
            subtitle = error,
            danger = true,
            trailing = DlRowTrailing.TextAction(L.retry, onRetry),
        )
        SessionListKind.Empty -> DlListRow(title = L.noAvailableModels, enabled = false)
        SessionListKind.Content -> Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            if (filteredGroups.isEmpty()) DlListRow(title = L.noMatchingModels.format(query), enabled = false)
            filteredGroups.forEach { group ->
                group.models.forEach { model ->
                    val selected = isCurrentModel(catalog, group, model)
                    DlListRow(
                        title = model.name ?: model.id,
                        subtitle = listOfNotNull(
                            group.displayName,
                            model.contextWindow?.let { DshS.modelContextWindow.format(compactTokens(it)) },
                        ).joinToString(" · "),
                        trailing = DlRowTrailing.Radio(selected),
                        onClick = {
                            if (!selected) onSelect(group.provider, model.id, model.defaultEffort)
                        },
                    )
                }
            }
        }
    }
    if (!error.isNullOrBlank() && catalog != null) {
        Text(
            error,
            color = Dsh.err,
            style = DshType.supporting,
            modifier = Modifier.padding(horizontal = DshSpace.s24, vertical = DshSpace.s4),
        )
    }
    val pair = currentOption
    if (pair != null && efforts.isNotEmpty()) {
        DlSectionHeader(L.reasoningEffort)
        DlSegmented(
            options = efforts.map { formatEffortLabel(it) },
            selectedIndex = efforts.indexOfFirst { it.equals(currentEffort, ignoreCase = true) },
            onSelect = { index -> onSelect(pair.first.provider, pair.second.id, efforts[index]) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = DshSpace.s24),
        )
    }
    Text(
        if (contextPercent != null) DshS.modelSheetContextNote.format(contextPercent) else DshS.modelSheetScopeNote,
        style = DshType.supporting,
        color = Dsh.labelSecondary,
        modifier = Modifier.padding(start = DshSpace.s24, end = DshSpace.s24, top = DshSpace.s12, bottom = DshSpace.s8),
    )
}

private fun isCurrentModel(catalog: MobileModelCatalog?, group: MobileModelGroup, model: MobileModelOption): Boolean =
    catalog != null && model.id == catalog.currentModel &&
        (group.provider == catalog.currentProvider || group.displayName == catalog.currentProvider)

private fun filterModelGroups(groups: List<MobileModelGroup>, query: String): List<MobileModelGroup> =
    if (query.isBlank()) {
        groups
    } else {
        groups.mapNotNull { group ->
            val models = group.models.filter {
                it.id.contains(query, ignoreCase = true) ||
                    (it.name?.contains(query, ignoreCase = true) == true) ||
                    group.displayName.contains(query, ignoreCase = true)
            }
            if (models.isEmpty()) null else group.copy(models = models)
        }
    }

internal fun formatEffortLabel(effort: String): String =
    effort.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }

internal fun friendlySelectModelError(raw: String?): String {
    val text = raw?.trim().orEmpty().ifBlank { return L.unknownError }
    val lower = text.lowercase()
    return when {
        "no adapter registered" in lower -> L.noAdapterRegistered
        "unsupported_reasoning" in lower || "does not support reasoning" in lower -> L.unsupportedReasoningEffort
        "model-unavailable" in lower -> L.modelUnavailable
        "调用" in text && "api" in lower -> L.modelApiFailed
        else -> text
    }
}
