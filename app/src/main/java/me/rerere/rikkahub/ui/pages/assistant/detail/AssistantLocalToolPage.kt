package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.browser.BrowserPreferences
import me.rerere.rikkahub.browser.BrowserToolDefaults
import me.rerere.rikkahub.data.ai.tools.ToolApprovalDefaults
import me.rerere.rikkahub.data.ai.tools.ToolPermissionPolicy
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.preferences.ToolApprovalPreferences
import me.rerere.rikkahub.ui.components.ai.tools.*
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import java.text.NumberFormat
import java.util.Locale

@Composable
fun AssistantLocalToolPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(parameters = { parametersOf(id) })
    val stored by vm.assistant.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val skills by vm.skills.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val nav = LocalNavController.current
    var pending by remember(id) { mutableStateOf<Assistant?>(null) }
    val assistant = pending ?: stored
    LaunchedEffect(stored) {
        val expected = pending
        if (expected != null && expected.localTools == stored.localTools &&
            expected.tokenBudgetSoftCap == stored.tokenBudgetSoftCap &&
            expected.tokenBudgetHardCap == stored.tokenBudgetHardCap &&
            expected.subAgentConcurrencyLimit == stored.subAgentConcurrencyLimit) pending = null
    }
    fun update(before: Assistant, requested: Assistant) {
        pending = mergeToolUiChanges(pending ?: stored, before, requested)
        vm.updateToolSettings(before, requested)
    }
    // Do not reset a restored permission queue with the ViewModel's initial placeholder.
    if (stored.id.toString() != id) {
        Scaffold(topBar = { LargeFlexibleTopAppBar(title = { Text("Инструменты") }, navigationIcon = { BackButton() }) }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        return
    }
    // Permission hosts survive closing the details panel.
    val controls = rememberLocalToolControls(assistant) { requested -> update(assistant, requested) }
    val browser = koinInject<BrowserPreferences>()
    val browserFlow = remember(browser) { browser.observeAll() }
    val browserOptions by browserFlow.collectAsStateWithLifecycle(initialValue = BrowserToolDefaults.DEFAULT_ENABLED)
    val metadata = remember(context.applicationContext) { LocalToolMetadata(context) }
    val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
    val approvalPreferences = koinInject<ToolApprovalPreferences>()
    val yolo by approvalPreferences.globalYoloFlow.collectAsStateWithLifecycle(initialValue = false)
    val alwaysAllow by approvalPreferences.alwaysAllowFlow.collectAsStateWithLifecycle(initialValue = emptySet())
    var definitions by remember(id) { mutableStateOf<Map<LocalToolOption, List<Tool>>>(emptyMap()) }
    var estimate by remember(id) { mutableStateOf<Long?>(null) }
    var metadataError by remember(id) { mutableStateOf(false) }
    LaunchedEffect(assistant, model, skills, browserOptions) {
        if (assistant.id.toString() != id) return@LaunchedEffect
        try {
            val result = withContext(Dispatchers.IO) {
                val all = metadata.toolsForAssistant(assistant, model)
                val enabled = assistant.localTools.flatMap { option ->
                    all[option].orEmpty().filter { option != LocalToolOption.Browser || browserOptions[it.name] == true }
                }
                val skillTools = skillToolsForAssistant(assistant, skills)
                val skillPrompt = skillPromptForAssistant(assistant, skills, model ?: Model())
                all to estimateToolTokens(enabled + skillTools, listOf(skillPrompt))
            }
            definitions = result.first; estimate = result.second; metadataError = false
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { estimate = null; metadataError = true }
    }
    var categoryId by rememberSaveable(id) { mutableStateOf<String?>(null) }
    var query by rememberSaveable(id) { mutableStateOf("") }
    var selectedKey by rememberSaveable(id) { mutableStateOf<String?>(null) }
    var sheetGeneration by rememberSaveable(id) { mutableIntStateOf(0) }
    var preset by remember { mutableStateOf<LocalToolPreset?>(null) }
    var accessRevision by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { accessRevision++ }
    LaunchedEffect(controls.busy) { if (!controls.busy) accessRevision++ }
    val category = LocalToolUiRegistry.categories.firstOrNull { it.id == categoryId }
    val selected = LocalToolUiRegistry.entries.firstOrNull { it.option::class.simpleName == selectedKey }
    val searching = query.isNotBlank()
    BackHandler(enabled = category != null || searching) { if (searching) query = "" else categoryId = null }
    AssistantLocalToolPageContent(
        assistant = assistant,
        estimate = estimate,
        metadataError = metadataError,
        query = query,
        category = category,
        controls = controls,
        definitions = definitions,
        accessRevision = accessRevision,
        onQueryChange = { query = it },
        onCategorySelected = { categoryId = it },
        onPresetSelected = { preset = it },
        onToolSelected = { selectedKey = it.option::class.simpleName; sheetGeneration++ },
        onBackToTools = { if (searching) query = "" else categoryId = null },
    )
    preset?.let { candidate ->
        val desired = LocalToolUiRegistry.preset(candidate)
        val enabled = assistant.localTools.toSet()
        val add = LocalToolUiRegistry.entries.filter { it.option in desired && it.option !in enabled }
        val remove = LocalToolUiRegistry.entries.filter { it.option !in desired && it.option in enabled }
        AlertDialog(onDismissRequest = { preset = null }, title = { Text("Применить «${candidate.title}»?") }, text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (add.isEmpty() && remove.isEmpty()) Text("Изменений нет")
                if (add.isNotEmpty()) { Text("Включить", style = MaterialTheme.typography.titleSmall); add.forEach { Text("• ${it.title(context)}") } }
                if (remove.isNotEmpty()) { Text("Выключить", style = MaterialTheme.typography.titleSmall); remove.forEach { Text("• ${it.title(context)}") } }
                Text("Разрешения Android запрашиваются при включении. Отказ оставит соответствующую функцию выключенной; необязательные доступы можно пропустить.", style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { TextButton(onClick = { preset = null; controls.applyOptions(desired) }) { Text("Применить") } },
            dismissButton = { TextButton(onClick = { preset = null }) { Text("Отмена") } })
    }
    selected?.let { entry ->
        val generation = sheetGeneration
        val access = androidAccessForTool(context, entry.option)
        val functions = definitions[entry.option].orEmpty()
        ModalBottomSheet(onDismissRequest = { selectedKey = null; sheetGeneration++ }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(entry.icon, null, Modifier.size(28.dp)); Text(entry.title(context), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                }
                Text(entry.description(context))
                Text("Функции ИИ", style = MaterialTheme.typography.titleMedium)
                if (functions.isEmpty()) Text(if (metadataError) "Не удалось загрузить список функций" else "Загружаю функции…")
                functions.forEach { Text(it.name, style = MaterialTheme.typography.bodyMedium) }
                HorizontalDivider(); Text("Разрешения Android", style = MaterialTheme.typography.titleMedium)
                if (access.isEmpty()) Text("Дополнительные разрешения не требуются")
                access.forEach { Text("${it.label}: ${if (it.granted) "выдано" else if (it.required) "не выдано" else "необязательно, не выдано"}",
                    color = if (!it.granted && it.required) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
                if (canRequestAndroidAccess(context, entry.option)) TextButton(enabled = !controls.busy, onClick = { controls.requestPermission(entry.option) }) { Text("Выдать разрешение") }
                Text("Одобрение инструмента", style = MaterialTheme.typography.titleMedium)
                val probe = JsonObject(mapOf("command" to JsonPrimitive("true"), "executable" to JsonPrimitive("true"), "input" to JsonPrimitive("")))
                functions.forEach { tool ->
                    val mode = when {
                        !ToolPermissionPolicy.canGrantForChat(tool.name, probe) -> "Спрашивать всегда"
                        yolo -> "Без запроса: «Я ГЛУПЫЙ»"
                        tool.name in alwaysAllow -> "Всегда разрешать"
                        ToolPermissionPolicy.apply(tool).needsApproval(probe) -> "Спрашивать"
                        else -> "Одобрение не требуется"
                    }
                    Text("${tool.name}: $mode", style = MaterialTheme.typography.bodySmall)
                }
                Text("Разрешения «Для этого чата» действуют в соответствующем чате. Для действий с защищёнными командами остаётся обязательное подтверждение HARDLINE.", style = MaterialTheme.typography.bodySmall)
                TextButton(enabled = functions.isNotEmpty(), onClick = { selectedKey = null; sheetGeneration++
                    nav.navigate(Screen.SettingToolApprovalsFocused(functions.firstOrNull()?.name ?: entry.option::class.simpleName.orEmpty())) }) { Text("Разрешения этого инструмента") }
                HorizontalDivider()
                LocalToolDetailSettings(
                    option = entry.option,
                    assistant = assistant,
                    onEnabledChange = { option, enabled ->
                        if (!controls.busy) controls.setEnabled(option, enabled)
                    },
                    controlsEnabled = !controls.busy,
                    onUpdate = { requested ->
                        if (selectedKey == entry.option::class.simpleName && sheetGeneration == generation) update(assistant, requested)
                    },
                )
            }
        }
    }
}

/** The production tools overview, with metadata and actions supplied by its host. */
@Composable
internal fun AssistantLocalToolPageContent(
    assistant: Assistant,
    estimate: Long?,
    metadataError: Boolean,
    query: String,
    category: LocalToolUiCategory?,
    controls: LocalToolControls,
    definitions: Map<LocalToolOption, List<Tool>>,
    accessRevision: Int,
    onQueryChange: (String) -> Unit,
    onCategorySelected: (String) -> Unit,
    onPresetSelected: (LocalToolPreset) -> Unit,
    onToolSelected: (LocalToolUiEntry) -> Unit,
    onBackToTools: () -> Unit,
) {
    val context = LocalContext.current
    val searching = query.isNotBlank()
    val matches = remember(query, context) { LocalToolUiRegistry.entries.filter {
        (it.title(context) + " " + it.description(context)).contains(query.trim(), ignoreCase = true)
    } }
    val visible = if (searching) matches else category?.options?.map(LocalToolUiRegistry::entry).orEmpty()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(topBar = {
        LargeFlexibleTopAppBar(title = { Text(if (searching) "Поиск инструментов" else category?.title ?: "Инструменты") },
            navigationIcon = {
                if (category != null || searching) IconButton(onClick = { onBackToTools() }) {
                    Icon(HugeIcons.ArrowLeft01, "Назад к инструментам")
                } else BackButton()
            }, scrollBehavior = scroll, colors = CustomColors.topBarColors)
    }, modifier = Modifier.nestedScroll(scroll.nestedScrollConnection), containerColor = CustomColors.topBarColors.containerColor) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Включено ${assistant.localTools.toSet().size} из ${LocalToolUiRegistry.entries.size}", style = MaterialTheme.typography.titleLarge)
                        Text(estimate?.let {
                            val format = NumberFormat.getNumberInstance(Locale.forLanguageTag("ru")).apply { minimumFractionDigits = 1; maximumFractionDigits = 1 }
                            "≈${format.format(it / 1000.0)} тыс. токенов в каждом запросе"
                        } ?: if (metadataError) "Не удалось оценить размер инструментов" else "Оцениваю размер инструментов…")
                        Text("Описания, схемы и выбранные навыки · примерно 4 символа на токен", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item { OutlinedTextField(value = query, onValueChange = onQueryChange, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text("Поиск по названию и описанию") }, leadingIcon = { Icon(HugeIcons.Search01, null) },
                trailingIcon = if (query.isNotEmpty()) ({ IconButton(onClick = { onQueryChange("") }) { Icon(HugeIcons.Cancel01, "Очистить поиск") } }) else null) }
            if (category == null && !searching) {
                item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LocalToolPreset.entries.forEach { candidate -> AssistChip(onClick = { onPresetSelected(candidate) }, label = { Text(candidate.title) },
                        enabled = !controls.busy || candidate == LocalToolPreset.None) }
                } }
                if (controls.busy) item { Text("Применяю изменения и проверяю разрешения…", style = MaterialTheme.typography.bodySmall) }
                items(LocalToolUiRegistry.categories, key = { it.id }) { group -> ToolCategoryRow(group, assistant.localTools.toSet()) { onCategorySelected(group.id) } }
            } else {
                if (!searching && category != null) item { ListItem(headlineContent = { Text("Включить всю категорию") },
                    supportingContent = { Text("Каждое разрешение Android запрашивается отдельно", style = MaterialTheme.typography.bodySmall) },
                    trailingContent = { Switch(checked = category.options.all { it in assistant.localTools }, enabled = !controls.busy, onCheckedChange = {
                        controls.applyOptions(if (it) assistant.localTools.toSet() + category.options else assistant.localTools.toSet() - category.options.toSet())
                    }) }) }
                if (visible.isEmpty()) item { Text("Инструменты не найдены") }
                items(visible, key = { it.option::class.simpleName.orEmpty() }) { entry ->
                    LocalToolCompactRow(entry, entry.option in assistant.localTools, controls, definitions[entry.option].orEmpty(), accessRevision,
                        if (searching) LocalToolUiRegistry.category(entry.option).title else null) { onToolSelected(entry) }
                }
            }
        }
    }
}

