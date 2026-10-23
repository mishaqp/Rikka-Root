package me.rerere.rikkahub.ui.pages.share.handler

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.miniapps.MiniAppActivity
import me.rerere.rikkahub.miniapps.MiniAppLauncher
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.utils.base64Encode
import me.rerere.rikkahub.utils.navigateToChatPage
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun ShareHandlerPage(text: String, streamUris: List<String>) {
    val context = LocalContext.current
    val navigator = LocalNavController.current
    var checkingArchive by remember(streamUris) { mutableStateOf(streamUris.size == 1) }
    LaunchedEffect(streamUris) {
        val archive = if (streamUris.size == 1) withContext(Dispatchers.IO) {
            val uri = streamUris.single().toUri()
            runCatching { MiniAppLauncher.archiveName(context, uri)?.let(MiniAppLauncher::isArchiveName) == true }.getOrDefault(false)
        } else false
        if (archive) {
            context.startActivity(android.content.Intent(context, MiniAppActivity::class.java).apply {
                action = android.content.Intent.ACTION_SEND
                putExtra(android.content.Intent.EXTRA_STREAM, streamUris.single().toUri())
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
            navigator.popBackStack()
        }
        checkingArchive = false
    }
    if (checkingArchive) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator() }
        return
    }
    val vm: ShareHandlerVM = koinViewModel(parameters = { parametersOf(text) })
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val navController = LocalNavController.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.share_handler_page_title))
                }
            )
        }
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = it + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Card {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = vm.shareText,
                            maxLines = 5,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall
                        )

                        streamUris.firstOrNull()?.let {
                            AsyncImage(
                                model = it,
                                contentDescription = null,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            items(settings.assistants) { assistant ->
                Surface(
                    onClick = {
                        scope.launch {
                            vm.updateAssistant(assistant.id)
                            navigateToChatPage(
                                navigator = navController,
                                initText = vm.shareText.base64Encode(),
                                initFiles = streamUris.map { it.toUri() }
                            )
                        }
                    },
                    tonalElevation = 4.dp,
                    shape = MaterialTheme.shapes.medium
                ) {
                    ListItem(
                        headlineContent = {
                            Text(
                                text = assistant.name.ifEmpty {
                                    stringResource(R.string.assistant_page_default_assistant)
                                },
                                maxLines = 1
                            )
                        },
                    )
                }
            }
        }
    }
}
