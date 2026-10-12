package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest

internal data class ChatScrollNavigationActivity(
    val scrolling: Boolean = false,
    val firstVisibleIndex: Int = 0,
    val firstVisibleOffset: Int = 0,
    val interactionRevision: Int = 0,
)

/** A new movement or tap cancels the previous idle timeout. Active scrolling never starts one. */
internal suspend fun observeChatScrollNavigation(
    activity: Flow<ChatScrollNavigationActivity>,
    onVisibilityChange: (Boolean) -> Unit,
) {
    var previous: ChatScrollNavigationActivity? = null
    activity.collectLatest { current ->
        val moved = previous?.let {
            it.firstVisibleIndex != current.firstVisibleIndex || it.firstVisibleOffset != current.firstVisibleOffset
        } == true
        val interacted = previous?.let { it.interactionRevision != current.interactionRevision } == true
        previous = current
        if (current.scrolling || moved || interacted) onVisibilityChange(true)
        if (!current.scrolling) {
            delay(1_500)
            onVisibilityChange(false)
        }
    }
}

internal class ChatScrollNavigationVisibility {
    var visible by mutableStateOf(false)
        private set
    var interactionRevision by mutableIntStateOf(0)
        private set

    fun onInteraction() {
        visible = true
        interactionRevision++
    }

    internal fun updateVisibility(visible: Boolean) {
        this.visible = visible
    }
}

@Composable
internal fun rememberChatScrollNavigationVisibility(state: LazyListState): ChatScrollNavigationVisibility {
    val visibility = remember(state) { ChatScrollNavigationVisibility() }
    LaunchedEffect(state, visibility) {
        observeChatScrollNavigation(
            activity = snapshotFlow {
                ChatScrollNavigationActivity(
                    scrolling = state.isScrollInProgress,
                    firstVisibleIndex = state.firstVisibleItemIndex,
                    firstVisibleOffset = state.firstVisibleItemScrollOffset,
                    interactionRevision = visibility.interactionRevision,
                )
            },
            onVisibilityChange = visibility::updateVisibility,
        )
    }
    return visibility
}

/** The existing 16dp list inset plus this 36dp gutter leaves 8dp beside the navigation circles. */
internal fun chatScrollNavigationGutter(showMessageJumper: Boolean, messageJumperOnLeft: Boolean): PaddingValues =
    PaddingValues(
        start = if (showMessageJumper && messageJumperOnLeft) 36.dp else 0.dp,
        end = if (showMessageJumper && !messageJumperOnLeft) 36.dp else 0.dp,
    )
