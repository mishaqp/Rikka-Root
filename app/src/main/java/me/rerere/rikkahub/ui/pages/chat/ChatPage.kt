package me.rerere.rikkahub.ui.pages.chat

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.currentWindowDpSize
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.browser.BrowserActivity
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getSelectedASRProvider
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.HistoryLoadState
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.service.ChatError
import me.rerere.rikkahub.ui.components.ai.ChatAttachmentPickerActions
import me.rerere.rikkahub.ui.components.ai.ChatRunningToolStrip
import me.rerere.rikkahub.ui.components.ai.rememberRunningToolObservation
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.components.ai.ChatInput
import me.rerere.rikkahub.ui.components.ai.ModelListSheet
import me.rerere.rikkahub.ui.components.ai.rememberModelListState
import me.rerere.rikkahub.ui.components.message.LocalChatMessageHazeState
import me.rerere.rikkahub.ui.modifier.chatBackdropBlur
import me.rerere.rikkahub.ui.components.ai.FilesPicker
import me.rerere.rikkahub.ui.components.ai.SearchMode
import me.rerere.rikkahub.ui.components.ai.completion.WorkspaceCompletionProvider
import me.rerere.rikkahub.ui.components.ai.rememberChatAttachmentPickerActions
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.ui.hooks.HapticAction
import me.rerere.rikkahub.ui.hooks.isHapticActionEnabled
import me.rerere.rikkahub.ui.hooks.EditStateContent
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.utils.base64Decode
import me.rerere.rikkahub.utils.navigateToChatPage
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

@Composable
fun ChatPage(id: Uuid, text: String?, files: List<Uri>, nodeId: Uuid? = null) {
    val vm: ChatVM = koinViewModel(
        parameters = {
            parametersOf(id.toString())
        }
    )
    val filesManager: FilesManager = koinInject()
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()

    val setting by vm.settings.collectAsStateWithLifecycle()
    val conversation by vm.conversation.collectAsStateWithLifecycle()
    val loadingJob by vm.conversationJob.collectAsStateWithLifecycle()
    val processingStatus by vm.processingStatus.collectAsStateWithLifecycle()
    val currentChatModel by vm.currentChatModel.collectAsStateWithLifecycle()
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val errors by vm.errors.collectAsStateWithLifecycle()

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val softwareKeyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    // Handle back press when drawer is open
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch {
            drawerState.close()
        }
    }

    // The drawer remains composed while hidden: clear its search focus on close
    // as well, otherwise Android keeps the search IME open over the conversation.
    LaunchedEffect(drawerState.isOpen) {
        focusManager.clearFocus(force = true)
        softwareKeyboardController?.hide()
    }

    val windowAdaptiveInfo = currentWindowDpSize()
    val isBigScreen =
        windowAdaptiveInfo.width > windowAdaptiveInfo.height && windowAdaptiveInfo.width >= 1100.dp

    // 进入大屏（永久抽屉）模式时重置抽屉状态为关闭，
    // 避免从横屏旋转回竖屏后，模态抽屉残留为打开状态且无法关闭（#1304）
    LaunchedEffect(isBigScreen) {
        if (isBigScreen && drawerState.isOpen) {
            drawerState.close()
        }
    }

    val startVoiceMode = rememberVoiceModeStarter(vm, setting)

    val inputState = vm.inputState

    // 初始化输入状态（处理传入的 files 和 text 参数）
    LaunchedEffect(files, text, conversation.historyLoadState) {
        if (conversation.historyLoadState != HistoryLoadState.COMPLETE) return@LaunchedEffect
        if (files.isNotEmpty()) {
            val parts = filesManager.importChatFiles(files).map { file ->
                val url = file.localUri.toString()
                when {
                    file.mimeType.startsWith("image/") -> UIMessagePart.Image(url = url)
                    file.mimeType.startsWith("video/") -> UIMessagePart.Video(url = url)
                    file.mimeType.startsWith("audio/") -> UIMessagePart.Audio(url = url)
                    else -> UIMessagePart.Document(
                        url = url,
                        fileName = file.displayName,
                        mime = file.mimeType,
                    )
                }
            }
            inputState.messageContent = parts
        }
        text?.base64Decode()?.let { decodedText ->
            if (decodedText.isNotEmpty()) {
                inputState.setMessageText(decodedText)
            }
        }
    }

    val chatListState = rememberLazyListState()
    LaunchedEffect(nodeId, conversation.messageNodes.size) {
        if (!vm.chatListInitialized && conversation.messageNodes.isNotEmpty()) {
            if (nodeId != null) {
                val index = conversation.messageNodes.indexOfFirst { it.id == nodeId }
                if (index >= 0) {
                    chatListState.scrollToItem(index)
                }
            } else {
                chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
            }
            vm.chatListInitialized = true
        }
    }

    when {
        isBigScreen -> {
            PermanentNavigationDrawer(
                drawerContent = {
                    ChatDrawerContent(
                        navController = navController,
                        current = conversation,
                        vm = vm,
                        settings = setting
                    )
                }
            ) {
                ChatPageContent(
                    onStartVoiceMode = startVoiceMode,
                    inputState = inputState,
                    loadingJob = loadingJob,
                    processingStatus = processingStatus,
                    setting = setting,
                    conversation = conversation,
                    drawerState = drawerState,
                    navController = navController,
                    vm = vm,
                    chatListState = chatListState,
                    assistant = assistant,
                    currentChatModel = currentChatModel,
                    bigScreen = true,
                    errors = errors,
                    onDismissError = { vm.dismissError(it) },
                    onClearAllErrors = { vm.clearAllErrors() },
                )
            }
        }

        else -> {
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    ChatDrawerContent(
                        navController = navController,
                        current = conversation,
                        vm = vm,
                        settings = setting,
                        isOpen = drawerState.isOpen,
                        onRequestOpen = { scope.launch { drawerState.open() } },
                        onRequestClose = { scope.launch { drawerState.close() } },
                    )
                }
            ) {
                ChatPageContent(
                    onStartVoiceMode = startVoiceMode,
                    inputState = inputState,
                    loadingJob = loadingJob,
                    processingStatus = processingStatus,
                    setting = setting,
                    conversation = conversation,
                    drawerState = drawerState,
                    navController = navController,
                    vm = vm,
                    chatListState = chatListState,
                    assistant = assistant,
                    currentChatModel = currentChatModel,
                    bigScreen = false,
                    errors = errors,
                    onDismissError = { vm.dismissError(it) },
                    onClearAllErrors = { vm.clearAllErrors() },
                )
            }
        }
    }
}

