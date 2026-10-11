package me.rerere.rikkahub.ui.components.message

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Existing attachment editor controls, their native crop entry point and permission buttons. */
@RunWith(Parameterized::class)
class ChatSketchFunctionChecklistTest(
    private val action: String, private val path: String,
    private val function: String, private val fragments: List<String>,
) {
    @Test fun existingAttachmentEditorActionRemainsWired() {
        val body = ChatProductionSource.canonical(ChatProductionSource.function(path, function))
        fragments.forEach { fragment -> assertTrue("$action missing in $path::$function: $fragment", body.contains(ChatProductionSource.canonical(fragment))) }
    }

    companion object {
        private const val SKETCH = "@ui/sketch/"
        private const val INPUT = "ui/components/ai/"
        private fun row(action: String, path: String, function: String, vararg fragments: String): Array<Any> = arrayOf(action, path, function, fragments.toList())
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun actions(): List<Array<Any>> = listOf(
            row("draw brush strokes", SKETCH + "SketchCanvas.kt", "SketchCanvas", "InProgressStrokes(", "nextBrush = { currentBrush }", "onStrokesFinished = state::draw"),
            row("select brush type", SKETCH + "SketchBrushMenu.kt", "SketchBrushMenu", "DropdownMenuItem(", "state.useBrush(brush)", "onDismissRequest = { expanded = false }"),
            row("select brush width", SKETCH + "SketchToolbar.kt", "SketchToolbar", "WidthOption(", "onClick = { state.width = width }"),
            row("erase drawing", SKETCH + "SketchToolbar.kt", "SketchToolbar", "FilledTonalIconToggleButton( checked = state.erasing, onCheckedChange = state::useEraser"),
            row("eraser pointer sampling and finish", SKETCH + "SketchCanvas.kt", "SketchCanvas", "state.erase((down.position - center) / scale, state.width.toPx() / scale)", "state.extend(line, (change.position - center) / scale)", "state.finish(line)"),
            row("select text tool and place text", SKETCH + "SketchToolbar.kt", "SketchToolbar", "checked = state.tool == SketchTool.Text", "onCheckedChange = state::useText"),
            row("text placement gesture", SKETCH + "SketchCanvas.kt", "SketchCanvas", "if (state.tool == SketchTool.Text)", "state.beginText((up.position - center) / scale, state.width.toPx() / scale)"),
            row("text entry confirm and cancel", SKETCH + "SketchTextInput.kt", "SketchTextInput", "TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank())", "TextButton(onClick = onDismiss)", "onDismissRequest = onDismiss"),
            row("text input writes to sketch", SKETCH + "SketchDialog.kt", "SketchDialog", "SketchTextInput(onConfirm = state::write, onDismiss = state::cancelText)"),
            row("select drawing tool", SKETCH + "SketchToolbar.kt", "SketchToolbar", "checked = state.tool == SketchTool.Select", "onCheckedChange = state::useSelect"),
            row("select move and scale gesture", SKETCH + "SketchSelection.kt", "selectGesture", "state.moving = SketchPlacement.scale(by, pivot)", "state.moving = SketchPlacement.move(it - start)", "state.select(marquee ?: Rect(center = start, radius = reach / 2), topmost = marquee == null)"),
            row("preset and custom color picker", SKETCH + "SketchToolbar.kt", "SketchToolbar", "ColorOption(", "onClick = { state.usePreset(color) }", "state.useCustom(SketchDefaults.CustomColor)", "state.pickingColor = !state.pickingColor"),
            row("custom hue saturation and value sliders", SKETCH + "SketchColorPicker.kt", "SketchColorPicker", "ColorSlider(", "onValueChange = { update(it, saturation, value) }", "onValueChange = { update(hue, it, value) }", "onValueChange = { update(hue, saturation, it) }", "state.useCustom(Color.hsv(newHue, newSaturation, newValue))"),
            row("undo redo and clear", SKETCH + "SketchDialog.kt", "SketchTopBar", "IconButton(onClick = state::undo, enabled = state.canUndo)", "IconButton(onClick = state::redo, enabled = state.canRedo)", "IconButton(onClick = state::clear, enabled = !state.isBlank)"),
            row("choose paper aspect ratio", SKETCH + "SketchPaperMenu.kt", "SketchPaperMenu", "DropdownMenuItem(", "enabled = !hasImage", "state.useAspectRatio(ratio)"),
            row("choose and remove background image", SKETCH + "SketchPaperMenu.kt", "SketchPaperMenu", "onChooseImage()", "state.useBackground(null)", "R.string.sketch_dialog_choose_image", "R.string.sketch_dialog_remove_image"),
            row("background picker imports image", SKETCH + "SketchDialog.kt", "SketchDialog", "ActivityResultContracts.PickVisualMedia()", "if (uri != null) scope.launch { loadBackground(uri) }", "state.useBackground(background)", "imagePicker.launch("),
            row("confirm completed sketch", SKETCH + "SketchDialog.kt", "SketchTopBar", "FilledIconButton(onClick = onConfirm, enabled = !state.isBlank)"),
            row("sketch confirmation sends result", SKETCH + "SketchDialog.kt", "SketchDialog", "onConfirm = { onConfirm(state.toResult()) }"),
            row("close sketch and confirm discard or keep editing", SKETCH + "SketchDialog.kt", "SketchDialog", "if (state.isBlank) onDismiss() else confirmDiscard = true", "onDismissRequest = { close() }", "onDismissRequest = { confirmDiscard = false }", "TextButton(onClick = { confirmDiscard = false })", "confirmDiscard = false onDismiss()"),
            row("attached image sketch replaces part without deleting historical files", INPUT + "AttachmentChips.kt", "MediaFileInputRow", "SketchDialog( image = part.url.toUri()", "if (it == part) UIMessagePart.Image(uri.toString()) else it", "state.shouldDeleteFileOnRemove(part)", "filesManager.deleteChatFiles(listOf(uri))"),
            row("crop success error cancellation cleanup", INPUT + "CropLauncher.kt", "useCropLauncher", "ActivityResultContracts.StartActivityForResult()", "android.app.Activity.RESULT_OK", "onCroppedImageReady(croppedUri)", "UCrop.RESULT_ERROR", "cropOutputUri?.toFile()?.delete()", "onCleanup?.invoke()"),
            row("crop scale rotate freestyle and launch", INPUT + "CropLauncher.kt", "useCropLauncher", "setFreeStyleCropEnabled(freeStyleCropEnabled)", "UCropActivity.SCALE, UCropActivity.ROTATE, UCropActivity.NONE", "cropActivityLauncher.launch(cropIntent)"),
            row("permission rationale proceed cancel and app settings", "ui/components/ui/permission/PermissionRationaleDialog.kt", "PermissionRationaleDialog", "if (hasPermanentlyDenied)", "onClick = onProceed", "onClick = onCancel", "R.string.permission_go_to_settings", "R.string.confirm"),
            row("compression cancellation and dialog dismiss", INPUT + "CompressContextDialog.kt", "CompressContextDialog", "TextButton(onClick = { currentJob?.cancel() currentJob = null })", "onDismissRequest = { if (!isLoading) { onDismiss() } }"),
            row("history edit cancel", INPUT + "ChatInput.kt", "TextInputRow", "R.string.cancel_edit", "modifier = Modifier.clickable { state.clearInput() }"),
            row("quick messages open and dismiss menu", INPUT + "ChatInput.kt", "QuickMessageButton", "IconButton( onClick = { expanded = !expanded })", "DropdownMenu(", "onDismissRequest = { expanded = false }"),
            row("model search and sheet dismiss", INPUT + "ModelList.kt", "ModelList", "OutlinedTextField(", "onValueChange = { searchKeywords = it }"),
            row("model sheet dismiss", INPUT + "ModelList.kt", "ModelListSheet", "ModalBottomSheet( onDismissRequest = { state.close() }", "sheetState.hide() state.close()"),
            row("search sheet dismiss and provider back", INPUT + "SearchPicker.kt", "SearchPickerButton", "onDismissRequest = { showSearchPicker = false }", "BackHandler(enabled = selectingProvider) { selectingProvider = false }"),
            row("search provider back button", INPUT + "SearchPicker.kt", "SearchProviderPicker", "IconButton(onClick = onBack)"),
            row("reasoning picker open and dismiss", INPUT + "ReasoningPicker.kt", "ReasoningButton", "onClick = { showPicker = true }", "onDismissRequest = { showPicker = false }"),
            row("input keyboard editing selection and ordinary paste", INPUT + "ChatInput.kt", "TextInputRow", "TextField( state = state.textContent", "else -> transferableContent", "TextFieldLineLimits.MultiLine(maxHeightInLines = 5)"),
            row("fullscreen editing uses shared input state", INPUT + "ChatInput.kt", "FullScreenEditor", "TextField( state = state.textContent", "onDismissRequest = { onDone() }"),
        )
    }
}
