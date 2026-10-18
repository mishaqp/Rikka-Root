package me.rerere.rikkahub.web

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.response.respond
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.singlePageApplication
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE

fun startWebServer(
    port: Int = 8080,
    host: String = "0.0.0.0",
    module: suspend Application.() -> Unit
): EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration> {
    return embeddedServer(CIO, port = port, host = host, module = {
        install(Compression)
        // The bundled web UI is same-origin. No CORS: a random website open in the phone's
        // browser must not read conversations or send messages through this server.
        intercept(ApplicationCallPipeline.Plugins) {
            val call = context
            val host = call.request.headers[HttpHeaders.Host]
            val origin = call.request.headers[HttpHeaders.Origin]
            if (!isAllowedWebRequest(host, origin)) {
                call.respond(HttpStatusCode.Forbidden)
                finish()
            }
        }
        install(SSE)
        install(DefaultHeaders)
        routing {
            staticResources("/", "static") {
                default("index.html")
                enableAutoHeadResponse()
                singlePageApplication()
            }
        }
        module()
    })
}

/**
 * DNS rebinding: an attacker's domain may resolve to this phone, so a Host header that is a
 * domain name (other than localhost or an mDNS *.local name) is refused. A cross-site request
 * carries an Origin whose host:port differs from Host and is refused too.
 */
fun isAllowedWebRequest(host: String?, origin: String?): Boolean {
    if (host.isNullOrBlank()) return false
    val hostName = hostNameOf(host).lowercase()
    val literal = hostName.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}")) || ':' in hostName
    if (!literal && hostName != "localhost" && !hostName.endsWith(".local")) return false
    if (origin == null || origin == "null") return origin == null
    val originAuthority = origin.substringAfter("://", "").substringBefore('/')
    return originAuthority.equals(host, ignoreCase = true)
}

private fun hostNameOf(authority: String): String =
    if (authority.startsWith("[")) authority.substringAfter('[').substringBefore(']')
    else authority.substringBeforeLast(':').takeIf { ':' in authority } ?: authority
