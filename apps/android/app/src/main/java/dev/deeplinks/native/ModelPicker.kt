package dev.deeplinks.native

import dev.deeplinks.native.DshIconSize
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshType
import dev.deeplinks.core.L
import dev.deeplinks.native.ui.DshListNote
import dev.deeplinks.native.ui.DshListRow
import dev.deeplinks.native.ui.DshListSection
import dev.deeplinks.native.ui.DshListTrailing
import dev.deeplinks.native.ui.DshSheet
import dev.deeplinks.native.ui.DshSheetHeader
import dev.deeplinks.native.util.SessionListKind
import dev.deeplinks.native.util.catalogKind
import dev.deeplinks.native.util.compactTokens
import java.util.Locale

private enum class ModelPickerPage { MENU, MODELS, EFFORT }

/**
 * 模型与推理强度：首页两行（模型 / 推理强度）→ 二级列表。
 * 模型按供应商分组，选中项打勾。
 */
@Composable
internal fun ModelPickerSheet(
    catalog: MobileModelCatalog?,
    loading: Boolean = false,
    error: String? = null,
    onRetry: () -> Unit = {},
    onDismiss: () -> Unit,
    onSelect: (provider: String, model: String, effort: String?) -> Unit,
) {
    var page by remember { mutableStateOf(ModelPickerPage.MENU) }
    var query by remember { mutableStateOf("") }
    val currentOption = remember(catalog) {
        catalog?.groups?.asSequence()?.flatMap { g -> g.models.asSequence().map { g to it } }
            ?.firstOrNull { (g, m) ->
                m.id == catalog.currentModel &&
                    (g.provider == catalog.currentProvider || g.displayName == catalog.currentProvider)
            }
    }
    val currentName = currentOption?.second?.name ?: catalog?.currentModel
    val currentEffort = catalog?.currentReasoningEffort
        ?: currentOption?.second?.defaultEffort
    val currentEfforts = currentOption?.second?.reasoningEfforts.orEmpty()
    val filteredGroups = remember(catalog, query) {
        val groups = catalog?.groups.orEmpty()
        if (query.isBlank()) groups
        else groups.mapNotNull { group ->
            val models = group.models.filter {
                it.id.contains(query, ignoreCase = true) ||
                    (it.name?.contains(query, ignoreCase = true) == true) ||
                    group.displayName.contains(query, ignoreCase = true)
            }
            if (models.isEmpty()) null else group.copy(models = models)
        }
    }

    DshSheet(onDismiss = onDismiss, title = null) {
        Row(verticalAlignment = Alignment.Top) {
            if (page != ModelPickerPage.MENU) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Button) { page = ModelPickerPage.MENU }
                        .semantics { contentDescription = L.back },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(ChevronLeftOutline16, contentDescription = null, tint = Dsh.labelSecondary, modifier = Modifier.size(DshIconSize.lg))
                }
            }
            Box(Modifier.weight(1f)) {
                DshSheetHeader(
                    title = when (page) {
                        ModelPickerPage.MENU -> L.modelAndEffort
                        ModelPickerPage.MODELS -> L.model
                        ModelPickerPage.EFFORT -> L.reasoningEffort
                    },
                    subtitle = if (currentName != null) {
                        listOfNotNull(currentName, currentEffort?.let { formatEffortLabel(it) }).joinToString(" ")
                    } else {
                        L.selectSessionModel
                    },
                )
            }
        }
        if (!error.isNullOrBlank() && catalog != null) {
            Text(error, color = Dsh.error, style = DshType.captionRelaxed, modifier = Modifier.padding(horizontal = DshSpace.s4))
        }

        when (page) {
            ModelPickerPage.MENU -> DshListSection {
                DshListRow(
                    title = L.model,
                    icon = Sparkle16,
                    subtitle = currentOption?.second?.contextWindow?.let { compactTokens(it) },
                    value = currentName ?: L.noneSelected,
                    onClick = { page = ModelPickerPage.MODELS },
                )
                DshListRow(
                    title = L.reasoningEffort,
                    icon = ThinkOutline16,
                    iconTint = Dsh.systemAccent,
                    subtitle = currentName?.let { L.reasoningEffortForModel.format(it) },
                    value = currentEffort?.let { formatEffortLabel(it) } ?: if (currentEfforts.isEmpty()) "—" else L.defaultLabel,
                    enabled = currentEfforts.isNotEmpty(),
                    onClick = { page = ModelPickerPage.EFFORT },
                )
            }
            ModelPickerPage.MODELS -> {
                Spacer(Modifier.height(DshSpace.s8))
                SheetSearchField(value = query, onValueChange = { query = it }, placeholder = L.searchModelProvider)
                when (catalogKind(
                    hasItems = catalog?.groups?.isNotEmpty() == true,
                    initialLoad = loading && catalog == null,
                    hasError = error != null && catalog == null,
                )) {
                    SessionListKind.Loading -> DshListSection { DshListNote(L.loadingModelList) }
                    SessionListKind.Error -> ChatHistoryError(
                        title = L.loadModelListFailed,
                        message = error,
                        onRetry = onRetry,
                    )
                    SessionListKind.Empty -> DshListSection { DshListNote(L.noAvailableModels) }
                    SessionListKind.Content -> if (filteredGroups.isEmpty()) {
                        DshListSection { DshListNote(L.noMatchingModels.format(query)) }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 460.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            filteredGroups.forEach { group ->
                                DshListSection(header = group.displayName) {
                                    group.models.forEach { model ->
                                        val selected = model.id == catalog?.currentModel &&
                                            (group.provider == catalog.currentProvider || group.displayName == catalog.currentProvider)
                                        DshListRow(
                                            title = model.name ?: model.id,
                                            value = model.contextWindow?.let { compactTokens(it) },
                                            onClick = {
                                                val effort = if (selected) currentEffort else model.defaultEffort
                                                onSelect(group.provider, model.id, effort)
                                                page = ModelPickerPage.MENU
                                            },
                                            trailing = if (selected) DshListTrailing.Check else DshListTrailing.None,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            ModelPickerPage.EFFORT -> DshListSection {
                if (currentEfforts.isEmpty()) {
                    DshListNote(L.noAdjustableReasoningEffort)
                }
                currentEfforts.forEach { effort ->
                    DshListRow(
                        title = formatEffortLabel(effort),
                        onClick = {
                            val pair = currentOption
                            if (pair != null) {
                                onSelect(pair.first.provider, pair.second.id, effort)
                                page = ModelPickerPage.MENU
                            }
                        },
                        trailing = if (effort.equals(currentEffort, ignoreCase = true)) DshListTrailing.Check else DshListTrailing.None,
                    )
                }
            }
        }
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
