package me.rerere.rikkahub.ui.pages.setting

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Play
import me.rerere.hugeicons.stroke.Delete01
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.highlight.LocalCodeHighlighter
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.richtext.HighlightCodeVisualTransformation
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.utils.plus
import me.rerere.search.DoubaoSearchMode
import me.rerere.search.SearchCommonOptions
import me.rerere.search.SearchResult
import me.rerere.search.SearchService
import me.rerere.search.SearchServiceOptions
import me.rerere.ui.components.FormItem
import org.koin.androidx.compose.koinViewModel
import kotlin.uuid.Uuid

@Composable
fun SettingSearchDetailPage(
    serviceId: Uuid,
    vm: SettingVM = koinViewModel()
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val nav = LocalNavController.current

    val service = settings.searchServices.find { it.id == serviceId } ?: return
    val serviceIndex = settings.searchServices.indexOf(service)
    var options by remember(service) { mutableStateOf(service) }

    fun save(transform: (SearchServiceOptions) -> SearchServiceOptions) {
        options = transform(options)
        vm.updateSettings { current -> current.copy(searchServices = current.searchServices.map { row ->
            if (row.id == serviceId) transform(row) else row
        }) }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(options.displayName)
                },
                navigationIcon = {
                    BackButton()
                },
                actions = {
                    if (settings.searchServices.size > 1) {
                        IconButton(
                            onClick = {
                                vm.updateSettings { current -> current.copy(searchServices = current.searchServices.filterNot { it.id == serviceId }) }
                                nav.popBackStack()
                            }
                        ) {
                            Icon(
                                imageVector = HugeIcons.Delete01,
                                contentDescription = stringResource(R.string.delete)
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            contentPadding = padding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item("config") {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = CustomColors.listItemColors.containerColor
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .animateContentSize()
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.setting_page_search_config),
                            style = MaterialTheme.typography.titleMedium
                        )

                        SearchServiceOptionsEditor(
                            options = options,
                            onUpdateOptions = { save(it) }
                        )

                        ProvideTextStyle(MaterialTheme.typography.labelMedium) {
                            SearchService.getService(options).Description()
                        }
                    }
                }
            }

            item("test") {
                SearchTestSection(
                    options = options,
                    commonOptions = settings.searchCommonOptions
                )
            }
        }
    }
}

