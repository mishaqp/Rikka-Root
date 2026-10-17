package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.BubbleChatQuestion
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Tools
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.message.tools.DefaultToolPreview
import me.rerere.rikkahub.ui.components.message.tools.ToolUIContext
import me.rerere.rikkahub.ui.components.message.tools.ToolUIRegistry
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
import me.rerere.rikkahub.ui.components.ui.ChainOfThoughtScope
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.ui.components.DotLoading
import me.rerere.rikkahub.data.ai.tools.ToolPermissionPolicy
import me.rerere.rikkahub.data.ai.tools.isManagementTool
import me.rerere.rikkahub.data.ai.tools.managementToolApprovalDescription
import me.rerere.rikkahub.service.ChatService.ApprovalScope
import me.rerere.ai.ui.ServerToolStatus

private const val ASK_USER_TOOL_NAME = "ask_user"
private val MCP_CUSTOM_APPROVAL_TOOL_NAMES = setOf(
    "mcp_add", "mcp_update", "mcp_delete", "mcp_set_enabled", "mcp_set_tool_approval",
)

@Composable
fun ChainOfThoughtScope.ChatMessageServerToolStep(
    tool: UIMessagePart.ServerTool,
    status: TimelineStatus = when (tool.status) {
        ServerToolStatus.IN_PROGRESS -> TimelineStatus.Running
        ServerToolStatus.COMPLETED -> TimelineStatus.ResultReceived
        ServerToolStatus.FAILED -> TimelineStatus.Failed
    },
    fontSizeRatio: Float = 1f,
) {
    val loading = !tool.isFinished
    var showDetails by remember(tool.toolCallId) { mutableStateOf(false) }
    val preview = remember(tool.output) {
        tool.output?.let { presentToolResult(tool.toolName, it.toString(), it) }
    }
    val headline = remember(tool.input) { presentToolHeadline(tool.toolName, tool.input) }
    ControlledChainOfThoughtStep(
        expanded = true,
        onExpandedChange = {},
        contentVisible = true,
        testTag = "chat_timeline_server_details:${tool.toolCallId}",
        onClick = { showDetails = true },
        icon = {
            if (loading) {
                DotLoading(size = 18.dp)
            } else {
                Icon(
                    imageVector = timelineToolIcon(tool.toolName) ?: HugeIcons.Tools,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = LocalContentColor.current.copy(alpha = 0.7f),
                )
            }
        },
        label = {
            TimelineStepTitle(headline ?: timelineToolDisplayName(tool.toolName), loading, fontSizeRatio)
        },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (preview != null) ToolResultPreviewText(
                    preview = preview,
                    testTag = "chat_timeline_server_preview:${tool.toolCallId}",
                    fontSizeRatio = fontSizeRatio,
                )
                if (status !in quietSuccessStatuses) Text(
                    text = when (status) {
                        TimelineStatus.Running -> stringResource(R.string.chat_timeline_status_running)
                        TimelineStatus.Failed -> stringResource(R.string.chat_timeline_status_failed)
                        else -> stringResource(R.string.chat_timeline_status_result)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status == TimelineStatus.Failed) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
    if (showDetails) ModalBottomSheet(onDismissRequest = { showDetails = false }) {
        SelectionContainer {
            Column(
                modifier = Modifier.fillMaxHeight(0.8f).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(tool.toolName, style = MaterialTheme.typography.titleMedium)
                Text(timelineStatusText(status), style = MaterialTheme.typography.labelMedium)
                tool.input?.let { input ->
                    Text(stringResource(R.string.chat_timeline_server_input), style = MaterialTheme.typography.labelMedium)
                    Text(presentToolDetails(tool.toolName, input, isResult = false), style = MaterialTheme.typography.bodySmall)
                }
                tool.output?.let { result ->
                    Text(stringResource(R.string.chat_timeline_server_output), style = MaterialTheme.typography.labelMedium)
                    Text(presentToolDetails(tool.toolName, result), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
fun ChainOfThoughtScope.ChatMessageToolStep(
    tool: UIMessagePart.Tool,
    loading: Boolean = false,
    status: TimelineStatus = presentToolStatus(tool, loading),
    options: ToolDisplayOptions? = null,
    fontSizeRatio: Float = 1f,
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String, scope: ApprovalScope, toolName: String) -> kotlinx.coroutines.Job)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
) {
    // The session keeps the actual headers for approval/execution; only this presentation copy is masked.
    val tool = remember(tool) {
        me.rerere.rikkahub.data.ai.tools.HttpToolSecretSanitizer.presentation(tool)
    }
    // ask_user 是交互式问答流程, 不走注册式渲染框架
    if (tool.toolName == ASK_USER_TOOL_NAME) {
        AskUserToolStep(tool = tool, loading = loading, fontSizeRatio = fontSizeRatio, onToolAnswer = onToolAnswer)
        return
    }

    val renderer = remember(tool.toolName) { ToolUIRegistry.resolve(tool.toolName) }
    val outputJson = remember(tool) {
        if (tool.isExecuted) {
            parseToolOutputJson(tool.toolName, tool.output.filterIsInstance<UIMessagePart.Text>().map { it.text })
        } else {
            null
        }
    }
    // 输出不是 JSON (例如超长被截断后只剩文本预览) 时, 定制渲染器读不到任何字段, 详情改用默认渲染展示原文
    val outputUnparsable = tool.isExecuted && outputJson == null
    val context = remember(tool, loading) {
        ToolUIContext(
            tool = tool,
            arguments = if (isManagementTool(tool.toolName)) JsonObject(emptyMap()) else tool.inputAsJson(),
            content = if (tool.isExecuted) outputJson ?: JsonObject(emptyMap()) else null,
            loading = loading,
        )
    }

    var showResult by remember { mutableStateOf(false) }
    var showDenyDialog by remember { mutableStateOf(false) }
    val submission = remember(tool.toolCallId) { ToolApprovalSubmission() }
    val approvalScope = rememberCoroutineScope()
    val inFlight = submission.inFlight
    var expanded by remember { mutableStateOf(true) }
    val isPending = tool.isPending
    val isDenied = tool.approvalState is ToolApprovalState.Denied
    val images = tool.output.filterIsInstance<UIMessagePart.Image>()
    val webviewParts = tool.output.filterIsInstance<UIMessagePart.Text>().filter { it.hasSkillWebviewMeta() }
    val broaderScopesAllowed = remember(tool.toolName, tool.input) {
        ToolPermissionPolicy.canGrantAlways(tool.toolName, tool.inputAsJson())
    }
    val chatScopeAllowed = remember(tool.toolName, tool.input) {
        ToolPermissionPolicy.canGrantForChat(tool.toolName, tool.inputAsJson())
    }
    val useCustomMcpPreview = tool.toolName in MCP_CUSTOM_APPROVAL_TOOL_NAMES
    val currentMcpConfig = if (isPending && tool.toolName == "mcp_update") {
        val mcpSettings by me.rerere.rikkahub.ui.hooks.rememberUserSettingsState()
        val serverId = runCatching { tool.inputAsJson().jsonObject["id"]?.jsonPrimitive?.contentOrNull }.getOrNull()
        mcpSettings.mcpServers.firstOrNull { it.id.toString() == serverId }
    } else null
    val redactedMcpPreview = if (isPending && useCustomMcpPreview) runCatching {
        (tool.inputAsJson() as? JsonObject)?.let {
            me.rerere.rikkahub.data.ai.mcp.control.McpApprovalRenderer.render(tool.toolName, it, currentMcpConfig)
        }
    }.getOrNull() else null
    val workflowPreview = if (isPending &&
        me.rerere.rikkahub.workflow.tools.WorkflowApprovalRenderer.isWorkflowTool(tool.toolName)) runCatching {
        me.rerere.rikkahub.workflow.tools.WorkflowApprovalRenderer.renderPlain(tool.toolName, tool.input)
    }.getOrNull() else null
    val managementPreview = if (isPending) managementToolApprovalDescription(tool.toolName, tool.inputAsJson()) else null
    val resultText = if (!tool.hasExecutionCheckpoint()) tool.output
        .filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }.takeIf { it.isNotBlank() } else null
    val resultPreview = remember(tool, outputJson) {
        presentToolResult(tool.toolName, resultText, outputJson)
    }
    val headline = remember(tool.toolName, tool.input) { presentToolHeadline(tool.toolName, tool.inputAsJson()) }
    // Command output and errors are always shown as text; other tools keep their own summary renderer.
    val ownPreview = resultPreview != null && (resultPreview !is ToolResultPreview.Summary ||
        outputUnparsable || !renderer.hasSummary(context) ||
        (outputJson as? JsonObject)?.let { toolWarningMessage(tool.toolName, it) } != null)
    val pendingPreview = if (isPending && !useCustomMcpPreview && workflowPreview == null && !isManagementTool(tool.toolName)) {
        ((tool.inputAsJson() as? JsonObject)?.get("command") as? JsonPrimitive)?.contentOrNull
            ?: tool.input.takeIf { it.isNotBlank() }?.let {
                presentToolDetails(tool.toolName, tool.inputAsJson(), isResult = false)
            }
    } else null

    // 摘要由注册的渲染器决定; 图片输出与拒绝原因为所有工具通用
    val hasExtraContent = renderer.hasSummary(context) || isDenied || images.isNotEmpty() ||
        webviewParts.isNotEmpty() || resultText != null || pendingPreview != null ||
        status !in quietSuccessStatuses

    ControlledChainOfThoughtStep(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        testTag = "chat_timeline_tool_details:${tool.toolCallId}",
        icon = {
            if (loading) {
                DotLoading(
                    size = 18.dp
                )
            } else {
                Icon(
                    imageVector = if (renderer.toolName.isNotEmpty()) renderer.icon(context)
                        else timelineToolIcon(tool.toolName) ?: renderer.icon(context),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = LocalContentColor.current.copy(alpha = 0.7f)
                )
            }
        },
        label = {
            TimelineStepTitle(headline ?: timelineToolDisplayName(tool.toolName), loading, fontSizeRatio)
        },
        extra = if (isPending && onToolApproval != null) {
            {
                Column {
                    // Copied from Agent's approval-card path: secret headers must not be
                    // displayed as raw JSON before the user approves MCP configuration.
                    if (useCustomMcpPreview) {
                        if (!redactedMcpPreview.isNullOrBlank()) {
                            Text(
                                text = redactedMcpPreview,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (!workflowPreview.isNullOrBlank()) Column(
                        modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                    ) {
                        Text(workflowPreview, style = MaterialTheme.typography.labelSmall)
                    }
                    if (!managementPreview.isNullOrBlank()) Text(
                        managementPreview,
                        modifier = Modifier.testTag("chat_management_approval:${tool.toolCallId}"),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    if (!chatScopeAllowed) Text(
                        "Этот инструмент всегда требует отдельного подтверждения.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(ToolApprovalAction.Once, ToolApprovalAction.Deny).forEach { action ->
                            IconButton(
                                modifier = Modifier.testTag(if (action == ToolApprovalAction.Once)
                                    "chat_timeline_approval_once:${tool.toolCallId}" else
                                    "chat_timeline_approval_deny:${tool.toolCallId}"),
                                enabled = !inFlight,
                                onClick = {
                                    if (inFlight) return@IconButton
                                    if (action == ToolApprovalAction.Deny) {
                                        showDenyDialog = true
                                    } else {
                                        submission.submit(approvalScope) {
                                            onToolApproval(tool.toolCallId, action.approved, "", action.scope, tool.toolName)
                                        }
                                    }
                                },
                            ) {
                                Icon(
                                    imageVector = if (action == ToolApprovalAction.Once) HugeIcons.Tick01 else HugeIcons.Cancel01,
                                    contentDescription = action.label,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                        IconButton(
                            modifier = Modifier.testTag("chat_timeline_approval_scopes:${tool.toolCallId}"),
                            enabled = !inFlight,
                            onClick = { showResult = true },
                        ) {
                            Icon(
                                imageVector = HugeIcons.MoreVertical,
                                contentDescription = stringResource(R.string.chat_timeline_scope_actions),
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }
        } else {
            null
        },
        extraBelowLabel = isPending && onToolApproval != null,
        onClick = { showResult = true },
        content = if (hasExtraContent) {
            {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // As in Moru, a successful step shows only its result; other states keep their label.
                    if (status !in quietSuccessStatuses) Text(
                        text = timelineStatusText(status),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (status == TimelineStatus.Failed || status == TimelineStatus.Denied ||
                            status == TimelineStatus.Blocked || status == TimelineStatus.Indeterminate)
                            MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (ownPreview && resultPreview != null) ToolResultPreviewText(
                        preview = resultPreview,
                        testTag = "chat_timeline_tool_preview:${tool.toolCallId}",
                        fontSizeRatio = fontSizeRatio,
                    ) else if (!tool.hasExecutionCheckpoint()) renderer.Summary(context)
                    if (pendingPreview != null) Text(
                        text = pendingPreview,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 12.sp * fontSizeRatio,
                            lineHeight = 16.8.sp * fontSizeRatio,
                            fontFamily = LocalTextStyle.current.fontFamily,
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    webviewParts.forEach { SkillWebviewCardOrNull(it) }
                    if (images.isNotEmpty() && options?.hideResultImages != true) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.wrapContentWidth(),
                        ) {
                            items(images) { image ->
                                ZoomableAsyncImage(
                                    model = image.url,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .height(120.dp)
                                        .widthIn(max = 240.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                )
                            }
                        }
                    }
                    if (isDenied) {
                        val reason = (tool.approvalState as ToolApprovalState.Denied).reason
                        Text(
                            text = stringResource(R.string.chat_message_tool_denied) +
                                if (reason.isNotBlank()) ": $reason" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        } else {
            null
        },
    )

    if (showDenyDialog && onToolApproval != null) {
        ToolDenyReasonDialog(
            onDismiss = { showDenyDialog = false },
            onConfirm = { reason ->
                showDenyDialog = false
                if (!inFlight) {
                    submission.submit(approvalScope) {
                        onToolApproval(tool.toolCallId, false, reason, ApprovalScope.Once, tool.toolName)
                    }
                }
            }
        )
    }

    if (showResult) {
        ModalBottomSheet(
            modifier = Modifier.testTag("chat_timeline_tool_sheet:${tool.toolCallId}"),
            sheetState = rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
            ),
            onDismissRequest = { showResult = false },
            content = {
                Column {
                    if (isPending && onToolApproval != null) {
                        if (!redactedMcpPreview.isNullOrBlank()) Text(
                            redactedMcpPreview, modifier = Modifier.padding(horizontal = 16.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (!workflowPreview.isNullOrBlank()) Column(
                            modifier = Modifier.padding(horizontal = 16.dp).heightIn(max = 320.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            Text(workflowPreview, style = MaterialTheme.typography.bodySmall)
                        }
                        if (!managementPreview.isNullOrBlank()) Text(
                            managementPreview, modifier = Modifier.padding(horizontal = 16.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        FlowRow(
                            modifier = Modifier.padding(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            listOf(ToolApprovalAction.ChatScope, ToolApprovalAction.Always).filterNot {
                                tool.toolName == "browser_eval_js" && it == ToolApprovalAction.Always
                            }.forEach { action ->
                                TextButton(
                                    enabled = !inFlight && if (action == ToolApprovalAction.ChatScope)
                                        chatScopeAllowed else broaderScopesAllowed,
                                    onClick = {
                                        submission.submit(approvalScope) {
                                            onToolApproval(tool.toolCallId, true, "", action.scope, tool.toolName)
                                        }
                                    },
                                ) { Text(action.label) }
                            }
                        }
                    }
                    // MCP controls can carry secrets in arguments; their pending sheet shows only
                    // the existing redacted approval preview and scoped actions.
                    if (!isPending || (!useCustomMcpPreview && !isManagementTool(tool.toolName))) {
                        if (resultPreview is ToolResultPreview.Error) DefaultToolPreview(context = context)
                        else if (outputUnparsable) DefaultToolPreview(context = context)
                        else renderer.Preview(context = context, onDismissRequest = { showResult = false })
                    }
                }
            },
        )
    }
}

private val quietSuccessStatuses = setOf(TimelineStatus.ResultReceived, TimelineStatus.Succeeded)

@Composable
private fun TimelineStepTitle(text: String, loading: Boolean, fontSizeRatio: Float) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(
            fontSize = 13.sp * fontSizeRatio,
            fontFamily = LocalTextStyle.current.fontFamily,
        ),
        color = MaterialTheme.colorScheme.secondary,
        modifier = Modifier.shimmer(isLoading = loading),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Command previews are compact; full failure reasons and partial-effect warnings are never cut. */
@Composable
private fun ToolResultPreviewText(preview: ToolResultPreview, testTag: String, fontSizeRatio: Float) {
    val base = MaterialTheme.typography.bodySmall.copy(
        fontSize = 12.sp * fontSizeRatio,
        lineHeight = 16.8.sp * fontSizeRatio,
        fontFamily = LocalTextStyle.current.fontFamily,
    )
    val mono = base.copy(fontFamily = FontFamily.Monospace, fontSize = 11.5.sp * fontSizeRatio)
    val error = MaterialTheme.colorScheme.error
    when (preview) {
        is ToolResultPreview.Command -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val hasOut = preview.stdout.isNotBlank()
            val hasErr = preview.stderr.isNotBlank()
            if (hasOut) Text(
                text = preview.stdout,
                modifier = Modifier.testTag(testTag),
                style = mono,
                maxLines = if (hasErr) 2 else 4,
                overflow = TextOverflow.Ellipsis,
            )
            if (hasErr) Text(
                text = preview.stderr,
                modifier = if (hasOut) Modifier else Modifier.testTag(testTag),
                style = mono,
                color = error,
                maxLines = if (hasOut) 2 else 4,
                overflow = TextOverflow.Ellipsis,
            )
            if (preview.exitCode != null) Text(
                text = stringResource(R.string.chat_timeline_exit_code, preview.exitCode),
                modifier = if (hasOut || hasErr) Modifier else Modifier.testTag(testTag),
                style = MaterialTheme.typography.labelSmall,
                color = error,
            )
            preview.reason?.let { reason -> Text(
                text = reason,
                style = base,
                color = error,
            ) }
        }

        is ToolResultPreview.Error -> Text(
            text = preview.message,
            modifier = Modifier.testTag(testTag),
            style = base,
            color = error,
        )

        is ToolResultPreview.Summary -> Text(
            text = preview.text,
            modifier = Modifier.testTag(testTag),
            style = base,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (preview.preserveFullText) Int.MAX_VALUE else 4,
            overflow = if (preview.preserveFullText) TextOverflow.Clip else TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun timelineStatusText(status: TimelineStatus): String = stringResource(when (status) {
    TimelineStatus.Running -> R.string.chat_timeline_status_running
    TimelineStatus.Waiting -> R.string.chat_timeline_status_waiting
    TimelineStatus.AwaitingApproval -> R.string.chat_timeline_status_approval
    TimelineStatus.AwaitingAnswer -> R.string.chat_timeline_status_answer
    TimelineStatus.Answered -> R.string.chat_timeline_status_answered
    TimelineStatus.Denied -> R.string.chat_timeline_status_denied
    TimelineStatus.ResultReceived, TimelineStatus.Succeeded -> R.string.chat_timeline_status_result
    TimelineStatus.Failed -> R.string.chat_timeline_status_failed
    TimelineStatus.Blocked -> R.string.chat_timeline_status_blocked
    TimelineStatus.Indeterminate -> R.string.chat_timeline_status_unknown
})

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChainOfThoughtScope.AskUserToolStep(
    tool: UIMessagePart.Tool,
    loading: Boolean,
    fontSizeRatio: Float,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)?,
) {
    val isPending = tool.isPending
    val isAnswered = tool.approvalState is ToolApprovalState.Answered
    val arguments = tool.inputAsJson()

    // Parse questions from arguments
    val questions = remember(arguments) {
        runCatching {
            arguments.jsonObject["questions"]?.jsonArray?.map { q ->
                val obj = q.jsonObject
                AskUserQuestion(
                    id = obj["id"]?.jsonPrimitive?.contentOrNull ?: "",
                    question = obj["question"]?.jsonPrimitive?.contentOrNull ?: "",
                    options = obj["options"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                    selectionType = obj["selection_type"]?.jsonPrimitive?.contentOrNull ?: "text"
                )
            } ?: emptyList()
        }.getOrElse { emptyList() }
    }

    // Track answers for text/single questions
    val answers = remember { mutableStateMapOf<String, String>() }
    // Track selected options for multi questions
    val multiAnswers = remember { mutableStateMapOf<String, Set<String>>() }

    var expanded by remember { mutableStateOf(true) }

    ControlledChainOfThoughtStep(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        icon = {
            if (loading) {
                DotLoading(size = 18.dp)
            } else {
                Icon(
                    imageVector = HugeIcons.BubbleChatQuestion,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = LocalContentColor.current.copy(alpha = 0.7f)
                )
            }
        },
        label = {
            Text(
                text = timelineToolDisplayName(ASK_USER_TOOL_NAME),
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp * fontSizeRatio,
                    fontFamily = LocalTextStyle.current.fontFamily),
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.shimmer(isLoading = loading),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        content = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                questions.forEach { q ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = q.question,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )

                        if (isPending && onToolAnswer != null) {
                            if (q.options.isNotEmpty()) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    q.options.forEach { option ->
                                        val selectedOptions = multiAnswers[q.id] ?: emptySet()
                                        FilterChip(
                                            selected = if (q.selectionType == "multi") {
                                                option in selectedOptions
                                            } else {
                                                answers[q.id] == option
                                            },
                                            onClick = {
                                                if (q.selectionType == "multi") {
                                                    multiAnswers[q.id] = if (option in selectedOptions) {
                                                        selectedOptions - option
                                                    } else {
                                                        selectedOptions + option
                                                    }
                                                } else {
                                                    answers[q.id] = option
                                                }
                                            },
                                            label = {
                                                Text(
                                                    text = option,
                                                    style = MaterialTheme.typography.labelSmall,
                                                )
                                            },
                                        )
                                    }
                                }
                            }

                            OutlinedTextField(
                                value = answers[q.id] ?: "",
                                onValueChange = { answers[q.id] = it },
                                modifier = Modifier.fillMaxWidth(),
                                textStyle = MaterialTheme.typography.bodySmall,
                                singleLine = false,
                                minLines = 1,
                                maxLines = 3,
                            )
                        } else if (isAnswered) {
                            // Show the user's answer
                            val answeredState = tool.approvalState as ToolApprovalState.Answered
                            val answerJson = runCatching {
                                JsonInstant.parseToJsonElement(answeredState.answer)
                            }.getOrNull()
                            val answerText = answerJson?.jsonObject?.get("answers")
                                ?.jsonObject?.get(q.id)?.jsonPrimitive?.contentOrNull
                                ?: answeredState.answer
                            Text(
                                text = answerText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }

                // Submit button
                if (isPending && onToolAnswer != null) {
                    FilledTonalButton(
                        onClick = {
                            val answerPayload = buildJsonObject {
                                put("answers", buildJsonObject {
                                    questions.forEach { q ->
                                        when (q.selectionType) {
                                            "multi" -> put(q.id, JsonPrimitive(
                                                (multiAnswers[q.id].orEmpty().toList() +
                                                    listOfNotNull(answers[q.id]?.takeIf { it.isNotBlank() }))
                                                    .joinToString(", ")
                                            ))
                                            else -> put(q.id, JsonPrimitive(answers[q.id] ?: ""))
                                        }
                                    }
                                })
                            }
                            onToolAnswer(tool.toolCallId, answerPayload.toString())
                        },
                        enabled = questions.all { q ->
                            when (q.selectionType) {
                                "multi" -> !multiAnswers[q.id].isNullOrEmpty() || !answers[q.id].isNullOrBlank()
                                else -> !answers[q.id].isNullOrBlank()
                            }
                        },
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Tick01,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = stringResource(R.string.chat_message_tool_submit),
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            }
        },
    )
}

private data class AskUserQuestion(
    val id: String,
    val question: String,
    val options: List<String>,
    val selectionType: String = "text", // "text" | "single" | "multi"
)

@Composable
private fun ToolDenyReasonDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var reason by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.chat_message_tool_deny_dialog_title))
        },
        text = {
            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it },
                label = { Text(stringResource(R.string.chat_message_tool_deny_dialog_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                minLines = 2,
                maxLines = 4
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(reason) }) {
                Text(stringResource(R.string.chat_message_tool_deny))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}
