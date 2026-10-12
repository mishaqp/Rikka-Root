package me.rerere.rikkahub.ui.components.message

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Executable inventory of existing chat actions. Every row checks the production function body
 * and its actual UI/callback association. Deleting a control, replacing a callback by a no-op,
 * or leaving its old code only in a comment fails that row. Private UI contracts are intentionally
 * structural and require review after a legitimate refactor; they are not click/permission tests.
 * Slash commands are absent in this revision, so there is no invented slash-action contract.
 */
@RunWith(Parameterized::class)
class ChatFunctionChecklistTest(
    private val action: String,
    private val path: String,
    private val function: String,
    private val required: List<String>,
) {
    @Test fun productionUiAndCallbackRemainConnected() {
        val body = ChatProductionSource.canonical(ChatProductionSource.function(path, function))
        required.forEach { fragment ->
            assertTrue("$action lost production UI/callback in $path::$function: $fragment", body.contains(ChatProductionSource.canonical(fragment)))
        }
    }

    companion object {
        private const val MESSAGE = "ui/components/message/"
        private const val INPUT = "ui/components/ai/"
        private const val CHAT = "ui/pages/chat/"
        private fun row(action: String, path: String, function: String, vararg fragments: String): Array<Any> =
            arrayOf(action, path, function, fragments.toList())

        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun actions(): List<Array<Any>> = listOf(
            row("copy message", MESSAGE + "ChatMessageActions.kt", "ChatMessageActionButtons", "contentDescription = stringResource(R.string.copy)", "onClick = { context.copyMessageToClipboard(message) }"),
            row("regenerate and confirm user regeneration", MESSAGE + "ChatMessageActions.kt", "ChatMessageActionButtons", "contentDescription = stringResource(R.string.regenerate)", "if (presentation.confirmRegeneration) { showRegenerateConfirm = true } else { onRegenerate() }", "onConfirm = { showRegenerateConfirm = false onRegenerate() }"),
            row("speak and stop assistant response", MESSAGE + "ChatMessageActions.kt", "ChatMessageActionButtons", "if (presentation.showTts)", "IconToggleButton(", "enabled = presentation.canSpeak(isAvailable)", "tts.speak(textToSpeak)", "tts.stop()"),
            row("open translation and submit selected language", MESSAGE + "ChatMessageActions.kt", "ChatMessageActionButtons", "if (presentation.showTranslation)", "contentDescription = stringResource(R.string.translate)", "onClick = { showTranslateDialog = true }", "onTranslate(message, language)"),
            row("clear message translation", MESSAGE + "ChatMessageActions.kt", "ChatMessageActionButtons", "onClearTranslation = { showTranslateDialog = false onClearTranslation(message) }"),
            row("open message actions", MESSAGE + "ChatMessageActions.kt", "ChatMessageActionButtons", "contentDescription = stringResource(R.string.more_options)", "onClick = onOpenActionSheet"),
            row("select and copy", MESSAGE + "ChatMessageActions.kt", "ChatMessageActionsSheet", "MessageSheetAction(HugeIcons.TextSelection, stringResource(R.string.select_and_copy), onClick = onSelectAndCopy)"),
            row("WebView preview only for nonblank text", MESSAGE + "ChatMessageActions.kt", "ChatMessageActionsSheet", "presentation.sheetActions.map", "ChatMessageSheetAction.WebPreview ->", "MessageSheetAction(HugeIcons.WebDesign01, stringResource(R.string.render_with_webview), onClick = onWebViewPreview)"),
            row("edit message", MESSAGE + "ChatMessageActions.kt", "ChatMessageActionsSheet", "MessageSheetAction(HugeIcons.Edit01, stringResource(R.string.edit), onClick = onEdit)"),
            row("share message", MESSAGE + "ChatMessageActions.kt", "ChatMessageActionsSheet", "MessageSheetAction(HugeIcons.Share04, stringResource(R.string.share), onClick = onShare)"),
            row("fork message", MESSAGE + "ChatMessageActions.kt", "ChatMessageActionsSheet", "MessageSheetAction(HugeIcons.GitFork, stringResource(R.string.create_fork), onClick = onFork)"),
            row("favorite and remove favorite", MESSAGE + "ChatMessageActions.kt", "ChatMessageActionsSheet", "canToggleFavorite = onToggleFavorite != null", "ChatMessageSheetAction.Favorite ->", "if (isFavorite) R.string.chat_message_remove_favorite else R.string.chat_message_add_favorite", "onClick = requireNotNull(onToggleFavorite)"),
            row("delete message", MESSAGE + "ChatMessageActions.kt", "ChatMessageActionsSheet", "MessageSheetAction(HugeIcons.Delete01, stringResource(R.string.delete), destructive = true, onClick = onDelete)"),
            row("sheet dismisses then dispatches selected action", MESSAGE + "ChatMessageActions.kt", "ChatMessageActionsSheet", "SegmentedListItem(", "onClick = { onDismissRequest() action.onClick() }"),
            row("branch previous and next with boundaries", MESSAGE + "ChatMessageBranch.kt", "ChatMessageBranchSelector", "if (node.messages.size > 1)", "contentDescription = \"Prev\"", "if (node.selectIndex > 0)", "selectIndex = node.selectIndex - 1", "contentDescription = \"Next\"", "if (node.selectIndex < node.messages.lastIndex)", "selectIndex = node.selectIndex + 1", "onUpdate("),
            row("last streaming action visibility", MESSAGE + "ChatMessage.kt", "ChatMessage", "val presentation = presentChatMessage(message, loading, lastMessage, readOnly)", "AnimatedVisibility( visible = presentation.showActions", "ChatMessageActionButtons("),
            row("context checkpoint expands and edits/deletes", MESSAGE + "ChatMessageContextCheckpoint.kt", "ChatMessageContextCheckpoint", ".clickable { expanded = !expanded }", "TextButton(onClick = onEdit, enabled = !readOnly)", "TextButton(onClick = onDelete, enabled = !readOnly)"),
            row("checkpoint route retains callbacks", MESSAGE + "ChatMessage.kt", "ChatMessage", "if (presentation.isCheckpoint)", "ChatMessageContextCheckpoint(", "onEdit = onEdit", "onDelete = onDelete", "return"),
            row("copy all selected message text", MESSAGE + "ChatMessageCopySheet.kt", "ChatMessageCopySheet", "TextButton(", "context.copyMessageToClipboard(message) onDismissRequest()", "SelectionContainer", "message.parts.filterIsInstance<UIMessagePart.Text>().filter { it.text.isNotBlank() }"),
            row("user bubble tap edits message", MESSAGE + "ChatMessage.kt", "ChatMessage", "onUserMessageClick = if (presentation.canEditFromBubble) onEdit else null"),
            row("video audio document open local files", MESSAGE + "ChatMessage.kt", "MessagePartsBlock", "is UIMessagePart.Video", "is UIMessagePart.Audio", "is UIMessagePart.Document", "ChatMessageMediaTile(", "ChatMessageFileChip(", "onClick = { context.openLocalFile(part.url) }"),
            row("image zoom viewer", MESSAGE + "ChatMessage.kt", "MessagePartsBlock", "is UIMessagePart.Image", "ZoomableAsyncImage(", "model = part.url"),
            row("citations expand and open links", MESSAGE + "ChatMessage.kt", "ChatMessageCitations", "ChatMessageFileChip(", "onClick = { expand = !expand }", "withLink(LinkAnnotation.Url(annotation.url))"),
            row("inline search citation opens matching URL", MESSAGE + "ChatMessage.kt", "MessagePartsBlock", "part.toolName == \"search_web\" && part.isExecuted", "if (citationId == id)", "context.openUrl(url)", "onClickCitation = handleClickCitation"),
            row("translation language picker and clear entry", MESSAGE + "ChatMessageTranslation.kt", "LanguageSelectionDialog", "onLanguageSelected(language)", "onClearTranslation()", "R.string.translation_clear"),
            row("collapse and expand translation", MESSAGE + "ChatMessageTranslation.kt", "CollapsibleTranslationText", "IconButton(", "onClick = { isCollapsed = !isCollapsed }", "R.string.expand_translation", "R.string.collapse_translation"),
            row("tool approvals four choices and denial reason", MESSAGE + "ChatMessageTools.kt", "ChatMessageToolStep", "ToolApprovalAction.entries.filterNot", "TextButton(", "onToolApproval(tool.toolCallId, action.approved, \"\", action.scope, tool.toolName)", "ToolDenyReasonDialog(", "onToolApproval(tool.toolCallId, false, reason, ApprovalScope.Once, tool.toolName)"),
            row("tool detail opens result sheet", MESSAGE + "ChatMessageTools.kt", "ChatMessageToolStep", "onClick = { showResult = true }", "onDismissRequest = { showResult = false }"),
            row("ask user options text and submit", MESSAGE + "ChatMessageTools.kt", "AskUserToolStep", "onValueChange = { answers[q.id] = it }", "FilledTonalButton(", "onToolAnswer(tool.toolCallId, answerPayload.toString())"),
            row("error copy and dismiss", "ui/components/ui/ErrorCard.kt", "ErrorCard", "ClipData.newPlainText(\"Error\", error.error.message ?: \"Unknown error\")", "contentDescription = \"Copy error message\"", "onClick = onDismiss", "R.string.chat_page_dismiss_error"),
            row("error clear all and identity routing", "ui/components/ui/ErrorCard.kt", "ErrorCardsDisplay", "if (errors.size > 1)", "onClick = onClearAllErrors", "onDismiss = { onDismissError(error.id) }"),
            row("error fast model recovery navigation", "ui/components/ui/ErrorCard.kt", "ErrorCard", "error.solution == ChatErrorSolution.CheckFastModelSettings", "LinkAnnotation.Clickable(", "navController.navigate(Screen.SettingModels)"),
            row("errors auto dismiss after five seconds", "ui/components/ui/ErrorCard.kt", "ErrorCard", "LaunchedEffect(error.id) { delay(5000) onDismiss() }"),
            row("cancellation is excluded from displayed errors", "service/ChatService.kt", "addError", "if (error is CancellationException) return", "ChatError(title = title, error = error, conversationId = conversationId, solution = solution)"),
            row("send click and stop route", INPUT + "ChatInput.kt", "ChatInput", "if (loading && state.isEmpty()) onCancelClick() else onSendClick()", "onClick = { sendMessage() }"),
            row("long send without answer", INPUT + "ChatInput.kt", "ChatInput", "if (loading && state.isEmpty()) onCancelClick() else onLongSendClick()", "onLongClick = { sendMessageWithoutAnswer() }"),
            row("send enabled state and accessible stop/send label", INPUT + "ChatInput.kt", "SendButton", "val presentation = chatInputPresentation(loading = loading, empty = empty)", "val showStop = presentation.showStop", ".combinedClickable( enabled = presentation.sendEnabled", "onClick = onClick", "onLongClick = onLongClick", "stringResource(if (stop) R.string.stop else R.string.send)"),
            row("IME send respects setting and empty input", INPUT + "ChatInput.kt", "TextInputRow", "onKeyboardAction = { if (settings.displaySetting.sendOnEnter && !state.isEmpty()) { onSendMessage() } }"),
            row("edit or send message then clear input", CHAT + "ChatPage.kt", "ChatPageContent", "onSendClick = {", "if (currentChatModel == null)", "vm.handleMessageEdit(", "vm.handleMessageSend(inputState.getContents())", "inputState.clearInput()"),
            row("long send answer false and stop generation", CHAT + "ChatPage.kt", "ChatPageContent", "onLongSendClick = {", "vm.handleMessageSend(content = inputState.getContents(), answer = false)", "onCancelClick = { vm.stopGeneration() }"),
            row("open model picker and select model", INPUT + "ChatInput.kt", "ChatInput", "ComposerModelButton(", "onClick = { modelListState.open() }", "state = modelListState", "onSelect = onUpdateChatModel"),
            row("model picker open and clear", INPUT + "ModelList.kt", "ModelSelectorButton", "onClick = { state.open() }", "if (allowClear && model != null)", "onClick = onClear"),
            row("model selection and provider long press", INPUT + "ModelList.kt", "ModelItem", ".combinedClickable(", "onClick = { onSelect(model) }", "onLongClick = {", "Screen.SettingProviderDetail("),
            row("model favorites add remove and reorder", INPUT + "ModelList.kt", "ModelList", "rememberReorderableLazyListState(", "moveSettingsItem(current.favoriteModels, moved, anchor, fromIndex < toIndex) { it }", "favoriteModels = settings.favoriteModels.filter { it != model.id }", "favoriteModels = (settings.favoriteModels + model.id).distinct()"),
            row("model providers collapse and jump", INPUT + "ModelList.kt", "ModelList", ".clickable { toggleCollapsed(providerSetting) }", "AssistChip(", "lazyListState.animateScrollToItem(position)"),
            row("search open picker", INPUT + "SearchPicker.kt", "SearchPickerButton", "onClick = {", "showSearchPicker = true", "SearchPicker("),
            row("search mode local built in off", INPUT + "SearchPicker.kt", "SearchPicker", "onCheckedChange = { if (!selected) onUpdateSearchMode(mode) }", "SearchMode.BUILT_IN", "val supportsBuiltInSearch = provider is ProviderSetting.Google || provider is ProviderSetting.OpenAI && provider.useResponseApi"),
            row("search provider select and settings", INPUT + "SearchPicker.kt", "SearchProviderPicker", "onClick = { onUpdateSearchService(index) }"),
            row("search settings navigation", INPUT + "SearchPicker.kt", "SearchPicker", "onClick = onSelectProvider", "navBackStack.navigate(Screen.SettingSearch)"),
            row("reasoning picker gated by model ability", INPUT + "ChatInput.kt", "ChatInput", "if (chatModel?.abilities?.contains(ModelAbility.REASONING) == true)", "ReasoningButton(", "onUpdateAssistant { current -> current.copy(reasoningLevel = it) }"),
            row("reasoning slider commit", INPUT + "ReasoningPicker.kt", "ReasoningPicker", "Slider(", "onValueChangeFinished = {", "onUpdateReasoningLevel("),
            row("more opens attachment sheet", CHAT + "ChatPage.kt", "ChatPageContent", "onMoreClick = { showFilesSheet = true }", "ChatFilesPickerSheet("),
            row("camera attachment", INPUT + "FilesPicker.kt", "FilesPicker", "AttachmentAction(HugeIcons.Camera01, R.string.take_picture, onTakePic)"),
            row("photo attachment", INPUT + "FilesPicker.kt", "FilesPicker", "AttachmentAction(HugeIcons.Image02, R.string.photo, onPickImage)"),
            row("sketch attachment", INPUT + "FilesPicker.kt", "FilesPicker", "AttachmentAction(HugeIcons.PaintBoard, R.string.sketch, onSketch)"),
            row("document attachment", INPUT + "FilesPicker.kt", "FilesPicker", "AttachmentAction(HugeIcons.Files02, R.string.upload_file, onPickFile)"),
            row("video and audio provider gate", INPUT + "FilesPicker.kt", "FilesPicker", "if (provider != null && provider is ProviderSetting.Google)", "AttachmentAction(HugeIcons.Video01, R.string.video, onPickVideo)", "AttachmentAction(HugeIcons.MusicNote03, R.string.audio, onPickAudio)"),
            row("camera permission and result import", INPUT + "ChatAttachmentPicker.kt", "rememberChatAttachmentPickerActions", "ActivityResultContracts.TakePicture()", "cameraPermission.requestPermissions()", "cameraLauncher.launch(cameraOutputUri!!)", "inputState.addImages(filesManager.createChatFilesByContents(listOf(cameraOutputUri!!)))"),
            row("photo video audio document launchers", INPUT + "ChatAttachmentPicker.kt", "rememberChatAttachmentPickerActions", "onPickImage = { imagePickerLauncher.launch(\"image/*\") }", "onPickVideo = { videoPickerLauncher.launch(\"video/*\") }", "onPickAudio = { audioPickerLauncher.launch(\"audio/*\") }", "onPickFile = { filePickerLauncher.launch(arrayOf(\"*/*\")) }"),
            row("sketch imports created image", INPUT + "ChatAttachmentPicker.kt", "rememberChatAttachmentPickerActions", "SketchDialog(", "onConfirm = { result ->", "inputState.addImages(listOfNotNull(filesManager.createChatFileBySketch(result)))", "onSketch = { showSketch = true }"),
            row("remove attachments and annotate image", INPUT + "AttachmentChips.kt", "MediaFileInputRow", "onRemove = { removePart(part, part.url) }", "onClick = { drawingOn = part }", "is UIMessagePart.Video", "is UIMessagePart.Audio", "is UIMessagePart.Document"),
            row("paste image and long text as document", INPUT + "ChatInput.kt", "TextInputRow", ".contentReceiver(receiveContentListener)", "transferableContent.hasMediaType(MediaType.Image)", "state.addImages(", "filesManager.createChatTextFile(text)", "state.addFiles(listOf(document))"),
            row("fullscreen editor open and save", INPUT + "ChatInput.kt", "TextInputRow", "isFullScreen = !isFullScreen", "FullScreenEditor(state = state) { isFullScreen = false }"),
            row("fullscreen editor save button", INPUT + "ChatInput.kt", "FullScreenEditor", "TextButton( onClick = { onDone() })", "R.string.chat_page_save"),
            row("quick message appends content", INPUT + "FilesPicker.kt", "QuickMessagesSheetRow", "Surface( onClick = { state.appendText(quickMessage.content) expanded = false onDismiss() }"),
            row("workspace at mention completion routing", INPUT + "ChatInput.kt", "TextInputRow", "CompletionPopup(", "onItemClick = { item -> state.applyCompletion(list.replacementRange, item) completionList = null }"),
            row("workspace at mention provider eligibility", INPUT + "completion/WorkspaceCompletionProvider.kt", "complete", "if (workspaceId.isNullOrBlank() || context.hasSelection) return null", "findWorkspaceMention(context.text, context.cursor)", "ChatCompletionItem("),
            row("MCP picker open", INPUT + "FilesPicker.kt", "FilesPicker", "McpToolsSheetRow(", "onClick = { page = FilesPickerPage.MCP }", "McpPickerPage("),
            row("MCP toggle updates enabled servers", INPUT + "McpPicker.kt", "McpPicker", "Switch(", "onCheckedChange = {", "onUpdateAssistant { current -> current.copy(", "mcpServers ="),
            row("workspace picker bind unbind and manage", INPUT + "FilesPicker.kt", "WorkspacePickerListItem", "ToolsSheetRow(", "onClick = { showSheet = true }", "WorkspaceSelectSheet(", "onUpdateAssistant { current -> current.copy(workspaceId = newId) }", "onNavigateToManage()"),
            row("workspace selection rows", INPUT + "WorkspaceSelectSheet.kt", "WorkspaceSelectSheet", "onClick = { onSelect(null) }", "onClick = { onSelect(workspace.id) }"),
            row("workspace detail and terminal", INPUT + "FilesPicker.kt", "WorkspacePickerListItem", "IconButton(onClick = { onNavigateToDetail(boundWorkspace.id) })", "IconButton(onClick = { onNavigateToTerminal(boundWorkspace.id) })"),
            row("workspace CWD picker", INPUT + "FilesPicker.kt", "FilesPicker", "if (showCwd)", "onClick = { showCwdSheet = true }", "WorkspaceCwdPickerSheet("),
            row("workspace CWD selection persists conversation", INPUT + "FilesPicker.kt", "FilesPicker", "WorkspaceCwdPickerSheet(", "onSelectCwd = { newCwd -> onUpdateConversation(conversation.copy(workspaceCwd = newCwd)) }"),
            row("workspace CWD navigate parent and directories", INPUT + "WorkspaceCwdPicker.kt", "WorkspaceCwdPickerSheet", "IconButton(", "browsePath = browsePath.substringBeforeLast('/', missingDelimiterValue = \"\")", "modifier = Modifier.clickable { browsePath = entry.path }"),
            row("workspace CWD reset and select", INPUT + "WorkspaceCwdPicker.kt", "WorkspaceCwdPickerSheet", "TextButton(onClick = { onSelectCwd(null)", "FilledTonalButton(onClick = { onSelectCwd(toAbsolutePath(browsePath))"),
            row("extensions picker", INPUT + "FilesPicker.kt", "FilesPicker", "onClick = { page = FilesPickerPage.EXTENSIONS }", "ExtensionPickerPage("),
            row("quick messages modes lorebooks skills toggles", "ui/components/ui/ExtensionSelector.kt", "ExtensionSelector", "onUpdate { current -> current.copy(quickMessageIds = if (checked) current.quickMessageIds + id else current.quickMessageIds - id) }", "onUpdate { current -> current.copy(modeInjectionIds = if (checked) current.modeInjectionIds + id else current.modeInjectionIds - id) }", "onUpdate { current -> current.copy(lorebookIds = if (checked) current.lorebookIds + id else current.lorebookIds - id) }", "onUpdate { current -> current.copy(enabledSkills = if (checked) current.enabledSkills + name else current.enabledSkills - name) }"),
            row("extension tabs navigate", "ui/components/ui/ExtensionSelector.kt", "ExtensionTabs", "ToggleButton(", "onCheckedChange = { if (!selected) scope.launch { pagerState.animateScrollToPage(index) } }"),
            row("extension managers quick messages prompts skills", INPUT + "FilesPicker.kt", "ExtensionPickerPage", "ExtensionSelector(", "onNavigateToQuickMessages = { onDismissAll() navController.navigate(Screen.QuickMessages) }", "onNavigateToPrompts = { onDismissAll() navController.navigate(Screen.Prompts) }", "onNavigateToSkills = { onDismissAll() navController.navigate(Screen.Skills) }"),
            row("context compression from attachments", CHAT + "ChatPage.kt", "ChatFilesPickerSheet", "FilesPicker(", "onCompressContext = { additionalPrompt, targetTokens, keepRecentMessages ->", "vm.handleCompressContext(additionalPrompt, targetTokens, keepRecentMessages)"),
            row("compression dialog options and confirmation", INPUT + "CompressContextDialog.kt", "CompressContextDialog", "onClick = { selectedTokens = tokens }", "onValueChange = { keepRecentMessages = it }", "onValueChange = { additionalPrompt = it }", "currentJob = onConfirm(additionalPrompt, selectedTokens, keepRecentMessages)", "TextButton(onClick = onDismiss)"),
            row("voice start attachment", INPUT + "FilesPicker.kt", "FilesPicker", "onStartVoiceMode?.let { start ->", "AttachmentAction(HugeIcons.Voice, R.string.chat_page_voice_title, start)"),
            row("voice retry and stop", INPUT + "VoiceModeRow.kt", "VoiceModeRow", "if (isError) TextButton(onClick = onRetry)", "TextButton(onClick = onStop)"),
            row("voice mode start and stop callbacks", INPUT + "ChatInput.kt", "ChatInput", "VoiceModeRow(", "onRetry = { onStartVoiceMode?.invoke() }", "onStop = onStopVoiceMode"),
            row("dictation start stop and permission", INPUT + "ChatInput.kt", "ChatInput", "AsrButton(", "ASRStatus.Listening -> asr.stop()", "asrPermission.requestPermissions()", "asr.start { transcript ->", "state.setMessageText(asrBaseText + spacer + transcript)"),
            row("queue resume", INPUT + "MessageQueuePanel.kt", "MessageQueuePanel", "if (state.paused)", "TextButton(onClick = onResume)"),
            row("queue edit and remove", INPUT + "MessageQueuePanel.kt", "MessageQueuePanel", "onClick = { editing = onBeginEdit(message.id) }", "onClick = { onRemove(message.id) }"),
            row("queue edit save cancel and release", INPUT + "MessageQueuePanel.kt", "MessageQueuePanel", "onFinishEdit(message.id, input.getContents())", "TextButton(onClick = { editing = null })", "onDispose { finishEdit(message.id, null) }"),
            row("header drawer open", CHAT + "ChatPage.kt", "TopBar", "ChatHeader(", "onOpenDrawer = { scope.launch { drawerState.open() } }"),
            row("header title edit and save", CHAT + "ChatPage.kt", "TopBar", "if (conversation.messageNodes.isNotEmpty())", "titleState.open(conversation.title)", "titleState.confirm()", "onUpdateTitle(it)"),
            row("header preview and new conversation", CHAT + "ChatPage.kt", "TopBar", "ChatHeader(", "onClickMenu = { onClickMenu() }", "onNewChat = { onNewChat() }"),
            row("preview search clear and jump", CHAT + "ChatList.kt", "ChatListPreview", "OutlinedTextField(", "onValueChange = { searchQuery = it }", "IconButton(onClick = { searchQuery = \"\" })", "onJumpToMessage(originalIndex)"),
            row("share enters message multiselection", CHAT + "ChatList.kt", "ChatListNormal", "onShare = { selecting = true", "selectedItems.addAll(conversation.messageNodes.map { it.id }", "ListSelectableItem(", "enabled = selecting"),
            row("selection cancel select all and export confirm", CHAT + "ChatList.kt", "ChatListNormal", "HorizontalFloatingToolbar(", "selecting = false selectedItems.clear()", "if (selectedItems.isNotEmpty()) { selectedItems.clear() } else { selectedItems.addAll(conversation.messageNodes.map { it.id }) }", "if (messages.isNotEmpty()) { showExportSheet = true }", "ChatExportSheet("),
            row("Markdown export", CHAT + "ConversationExport.kt", "ChatExportSheet", "OutlinedCard( onClick = { exportToMarkdown(context, conversation, selectedMessages, exportOptions)"),
            row("image export and reasoning options", CHAT + "ConversationExport.kt", "ChatExportSheet", "exportOptions = exportOptions.copy(includeReasoning = it)", "enabled = exportOptions.includeReasoning", "exportOptions = exportOptions.copy(expandReasoning = it)", "exportToImage("),
            row("suggestion fills input", CHAT + "ChatPage.kt", "ChatPageContent", "onClickSuggestion = { suggestion -> inputState.editingMessage = null inputState.setMessageText(suggestion) }"),
            row("conversation system prompt edit", CHAT + "ChatList.kt", "ChatListNormal", "assistant?.allowConversationSystemPrompt == true", "ConversationSystemPromptButton(", "onSystemPromptChange = onConversationSystemPromptChange"),
            row("conversation system prompt expand save and reset", CHAT + "ConversationSystemPromptCard.kt", "ConversationSystemPromptButton", "onClick = { expanded = !expanded }", "onSystemPromptChange(null)", "onSystemPromptChange(editText.ifBlank { null })"),
            row("message scroll jumper", CHAT + "ChatList.kt", "MessageJumper", "state.scrollToItem(0)", "state.animateScrollToItem(", "state.scrollToItem(state.layoutInfo.totalItemsCount - 1)", "R.string.chat_page_scroll_to_bottom"),
            row("message list forwards every selected-node action", CHAT + "ChatList.kt", "ChatListNormal", "ChatMessage(", "onRegenerate = { onRegenerate(node.currentMessage) }", "onEdit = { onEdit(node.currentMessage) }", "onFork = { onForkMessage(node.currentMessage) }", "onDelete = { onDelete(node.currentMessage) }", "onUpdate = { onUpdateMessage(it) }", "onToggleFavorite = { onToggleFavorite?.invoke(node) }", "onTranslate = onTranslate", "onClearTranslation = onClearTranslation", "onToolApproval = onToolApproval", "onToolAnswer = onToolAnswer"),
            row("message connects buttons sheet and tool callbacks", MESSAGE + "ChatMessage.kt", "ChatMessage", "ChatMessageActionButtons( message = message, readOnly = readOnly, onRegenerate = onRegenerate, node = node, onUpdate = onUpdate, onOpenActionSheet = { showActionsSheet = true }, onTranslate = onTranslate, onClearTranslation = onClearTranslation", "ChatMessageActionsSheet( message = message, readOnly = readOnly, onEdit = onEdit, onDelete = onDelete, onShare = onShare, onFork = onFork", "onToolApproval = if (presentation.canInteractWithTools) onToolApproval else null", "onToolAnswer = if (presentation.canInteractWithTools) onToolAnswer else null"),
            row("incomplete history offers recovery instead of editing", CHAT + "ChatPage.kt", "ChatPageContent", "if (conversation.historyLoadState == HistoryLoadState.INCOMPLETE)", "HistoryRecoveryExport()", "else if (conversation.historyLoadState == HistoryLoadState.COMPLETE) ChatInput("),
            row("read-only recovery exports the raw complete backup", CHAT + "HistoryRecoveryExport.kt", "HistoryRecoveryExport", "Text(stringResource(R.string.chat_page_history_read_only)", "Text(stringResource(R.string.chat_page_history_export))", "ActivityResultContracts.CreateDocument(\"application/zip\")", "backupManager.createBackup(includeDatabase = true, includeFiles = true)", "launcher.launch(\"rikka_history_backup.zip\")"),
            row("chat page forwards actions to actual view model", CHAT + "ChatPage.kt", "ChatPageContent", "onRegenerate = { vm.regenerateAtMessage(it) }", "onEdit = { inputState.editingMessage = it.id inputState.setContents(it.parts) }", "vm.forkMessage(message = it)", "onDelete = { if (loadingJob != null) { vm.showDeleteBlockedWhileGeneratingError() } else { vm.deleteMessage(it) } }", "onUpdateMessage = { newNode -> vm.updateConversation(", "onTranslate = { message, locale -> vm.translateMessage(message, locale) }", "onClearTranslation = { message -> vm.clearTranslationField(message.id) }", "onToolApproval = { toolCallId, approved, reason, scope, toolName -> vm.handleToolApproval(toolCallId, approved, reason, scope, toolName) }", "onToolAnswer = { toolCallId, answer -> vm.handleToolAnswer(toolCallId, answer) }", "onToggleFavorite = { node -> vm.toggleMessageFavorite(node) }"),
        )
    }
}
