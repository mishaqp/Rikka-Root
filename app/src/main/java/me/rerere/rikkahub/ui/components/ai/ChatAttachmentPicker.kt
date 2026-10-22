package me.rerere.rikkahub.ui.components.ai

import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.dokar.sonner.ToastType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.android.appTempFolder
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.ui.sketch.SketchDialog
import me.rerere.ui.sketch.SketchResult
import me.rerere.rikkahub.ui.components.ui.permission.PermissionCamera
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.utils.ImageUtils
import me.rerere.rikkahub.utils.isAllowedFileType
import org.koin.compose.koinInject
import java.io.File
import kotlin.uuid.Uuid

internal data class ChatAttachmentPickerActions(
    val onTakePicture: () -> Unit,
    val onPickImage: () -> Unit,
    val onPickVideo: () -> Unit,
    val onPickAudio: () -> Unit,
    val onPickFile: () -> Unit,
    val onSketch: () -> Unit,
)

@Composable
internal fun rememberChatAttachmentPickerActions(
    inputState: ChatInputState,
    setting: Settings,
    onAttachmentAdded: () -> Unit,
): ChatAttachmentPickerActions {
    val context = LocalContext.current
    val resources = LocalResources.current
    val toaster = LocalToaster.current
    val filesManager: FilesManager = koinInject()
    val scope = rememberCoroutineScope()
    val cameraPermission = rememberPermissionState(PermissionCamera)
    PermissionManager(permissionState = cameraPermission)

    var cameraOutputUri by remember { mutableStateOf<Uri?>(null) }
    var cameraOutputFile by remember { mutableStateOf<File?>(null) }
    val (_, launchCameraCrop) = useCropLauncher(
        onCroppedImageReady = { croppedUri ->
            inputState.addImages(filesManager.importChatFilesAsync(listOf(croppedUri)).map { it.localUri })
            onAttachmentAdded()
        },
        onCleanup = {
            cameraOutputFile?.delete()
            cameraOutputFile = null
            cameraOutputUri = null
        }
    )
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captureSuccessful ->
        if (captureSuccessful && cameraOutputUri != null) {
            if (setting.displaySetting.skipCropImage) {
                val source = cameraOutputUri!!
                val capturedFile = cameraOutputFile
                cameraOutputFile = null
                cameraOutputUri = null
                scope.launch(Dispatchers.Main.immediate, start = CoroutineStart.UNDISPATCHED) {
                    try {
                        inputState.addImages(filesManager.importChatFilesAsync(listOf(source)).map { it.localUri })
                        onAttachmentAdded()
                    } finally {
                        withContext(NonCancellable + Dispatchers.IO) { capturedFile?.delete() }
                    }
                }
            } else {
                launchCameraCrop(cameraOutputUri!!)
            }
        } else {
            cameraOutputFile?.delete()
            cameraOutputFile = null
            cameraOutputUri = null
        }
    }
    val onTakePicture: () -> Unit = {
        if (cameraPermission.allRequiredPermissionsGranted) {
            cameraOutputFile = context.cacheDir.resolve("camera_${Uuid.random()}.jpg")
            cameraOutputUri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", cameraOutputFile!!
            )
            cameraLauncher.launch(cameraOutputUri!!)
        } else {
            cameraPermission.requestPermissions()
        }
    }

    var preCropTempFile by remember { mutableStateOf<File?>(null) }
    val (_, launchImageCrop) = useCropLauncher(
        onCroppedImageReady = { croppedUri ->
            inputState.addImages(filesManager.importChatFilesAsync(listOf(croppedUri)).map { it.localUri })
            onAttachmentAdded()
        },
        onCleanup = {
            preCropTempFile?.delete()
            preCropTempFile = null
        }
    )
    val imagePickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            if (selectedUris.isNotEmpty()) {
                scope.launch(Dispatchers.Main.immediate, start = CoroutineStart.UNDISPATCHED) {
                    if (setting.displaySetting.skipCropImage || selectedUris.size > 1) {
                        inputState.addImages(filesManager.importChatFilesAsync(selectedUris).map { it.localUri })
                        onAttachmentAdded()
                    } else {
                        val source = selectedUris.single()
                        var temporary: File? = null
                        try {
                            val prepared = withContext(Dispatchers.IO) {
                                val tempFile = File(context.appTempFolder, "pick_temp_${Uuid.random()}.jpg")
                                temporary = tempFile
                                currentCoroutineContext().ensureActive()
                                val converted = ImageUtils.isHeifImage(context, source) &&
                                    ImageUtils.convertHeifToJpeg(context, source, tempFile)
                                currentCoroutineContext().ensureActive()
                                if (!converted) {
                                    requireNotNull(context.contentResolver.openInputStream(source)).use { input ->
                                        tempFile.outputStream().use { output ->
                                            val buffer = ByteArray(64 * 1024)
                                            while (true) {
                                                currentCoroutineContext().ensureActive()
                                                val size = input.read(buffer)
                                                if (size < 0) break
                                                currentCoroutineContext().ensureActive()
                                                output.write(buffer, 0, size)
                                            }
                                        }
                                    }
                                }
                                tempFile
                            }
                            preCropTempFile = prepared
                            launchImageCrop(prepared.toUri())
                        } catch (cancelled: CancellationException) {
                            withContext(NonCancellable + Dispatchers.IO) { temporary?.delete() }
                            throw cancelled
                        } catch (error: Exception) {
                            withContext(Dispatchers.IO) { temporary?.delete() }
                            Log.e("ImagePickButton", "Failed to copy image to temp, falling back", error)
                            launchImageCrop(source)
                        }
                    }
                }
            }
        }

    val videoPickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            if (selectedUris.isNotEmpty()) scope.launch(Dispatchers.Main.immediate, start = CoroutineStart.UNDISPATCHED) {
                inputState.addVideos(filesManager.importChatFilesAsync(selectedUris).map { it.localUri })
                onAttachmentAdded()
            }
        }

    val audioPickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            if (selectedUris.isNotEmpty()) scope.launch(Dispatchers.Main.immediate, start = CoroutineStart.UNDISPATCHED) {
                inputState.addAudios(filesManager.importChatFilesAsync(selectedUris).map { it.localUri })
                onAttachmentAdded()
            }
        }

    val filePickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) scope.launch(Dispatchers.Main.immediate, start = CoroutineStart.UNDISPATCHED) {
                val metadata = withContext(Dispatchers.IO) {
                    uris.map { uri ->
                        currentCoroutineContext().ensureActive()
                        Triple(uri, filesManager.getFileNameFromUri(uri) ?: "file",
                            filesManager.getFileMimeType(uri) ?: "text/plain")
                    }
                }
                val (accepted, rejected) = metadata.partition { (_, name, mime) -> isAllowedFileType(name, mime) }
                rejected.forEach { (_, name, _) ->
                    toaster.show(resources.getString(R.string.chat_input_unsupported_file_type, name), type = ToastType.Error)
                }
                val imported = filesManager.importChatFilesAsync(accepted.map { it.first })
                // Keep publication synchronous after the cancellable copy hands ownership to UI.
                val importedUris = imported.mapTo(hashSetOf()) { it.sourceUri }
                accepted.filterNot { it.first in importedUris }.forEach { (_, name, _) ->
                    toaster.show(resources.getString(R.string.chat_input_file_read_failed, name), type = ToastType.Error)
                }
                val sourceMime = accepted.associate { it.first to it.third }
                val documents = imported.map { file ->
                    UIMessagePart.Document(url = file.localUri.toString(), fileName = file.displayName,
                        mime = sourceMime.getValue(file.sourceUri))
                }
                if (documents.isNotEmpty()) {
                    inputState.addFiles(documents)
                    onAttachmentAdded()
                }
            }
        }

    var showSketch by remember { mutableStateOf(false) }
    if (showSketch) {
        SketchDialog(
            onDismiss = { showSketch = false },
            onConfirm = { result ->
                showSketch = false
                scope.launch(Dispatchers.Main.immediate) {
                    inputState.addImages(listOfNotNull(filesManager.createChatFileBySketch(result)))
                    onAttachmentAdded()
                }
            },
        )
    }

    return ChatAttachmentPickerActions(
        onTakePicture = onTakePicture,
        onPickImage = { imagePickerLauncher.launch("image/*") },
        onPickVideo = { videoPickerLauncher.launch("video/*") },
        onPickAudio = { audioPickerLauncher.launch("audio/*") },
        onPickFile = { filePickerLauncher.launch(arrayOf("*/*")) },
        onSketch = { showSketch = true },
    )
}

/** 把画板的结果存成聊天附件。 */
internal suspend fun FilesManager.createChatFileBySketch(result: SketchResult): Uri? = withContext(Dispatchers.IO) {
    createChatFilesByByteArrays(
        byteArrays = listOf(result.encode()),
        displayName = "sketch.${result.extension}",
        mimeType = result.mimeType,
    ).firstOrNull()
}
