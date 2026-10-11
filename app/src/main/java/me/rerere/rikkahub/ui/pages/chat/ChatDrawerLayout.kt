package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.theme.LocalAmoledDarkMode

/** Shared production layout, also rendered directly by the JVM screenshot fixtures. */
@Composable
internal fun ChatDrawerFrame(content: @Composable ColumnScope.() -> Unit) {
    val width = minOf(LocalConfiguration.current.screenWidthDp.dp * .85f, 320.dp)
    val colors = MaterialTheme.colorScheme
    ModalDrawerSheet(modifier = Modifier.width(width).testTag("chat_drawer_sheet"),
        drawerContainerColor = if (LocalAmoledDarkMode.current) colors.background else colors.surfaceContainerLow,
        drawerContentColor = colors.onSurface) {
        Column(Modifier.fillMaxSize(), content = content)
    }
}

@Composable
internal fun ChatDrawerTopArea(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp).padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
internal fun ChatDrawerFolderControls(
    sort: @Composable () -> Unit,
    folders: @Composable () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.layout.Box(Modifier.weight(1f)) { folders() }
        sort()
    }
}
