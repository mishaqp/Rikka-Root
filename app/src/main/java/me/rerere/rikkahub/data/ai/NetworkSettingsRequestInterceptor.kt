package me.rerere.rikkahub.data.ai

import io.ktor.http.HttpHeaders
import okhttp3.Interceptor

/** Existing shared-client request transformation, with suppliers for current settings and proxy state. */
internal fun networkSettingsRequestInterceptor(
    acceptLanguage: String,
    defaultUserAgent: String,
    userAgent: () -> String,
    applyProxyChanges: () -> Unit,
): Interceptor = Interceptor { chain ->
    withOkHttpIOException {
        applyProxyChanges()
        val originalRequest = chain.request()
        val requestBuilder = originalRequest.newBuilder()
            .addHeader(HttpHeaders.AcceptLanguage, acceptLanguage)

        if (originalRequest.header(HttpHeaders.UserAgent) == null) {
            requestBuilder.addHeader(HttpHeaders.UserAgent, userAgent().trim().ifEmpty { defaultUserAgent })
        }

        chain.proceed(requestBuilder.build())
    }
}