@Composable
private fun ToolCategoryRow(category: LocalToolUiCategory, enabled: Set<LocalToolOption>, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(category.icon, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) { Text(category.title, style = MaterialTheme.typography.titleMedium)
                Text("${category.options.count { it in enabled }}/${category.options.size} включено", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Icon(HugeIcons.ArrowRight01, "Открыть категорию")
        }
    }
}

@Composable
private fun LocalToolCompactRow(entry: LocalToolUiEntry, enabled: Boolean, controls: LocalToolControls, functions: List<Tool>, accessRevision: Int, categoryLabel: String?, onOpen: () -> Unit) {
    val context = LocalContext.current
    val access = remember(context, entry.option, accessRevision, controls.busy) { androidAccessForTool(context, entry.option) }
    Card(onClick = onOpen, enabled = !controls.busy, modifier = Modifier.fillMaxWidth()) {
        ListItem(leadingContent = { Icon(entry.icon, null, tint = MaterialTheme.colorScheme.primary) }, headlineContent = { Text(entry.title(context)) }, supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(entry.description(context), maxLines = 1, overflow = TextOverflow.Ellipsis)
                categoryLabel?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (entry.requiresRoot) ToolBadge("Root")
                    if (access.isNotEmpty()) ToolBadge("Разрешение Android", access.any { !it.granted })
                    if (entry.option in setOf(LocalToolOption.AskUser, LocalToolOption.SkillImport,
                            LocalToolOption.ManageAssistants, LocalToolOption.ManageAppSettings) ||
                        functions.any { ToolApprovalDefaults.requiresApproval(it.name) }) ToolBadge("Одобрение")
                    if (entry.background) ToolBadge("Фон")
                }
            }
        }, trailingContent = { Switch(checked = enabled, enabled = !controls.busy, onCheckedChange = { controls.setEnabled(entry.option, it) }) })
    }
}

@Composable
private fun ToolBadge(text: String, error: Boolean = false) {
    Surface(shape = MaterialTheme.shapes.small, color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer) {
        Text(text, Modifier.padding(horizontal = 6.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall)
    }
}