@Suppress("UNCHECKED_CAST")
@Composable
private fun SearchServiceOptionsEditor(
    options: SearchServiceOptions,
    onUpdateOptions: ((SearchServiceOptions) -> SearchServiceOptions) -> Unit
) {
    when (options) {
        is SearchServiceOptions.TavilyOptions -> {
            TavilyOptions(options) { edit -> onUpdateOptions { current -> if (current::class == options::class) edit(current as SearchServiceOptions.TavilyOptions) else current } }
        }
        is SearchServiceOptions.ExaOptions -> {
            ExaOptions(options) { edit -> onUpdateOptions { current -> if (current::class == options::class) edit(current as SearchServiceOptions.ExaOptions) else current } }
        }
        is SearchServiceOptions.ZhipuOptions -> {
            ZhipuOptions(options) { edit -> onUpdateOptions { current -> if (current::class == options::class) edit(current as SearchServiceOptions.ZhipuOptions) else current } }
        }
        is SearchServiceOptions.DoubaoOptions -> {
            DoubaoOptions(options) { edit -> onUpdateOptions { current -> if (current::class == options::class) edit(current as SearchServiceOptions.DoubaoOptions) else current } }
        }
        is SearchServiceOptions.SearXNGOptions -> {
            SearXNGOptions(options) { edit -> onUpdateOptions { current -> if (current::class == options::class) edit(current as SearchServiceOptions.SearXNGOptions) else current } }
        }
        is SearchServiceOptions.LinkUpOptions -> {
            SearchLinkUpOptions(options) { edit -> onUpdateOptions { current -> if (current::class == options::class) edit(current as SearchServiceOptions.LinkUpOptions) else current } }
        }
        is SearchServiceOptions.BraveOptions -> {
            BraveOptions(options) { edit -> onUpdateOptions { current -> if (current::class == options::class) edit(current as SearchServiceOptions.BraveOptions) else current } }
        }
        is SearchServiceOptions.MetasoOptions -> {
            MetasoOptions(options) { edit -> onUpdateOptions { current -> if (current::class == options::class) edit(current as SearchServiceOptions.MetasoOptions) else current } }
        }
        is SearchServiceOptions.OllamaOptions -> {
            OllamaOptions(options) { edit -> onUpdateOptions { current -> if (current::class == options::class) edit(current as SearchServiceOptions.OllamaOptions) else current } }
        }
        is SearchServiceOptions.PerplexityOptions -> {
            PerplexityOptions(options) { edit -> onUpdateOptions { current -> if (current::class == options::class) edit(current as SearchServiceOptions.PerplexityOptions) else current } }
        }
        is SearchServiceOptions.BingLocalOptions -> {}
        is SearchServiceOptions.DuckDuckGoOptions -> {}
        is SearchServiceOptions.FirecrawlOptions -> {
            FirecrawlOptions(options) { edit -> onUpdateOptions { current -> if (current::class == options::class) edit(current as SearchServiceOptions.FirecrawlOptions) else current } }
        }
        is SearchServiceOptions.JinaOptions -> {
            JinaOptions(options) { edit -> onUpdateOptions { current -> if (current::class == options::class) edit(current as SearchServiceOptions.JinaOptions) else current } }
        }
        is SearchServiceOptions.BochaOptions -> {
            BochaOptions(options) { edit -> onUpdateOptions { current -> if (current::class == options::class) edit(current as SearchServiceOptions.BochaOptions) else current } }
        }
        is SearchServiceOptions.RikkaHubOptions -> {
            RikkaHubOptions(options) { edit -> onUpdateOptions { current -> if (current::class == options::class) edit(current as SearchServiceOptions.RikkaHubOptions) else current } }
        }
        is SearchServiceOptions.GrokOptions -> {
            GrokOptions(options) { edit -> onUpdateOptions { current -> if (current::class == options::class) edit(current as SearchServiceOptions.GrokOptions) else current } }
        }
        is SearchServiceOptions.TinyfishOptions -> {
            TinyfishOptions(options) { edit -> onUpdateOptions { current -> if (current::class == options::class) edit(current as SearchServiceOptions.TinyfishOptions) else current } }
        }
        is SearchServiceOptions.SerperOptions -> {
            SerperOptions(options) { edit -> onUpdateOptions { current -> if (current::class == options::class) edit(current as SearchServiceOptions.SerperOptions) else current } }
        }
        is SearchServiceOptions.CustomJsOptions -> {
            CustomJsOptions(options) { edit -> onUpdateOptions { current -> if (current::class == options::class) edit(current as SearchServiceOptions.CustomJsOptions) else current } }
        }
    }
}

@Composable
private fun SearchTestSection(
    options: SearchServiceOptions,
    commonOptions: SearchCommonOptions
) {
    var query by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Result<SearchResult>?>(null) }
    val scope = rememberCoroutineScope()

    Card(
        colors = CardDefaults.cardColors(
            containerColor = CustomColors.listItemColors.containerColor
        )
    ) {
        Column(
            modifier = Modifier
                .animateContentSize()
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.setting_page_search_test),
                style = MaterialTheme.typography.titleMedium
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.setting_page_search_test_query_hint)) },
                    singleLine = true
                )

                IconButton(
                    onClick = {
                        if (query.isNotBlank() && !testing) {
                            testing = true
                            result = null
                            scope.launch {
                                val service = SearchService.getService(options)
                                val params = JsonObject(
                                    mapOf("query" to JsonPrimitive(query))
                                )
                                result = service.search(params, commonOptions, options)
                                testing = false
                            }
                        }
                    },
                    enabled = query.isNotBlank() && !testing
                ) {
                    if (testing) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(4.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = HugeIcons.Play,
                            contentDescription = stringResource(R.string.setting_page_search_test_run)
                        )
                    }
                }
            }

            result?.let { res ->
                res.onSuccess { searchResult ->
                    searchResult.answer?.let { answer ->
                        Text(
                            text = answer,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    searchResult.items.forEachIndexed { index, item ->
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                            )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "${index + 1}. ${item.title}",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    text = item.url,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = item.text.take(200),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                res.onFailure { error ->
                    Text(
                        text = error.message ?: stringResource(R.string.search_detail_unknown_error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
internal fun TavilyOptions(
    options: SearchServiceOptions.TavilyOptions,
    onUpdateOptions: ((SearchServiceOptions.TavilyOptions) -> SearchServiceOptions.TavilyOptions) -> Unit
) {
    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_api_key))
        }
    ) {
        OutlinedTextField(
            value = options.apiKey,
            onValueChange = {
                onUpdateOptions { current -> current.copy(apiKey = it) }
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_depth))
        }
    ) {
        val depthOptions = listOf("basic", "advanced")
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth()
        ) {
            depthOptions.forEachIndexed { index, depth ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = depthOptions.size),
                    onClick = {
                        onUpdateOptions { current -> current.copy(depth = depth) }
                    },
                    selected = options.depth == depth
                ) {
                    Text(depth.replaceFirstChar { it.uppercase() })
                }
            }
        }
    }
}

