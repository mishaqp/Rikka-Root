package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import me.rerere.rikkahub.data.datastore.MessageNavButtonsMode
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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


internal fun chatMessageNavigationVisible(mode: MessageNavButtonsMode, scrollingVisible: Boolean): Boolean = when (mode) {
    MessageNavButtonsMode.ALWAYS -> true
    MessageNavButtonsMode.SCROLL -> scrollingVisible
    MessageNavButtonsMode.NEVER -> false
}

/** An explicit user scroll pauses following; zero retains the legacy immediate resume. */
internal suspend fun observeChatAutoFollowReady(
    scrolling: Flow<Boolean>,
    idleSeconds: Int,
    onReady: (Boolean) -> Unit,
) {
    var hasScrolled = false
    scrolling.collectLatest { active ->
        if (active) {
            hasScrolled = true
            onReady(false)
        } else {
            if (hasScrolled) {
                onReady(false)
                delay(idleSeconds.coerceIn(0, 64) * 1_000L)
            }
            onReady(true)
        }
    }
}

@Composable
internal fun rememberChatAutoFollowReady(state: LazyListState, idleSeconds: Int): State<Boolean> {
    val ready = remember(state) { mutableStateOf(true) }
    LaunchedEffect(state, idleSeconds) {
        observeChatAutoFollowReady(snapshotFlow { state.isScrollInProgress }, idleSeconds) { ready.value = it }
    }
    return ready
}