@Composable
private fun ChatPageContent(
    onStartVoiceMode: () -> Unit,
    inputState: ChatInputState,
    loadingJob: Job?,
    processingStatus: String? = null,
    setting: Settings,
    bigScreen: Boolean,
    conversation: Conversation,
    drawerState: DrawerState,
    navController: Navigator,
    vm: ChatVM,
    chatListState: LazyListState,
    assistant: Assistant,
    currentChatModel: Model?,
    errors: List<ChatError>,
    onDismissError: (Uuid) -> Unit,
    onClearAllErrors: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val modelRequiredMessage = stringResource(R.string.chat_header_model_required)
    val workspaceRepository: WorkspaceRepository = koinInject()
    var previewMode by rememberSaveable { mutableStateOf(false) }
    val hazeState = rememberHazeState()
    val messageBackgroundHazeState = rememberHazeState()
    var showFilesSheet by remember { mutableStateOf(false) }
    val attachmentPickerActions = rememberChatAttachmentPickerActions(
        inputState = inputState,
        setting = setting,
        onAttachmentAdded = { showFilesSheet = false },
    )
    val allowAudioVideoAttachments =
        currentChatModel?.findProvider(setting.providers) is ProviderSetting.Google

    val completionProviders = remember(assistant.workspaceId, conversation.workspaceCwd, workspaceRepository) {
        assistant.workspaceId?.let { workspaceId ->
            listOf(
                WorkspaceCompletionProvider(
                    workspaceId = workspaceId.toString(),
                    repository = workspaceRepository,
                    currentCwd = conversation.workspaceCwd,
                )
            )
        }.orEmpty()
    }

    TTSAutoPlay(vm = vm, setting = setting, conversation = conversation)
    val runningToolObservation = rememberRunningToolObservation(conversation, loadingJob != null)

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize()
    ) {
        AssistantBackground(
            setting = setting, assistant = assistant, bigScreen = bigScreen,
            modifier = Modifier.hazeSource(messageBackgroundHazeState).hazeSource(hazeState),
        )
        Scaffold(
            topBar = {
                Column {
                    TopBar(
                        assistant = assistant,
                        conversation = conversation,
                        currentChatModel = currentChatModel,
                        setting = setting,
                        onUpdateChatModel = vm::setChatModel,
                        modifier = Modifier.chatBackdropBlur(hazeState, setting.displaySetting.glassTheme && !setting.displaySetting.glassEconomy),
                        bigScreen = bigScreen,
                        drawerState = drawerState,
                        previewMode = previewMode,
                        onNewChat = {
                            navigateToChatPage(navController)
                        },
                        onClickMenu = {
                            previewMode = !previewMode
                        },
                        onUpdateTitle = {
                            vm.updateTitle(it)
                        }
                    )
                }
            },
            bottomBar = {
                Column(Modifier.background(MaterialTheme.colorScheme.background)) {
                    if (conversation.historyLoadState == HistoryLoadState.COMPLETE) {
                        Box(Modifier.fillMaxWidth().animateContentSize(
                            animationSpec = tween(180, easing = CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f)),
                            alignment = Alignment.BottomCenter,
                        )) {
                            ChatRunningToolStrip(
                                observation = runningToolObservation,
                                onStop = vm::stopGeneration,
                                modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 6.dp),
                            )
                        }
                    }
                    val messageQueue by vm.messageQueue.collectAsStateWithLifecycle()
                    val voiceState by vm.voiceSession.state.collectAsStateWithLifecycle()
                    if (conversation.historyLoadState == HistoryLoadState.INCOMPLETE) {
                        HistoryRecoveryExport()
                    } else if (conversation.historyLoadState == HistoryLoadState.COMPLETE) ChatInput(
                        onStartVoiceMode = onStartVoiceMode,
                        voiceState = voiceState,
                        onStopVoiceMode = vm.voiceSession::stop,
                        state = inputState,
                        messageQueue = messageQueue,
                        onRemoveQueuedMessage = vm::removeQueuedMessage,
                        onBeginEditQueuedMessage = vm::beginEditQueuedMessage,
                        onFinishEditQueuedMessage = vm::finishEditQueuedMessage,
                        onResumeMessageQueue = vm::resumeMessageQueue,
                        loading = loadingJob != null,
                        settings = setting,
                        assistant = assistant,
                        chatModel = currentChatModel,
                        contextUsageTokens = conversation.currentMessages.lastOrNull { it.role == MessageRole.ASSISTANT }?.usage?.promptTokens,
                        hazeState = hazeState,
                        completionProviders = completionProviders,
                        onCancelClick = {
                            vm.stopGeneration()
                        },
                        onUpdateSearchMode = { mode ->
                            vm.updateSearch(
                                enableWebSearch = mode == SearchMode.LOCAL,
                                builtInSearch = mode == SearchMode.BUILT_IN,
                            )
                        },
                        onSendClick = {
                            if (currentChatModel == null) {
                                toaster.show(modelRequiredMessage, type = ToastType.Error)
                                return@ChatInput
                            }
                            if (inputState.isEditing()) {
                                vm.handleMessageEdit(
                                    parts = inputState.getContents(
                                        keepThinkingAndToolCardsWhenEditing =
                                            conversation.messageNodes.asSequence().flatMap { it.messages.asSequence() }
                                                .firstOrNull { it.id == inputState.editingMessage }?.role != MessageRole.ASSISTANT ||
                                                setting.displaySetting.keepThinkingAndToolCardsWhenEditingAssistant,
                                    ),
                                    messageId = inputState.editingMessage!!,
                                )
                            } else {
                                vm.handleMessageSend(inputState.getContents())
                                scope.launch {
                                    delay(100.milliseconds)
                                    chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
                                }
                            }
                            inputState.clearInput()
                        },
                        onLongSendClick = {
                            if (inputState.isEditing()) {
                                vm.handleMessageEdit(
                                    parts = inputState.getContents(
                                        keepThinkingAndToolCardsWhenEditing =
                                            conversation.messageNodes.asSequence().flatMap { it.messages.asSequence() }
                                                .firstOrNull { it.id == inputState.editingMessage }?.role != MessageRole.ASSISTANT ||
                                                setting.displaySetting.keepThinkingAndToolCardsWhenEditingAssistant,
                                    ),
                                    messageId = inputState.editingMessage!!,
                                )
                            } else {
                                vm.handleMessageSend(content = inputState.getContents(), answer = false)
                                scope.launch {
                                    chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
                                }
                            }
                            inputState.clearInput()
                        },
                        onUpdateChatModel = vm::setChatModel,
                        onUpdateAssistant = vm::updateAssistant,
                        onUpdateSearchService = { index ->
                            vm.updateSettings { it.copy(searchServiceSelected = index) }
                        },
                        onMoreClick = {
                            showFilesSheet = true
                        },
                    )
                }
            },
            containerColor = Color.Transparent,
        ) { innerPadding ->
            CompositionLocalProvider(LocalChatMessageHazeState provides messageBackgroundHazeState) {
            ChatList(
                innerPadding = innerPadding,
                conversation = conversation,
                state = chatListState,
                loading = loadingJob != null,
                activeRunningToolMessageKey = runningToolObservation.activeClientKey?.messageKey,
                activeRunningToolCallId = runningToolObservation.activeClientKey?.toolCallId,
                processingStatus = processingStatus,
                previewMode = previewMode,
                settings = setting,
                hazeState = hazeState,
                errors = errors,
                onDismissError = onDismissError,
                onClearAllErrors = onClearAllErrors,
                onRegenerate = {
                    vm.regenerateAtMessage(it)
                },
                onEdit = {
                    inputState.editingMessage = it.id
                    inputState.setContents(it.parts)
                },
                onForkMessage = {
                    scope.launch {
                        val fork = vm.forkMessage(message = it)
                        navigateToChatPage(navController, chatId = fork.id)
                    }
                },
                onDelete = {
                    if (loadingJob != null) {
                        vm.showDeleteBlockedWhileGeneratingError()
                    } else {
                        vm.deleteMessage(it)
                    }
                },
                // Branch arrows change only the selection; the service applies it to the live history.
                onUpdateMessage = { newNode ->
                    vm.selectMessageNode(newNode.id, newNode.selectIndex)
                },
                onClickSuggestion = { suggestion ->
                    if (inputState.isEditing()) inputState.clearInput()
                    inputState.setMessageText(suggestion)
                },
                // Moru's default tap: send the suggestion as the next message (opt-in via settings).
                onSendSuggestion = { suggestion ->
                    if (currentChatModel == null) {
                        toaster.show(modelRequiredMessage, type = ToastType.Error)
                    } else {
                        vm.handleMessageSend(listOf(UIMessagePart.Text(suggestion)))
                        scope.launch {
                            delay(100.milliseconds)
                            chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
                        }
                    }
                },
                onTranslate = { message, locale ->
                    vm.translateMessage(message, locale)
                },
                onClearTranslation = { message ->
                    vm.clearTranslationField(message.id)
                },
                onJumpToMessage = { index ->
                    previewMode = false
                    scope.launch {
                        chatListState.requestScrollToItem(index)
                    }
                },
                onToolApproval = { toolCallId, approved, reason, scope, toolName ->
                    vm.handleToolApproval(toolCallId, approved, reason, scope, toolName)
                },
                onToolAnswer = { toolCallId, answer ->
                    vm.handleToolAnswer(toolCallId, answer)
                },
                onToggleFavorite = { node ->
                    vm.toggleMessageFavorite(node)
                },
                onConversationSystemPromptChange = { newPrompt ->
                    vm.updateConversation(conversation.copy(customSystemPrompt = newPrompt))
                    vm.saveConversationAsync()
                },
            )
            }
        }

        if (showFilesSheet) {
            ChatFilesPickerSheet(
                inputState = inputState,
                setting = setting,
                conversation = conversation,
                assistant = assistant,
                chatModel = currentChatModel,
                vm = vm,
                attachmentPickerActions = attachmentPickerActions,
                onStartVoiceMode = onStartVoiceMode,
                onDismiss = { showFilesSheet = false },
            )
        }
    }
}

