package me.rerere.rikkahub.ui.pages.chat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import com.dokar.sonner.ToastType
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.FolderAdd
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.Sorting01
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.ConversationSortOrder
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.Folder
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.hasFastModelTarget
import me.rerere.rikkahub.ui.components.ui.BackupReminderCard
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.hooks.EditStateContent
import me.rerere.rikkahub.ui.hooks.readBooleanPreference
import me.rerere.rikkahub.ui.hooks.rememberIsPlayStoreVersion
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.utils.navigateToChatPage
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun ChatDrawerContent(
    navController: Navigator,
    vm: ChatVM,
    settings: Settings,
    current: Conversation,
    isOpen: Boolean = true,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val isPlayStore = rememberIsPlayStoreVersion()
    val repo = koinInject<ConversationRepository>()

    val activity = context as ComponentActivity
    val drawerVm: ChatDrawerVM = koinViewModel(viewModelStoreOwner = activity)

    LaunchedEffect(drawerVm, isOpen) { if (isOpen) drawerVm.refreshDateGrouping() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { drawerVm.refreshDateGrouping() }
    val conversations = drawerVm.conversations.collectAsLazyPagingItems()
    val folders by drawerVm.folders.collectAsStateWithLifecycle()
    val selectedFolderId by drawerVm.selectedFolderId.collectAsStateWithLifecycle()
    val conversationListState = rememberLazyListState(
        initialFirstVisibleItemIndex = drawerVm.scrollIndex,
        initialFirstVisibleItemScrollOffset = drawerVm.scrollOffset,
    )

    LaunchedEffect(conversationListState) {
        snapshotFlow {
            conversationListState.firstVisibleItemIndex to
                conversationListState.firstVisibleItemScrollOffset
        }
            .distinctUntilChanged()
            .collectLatest { (index, offset) ->
                drawerVm.saveScrollPosition(index, offset)
            }
    }

    val conversationJobs by vm.conversationJobs.collectAsStateWithLifecycle(
        initialValue = emptyMap(),
    )

    // 昵称编辑状态
    val nicknameEditState = useEditState<String> { newNickname ->
        vm.updateSettings(
            settings.copy(
                displaySetting = settings.displaySetting.copy(
                    userNickname = newNickname
                )
            )
        )
    }

    // 移动对话状态
    var showMoveToAssistantSheet by remember { mutableStateOf(false) }
    var conversationToMove by remember { mutableStateOf<Conversation?>(null) }
    val bottomSheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)

    // 文件夹相关状态
    var showMoveToFolderSheet by remember { mutableStateOf(false) }
    var conversationToMoveFolder by remember { mutableStateOf<Conversation?>(null) }
    val folderSheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var folderToRename by remember { mutableStateOf<Folder?>(null) }
    var folderToDelete by remember { mutableStateOf<Folder?>(null) }

    val updateCheckDisabledUntil = settings.displaySetting.updateCheckDisabledUntilEpochMillis
    var updateChecksEnabled by remember(updateCheckDisabledUntil) {
        mutableStateOf(updateCheckDisabledUntil <= System.currentTimeMillis())
    }
    LaunchedEffect(updateCheckDisabledUntil) {
        while (true) {
            val remaining = updateCheckDisabledUntil - System.currentTimeMillis()
            if (remaining <= 0) {
                updateChecksEnabled = true
                break
            }
            updateChecksEnabled = false
            delay(minOf(remaining, 60 * 60 * 1_000L))
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val deletionCoordinator = remember { ChatDrawerDeleteCoordinator() }
    val pendingRemovalIds by deletionCoordinator.pendingIds.collectAsStateWithLifecycle()
    val latestCurrentId by rememberUpdatedState(current.id)
    val currentRowMetadata = remember(current.id, current.messageNodes) {
        mapOf(current.id to ChatDrawerRowMetadata.fromConversation(current))
    }
    val showAllFolders by drawerVm.showAllFolders.collectAsStateWithLifecycle()
    val presentationSort by drawerVm.presentationSort.collectAsStateWithLifecycle()
    var conversationToRename by remember { mutableStateOf<Conversation?>(null) }
    val deleteFolderGeneratingMessage = stringResource(R.string.chat_page_delete_folder_generating)
    ChatDrawerFrame {
        ConversationList(
            current = current,
            conversations = conversations,
            conversationJobs = conversationJobs.keys,
            listState = conversationListState,
            pendingRemovalIds = pendingRemovalIds,
            folderSelected = !showAllFolders,
            rowMetadata = currentRowMetadata,
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp),
            headerContent = {
                ChatDrawerTopArea {
                    ChatDrawerHeader(
                        settings = settings,
                        current = current,
                        onEditNickname = { nicknameEditState.open(settings.displaySetting.userNickname) },
                        onUpdateAvatar = { avatar ->
                            vm.updateSettings(settings.copy(displaySetting = settings.displaySetting.copy(userAvatar = avatar)))
                        },
                        onNavigate = { navController.navigate(it) },
                        showUpdateStatus = updateChecksEnabled && !isPlayStore,
                    )
                    ChatDrawerNewChatButton(onClick = { navigateToChatPage(navController) })
                    BackupReminderCard(settings, onClick = { navController.navigate(Screen.Backup) })
                    ChatDrawerSearchBar(onNavigate = { navController.navigate(it) })
                    ChatDrawerAssistantCard(
                        settings = settings,
                        onUpdateSettings = { updated ->
                            val updateJob = vm.updateSettings(updated)
                            scope.launch {
                                updateJob.join()
                                val id = if (context.readBooleanPreference("create_new_conversation_on_start", true)) {
                                    Uuid.random()
                                } else {
                                    repo.getConversationsOfAssistant(updated.assistantId).first().firstOrNull()?.id ?: Uuid.random()
                                }
                                navigateToChatPage(navController, id)
                            }
                        },
                        onNavigate = { navController.navigate(it) },
                    )
                    ChatDrawerFolderControls(
                        sort = { ConversationSortButton(
                            presentationSort = presentationSort,
                            onPresentationSortChange = {
                                drawerVm.setPresentationSort(it)
                                scope.launch { conversationListState.scrollToItem(0) }
                            },
                            sortOrder = settings.displaySetting.conversationSortOrder,
                            onSortOrderChange = { sortOrder ->
                                vm.updateSettings(settings.copy(displaySetting = settings.displaySetting.copy(conversationSortOrder = sortOrder)))
                                scope.launch { conversationListState.scrollToItem(0) }
                            },
                        ) },
                        folders = { FolderBar(
                        folders = folders, selectedFolderId = selectedFolderId,
                        showAll = showAllFolders,
                        onSelectAll = { drawerVm.selectAllFolders() },
                        onSelect = { drawerVm.selectFolder(it) },
                        onCreate = { showCreateFolderDialog = true },
                        onRename = { folderToRename = it },
                        onDelete = { folderToDelete = it },
                        modifier = Modifier.fillMaxWidth(),
                    ) },
                    )
                }
            },
            onNewChat = { navigateToChatPage(navController) },
            onClick = { navigateToChatPage(navController, it.id) },
            onRename = { conversationToRename = it },
            onRegenerateTitle = { conversation ->
                if (settings.hasFastModelTarget()) {
                    vm.generateTitle(conversation, true)
                } else {
                    scope.launch {
                        if (snackbarHostState.showSnackbar(
                                message = "Выберите быструю модель в настройках", actionLabel = "Настройки",
                                withDismissAction = true, duration = SnackbarDuration.Short,
                            ) == SnackbarResult.ActionPerformed) {
                            navController.navigate(Screen.SettingModels)
                        }
                    }
                }
            },
            onDelete = { conversation ->
                scope.launch {
                    try {
                        deletionCoordinator.delete(
                            id = conversation.id,
                            offerUndo = {
                                snackbarHostState.showSnackbar("Чат удалён", actionLabel = "Отменить",
                                    withDismissAction = true, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed
                            },
                            commit = {
                                withContext(Dispatchers.IO) { repo.deleteConversation(conversation) }
                                conversations.refresh()
                                if (scope.isActive && conversation.id == latestCurrentId) navigateToChatPage(navController)
                            },
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        if (scope.isActive) snackbarHostState.showSnackbar("Не удалось удалить чат. Попробуйте ещё раз.")
                    }
                }
            },
            onPin = { vm.updatePinnedStatus(it) },
            onMoveToAssistant = { conversationToMove = it; showMoveToAssistantSheet = true },
            onMoveToFolder = { conversationToMoveFolder = it; showMoveToFolderSheet = true },
        )
        SnackbarHost(snackbarHostState, Modifier.padding(horizontal = 8.dp))
        ChatDrawerBottomBar(settings.assistantId.toString(), onNavigate = { navController.navigate(it) })
    }

    conversationToRename?.let { conversation ->
        var title by remember(conversation.id) { mutableStateOf(conversation.title) }
        AlertDialog(
            onDismissRequest = { conversationToRename = null },
            title = { Text("Переименовать чат") },
            text = {
                OutlinedTextField(title, onValueChange = { title = it }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), label = { Text("Название чата") })
            },
            confirmButton = {
                TextButton(enabled = title.isNotBlank(), onClick = {
                    vm.renameConversation(conversation, title)
                    conversationToRename = null
                }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { conversationToRename = null }) { Text("Отмена") } },
        )
    }

    // 昵称编辑对话框
    nicknameEditState.EditStateContent { nickname, onUpdate ->
        AlertDialog(
            onDismissRequest = {
                nicknameEditState.dismiss()
            },
            title = {
                Text(stringResource(R.string.chat_page_edit_nickname))
            },
            text = {
                OutlinedTextField(
                    value = nickname,
                    onValueChange = onUpdate,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.chat_page_nickname_placeholder)) }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        nicknameEditState.confirm()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        nicknameEditState.dismiss()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            }
        )
    }

    // 移动到文件夹 Bottom Sheet
    if (showMoveToFolderSheet) {
        val doMove: (Uuid?) -> Unit = { folderId ->
            conversationToMoveFolder?.let { conversation ->
                drawerVm.moveConversationToFolder(conversation.id, folderId)
                scope.launch {
                    folderSheetState.hide()
                    showMoveToFolderSheet = false
                    conversationToMoveFolder = null
                    conversations.refresh()
                }
            }
        }
        ModalBottomSheet(
            onDismissRequest = {
                showMoveToFolderSheet = false
                conversationToMoveFolder = null
            },
            sheetState = folderSheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.chat_page_move_to_folder),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // 移出文件夹（未归类）
                Surface(
                    onClick = { doMove(null) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = if (conversationToMoveFolder?.folderId == null) {
                        MaterialTheme.colorScheme.surfaceVariant
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(HugeIcons.Folder01, null)
                        Text(
                            text = stringResource(R.string.chat_page_remove_from_folder),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(folders) { folder ->
                        val isCurrent = folder.id == conversationToMoveFolder?.folderId
                        Surface(
                            onClick = { doMove(folder.id) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            color = if (isCurrent) {
                                MaterialTheme.colorScheme.surfaceVariant
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                            tonalElevation = if (isCurrent) 2.dp else 0.dp
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(HugeIcons.Folder01, null)
                                Text(
                                    text = folder.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 新建文件夹对话框
    if (showCreateFolderDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            title = { Text(stringResource(R.string.chat_page_create_folder)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.chat_page_folder_name)) }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        drawerVm.createFolder(name)
                        showCreateFolderDialog = false
                    },
                    enabled = name.isNotBlank()
                ) { Text(stringResource(R.string.chat_page_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFolderDialog = false }) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            }
        )
    }

    // 重命名文件夹对话框
    folderToRename?.let { folder ->
        var name by remember(folder.id) { mutableStateOf(folder.name) }
        AlertDialog(
            onDismissRequest = { folderToRename = null },
            title = { Text(stringResource(R.string.chat_page_rename_folder)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        drawerVm.renameFolder(folder.id, name)
                        folderToRename = null
                    },
                    enabled = name.isNotBlank()
                ) { Text(stringResource(R.string.chat_page_save)) }
            },
            dismissButton = {
                TextButton(onClick = { folderToRename = null }) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            }
        )
    }

    // 删除文件夹确认
    folderToDelete?.let { folder ->
        AlertDialog(
            onDismissRequest = { folderToDelete = null },
            title = { Text(stringResource(R.string.chat_page_delete_folder)) },
            text = { Text(stringResource(R.string.chat_page_delete_folder_confirm, folder.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (drawerVm.deleteFolder(folder.id)) {
                            folderToDelete = null
                            conversations.refresh()
                        } else {
                            toaster.show(deleteFolderGeneratingMessage, type = ToastType.Warning)
                        }
                    }
                ) { Text(stringResource(R.string.chat_page_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { folderToDelete = null }) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            }
        )
    }

    // 移动到助手 Bottom Sheet
    if (showMoveToAssistantSheet) {
        ModalBottomSheet(
            onDismissRequest = {
                showMoveToAssistantSheet = false
                conversationToMove = null
            },
            sheetState = bottomSheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.chat_page_move_to_assistant),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(settings.assistants) { assistant ->
                        AssistantItem(
                            assistant = assistant,
                            isCurrentAssistant = assistant.id == conversationToMove?.assistantId,
                            onClick = {
                                conversationToMove?.let { conversation ->
                                    vm.moveConversationToAssistant(conversation, assistant.id)
                                    scope.launch {
                                        bottomSheetState.hide()
                                        showMoveToAssistantSheet = false
                                        conversationToMove = null
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun FolderBar(
    folders: List<Folder>,
    selectedFolderId: Uuid?,
    showAll: Boolean,
    onSelectAll: () -> Unit,
    onSelect: (Uuid?) -> Unit,
    onCreate: () -> Unit,
    onRename: (Folder) -> Unit,
    onDelete: (Folder) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Only the folder strip scrolls sideways; the sorting action stays visible.
    LazyRow(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        item(key = "all") { FolderChip("Все", showAll, onSelectAll, null) }
        if (folders.isNotEmpty()) item(key = "unfiled") {
            FolderChip("Без папки", !showAll && selectedFolderId == null, { onSelect(null) }, null)
        }
        items(folders, key = { it.id }) { folder ->
            var menuExpanded by remember { mutableStateOf(false) }
            Box {
                FolderChip(folder.name, !showAll && selectedFolderId == folder.id,
                    { onSelect(folder.id) }, { menuExpanded = true }, HugeIcons.Folder01)
                DropdownMenu(menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(text = { Text("Переименовать") },
                        leadingIcon = { Icon(HugeIcons.PencilEdit01, null) },
                        onClick = { menuExpanded = false; onRename(folder) })
                    DropdownMenuItem(text = { Text("Удалить") },
                        leadingIcon = { Icon(HugeIcons.Delete01, null) },
                        onClick = { menuExpanded = false; onDelete(folder) })
                }
            }
        }
        item(key = "create") { FolderChip("+ Папка", false, onCreate, null, HugeIcons.FolderAdd) }
    }
}

@Composable
internal fun ConversationSortButton(
    presentationSort: DrawerConversationSort,
    onPresentationSortChange: (DrawerConversationSort) -> Unit,
    sortOrder: ConversationSortOrder,
    onSortOrderChange: (ConversationSortOrder) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menuExpanded = true }) {
            Icon(HugeIcons.Sorting01, "Сортировка чатов: ${presentationSort.label}")
        }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            Text("Сортировка: ${presentationSort.label}", Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            DrawerConversationSort.entries.forEach { option ->
                DropdownMenuItem(text = { Text(option.label) },
                    trailingIcon = { if (option == presentationSort) Icon(HugeIcons.Tick01, null) },
                    onClick = { menuExpanded = false; onPresentationSortChange(option) })
            }
            HorizontalDivider()
            Text("Дата для сортировки", Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ConversationSortOrder.entries.forEach { option ->
                DropdownMenuItem(text = { Text(if (option == ConversationSortOrder.UPDATE_TIME) "Обновление чата" else "Создание чата") },
                    trailingIcon = { if (option == sortOrder) Icon(HugeIcons.Tick01, null) },
                    onClick = { menuExpanded = false; if (option != sortOrder) onSortOrderChange(option) })
            }
        }
    }
}

@Composable
private fun FolderChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    icon: ImageVector? = null,
) {
    Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
        Surface(
            shape = CircleShape,
            color = if (selected) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surface.copy(alpha = 0f),
            contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clip(CircleShape).combinedClickable(
                role = Role.Button, onClick = onClick, onLongClick = onLongClick,
                onLongClickLabel = if (onLongClick != null) "Действия с папкой: $label" else null,
            ).semantics { contentDescription = label; this.selected = selected },
        ) {
            Row(Modifier.heightIn(min = 32.dp).padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (icon != null) Icon(icon, null, Modifier.size(14.dp))
                Text(label, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun AssistantItem(
    assistant: Assistant,
    isCurrentAssistant: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (isCurrentAssistant) {
            MaterialTheme.colorScheme.surfaceVariant
        } else {
            MaterialTheme.colorScheme.surface
        },
        tonalElevation = if (isCurrentAssistant) 2.dp else 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            UIAvatar(
                name = assistant.name,
                value = assistant.avatar,
                onClick = onClick,
                modifier = Modifier.size(40.dp),
            )
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (isCurrentAssistant) {
                    Text(
                        text = stringResource(R.string.assistant_page_current_assistant),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
