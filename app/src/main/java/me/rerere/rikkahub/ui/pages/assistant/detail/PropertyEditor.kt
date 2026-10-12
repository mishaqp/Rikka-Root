package me.rerere.rikkahub.ui.pages.assistant.detail

import me.rerere.rikkahub.data.datastore.updateHeaderName
import me.rerere.rikkahub.data.datastore.updateHeaderValue
import me.rerere.rikkahub.data.datastore.updateBodyKey
import me.rerere.rikkahub.data.datastore.updateBodyValue
import me.rerere.rikkahub.data.datastore.deleteHeaderRow
import me.rerere.rikkahub.data.datastore.deleteBodyRow

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.key
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.highlight.LocalCodeHighlighter
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.richtext.HighlightCodeVisualTransformation
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.ui.components.SelectTextField

private val jsonLenient = Json {
    ignoreUnknownKeys = true
    isLenient = true
    prettyPrint = true
}

private val COMMON_HEADER_NAMES = listOf(
    "User-Agent",
    "HTTP-Referer",
    "X-Title",
    "Referer",
    "Origin",
    "Accept-Language",
    "Cookie",
    "anthropic-beta",
    "OpenAI-Organization",
    "OpenAI-Project",
)

// 按已输入内容过滤常用请求头，没有匹配(或已完整输入)时展示全部
private fun commonHeaderNames(input: String): List<String> {
    val keyword = input.trim()
    val matched = COMMON_HEADER_NAMES.filter {
        it.contains(keyword, ignoreCase = true) && !it.equals(keyword, ignoreCase = true)
    }
    return matched.ifEmpty { COMMON_HEADER_NAMES }
}

/** Local row tokens and pending intents are editor state only, never part of the saved schema. */
private class PendingPropertyRows<T>(initial: List<T>) {
    private class Pending<T>(val mutation: (List<T>) -> List<T>, val order: List<Int>) {
        var applied by mutableStateOf<List<T>?>(null)
        fun apply(current: List<T>) = mutation(current).also { applied = it }
    }
    var rows by mutableStateOf(initial)
        private set
    var order by mutableStateOf(initial.indices.toList())
        private set
    private var nextToken = initial.size
    private val pending = mutableListOf<Pending<T>>()

    fun receive(committed: List<T>) {
        // Applied snapshots come from the real transform, including concurrent fields it retained.
        // A later snapshot may arrive directly when StateFlow conflates intermediate commits.
        val acknowledged = pending.indexOfLast { it.applied == committed }
        if (acknowledged >= 0) {
            val committedOrder = pending[acknowledged].order
            repeat(acknowledged + 1) { pending.removeAt(0) }
            if (pending.isEmpty()) order = committedOrder
        }
        if (pending.isEmpty() && rows != committed) {
            if (rows.size != committed.size) order = committed.map { nextToken++ }
            rows = committed
        }
    }

    fun edit(token: Int, transform: (T) -> T, mutation: (List<T>, List<T>, Int) -> List<T>): ((List<T>) -> List<T>)? {
        val target = order.indexOf(token)
        if (target < 0) return null
        val observed = rows
        rows = rows.mapIndexed { i, row -> if (i == target) transform(row) else row }
        return enqueue { current -> mutation(current, observed, target) }
    }

    fun delete(token: Int, mutation: (List<T>, List<T>, Int) -> List<T>): ((List<T>) -> List<T>)? {
        val target = order.indexOf(token)
        if (target < 0) return null
        val observed = rows
        rows = rows.filterIndexed { i, _ -> i != target }
        order = order.filterIndexed { i, _ -> i != target }
        return enqueue { current -> mutation(current, observed, target) }
    }

    fun add(row: T): (List<T>) -> List<T> {
        rows = rows + row
        order = order + nextToken++
        return enqueue { it + row }
    }

    private fun enqueue(mutation: (List<T>) -> List<T>): (List<T>) -> List<T> {
        val intent = Pending(mutation, order)
        pending += intent
        return intent::apply
    }
}