@Composable
internal fun ExaOptions(
    options: SearchServiceOptions.ExaOptions,
    onUpdateOptions: ((SearchServiceOptions.ExaOptions) -> SearchServiceOptions.ExaOptions) -> Unit
) {
    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_api_key))
        }
    ) {
        OutlinedTextField(
            value = options.apiKey,
            onValueChange = {
                onUpdateOptions { current -> current.copy(apiKey = it) }
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
internal fun ZhipuOptions(
    options: SearchServiceOptions.ZhipuOptions,
    onUpdateOptions: ((SearchServiceOptions.ZhipuOptions) -> SearchServiceOptions.ZhipuOptions) -> Unit
) {
    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_api_key))
        }
    ) {
        OutlinedTextField(
            value = options.apiKey,
            onValueChange = {
                onUpdateOptions { current -> current.copy(apiKey = it) }
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
internal fun DoubaoOptions(
    options: SearchServiceOptions.DoubaoOptions,
    onUpdateOptions: ((SearchServiceOptions.DoubaoOptions) -> SearchServiceOptions.DoubaoOptions) -> Unit
) {
    FormItem(label = { Text(stringResource(R.string.search_detail_api_key)) }) {
        OutlinedTextField(
            value = options.apiKey,
            onValueChange = { onUpdateOptions { current -> current.copy(apiKey = it) } },
            modifier = Modifier.fillMaxWidth()
        )
    }

    FormItem(label = { Text("Mode") }) {
        val modes = DoubaoSearchMode.entries
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            modes.forEachIndexed { index, mode ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                    onClick = { onUpdateOptions { current -> current.copy(mode = mode) } },
                    selected = options.mode == mode
                ) {
                    Text(mode.name.lowercase().replaceFirstChar(Char::uppercase))
                }
            }
        }
    }
}

@Composable
internal fun SearXNGOptions(
    options: SearchServiceOptions.SearXNGOptions,
    onUpdateOptions: ((SearchServiceOptions.SearXNGOptions) -> SearchServiceOptions.SearXNGOptions) -> Unit
) {
    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_api_url))
        }
    ) {
        OutlinedTextField(
            value = options.url,
            onValueChange = {
                onUpdateOptions { current -> current.copy(url = it) }
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_engines))
        }
    ) {
        OutlinedTextField(
            value = options.engines,
            onValueChange = {
                onUpdateOptions { current -> current.copy(engines = it) }
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_language))
        }
    ) {
        OutlinedTextField(
            value = options.language,
            onValueChange = {
                onUpdateOptions { current -> current.copy(language = it) }
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_username))
        }
    ) {
        OutlinedTextField(
            value = options.username,
            onValueChange = {
                onUpdateOptions { current -> current.copy(username = it) }
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_password))
        }
    ) {
        OutlinedTextField(
            value = options.password,
            onValueChange = {
                onUpdateOptions { current -> current.copy(password = it) }
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
internal fun SearchLinkUpOptions(
    options: SearchServiceOptions.LinkUpOptions,
    onUpdateOptions: ((SearchServiceOptions.LinkUpOptions) -> SearchServiceOptions.LinkUpOptions) -> Unit
) {
    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_api_key))
        }
    ) {
        OutlinedTextField(
            value = options.apiKey,
            onValueChange = {
                onUpdateOptions { current -> current.copy(apiKey = it) }
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_depth))
        }
    ) {
        val depthOptions = listOf("standard", "deep")
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth()
        ) {
            depthOptions.forEachIndexed { index, depth ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = depthOptions.size),
                    onClick = {
                        onUpdateOptions { current -> current.copy(depth = depth) }
                    },
                    selected = options.depth == depth
                ) {
                    Text(depth.replaceFirstChar { it.uppercase() })
                }
            }
        }
    }
}

