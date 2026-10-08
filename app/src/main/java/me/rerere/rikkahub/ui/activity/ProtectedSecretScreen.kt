package me.rerere.rikkahub.ui.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.local.PersonalUiRequest
import me.rerere.rikkahub.data.ai.tools.local.formatProtectedSecret
import java.util.Base64

/** Deliberately no rememberSaveable, clipboard, Intent payload or message parts. */
@Composable
internal fun ColumnScope.ProtectedSecretScreen(request: PersonalUiRequest, onSubmit: (ByteArray) -> Unit, onClose: () -> Unit) {
    if (request is PersonalUiRequest.Encrypt) {
        var value by remember { mutableStateOf("") }
        var invalid by remember { mutableStateOf(false) }
        DisposableEffect(Unit) { onDispose { value="" } }
        // Bounded field scrolls internally; the form also scrolls when the keyboard narrows the viewport.
        // The activity's Cancel button stays outside this weighted area.
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.personal_tools_secret_input_notice))
            Text(stringResource(if (request.encoding == "base64") R.string.personal_tools_secret_base64 else R.string.personal_tools_secret_text))
            OutlinedTextField(value=value, onValueChange={
                val limit=if (request.encoding == "base64") 87384 else 16000
                if (it.length <= limit) { value=it; invalid=false } else invalid=true
            }, modifier=Modifier.fillMaxWidth(), visualTransformation=PasswordVisualTransformation(), isError=invalid, maxLines=5,
                keyboardOptions=KeyboardOptions(autoCorrectEnabled=false, keyboardType=KeyboardType.Password),
                label={ Text(stringResource(R.string.personal_tools_secret_label)) })
            if (invalid) Text(stringResource(R.string.personal_tools_secret_invalid))
            Button(onClick={
                var bytes: ByteArray?=null
                try {
                    bytes=if (request.encoding == "base64") Base64.getDecoder().decode(value) else value.toByteArray(Charsets.UTF_8)
                    require(bytes.size <= 65536)
                    value=""; onSubmit(bytes)
                } catch (_: IllegalArgumentException) { bytes?.fill(0); invalid=true }
            }) { Text(stringResource(R.string.personal_tools_encrypt)) }
        }
    } else if (request is PersonalUiRequest.Reveal) {
        val text=remember(request) { formatProtectedSecret(request.bytes) }
        Text(stringResource(R.string.personal_tools_secret_output_notice))
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), verticalArrangement=Arrangement.SpaceBetween) { Text(text) }
        Button(onClick=onClose) { Text(stringResource(R.string.personal_tools_done)) }
    }
}
