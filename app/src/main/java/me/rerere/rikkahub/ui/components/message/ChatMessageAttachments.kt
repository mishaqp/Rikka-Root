package me.rerere.rikkahub.ui.components.message

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.util.lerp
import androidx.core.content.FileProvider
import androidx.core.net.toFile
import androidx.core.net.toUri
import me.rerere.rikkahub.R
import me.rerere.rikkahub.utils.getActivity
import java.util.Locale

/**
 * 按压进度：按下趋向 1，松开回到 0，用来让容器的圆角在按下时收紧。
 * 回弹时可能略微超出 0..1
 */
@Composable
internal fun animatePressProgress(interactionSource: InteractionSource): State<Float> {
    val pressed by interactionSource.collectIsPressedAsState()
    return animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "PressProgress",
    )
}

internal fun Context.openLocalFile(url: String, mimeType: String? = null) {
    fun explain(message: Int) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    try {
        val source = url.toUri()
        val uri = when (source.scheme?.lowercase(Locale.ROOT)) {
            "file" -> {
                val file = source.toFile()
                if (!file.isFile || !file.canRead()) {
                    explain(R.string.chat_attachment_file_unavailable)
                    return
                }
                FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            }
            "content" -> source
            "http", "https" -> {
                if (source.host.isNullOrBlank()) {
                    explain(R.string.chat_attachment_invalid_uri)
                    return
                }
                source
            }
            else -> {
                explain(R.string.chat_attachment_invalid_uri)
                return
            }
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            if (uri.scheme == "content") {
                val declaredType = mimeType?.takeIf { it.isNotBlank() }
                val inferredType = if (source.scheme == "content") contentResolver.getType(source) else {
                    val extension = source.lastPathSegment?.substringAfterLast('.', "")?.lowercase(Locale.ROOT)
                    MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                }
                val resolvedType = declaredType?.takeUnless { it.endsWith("/*") || it == "application/octet-stream" }
                    ?: inferredType ?: declaredType ?: "application/octet-stream"
                setDataAndType(uri, resolvedType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newRawUri("attachment", uri)
            } else {
                // HTTP(S) browser filters accept the URI without a local-file MIME constraint.
                data = uri
            }
        }
        val chooser = Intent.createChooser(intent, null).apply {
            if (getActivity() == null) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(chooser)
    } catch (_: ActivityNotFoundException) {
        explain(R.string.chat_attachment_no_viewer)
    } catch (_: SecurityException) {
        explain(R.string.chat_attachment_open_failed)
    } catch (_: IllegalArgumentException) {
        explain(R.string.chat_attachment_open_failed)
    }
}

// 视频、音频附件：和图片缩略图等高的色块
@Composable
internal fun ChatMessageMediaTile(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressProgress by animatePressProgress(interactionSource)
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(lerp(20.dp, 8.dp, pressProgress)),
        color = MaterialTheme.colorScheme.secondaryContainer,
        interactionSource = interactionSource,
    ) {
        Box(modifier = Modifier.size(72.dp), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

// 文件胶囊：文档附件、工作区里改过的文件
@Composable
internal fun ChatMessageFileChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.tertiaryContainer,
    icon: (@Composable () -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressProgress by animatePressProgress(interactionSource)
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(percent = lerp(50, 22, pressProgress).coerceIn(0, 50)),
        color = color,
        interactionSource = interactionSource,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            icon?.invoke()
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 200.dp),
            )
        }
    }
}