@Composable
internal fun BraveOptions(
    options: SearchServiceOptions.BraveOptions,
    onUpdateOptions: ((SearchServiceOptions.BraveOptions) -> SearchServiceOptions.BraveOptions) -> Unit
) {
    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_api_key))
        }
    ) {
        OutlinedTextField(
            value = options.apiKey,
            onValueChange = {
                onUpdateOptions { current -> current.copy(apiKey = it) }
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
internal fun SerperOptions(
    options: SearchServiceOptions.SerperOptions,
    onUpdateOptions: ((SearchServiceOptions.SerperOptions) -> SearchServiceOptions.SerperOptions) -> Unit
) {
    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_api_key))
        }
    ) {
        OutlinedTextField(
            value = options.apiKey,
            onValueChange = {
                onUpdateOptions { current -> current.copy(apiKey = it) }
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
internal fun MetasoOptions(
    options: SearchServiceOptions.MetasoOptions,
    onUpdateOptions: ((SearchServiceOptions.MetasoOptions) -> SearchServiceOptions.MetasoOptions) -> Unit
) {
    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_api_key))
        }
    ) {
        OutlinedTextField(
            value = options.apiKey,
            onValueChange = {
                onUpdateOptions { current -> current.copy(apiKey = it) }
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
internal fun OllamaOptions(
    options: SearchServiceOptions.OllamaOptions,
    onUpdateOptions: ((SearchServiceOptions.OllamaOptions) -> SearchServiceOptions.OllamaOptions) -> Unit
) {
    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_api_key))
        }
    ) {
        OutlinedTextField(
            value = options.apiKey,
            onValueChange = {
                onUpdateOptions { current -> current.copy(apiKey = it) }
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
internal fun PerplexityOptions(
    options: SearchServiceOptions.PerplexityOptions,
    onUpdateOptions: ((SearchServiceOptions.PerplexityOptions) -> SearchServiceOptions.PerplexityOptions) -> Unit
) {
    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_api_key))
        }
    ) {
        OutlinedTextField(
            value = options.apiKey,
            onValueChange = {
                onUpdateOptions { current -> current.copy(apiKey = it) }
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_max_tokens))
        }
    ) {
        OutlinedTextField(
            value = options.maxTokens?.takeIf { it > 0 }?.toString() ?: "",
            onValueChange = { value ->
                onUpdateOptions { current -> current.copy(maxTokens = value.toIntOrNull()) }
            },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_max_tokens_per_page))
        }
    ) {
        OutlinedTextField(
            value = options.maxTokensPerPage?.takeIf { it > 0 }?.toString() ?: "",
            onValueChange = { value ->
                onUpdateOptions { current -> current.copy(maxTokensPerPage = value.toIntOrNull()) }
            },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
    }
}

@Composable
internal fun FirecrawlOptions(
    options: SearchServiceOptions.FirecrawlOptions,
    onUpdateOptions: ((SearchServiceOptions.FirecrawlOptions) -> SearchServiceOptions.FirecrawlOptions) -> Unit
) {
    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_api_key))
        }
    ) {
        OutlinedTextField(
            value = options.apiKey,
            onValueChange = {
                onUpdateOptions { current -> current.copy(apiKey = it) }
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
internal fun JinaOptions(
    options: SearchServiceOptions.JinaOptions,
    onUpdateOptions: ((SearchServiceOptions.JinaOptions) -> SearchServiceOptions.JinaOptions) -> Unit
) {
    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_api_key))
        }
    ) {
        OutlinedTextField(
            value = options.apiKey,
            onValueChange = {
                onUpdateOptions { current -> current.copy(apiKey = it) }
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_search_url))
        }
    ) {
        OutlinedTextField(
            value = options.searchUrl,
            onValueChange = {
                onUpdateOptions { current -> current.copy(searchUrl = it.trim()) }
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text("https://s.jina.ai/")
            }
        )
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_scrape_url))
        }
    ) {
        OutlinedTextField(
            value = options.scrapeUrl,
            onValueChange = {
                onUpdateOptions { current -> current.copy(scrapeUrl = it.trim()) }
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text("https://r.jina.ai/")
            }
        )
    }
}

@Composable
internal fun BochaOptions(
    options: SearchServiceOptions.BochaOptions,
    onUpdateOptions: ((SearchServiceOptions.BochaOptions) -> SearchServiceOptions.BochaOptions) -> Unit
) {
    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_api_key))
        }
    ) {
        OutlinedTextField(
            value = options.apiKey,
            onValueChange = {
                onUpdateOptions { current -> current.copy(apiKey = it) }
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_summary))
        },
        description = {
            Text(stringResource(R.string.search_detail_summary_desc))
        },
        tail = {
            Switch(
                checked = options.summary,
                onCheckedChange = { checked ->
                    onUpdateOptions { current -> current.copy(summary = checked) }
                }
            )
        }
    )
}