@Composable
private fun ChatFilesPickerSheet(
    inputState: ChatInputState,
    setting: Settings,
    conversation: Conversation,
    assistant: Assistant,
    chatModel: Model?,
    vm: ChatVM,
    attachmentPickerActions: ChatAttachmentPickerActions,
    onStartVoiceMode: () -> Unit,
    onDismiss: () -> Unit,
) {
    val voiceState by vm.voiceSession.state.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var showCompressDialog by remember { mutableStateOf(false) }

    fun dismissAll() {
        showCompressDialog = false
        onDismiss()
    }

    val filesSheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    )
    val maxPickerContentHeight = (currentWindowDpSize().height * 0.8f - 22.dp).coerceAtLeast(0.dp)
    ModalBottomSheet(
        sheetState = filesSheetState,
        onDismissRequest = { dismissAll() },
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        dragHandle = {
            Box(Modifier.padding(top = 8.dp, bottom = 10.dp).size(40.dp, 4.dp)
                .background(MaterialTheme.colorScheme.outlineVariant, CircleShape))
        },
    ) {
        Box(Modifier.heightIn(max = maxPickerContentHeight).testTag("chat_plus_content")) {
            FilesPicker(
                conversation = conversation,
                state = inputState,
                assistant = assistant,
                chatModel = chatModel,
                mcpManager = vm.mcpManager,
                onCompressContext = { additionalPrompt, targetTokens, keepRecentMessages ->
                    vm.handleCompressContext(additionalPrompt, targetTokens, keepRecentMessages)
                },
                onUpdateAssistant = vm::updateAssistant,
                onUpdateConversation = {
                    vm.updateConversation(it)
                    vm.saveConversationAsync()
                },
                showCompressDialog = showCompressDialog,
                onShowCompressDialogChange = { showCompressDialog = it },
                onDismiss = { dismissAll() },
                onTakePic = attachmentPickerActions.onTakePicture,
                onPickImage = attachmentPickerActions.onPickImage,
                onPickVideo = attachmentPickerActions.onPickVideo,
                onPickAudio = attachmentPickerActions.onPickAudio,
                onPickFile = attachmentPickerActions.onPickFile,
                onSketch = attachmentPickerActions.onSketch,
                onStartVoiceMode = if (
                    setting.getSelectedASRProvider()?.supportsServerVadVoiceMode == true &&
                    voiceState.phase == VoicePhase.Off
                ) {
                    {
                        dismissAll()
                        focusManager.clearFocus(force = true)
                        keyboardController?.hide()
                        onStartVoiceMode()
                    }
                } else null,
            )
        }
    }
}

