package me.rerere.rikkahub.ui.pages.setting

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.ChatFontFamily
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FileUtils
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.theme.rememberChatFontFamily
import me.rerere.rikkahub.utils.plus
import me.rerere.ui.components.Select
import org.koin.androidx.compose.koinViewModel
import java.io.File
import kotlin.math.roundToInt

@Composable
fun SettingPreferencesUIPage() = SettingInterfacePage()

@Composable
fun SettingPreferencesUILegacyPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var displaySetting by remember(settings) { mutableStateOf(settings.displaySetting) }
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val chatFontFamily = rememberChatFontFamily(displaySetting)

    fun updateDisplaySetting(setting: (DisplaySetting) -> DisplaySetting) {
        displaySetting = setting(displaySetting)
        vm.updateDisplaySetting(setting)
    }

    val importSuccessMsg = stringResource(R.string.setting_display_page_custom_font_import_success)
    val importFailedMsg = stringResource(R.string.setting_display_page_custom_font_import_failed)
    val fontPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    importCustomChatFontInternal(context, uri)
                }
            }.onSuccess { importedFont ->
                updateDisplaySetting { current -> current.copy(
                        chatFontFamily = ChatFontFamily.CUSTOM,
                        chatCustomFontPath = importedFont.relativePath,
                        chatCustomFontName = importedFont.displayName,
                    ) }
                toaster.show(importSuccessMsg, type = ToastType.Success)
            }.onFailure { error ->
                toaster.show(importFailedMsg.format(error.message.orEmpty()), type = ToastType.Error)
            }
        }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.setting_page_preferences_ui))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_page_message_display_settings)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_show_user_avatar_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_show_user_avatar_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.showUserAvatar,
                                onCheckedChange = {
                                    updateDisplaySetting { current -> current.copy(showUserAvatar = it) }
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_show_assistant_bubble_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_show_assistant_bubble_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.showAssistantBubble,
                                onCheckedChange = {
                                    updateDisplaySetting { current -> current.copy(showAssistantBubble = it) }
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_bubble_opacity_title)) },
                        supportingContent = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Slider(
                                    value = displaySetting.bubbleOpacity,
                                    onValueChange = {
                                        updateDisplaySetting { current -> current.copy(bubbleOpacity = it) }
                                    },
                                    valueRange = 0.1f..1.0f,
                                    steps = 8,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(text = "${(displaySetting.bubbleOpacity * 100).roundToInt()}%")
                            }
                        }
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_chat_list_model_icon_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_chat_list_model_icon_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.showModelIcon,
                                onCheckedChange = {
                                    updateDisplaySetting { current -> current.copy(showModelIcon = it) }
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_show_model_name_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_show_model_name_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.showModelName,
                                onCheckedChange = {
                                    updateDisplaySetting { current -> current.copy(showModelName = it) }
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_show_datetime_in_message_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_show_datetime_in_message_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.showDateTimeInMessage,
                                onCheckedChange = {
                                    updateDisplaySetting { current -> current.copy(showDateTimeInMessage = it) }
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_show_token_usage_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_show_token_usage_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.showTokenUsage,
                                onCheckedChange = {
                                    updateDisplaySetting { current -> current.copy(showTokenUsage = it) }
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_show_thinking_content_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_show_thinking_content_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.showThinkingContent,
                                onCheckedChange = {
                                    updateDisplaySetting { current -> current.copy(showThinkingContent = it) }
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_auto_collapse_thinking_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_auto_collapse_thinking_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.autoCloseThinking,
                                onCheckedChange = {
                                    updateDisplaySetting { current -> current.copy(autoCloseThinking = it) }
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_enable_latex_rendering_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_enable_latex_rendering_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.enableLatexRendering,
                                onCheckedChange = {
                                    updateDisplaySetting { current -> current.copy(enableLatexRendering = it) }
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_chat_font_family_title)) },
                        supportingContent = {
                            Select(
                                options = ChatFontFamily.entries,
                                selectedOption = displaySetting.chatFontFamily,
                                onOptionSelected = { family ->
                                    if (family == ChatFontFamily.CUSTOM && displaySetting.chatCustomFontPath.isBlank()) {
                                        fontPickerLauncher.launch(CustomFontMimeTypesUI)
                                    } else {
                                        updateDisplaySetting { current -> current.copy(chatFontFamily = family) }
                                    }
                                },
                                modifier = Modifier
                                    .padding(top = 4.dp)
                                    .fillMaxWidth(),
                                optionToString = { it.labelUI() },
                                optionLeading = { family ->
                                    Text(
                                        text = "Aa",
                                        fontFamily = family.toFontFamilyUI(chatFontFamily),
                                    )
                                }
                            )
                        }
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_custom_font_title)) },
                        supportingContent = {
                            Text(
                                if (displaySetting.chatCustomFontName.isNotBlank()) {
                                    displaySetting.chatCustomFontName
                                } else {
                                    stringResource(R.string.setting_display_page_custom_font_not_imported)
                                }
                            )
                        },
                        trailingContent = {
                            Row {
                                IconButton(
                                    onClick = { fontPickerLauncher.launch(CustomFontMimeTypesUI) }
                                ) {
                                    Icon(
                                        HugeIcons.FileImport,
                                        contentDescription = stringResource(
                                            R.string.setting_display_page_custom_font_import
                                        )
                                    )
                                }
                                if (displaySetting.chatCustomFontPath.isNotBlank()) {
                                    IconButton(
                                        onClick = {
                                            deleteCustomChatFontInternal(context, displaySetting.chatCustomFontPath)
                                            updateDisplaySetting { current -> current.copy(
                                                    chatFontFamily = ChatFontFamily.DEFAULT,
                                                    chatCustomFontPath = "",
                                                    chatCustomFontName = "",
                                                ) }
                                        }
                                    ) {
                                        Icon(
                                            HugeIcons.Delete02,
                                            contentDescription = stringResource(
                                                R.string.setting_display_page_custom_font_remove
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_font_size_title)) },
                        supportingContent = {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Slider(
                                        value = displaySetting.fontSizeRatio,
                                        onValueChange = {
                                            updateDisplaySetting { current -> current.copy(fontSizeRatio = it) }
                                        },
                                        valueRange = 0.5f..2f,
                                        steps = 11,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(text = "${(displaySetting.fontSizeRatio * 100).toInt()}%")
                                }
                                MarkdownBlock(
                                    content = stringResource(R.string.setting_display_page_font_size_preview),
                                    style = LocalTextStyle.current.copy(
                                        fontSize = LocalTextStyle.current.fontSize * displaySetting.fontSizeRatio,
                                        lineHeight = LocalTextStyle.current.lineHeight * displaySetting.fontSizeRatio,
                                        fontFamily = chatFontFamily
                                    )
                                )
                            }
                        }
                    )
                }
            }

            item {
                ToolTimelineDisplaySettings(
                    displaySetting = displaySetting,
                    onUpdate = ::updateDisplaySetting,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }

            item {
                MessageBehaviorDisplaySettings(
                    displaySetting = displaySetting,
                    onUpdate = ::updateDisplaySetting,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }

            item {
                MessageActionsDisplaySettings(
                    displaySetting = displaySetting,
                    onUpdate = ::updateDisplaySetting,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_page_code_display_settings)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_code_block_auto_wrap_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_code_block_auto_wrap_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.codeBlockAutoWrap,
                                onCheckedChange = {
                                    updateDisplaySetting { current -> current.copy(codeBlockAutoWrap = it) }
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_code_block_auto_collapse_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_code_block_auto_collapse_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.codeBlockAutoCollapse,
                                onCheckedChange = {
                                    updateDisplaySetting { current -> current.copy(codeBlockAutoCollapse = it) }
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_show_line_numbers_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_show_line_numbers_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.showLineNumbers,
                                onCheckedChange = {
                                    updateDisplaySetting { current -> current.copy(showLineNumbers = it) }
                                }
                            )
                        },
                    )
                }
            }
        }
    }
}

