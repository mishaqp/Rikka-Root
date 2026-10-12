package me.rerere.rikkahub.ui.components.message

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Leaf controls reached from messages; owner scopes disambiguate repeated renderer methods. */
@RunWith(Parameterized::class)
class ChatRichContentChecklistTest(
    private val action: String, private val path: String,
    private val function: String, private val fragments: List<String>,
) {
    @Test fun productionLeafControlRetainsItsAction() {
        val body = ChatProductionSource.canonical(ChatProductionSource.function(path, function))
        fragments.forEach { fragment -> assertTrue("$action missing in $path::$function: $fragment", body.contains(ChatProductionSource.canonical(fragment))) }
    }

    companion object {
        private const val MESSAGE = "ui/components/message/"
        private const val RICH = "ui/components/richtext/"
        private fun row(action: String, path: String, function: String, vararg fragments: String): Array<Any> = arrayOf(action, path, function, fragments.toList())
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun actions(): List<Array<Any>> = listOf(
            row("thinking group expands and collapses", "ui/components/ui/ChainOfThought.kt", "ChainOfThought", ".clickable { expanded = !expanded }", "collapsedVisibleIndices?.mapNotNull { steps.getOrNull(it) }", "if (summaryLabel == null && canCollapse)"),
            row("thinking step title opens or toggles expansion", "ui/components/ui/ChainOfThought.kt", "ChainOfThoughtStepContent", ".clickable { onClick() }", ".clickable { onExpandedChange(!expanded) }"),
            row("reasoning step controlled expansion", MESSAGE + "ChatMessageReasoning.kt", "ChatMessageReasoningStep", "ControlledChainOfThoughtStep(", "expanded = state.expandState == ReasoningCardState.Expanded", "onExpandedChange = { state.onExpandedChange(it, loading, showThinkingContent) }"),
            row("approval pending gate and MCP redacted summary", MESSAGE + "ChatMessageTools.kt", "ChatMessageToolStep", "extra = if (isPending && onToolApproval != null)", "if (useCustomMcpPreview)", "McpApprovalRenderer.render(tool.toolName, it)", "text = redactedMcpPreview"),
            row("approval scope policy exclusion and in-flight debounce", MESSAGE + "ChatMessageTools.kt", "ChatMessageToolStep", "ToolPermissionPolicy.canGrantAlways(tool.toolName, tool.inputAsJson())", "ToolPermissionPolicy.canGrantForChat(tool.toolName, tool.inputAsJson())", "tool.toolName == \"browser_eval_js\" && it == ToolApprovalAction.Always", "chatScopeAllowed else broaderScopesAllowed", "if (inFlight) return@IconButton", "submission.submit(approvalScope)"),
            row("ask user single and multiple selection", MESSAGE + "ChatMessageTools.kt", "AskUserToolStep", "FilterChip(", "if (q.selectionType == \"multi\")", "multiAnswers[q.id] = if (option in selectedOptions) { selectedOptions - option } else { selectedOptions + option }", "answers[q.id] = option"),
            row("ask user submit requires every answer", MESSAGE + "ChatMessageTools.kt", "AskUserToolStep", "FilledTonalButton(", "enabled = questions.all { q ->", "\"multi\" -> !multiAnswers[q.id].isNullOrEmpty() || !answers[q.id].isNullOrBlank()", "else -> !answers[q.id].isNullOrBlank()", "onToolAnswer(tool.toolCallId, answerPayload.toString())"),
            row("memory tool delete", MESSAGE + "tools/BuiltinToolUIs.kt", "MemoryToolUI::Preview", "IconButton(", "memoryRepo.deleteMemory(memoryId)", "onDismissRequest()", "R.string.tool_ui_delete_memory"),
            row("text to speech tool replay", MESSAGE + "tools/BuiltinToolUIs.kt", "TextToSpeechToolUI::Summary", "FilledTonalIconButton(", "onClick = { scope.launch { eventBus.emit(AppEvent.Speak(text)) } }", "R.string.tool_ui_replay"),
            row("search result and image URLs", MESSAGE + "tools/BuiltinToolUIs.kt", "SearchWebPreview", ".clickable { context.openUrl(imageUrl) }", "Card( onClick = { context.openUrl(url) }"),
            row("bug report file share", MESSAGE + "tools/ToolUI.kt", "DefaultToolPreview", "context.tool.toolName == \"generate_bug_report\"", "TextButton(onClick = {", "Intent(Intent.ACTION_SEND)", "putExtra(Intent.EXTRA_STREAM, uri)", "androidContext.startActivity(Intent.createChooser(send,"),
            row("skill mini-app open scoped browser", MESSAGE + "SkillWebviewCard.kt", "SkillWebviewCardOrNull", "FilledTonalButton(onClick = {", "shouldWrapInIframe(webview.url, webview.iframe)", "context.startActivity(BrowserActivity.intent(", "skillRoot = skillRoot?.canonicalPath", "R.string.skill_webview_card_open"),
            row("message connects edited files controls", MESSAGE + "ChatMessage.kt", "ChatMessage", "EditedFilesList( parts = message.parts, assistant = assistant"),
            row("edited files select and show more", MESSAGE + "ChatMessageEditedFiles.kt", "EditedFilesList", "ProducedFileChip(entry.displayName", "selected = entry", "chat_produced_files_more", "showAll = true"),
            row("edited file export", MESSAGE + "ChatMessageEditedFiles.kt", "EditedFilesList", "ActivityResultContracts.CreateDocument(\"*/*\")", "exportLauncher.launch(entry.displayName)", "workspaceRepository.exportFile(workspaceId ?: return@use, area, relativePath, output)"),
            row("edited file share", MESSAGE + "ChatMessageEditedFiles.kt", "EditedFilesList", "FileActionCard(", "FileProvider.getUriForFile(", "Intent(Intent.ACTION_SEND)", "putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(", "context.startActivity(Intent.createChooser(intent, null))"),
            row("code collapse and expand", RICH + "HighlightCodeBlock.kt", "HighlightCodeBlock", "val displayOptions = currentMarkdownDisplayOptions()", "val canCollapse = codeLines.size > COLLAPSE_LINES", "onToggleExpanded = { isExpanded = !isExpanded }", "val displayCode = if (isExpanded) code else collapsedCode"),
            row("code block connects action toolbar", RICH + "HighlightCodeBlock.kt", "HighlightCodeBlock", "HighlightCodeActions(", "onTogglePreviewMode = {"),
            row("code save document and copy", RICH + "HighlightCodeBlock.kt", "HighlightCodeActions", "HugeIcons.Download04", "createDocumentLauncher.launch(", "HugeIcons.Copy01", "clipboardManager.setClipEntry(ClipEntry(ClipData.newPlainText(\"code\", code)))"),
            row("code HTML SVG inline preview and fullscreen", RICH + "HighlightCodeBlock.kt", "HighlightCodeActions", "if (canInlinePreview)", "onTogglePreviewMode()", "if (completeCodeBlock && normalizedLanguage in PREVIEWABLE_LANGUAGES)", "navController.navigate(Screen.WebView(contentId = contentId))"),
            row("Mermaid fullscreen and PNG download", RICH + "Mermaid.kt", "Mermaid", "IconButton(", "navController.navigate(Screen.WebView(contentId = contentId))", "webViewState.webView?.evaluateJavascript( \"exportSvgToPng();\", null )", "R.string.mermaid_export"),
            row("native Markdown table copy and CSV download", RICH + "MarkdownTableBlock.kt", "MarkdownTableBlock", "ClipData.newPlainText(\"table\", tableMarkdown)", "ActivityResultContracts.CreateDocument(\"text/csv\")", "outputStream.write(csv.toByteArray(Charsets.UTF_8))", "createDocumentLauncher.launch("),
            row("HTML details expand collapse", RICH + "MarkdownNew.kt", "HtmlDetails", ".clickable { expanded = !expanded }"),
            row("chart and table tabs", "@ui/charts/ChartCard.kt", "ChartModeToggle", "onClick = { onModeChange(ChartDisplayMode.Chart) }", "onClick = { onModeChange(ChartDisplayMode.Table) }"),
            row("chart mode tab click", "@ui/charts/ChartCard.kt", "ChartModeButton", ".clickable(role = Role.Tab, onClick = onClick)"),
            row("image opens full-screen pager and closes", RICH + "ZoomableAsyncImage.kt", "ZoomableAsyncImage", ".clickable { showImageViewer = true }", "ImagePreviewDialog(images = listOf(model ?: \"\")) { showImageViewer = false }"),
            row("image pager gestures and gallery save", "ui/components/ui/ImagePreviewDialog.kt", "ImagePreviewDialog", "ImagePager(", "IconButton(", "filesManager.saveMessageImage(context, imgUrl)", "Icon(HugeIcons.Download01"),
        )
    }
}
