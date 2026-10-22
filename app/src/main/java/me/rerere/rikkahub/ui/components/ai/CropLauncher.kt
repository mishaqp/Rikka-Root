package me.rerere.rikkahub.ui.components.ai

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toFile
import com.dokar.sonner.ToastType
import com.yalantis.ucrop.UCrop
import com.yalantis.ucrop.UCropActivity
import me.rerere.common.android.Logging
import me.rerere.common.android.appTempFolder
import me.rerere.rikkahub.ui.context.LocalToaster
import java.io.File
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun useCropLauncher(
    onCroppedImageReady: suspend (Uri) -> Unit,
    onCleanup: (() -> Unit)? = null,
    aspectRatio: Pair<Float, Float>? = null,
    freeStyleCropEnabled: Boolean = true
): Pair<ActivityResultLauncher<Intent>, (Uri) -> Unit> {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    var cropOutputUri by remember { mutableStateOf<Uri?>(null) }
    val scope = rememberCoroutineScope()

    val cropActivityLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val completedOutput = cropOutputUri
        cropOutputUri = null
        // The external crop is finished; its input can now be released. The output stays
        // leased until the suspending consumer has finished copying it.
        onCleanup?.invoke()
        scope.launch(Dispatchers.Main.immediate, start = CoroutineStart.UNDISPATCHED) {
            try {
                when (result.resultCode) {
                    android.app.Activity.RESULT_OK -> completedOutput?.let { onCroppedImageReady(it) }
                    UCrop.RESULT_ERROR -> {
                        val error = result.data?.let { UCrop.getError(it) }
                        Logging.log("CropLauncher", "crop failed: ${error?.message} | ${error?.stackTraceToString()}")
                        toaster.show("Не удалось обрезать изображение: ${error?.message ?: "неизвестная ошибка"}", type = ToastType.Error)
                    }
                }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { completedOutput?.toFile()?.delete() }
            }
        }
    }

    val launchCrop: (Uri) -> Unit = { sourceUri ->
        val outputFile = File(context.appTempFolder, "crop_output_${System.currentTimeMillis()}.jpg")
        cropOutputUri = Uri.fromFile(outputFile)

        var crop = UCrop.of(sourceUri, cropOutputUri!!).withOptions(UCrop.Options().apply {
            setFreeStyleCropEnabled(freeStyleCropEnabled)
            setAllowedGestures(
                UCropActivity.SCALE, UCropActivity.ROTATE, UCropActivity.NONE
            )
            // Chat attachments are written to a .jpg output file. PNG encoding is
            // particularly expensive for large camera/gallery images and provides
            // no benefit for this flow, so use JPEG to keep confirmation responsive.
            setCompressionFormat(Bitmap.CompressFormat.JPEG)
            setCompressionQuality(90)
        }).withMaxResultSize(4096, 4096)
        aspectRatio?.let { (x, y) ->
            crop = crop.withAspectRatio(x, y)
        }
        val cropIntent = crop.getIntent(context)

        cropActivityLauncher.launch(cropIntent)
    }

    return Pair(cropActivityLauncher, launchCrop)
}