/** The tool timeline controls share the message display page and update existing preferences. */
@Composable
internal fun ToolTimelineDisplaySettings(
    displaySetting: DisplaySetting,
    onUpdate: ((DisplaySetting) -> DisplaySetting) -> Unit,
    modifier: Modifier = Modifier,
) {
    CardGroup(
        modifier = modifier,
        title = { Text(stringResource(R.string.setting_display_tool_timeline_title)) },
    ) {
        item(
            headlineContent = { Text(stringResource(R.string.setting_display_tool_summary_title)) },
            supportingContent = { Text(stringResource(R.string.setting_display_tool_summary_description)) },
            trailingContent = {
                Switch(
                    checked = displaySetting.showToolSummary,
                    onCheckedChange = { value -> onUpdate { current -> current.copy(showToolSummary = value) } },
                    modifier = Modifier.testTag("setting_tool_summary"),
                )
            },
        )
        item(
            headlineContent = { Text(stringResource(R.string.setting_display_tool_result_images_title)) },
            supportingContent = { Text(stringResource(R.string.setting_display_tool_result_images_description)) },
            trailingContent = {
                Switch(
                    checked = displaySetting.hideToolResultImages,
                    onCheckedChange = { value -> onUpdate { current -> current.copy(hideToolResultImages = value) } },
                    modifier = Modifier.testTag("setting_tool_result_images"),
                )
            },
        )
        item(
            headlineContent = { Text(stringResource(R.string.setting_display_tool_auto_collapse_title)) },
            supportingContent = { Text(stringResource(R.string.setting_display_tool_auto_collapse_description)) },
            trailingContent = {
                Switch(
                    checked = displaySetting.autoCollapseToolSteps,
                    onCheckedChange = { value -> onUpdate { current -> current.copy(autoCollapseToolSteps = value) } },
                    modifier = Modifier.testTag("setting_tool_auto_collapse"),
                )
            },
        )
    }
}