@Composable
private fun TopBar(
    assistant: Assistant,
    conversation: Conversation,
    currentChatModel: Model?,
    setting: Settings,
    onUpdateChatModel: (Model) -> Unit,
    modifier: Modifier = Modifier,
    drawerState: DrawerState,
    bigScreen: Boolean,
    previewMode: Boolean,
    onClickMenu: () -> Unit,
    onNewChat: () -> Unit,
    onUpdateTitle: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val titleState = useEditState<String> {
        onUpdateTitle(it)
    }

    val context = LocalContext.current
    val navController = LocalNavController.current
    val workspaceRepository: WorkspaceRepository = koinInject()
    val workspaces by workspaceRepository.listFlow().collectAsState(initial = emptyList())
    val workspaceId = workspaces.firstOrNull { it.id == assistant.workspaceId?.toString() }?.id
    val editTitleWarning = stringResource(R.string.chat_page_edit_title_warning)
    val title = conversation.title.ifBlank { stringResource(R.string.chat_page_new_chat) }
    val modelListState = rememberModelListState(currentChatModel?.id, setting.providers, ModelType.CHAT)
    ChatHeader(
        title = title,
        bigScreen = bigScreen,
        previewMode = previewMode,
        canRename = conversation.historyLoadState == HistoryLoadState.COMPLETE,
        showWorkspace = workspaceId != null,
        modelName = currentChatModel?.displayName?.takeIf { it.isNotBlank() }
            ?: currentChatModel?.modelId?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.model_list_select_model),
        onSelectModel = modelListState::open,
        showAvatar = setting.displaySetting.useAssistantAvatarInHeader ?: true,
        modifier = modifier,
        avatar = {
            UIAvatar(name = assistant.name, value = assistant.avatar, modifier = Modifier.size(16.dp))
        },
        onLongClickAvatar = { navController.navigate(Screen.AssistantDetail(assistant.id.toString())) },
        avatarHapticFeedbackEnabled = isHapticActionEnabled(setting.displaySetting, HapticAction.DRAWER),
        onOpenDrawer = { scope.launch { drawerState.open() } },
        onClickTitle = {
            if (conversation.messageNodes.isNotEmpty()) {
                titleState.open(conversation.title)
            } else {
                toaster.show(editTitleWarning, type = ToastType.Warning)
            }
        },
        onClickMenu = { onClickMenu() },
        onNewChat = { onNewChat() },
        onFiles = { workspaceId?.let { navController.navigate(Screen.WorkspaceDetail(it, openFiles = true)) } },
        onTerminal = { workspaceId?.let { navController.navigate(Screen.WorkspaceTerminal(it)) } },
        onBrowser = { context.startActivity(BrowserActivity.intent(context, conversationId = conversation.id.toString())) },
    )
    ModelListSheet(state = modelListState, onSelect = onUpdateChatModel)
    titleState.EditStateContent { title, onUpdate ->
        AlertDialog(
            onDismissRequest = {
                titleState.dismiss()
            },
            title = {
                Text(stringResource(R.string.chat_page_edit_title))
            },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = onUpdate,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        titleState.confirm()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        titleState.dismiss()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            }
        )
    }
}
