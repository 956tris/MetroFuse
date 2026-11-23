package dev.metrofuse.examples

import dev.metrofuse.api.AudioResult
import dev.metrofuse.api.ProviderError
import dev.metrofuse.api.SearchResult
import dev.metrofuse.api.StreamDescriptor
import dev.metrofuse.api.StreamTransport
import dev.metrofuse.api.Track
import dev.metrofuse.sdk.MetroFusePlugin
import dev.metrofuse.sdk.PluginBuilder

/** Minimal Plugin API 1.2.0 example using [MetroFusePlugin] and [PluginBuilder] DSL. */
class SimpleDslPlugin : MetroFusePlugin() {
    override suspend fun PluginBuilder.setup() {
        search("example.native.simple.search") { request ->
            SearchResult(
                tracks = listOf(Track("example:simple:${request.query}", request.query, "MetroFuse", "Examples")),
            )
        }
        audioSource(
            id = "example.native.simple.audio",
            transports = setOf(StreamTransport.DIRECT),
            codecs = setOf("pcm"),
            formats = setOf("wav"),
        ) { query ->
            val uri = query.identity.identifiers["fixtureUri"]
            if (uri == null || !uri.startsWith("file:/")) {
                AudioResult(error = ProviderError("NO_MATCH", "Track has no local fixture"))
            } else {
                AudioResult(StreamDescriptor(StreamTransport.DIRECT, uri, "audio/wav", codec = "pcm"))
            }
        }
        context.log("Simple DSL plugin ready (API 1.2.0)")
    }
}