@Composable
internal fun RikkaHubOptions(
    options: SearchServiceOptions.RikkaHubOptions,
    onUpdateOptions: ((SearchServiceOptions.RikkaHubOptions) -> SearchServiceOptions.RikkaHubOptions) -> Unit
) {
    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_api_key))
        }
    ) {
        OutlinedTextField(
            value = options.apiKey,
            onValueChange = {
                onUpdateOptions { current -> current.copy(apiKey = it) }
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_depth))
        }
    ) {
        val depthOptions = listOf("standard", "deep")
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth()
        ) {
            depthOptions.forEachIndexed { index, depth ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = depthOptions.size),
                    onClick = {
                        onUpdateOptions { current -> current.copy(depth = depth) }
                    },
                    selected = options.depth == depth
                ) {
                    Text(depth.replaceFirstChar { it.uppercase() })
                }
            }
        }
    }
}

@Composable
internal fun TinyfishOptions(
    options: SearchServiceOptions.TinyfishOptions,
    onUpdateOptions: ((SearchServiceOptions.TinyfishOptions) -> SearchServiceOptions.TinyfishOptions) -> Unit
) {
    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_api_key))
        }
    ) {
        OutlinedTextField(
            value = options.apiKey,
            onValueChange = {
                onUpdateOptions { current -> current.copy(apiKey = it) }
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
internal fun GrokOptions(
    options: SearchServiceOptions.GrokOptions,
    onUpdateOptions: ((SearchServiceOptions.GrokOptions) -> SearchServiceOptions.GrokOptions) -> Unit
) {
    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_api_key))
        }
    ) {
        OutlinedTextField(
            value = options.apiKey,
            onValueChange = {
                onUpdateOptions { current -> current.copy(apiKey = it) }
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_model))
        }
    ) {
        OutlinedTextField(
            value = options.model,
            onValueChange = {
                onUpdateOptions { current -> current.copy(model = it) }
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_custom_url))
        }
    ) {
        OutlinedTextField(
            value = options.customUrl,
            onValueChange = {
                onUpdateOptions { current -> current.copy(customUrl = it) }
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_system_prompt))
        }
    ) {
        OutlinedTextField(
            value = options.systemPrompt,
            onValueChange = {
                onUpdateOptions { current -> current.copy(systemPrompt = it) }
            },
            minLines = 3,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
internal fun CustomJsOptions(
    options: SearchServiceOptions.CustomJsOptions,
    onUpdateOptions: ((SearchServiceOptions.CustomJsOptions) -> SearchServiceOptions.CustomJsOptions) -> Unit
) {
    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_name))
        }
    ) {
        OutlinedTextField(
            value = options.name,
            onValueChange = {
                onUpdateOptions { current -> current.copy(name = it) }
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.search_detail_custom_search_placeholder)) }
        )
    }

    val highlighter = LocalCodeHighlighter.current
    val darkMode = LocalDarkMode.current

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_search_script))
        }
    ) {
        OutlinedTextField(
            value = options.searchScript,
            onValueChange = {
                onUpdateOptions { current -> current.copy(searchScript = it) }
            },
            modifier = Modifier.fillMaxWidth(),
            minLines = 8,
            maxLines = 20,
            visualTransformation = HighlightCodeVisualTransformation(
                language = "javascript",
                highlighter = highlighter,
                darkMode = darkMode
            ),
            textStyle = MaterialTheme.typography.bodySmall.merge(fontFamily = JetbrainsMono),
        )
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_scrape_script))
        },
        description = {
            Text(stringResource(R.string.search_detail_scrape_script_desc))
        }
    ) {
        OutlinedTextField(
            value = options.scrapeScript,
            onValueChange = {
                onUpdateOptions { current -> current.copy(scrapeScript = it) }
            },
            modifier = Modifier.fillMaxWidth(),
            minLines = 4,
            maxLines = 20,
            placeholder = {
                Text(
                    text = SearchServiceOptions.CustomJsOptions.DEFAULT_SCRAPE_SCRIPT.trimIndent(),
                    style = MaterialTheme.typography.bodySmall.merge(fontFamily = JetbrainsMono),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            },
            visualTransformation = HighlightCodeVisualTransformation(
                language = "javascript",
                highlighter = highlighter,
                darkMode = darkMode
            ),
            textStyle = MaterialTheme.typography.bodySmall.merge(fontFamily = JetbrainsMono),
        )
    }
}