/** The user-message controls share the existing display page and its atomic preference updates. */
@Composable
internal fun MessageBehaviorDisplaySettings(
    displaySetting: DisplaySetting,
    onUpdate: ((DisplaySetting) -> DisplaySetting) -> Unit,
    modifier: Modifier = Modifier,
) {
    CardGroup(
        modifier = modifier,
        title = { Text(stringResource(R.string.setting_display_user_messages_title)) },
    ) {
        item(
            headlineContent = { Text(stringResource(R.string.setting_display_collapse_user_messages_title)) },
            supportingContent = { Text(stringResource(R.string.setting_display_collapse_user_messages_description)) },
            trailingContent = {
                Switch(
                    checked = displaySetting.collapseLongUserMessages,
                    onCheckedChange = { value -> onUpdate { it.copy(collapseLongUserMessages = value) } },
                    modifier = Modifier.testTag("setting_user_message_collapse"),
                )
            },
        )
        if (displaySetting.collapseLongUserMessages) {
            item(
                headlineContent = { Text(stringResource(R.string.setting_display_user_message_collapse_lines_title)) },
                supportingContent = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Slider(
                            value = displaySetting.userMessageCollapseLines.coerceIn(1, 30).toFloat(),
                            onValueChange = { value -> onUpdate { it.copy(userMessageCollapseLines = value.roundToInt()) } },
                            valueRange = 1f..30f,
                            steps = 28,
                            modifier = Modifier.weight(1f).testTag("setting_user_message_collapse_lines"),
                        )
                        Text(text = displaySetting.userMessageCollapseLines.toString())
                    }
                },
            )
        }
        item(
            headlineContent = { Text(stringResource(R.string.setting_display_user_message_actions_title)) },
            supportingContent = { Text(stringResource(R.string.setting_display_user_message_actions_description)) },
            trailingContent = {
                Switch(
                    checked = displaySetting.showUserMessageActions,
                    onCheckedChange = { value -> onUpdate { it.copy(showUserMessageActions = value) } },
                    modifier = Modifier.testTag("setting_user_message_actions"),
                )
            },
        )
    }
}

/** Moru's action, suggestion and file-row switches on the same message display page. */
@Composable
internal fun MessageActionsDisplaySettings(
    displaySetting: DisplaySetting,
    onUpdate: ((DisplaySetting) -> DisplaySetting) -> Unit,
    modifier: Modifier = Modifier,
) {
    CardGroup(
        modifier = modifier,
        title = { Text(stringResource(R.string.setting_display_message_actions_title)) },
    ) {
        item(
            headlineContent = { Text(stringResource(R.string.setting_display_confirm_assistant_regeneration_title)) },
            supportingContent = { Text(stringResource(R.string.setting_display_confirm_assistant_regeneration_description)) },
            trailingContent = {
                Switch(
                    checked = displaySetting.confirmAssistantRegeneration,
                    onCheckedChange = { value -> onUpdate { it.copy(confirmAssistantRegeneration = value) } },
                    modifier = Modifier.testTag("setting_confirm_assistant_regeneration"),
                )
            },
        )
        item(
            headlineContent = { Text(stringResource(R.string.setting_display_insert_suggestion_only_title)) },
            supportingContent = { Text(stringResource(R.string.setting_display_insert_suggestion_only_description)) },
            trailingContent = {
                Switch(
                    checked = displaySetting.insertSuggestionOnly,
                    onCheckedChange = { value -> onUpdate { it.copy(insertSuggestionOnly = value) } },
                    modifier = Modifier.testTag("setting_insert_suggestion_only"),
                )
            },
        )
        item(
            headlineContent = { Text(stringResource(R.string.setting_display_show_produced_files_title)) },
            supportingContent = { Text(stringResource(R.string.setting_display_show_produced_files_description)) },
            trailingContent = {
                Switch(
                    checked = displaySetting.showProducedFiles,
                    onCheckedChange = { value -> onUpdate { it.copy(showProducedFiles = value) } },
                    modifier = Modifier.testTag("setting_show_produced_files"),
                )
            },
        )
    }
}

