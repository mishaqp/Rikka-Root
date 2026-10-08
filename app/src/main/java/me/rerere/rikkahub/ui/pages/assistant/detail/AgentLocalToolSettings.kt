package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.ui.CardGroup

@Composable
internal fun AgentLocalToolSettings(assistant: Assistant, onUpdate: (Assistant) -> Unit) {
    val enabled = LocalToolOption.CostGuards in assistant.localTools
    CardGroup {
        item(
            headlineContent = { Text(stringResource(R.string.agent_tools_cost_title)) },
            supportingContent = { Text(stringResource(R.string.agent_tools_cost_desc)) },
            trailingContent = { Switch(checked = enabled, onCheckedChange = { checked ->
                onUpdate(assistant.copy(localTools = if (checked)
                    (assistant.localTools + LocalToolOption.CostGuards).distinct()
                    else assistant.localTools - LocalToolOption.CostGuards))
            }) },
        )
        if (enabled) item(headlineContent = {
            Column(Modifier.padding(PaddingValues(vertical = 8.dp))) {
                TokenCapField(assistant.tokenBudgetSoftCap, R.string.agent_tools_soft_cap) { cap ->
                    onUpdate(assistant.copy(tokenBudgetSoftCap = cap))
                }
                TokenCapField(assistant.tokenBudgetHardCap, R.string.agent_tools_hard_cap) { cap ->
                    onUpdate(assistant.copy(tokenBudgetHardCap = cap))
                }
                Text(stringResource(R.string.agent_tools_caps_note))
            }
        })
    }
}

@Composable
private fun TokenCapField(value: Int?, label: Int, onChange: (Int?) -> Unit) {
    OutlinedTextField(
        value = value?.toString().orEmpty(),
        onValueChange = { input ->
            if (input.isEmpty()) onChange(null)
            else if (input.all(Char::isDigit)) input.toIntOrNull()?.takeIf { it > 0 }?.let(onChange)
        },
        label = { Text(stringResource(label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}
