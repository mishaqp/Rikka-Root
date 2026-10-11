package me.rerere.rikkahub.ui.pages.setting

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.rikkahub.data.ai.tools.ToolPermissionPolicy
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.preferences.ToolApprovalPreferences
import me.rerere.rikkahub.ui.components.ai.RootCommandJournalButton
import me.rerere.rikkahub.ui.components.ai.tools.LocalToolMetadata
import me.rerere.rikkahub.ui.components.ai.tools.LocalToolUiEntry
import me.rerere.rikkahub.ui.components.ai.tools.LocalToolUiRegistry
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.compose.koinInject

private data class ApprovalFunction(
    val name: String,
    val requiresApproval: Boolean,
    val mandatory: Boolean,
    val noAlways: Boolean,
) {
    fun status(alwaysAllow: Set<String>, yolo: Boolean): String = when {
        mandatory -> "Спрашивать"
        name in alwaysAllow || yolo -> "Всегда разрешать"
        requiresApproval -> "Спрашивать"
        else -> "Одобрение не требуется"
    }
}

private data class ApprovalGroup(
    val entry: LocalToolUiEntry,
    val title: String,
    val description: String,
    val functions: List<ApprovalFunction>,
)

@Composable
fun SettingToolApprovalsPage(focusTool: String? = null) {
    val context = LocalContext.current
    val preferences = koinInject<ToolApprovalPreferences>()
    val yolo by preferences.globalYoloFlow.collectAsStateWithLifecycle(initialValue = false)
    val alwaysAllow by preferences.alwaysAllowFlow.collectAsStateWithLifecycle(initialValue = emptySet())
    val askAfterWebContent by preferences.askAfterWebContentFlow.collectAsStateWithLifecycle(initialValue = false)
    val scope = rememberCoroutineScope()
    var showConfirmation by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val groups = remember(context) {
        val tools = LocalToolMetadata(context).toolsForAssistant(Assistant())
        // Inspect existing approval predicates with harmless arguments; never execute tools.
        val probe = JsonObject(mapOf("command" to JsonPrimitive("true"),
            "executable" to JsonPrimitive("true"), "input" to JsonPrimitive("")))
        LocalToolUiRegistry.entries.map { entry ->
            ApprovalGroup(entry, entry.title(context), entry.description(context),
                tools[entry.option].orEmpty().distinctBy { it.name }.map { tool ->
                    ApprovalFunction(tool.name, ToolPermissionPolicy.apply(tool).needsApproval(probe),
                        !ToolPermissionPolicy.canGrantForChat(tool.name, probe),
                        !ToolPermissionPolicy.canGrantAlways(tool.name, probe))
                })
        }
    }
    val focusedGroup = groups.firstOrNull { group -> group.functions.any { it.name == focusTool } }
    var selectedCategoryId by rememberSaveable(focusTool) {
        mutableStateOf(focusedGroup?.let { LocalToolUiRegistry.category(it.entry.option).id })
    }
    var query by rememberSaveable { mutableStateOf("") }
    val search = query.trim()
    val selectedCategory = LocalToolUiRegistry.categories.firstOrNull { it.id == selectedCategoryId }
    val overview = selectedCategory == null && search.isEmpty()
    val shownGroups = groups.mapNotNull { group ->
        when {
            search.isNotEmpty() -> {
                val groupMatches = group.title.contains(search, ignoreCase = true) ||
                    group.description.contains(search, ignoreCase = true) ||
                    LocalToolUiRegistry.category(group.entry.option).title.contains(search, ignoreCase = true)
                val functions = group.functions.filter { function ->
                    groupMatches || function.name.contains(search, ignoreCase = true) ||
                        ToolPermissionPolicy.registry[function.name]?.contains(search, ignoreCase = true) == true
                }
                if (groupMatches || functions.isNotEmpty()) group.copy(functions = functions) else null
            }
            selectedCategory != null && group.entry.option in selectedCategory.options -> group
            else -> null
        }
    }
    val mappedNames = remember(groups) { groups.flatMap { it.functions }.mapTo(mutableSetOf()) { it.name } }
    val otherGrants = (alwaysAllow - mappedNames).sorted().filter { name ->
        search.isEmpty() || name.contains(search, ignoreCase = true) ||
            ToolPermissionPolicy.registry[name]?.contains(search, ignoreCase = true) == true
    }
    val focusedOtherIndex = focusTool?.let { otherGrants.indexOf(it) } ?: -1
    val focusedOtherListIndex = if (focusedOtherIndex >= 0) {
        val precedingItems = if (overview) 4 + LocalToolUiRegistry.categories.size
            else 2 + shownGroups.sumOf { 1 + it.functions.size }
        precedingItems + (if (error != null) 1 else 0) + 1 + focusedOtherIndex
    } else null
    val listState = rememberLazyListState()
    BackHandler(enabled = selectedCategoryId != null) { selectedCategoryId = null; query = "" }
    LaunchedEffect(selectedCategoryId, search, focusTool, focusedOtherListIndex) {
        var focusIndex: Int? = null
        if (selectedCategory != null && search.isEmpty() && focusTool != null) {
            var index = 2 // Search field and category introduction.
            shownGroups.forEach { group ->
                val functionIndex = group.functions.indexOfFirst { it.name == focusTool }
                if (functionIndex >= 0) focusIndex = index + 1 + functionIndex
                index += 1 + group.functions.size
            }
        }
        listState.scrollToItem(focusIndex ?: focusedOtherListIndex ?: 0)
    }

    fun save(action: suspend () -> Unit) {
        if (saving) return
        saving = true
        scope.launch {
            try { action(); error = null }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "Не удалось сохранить разрешения. Проверьте свободное место." }
            finally { saving = false }
        }
    }

    if (showConfirmation) AlertDialog(
        onDismissRequest = { showConfirmation = false },
        title = { Text("Отключить все запросы одобрения?") },
        text = { Text(ToolPermissionPolicy.warningText(), modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) },
        confirmButton = {
            TextButton(onClick = { showConfirmation = false; save { preferences.setYolo(true) } }) {
                Text("Да, я ГЛУПЫЙ, включить", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = { showConfirmation = false }) { Text("Оставить одобрения") }
        },
    )

    Scaffold(topBar = {
        LargeFlexibleTopAppBar(
            title = { Text(if (search.isNotEmpty()) "Поиск инструментов" else selectedCategory?.title ?: "Разрешения инструментов") },
            navigationIcon = {
                if (selectedCategory != null) FilledTonalIconButton(onClick = { selectedCategoryId = null; query = "" }) {
                    Icon(HugeIcons.ArrowLeft01, contentDescription = "Все категории")
                } else BackButton()
            },
            colors = CustomColors.topBarColors,
        )
    }, containerColor = CustomColors.topBarColors.containerColor) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "search") {
                OutlinedTextField(value = query, onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("Категория, группа или функция") },
                    leadingIcon = { Icon(HugeIcons.Search01, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                            Icon(HugeIcons.Cancel01, contentDescription = "Очистить поиск")
                        }
                    })
            }
            if (overview) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (yolo) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (yolo) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Я ГЛУПЫЙ — автоодобрять всё", style = MaterialTheme.typography.titleMedium)
                                Text(if (yolo) "Вкл. Инструменты выполняются автоматически. Запретные root-команды и вопросы пользователю требуют ответа."
                                    else "Выкл. Инструменты с побочными эффектами запрашивают одобрение перед запуском.",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(checked = yolo, enabled = !saving, onCheckedChange = {
                                if (it) showConfirmation = true else save { preferences.setYolo(false) }
                            })
                        }
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Спрашивать после веб-контента", style = MaterialTheme.typography.titleMedium)
                            Text("После веб-поиска или чтения страницы запрашивать одобрение в этом чате, включая MCP. По умолчанию выключено.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = askAfterWebContent, enabled = !saving, onCheckedChange = {
                            save { preferences.setAskAfterWebContent(it) }
                        })
                    }
                }
                item { Text("Категории", style = MaterialTheme.typography.titleMedium) }
                items(LocalToolUiRegistry.categories, key = { "category:${it.id}" }) { category ->
                    val categoryGroups = groups.filter { it.entry.option in category.options }
                    val functions = categoryGroups.flatMap { it.functions }.distinctBy { it.name }
                    val allowed = functions.count { it.status(alwaysAllow, yolo) == "Всегда разрешать" }
                    val ask = functions.count { it.status(alwaysAllow, yolo) == "Спрашивать" }
                    CardGroup {
                        item(onClick = { selectedCategoryId = category.id },
                            leadingContent = { Icon(category.icon, contentDescription = null, modifier = Modifier.size(28.dp)) },
                            headlineContent = { Text(category.title) },
                            supportingContent = {
                                Text("Групп: ${categoryGroups.size} · функций: ${functions.size}\nВсегда разрешать: $allowed · спрашивать: $ask · без одобрения: ${functions.size - allowed - ask}")
                            })
                    }
                }
            } else {
                item {
                    Text("Статусы показывают правила на этом устройстве. Одобрение для текущего чата и запрос после веб-контента учитываются при запуске.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (shownGroups.isEmpty() && otherGrants.isEmpty()) item { Text("Инструменты не найдены.") }
                shownGroups.forEach { group ->
                    item(key = "group:${group.entry.option}") {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                            Icon(group.entry.icon, contentDescription = null, modifier = Modifier.size(28.dp))
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (search.isNotEmpty()) Text(LocalToolUiRegistry.category(group.entry.option).title,
                                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                Text(group.title, style = MaterialTheme.typography.titleMedium)
                                Text(group.description, style = MaterialTheme.typography.bodySmall)
                                if (group.functions.isEmpty()) Text("Отдельных функций с одобрением нет.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    items(group.functions, key = { "function:${group.entry.option}:${it.name}" }) { function ->
                        ApprovalFunctionRow(function, function.status(alwaysAllow, yolo), function.name == focusTool,
                            function.name in alwaysAllow, saving) { save { preferences.revoke(function.name) } }
                    }
                }
            }
            error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            if (otherGrants.isNotEmpty()) {
                item {
                    Text("Другие инструменты", style = MaterialTheme.typography.titleMedium)
                    Text("Сохранённые разрешения MCP, workspace, навыков и других инструментов.", style = MaterialTheme.typography.bodySmall)
                }
                items(otherGrants, key = { "other:$it" }) { name ->
                    ApprovalFunctionRow(ApprovalFunction(name, true, false, false), "Всегда разрешать",
                        name == focusTool, true, saving) { save { preferences.revoke(name) } }
                }
            }
            item { RootCommandJournalButton() }
        }
    }
}

@Composable
private fun ApprovalFunctionRow(
    function: ApprovalFunction,
    status: String,
    highlighted: Boolean,
    granted: Boolean,
    saving: Boolean,
    onRevoke: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = if (highlighted) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainer)) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(function.name, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                ToolPermissionPolicy.registry[function.name]?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text(status, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                when {
                    function.mandatory -> Text("Каждый запуск требует подтверждения. «Всегда разрешать» и автоодобрение его не заменяют.",
                        style = MaterialTheme.typography.bodySmall)
                    function.noAlways -> Text("Для этой функции нельзя сохранить «Всегда разрешать».", style = MaterialTheme.typography.bodySmall)
                }
                if (function.name in setOf("root_exec", "ssh_exec", "ssh_exec_saved", "termux_run_command", "termux_session_start", "termux_session_send")) {
                    Text("Защищённые команды всегда требуют отдельного подтверждения.", style = MaterialTheme.typography.bodySmall)
                }
                if (function.name == "clipboard_tool") Text("Чтение буфера не требует одобрения; запись учитывает это разрешение.",
                    style = MaterialTheme.typography.bodySmall)
            }
            if (granted) TextButton(enabled = !saving, onClick = onRevoke) { Text("Отозвать") }
        }
    }
}