private val CustomFontMimeTypesUI = arrayOf(
    "font/*",
    "application/x-font-ttf",
    "application/x-font-otf",
    "application/vnd.ms-opentype",
    "application/octet-stream",
    "*/*",
)

private val CustomFontExtensionsUI = setOf("ttf", "otf", "ttc")

private data class ImportedChatFontUI(
    val relativePath: String,
    val displayName: String,
)

@Composable
private fun ChatFontFamily.labelUI(): String = when (this) {
    ChatFontFamily.DEFAULT -> stringResource(R.string.setting_display_page_chat_font_family_default)
    ChatFontFamily.SERIF -> stringResource(R.string.setting_display_page_chat_font_family_serif)
    ChatFontFamily.MONOSPACE -> stringResource(R.string.setting_display_page_chat_font_family_monospace)
    ChatFontFamily.CUSTOM -> stringResource(R.string.setting_display_page_chat_font_family_custom)
}

private fun ChatFontFamily.toFontFamilyUI(customFontFamily: FontFamily): FontFamily = when (this) {
    ChatFontFamily.DEFAULT -> FontFamily.Default
    ChatFontFamily.SERIF -> FontFamily.Serif
    ChatFontFamily.MONOSPACE -> FontFamily.Monospace
    ChatFontFamily.CUSTOM -> customFontFamily
}

private fun importCustomChatFontInternal(context: Context, uri: Uri): ImportedChatFontUI {
    val displayName = FileUtils.getFileNameFromUri(context, uri)?.takeIf { it.isNotBlank() } ?: "custom_font"
    val extension = displayName.substringAfterLast('.', "")
        .lowercase()
        .takeIf { it in CustomFontExtensionsUI }
        ?: "ttf"
    val fontDir = File(context.filesDir, FileFolders.FONTS).apply { mkdirs() }
    val targetFile = File(fontDir, "chat_font.${System.currentTimeMillis()}.$extension")
    val tempFile = File(fontDir, "chat_font_import.tmp")

    try {
        tempFile.delete()
        context.contentResolver.openInputStream(uri)?.use { input ->
            tempFile.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: error("Unable to open selected font")

        runCatching {
            Typeface.createFromFile(tempFile)
        }.onFailure { error ->
            throw IllegalArgumentException(error.message ?: "Invalid font file", error)
        }

        replaceCustomChatFontInternal(fontDir, tempFile, targetFile)
    } catch (error: Throwable) {
        tempFile.delete()
        throw error
    }

    val relativePath = FileUtils.getRelativePathInFilesDir(context.filesDir, targetFile)
        ?: "${FileFolders.FONTS}/${targetFile.name}"
    return ImportedChatFontUI(relativePath = relativePath, displayName = displayName)
}

private fun replaceCustomChatFontInternal(fontDir: File, tempFile: File, targetFile: File) {
    val existingFiles = fontDir.listFiles { file ->
        file.isFile && file.name.startsWith("chat_font.") && file != tempFile
    }?.toList().orEmpty()
    val backups = existingFiles.map { file ->
        file to File(fontDir, "previous_${file.name}").also { it.delete() }
    }

    try {
        backups.forEach { (file, backup) ->
            check(file.renameTo(backup)) { "Unable to prepare existing font for replacement" }
        }
        check(tempFile.renameTo(targetFile)) { "Unable to save selected font" }
        backups.forEach { (_, backup) -> backup.delete() }
    } catch (error: Throwable) {
        tempFile.delete()
        backups.forEach { (file, backup) ->
            if (!file.exists() && backup.exists()) {
                backup.renameTo(file)
            }
        }
        throw error
    }
}

private fun deleteCustomChatFontInternal(context: Context, relativePath: String) {
    val filesDir = runCatching { context.filesDir.canonicalFile }.getOrNull() ?: return
    val fontFile = runCatching { File(filesDir, relativePath).canonicalFile }.getOrNull() ?: return
    if (fontFile.path.startsWith("${filesDir.path}${File.separator}")) {
        fontFile.delete()
    }
}
