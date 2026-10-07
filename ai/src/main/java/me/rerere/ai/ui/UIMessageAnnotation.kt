package me.rerere.ai.ui

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class UIMessageAnnotation {
    /** Provider confirms web grounding or URL retrieval, even when no citation is emitted. */
    @Serializable
    @SerialName("web_content_used")
    data object WebContentUsed : UIMessageAnnotation()

    @Serializable
    @SerialName("url_citation")
    data class UrlCitation(
        val title: String,
        val url: String
    ) : UIMessageAnnotation()
}