@Composable
fun CustomHeaders(headers: List<CustomHeader>, onUpdate: ((List<CustomHeader>) -> List<CustomHeader>) -> Unit) {
    val pending = remember { PendingPropertyRows(headers) }
    pending.receive(headers)
    Column(
        modifier = Modifier.padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(stringResource(R.string.assistant_page_custom_headers))
        Spacer(Modifier.height(8.dp))

        pending.rows.forEachIndexed { index, header ->
            val token = pending.order[index]
            key(token) {
                var headerName by remember(header.name) { mutableStateOf(header.name) }
                var headerValue by remember(header.value) { mutableStateOf(header.value) }

                CardGroup {
                    item(
                        supportingContent = {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                val updateHeaderName: (String) -> Unit = { name ->
                                    headerName = name
                                    pending.edit(token, { it.copy(name = name.trim()) }) { current, observed, target ->
                                        updateHeaderName(current, observed, target, name.trim())
                                    }?.let(onUpdate)
                                }
                                SelectTextField(
                                    value = headerName,
                                    options = commonHeaderNames(headerName),
                                    onValueChange = updateHeaderName,
                                    onOptionSelected = updateHeaderName,
                                    label = { Text(stringResource(R.string.assistant_page_header_name)) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = headerValue,
                                    onValueChange = {
                                        headerValue = it
                                        val value = it.trim()
                                        pending.edit(token, { row -> row.copy(value = value) }) { current, observed, target ->
                                            updateHeaderValue(current, observed, target, value)
                                        }?.let(onUpdate)
                                    },
                                    label = { Text(stringResource(R.string.assistant_page_header_value)) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        },
                        trailingContent = {
                            IconButton(onClick = {
                                pending.delete(token, ::deleteHeaderRow)?.let(onUpdate)
                            }) {
                                Icon(
                                    HugeIcons.Delete01,
                                    contentDescription = stringResource(R.string.assistant_page_delete_header)
                                )
                            }
                        },
                        headlineContent = {},
                    )
                }
            }
        }

        Button(
            onClick = {
                val added = CustomHeader("", "")
                onUpdate(pending.add(added))
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(HugeIcons.Add01, contentDescription = stringResource(R.string.assistant_page_add_header))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.assistant_page_add_header))
        }
    }
}

@Composable
fun CustomBodies(customBodies: List<CustomBody>, onUpdate: ((List<CustomBody>) -> List<CustomBody>) -> Unit) {
    val pending = remember { PendingPropertyRows(customBodies) }
    pending.receive(customBodies)
    val context = LocalContext.current
    Column(
        modifier = Modifier.padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(stringResource(R.string.assistant_page_custom_bodies))
        Spacer(Modifier.height(8.dp))

        pending.rows.forEachIndexed { index, body ->
            val token = pending.order[index]
            key(token) {
                var bodyKey by remember(body.key) { mutableStateOf(body.key) }
                var bodyValueString by remember(body.value) {
                    mutableStateOf(jsonLenient.encodeToString(JsonElement.serializer(), body.value))
                }
                var jsonParseError by remember { mutableStateOf<String?>(null) }

                CardGroup {
                    item(
                        supportingContent = {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedTextField(
                                    value = bodyKey,
                                    onValueChange = {
                                        bodyKey = it
                                        val key = it.trim()
                                        pending.edit(token, { row -> row.copy(key = key) }) { current, observed, target ->
                                            updateBodyKey(current, observed, target, key)
                                        }?.let(onUpdate)
                                    },
                                    label = { Text(stringResource(R.string.assistant_page_body_key)) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = bodyValueString,
                                    onValueChange = { newString ->
                                        bodyValueString = newString
                                        try {
                                            val newJsonValue = jsonLenient.parseToJsonElement(newString)
                                            pending.edit(token, { row -> row.copy(value = newJsonValue) }) { current, observed, target ->
                                                updateBodyValue(current, observed, target, newJsonValue)
                                            }?.let(onUpdate)
                                            jsonParseError = null
                                        } catch (e: Exception) {
                                            jsonParseError =
                                                context.getString(
                                                    R.string.assistant_page_invalid_json,
                                                    e.message?.take(100) ?: ""
                                                )
                                        }
                                    },
                                    label = { Text(stringResource(R.string.assistant_page_body_value)) },
                                    modifier = Modifier.fillMaxWidth(),
                                    isError = jsonParseError != null,
                                    supportingText = {
                                        if (jsonParseError != null) {
                                            Text(jsonParseError!!)
                                        }
                                    },
                                    minLines = 3,
                                    maxLines = 5,
                                    visualTransformation = HighlightCodeVisualTransformation(
                                        language = "json",
                                        highlighter = LocalCodeHighlighter.current,
                                        darkMode = LocalDarkMode.current
                                    ),
                                    textStyle = LocalTextStyle.current.merge(fontFamily = JetbrainsMono),
                                )
                            }
                        },
                        trailingContent = {
                            IconButton(onClick = {
                                pending.delete(token, ::deleteBodyRow)?.let(onUpdate)
                            }) {
                                Icon(
                                    HugeIcons.Delete01,
                                    contentDescription = stringResource(R.string.assistant_page_delete_body)
                                )
                            }
                        },
                        headlineContent = {},
                    )
                }
            }
        }

        Button(
            onClick = {
                val added = CustomBody("", JsonPrimitive(""))
                onUpdate(pending.add(added))
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(HugeIcons.Add01, contentDescription = stringResource(R.string.assistant_page_add_body))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.assistant_page_add_body))
        }
    }
}
