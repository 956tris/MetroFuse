/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.net.ConnectivityManager
import android.net.Uri
import android.util.Log
import androidx.media3.common.PlaybackException
import com.metrolist.innertube.NewPipeExtractor
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.YouTubeClient
import com.metrolist.innertube.models.YouTubeClient.Companion.ANDROID_CREATOR
import com.metrolist.innertube.models.YouTubeClient.Companion.ANDROID_VR_1_43_32
import com.metrolist.innertube.models.YouTubeClient.Companion.ANDROID_VR_1_61_48
import com.metrolist.innertube.models.YouTubeClient.Companion.ANDROID_VR_NO_AUTH
import com.metrolist.innertube.models.YouTubeClient.Companion.IOS
import com.metrolist.innertube.models.YouTubeClient.Companion.IPADOS
import com.metrolist.innertube.models.YouTubeClient.Companion.MOBILE
import com.metrolist.innertube.models.YouTubeClient.Companion.TVHTML5
import com.metrolist.innertube.models.YouTubeClient.Companion.TVHTML5_SIMPLY_EMBEDDED_PLAYER
import com.metrolist.innertube.models.YouTubeClient.Companion.WEB
import com.metrolist.innertube.models.YouTubeClient.Companion.WEB_CREATOR
import com.metrolist.innertube.models.YouTubeClient.Companion.WEB_REMIX
import com.metrolist.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_PRIVATELY_OWNED_TRACK
import com.metrolist.innertube.models.response.PlayerResponse
import com.metrolist.music.constants.AudioQuality
import com.metrolist.music.utils.cipher.CipherDeobfuscator
import com.metrolist.music.utils.YTPlayerUtils.MAIN_CLIENT
import com.metrolist.music.utils.YTPlayerUtils.STREAM_FALLBACK_CLIENTS
import com.metrolist.music.utils.YTPlayerUtils.validateStatus
import com.metrolist.music.utils.potoken.PoTokenGenerator
import com.metrolist.music.utils.potoken.PoTokenResult
import com.metrolist.music.utils.sabr.EjsNTransformSolver
import okhttp3.OkHttpClient
import timber.log.Timber

object YTPlayerUtils {
    private const val logTag = "YTPlayerUtils"
    private const val TAG = "YTPlayerUtils"
    private const val UPLOADED_TRACKS_PLAYLIST_PREFIX = "MLPT"

    private val WEB_CLIENTS = listOf("WEB", "WEB_REMIX", "WEB_CREATOR", "TVHTML5")

    private val httpClient = OkHttpClient.Builder()
        .proxy(YouTube.proxy)
        .build()

    private val poTokenGenerator = PoTokenGenerator()

    private val MAIN_CLIENT: YouTubeClient = WEB_REMIX

    private val STREAM_FALLBACK_CLIENTS: Array<YouTubeClient> = arrayOf(
        TVHTML5_SIMPLY_EMBEDDED_PLAYER,  // Try embedded player first for age-restricted content
        TVHTML5,
        ANDROID_VR_1_43_32,
        ANDROID_VR_1_61_48,
        ANDROID_CREATOR,
        IPADOS,
        ANDROID_VR_NO_AUTH,
        MOBILE,
        IOS,
        WEB,
        WEB_CREATOR
    )
    /**
     * Container for everything the player needs to begin streaming a track:
     * audio config, video metadata, playback-tracking URLs, the resolved stream format/URL,
     * and whether the track is a privately-owned (uploaded) item requiring cookie auth.
     */
    data class PlaybackData(
        val audioConfig: PlayerResponse.PlayerConfig.AudioConfig?,
        val videoDetails: PlayerResponse.VideoDetails?,
        val playbackTracking: PlayerResponse.PlaybackTracking?,
        val format: PlayerResponse.StreamingData.Format,
        val streamUrl: String,
        val streamExpiresInSeconds: Int,
        val isPrivatelyOwned: Boolean = false,
    )
    /**
     * Custom player response intended to use for playback.
     * Metadata like audioConfig and videoDetails are from [MAIN_CLIENT].
     * Format & stream can be from [MAIN_CLIENT] or [STREAM_FALLBACK_CLIENTS].
     */
    @Deprecated(
        message = "YouTube/Opus playback is disabled in this Apple Music wrapper build.",
        level = DeprecationLevel.ERROR,
    )
    suspend fun playerResponseForPlayback(
        videoId: String,
        playlistId: String? = null,
        isUploadedHint: Boolean = false,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
    ): Result<PlaybackData> = runCatching {
        Timber.tag(TAG).d("=== PLAYER RESPONSE FOR PLAYBACK ===")
        Timber.tag(TAG).d("videoId: $videoId")
        Timber.tag(TAG).d("playlistId: $playlistId")
        Timber.tag(TAG).d("audioQuality: $audioQuality")

        // Check if this is an uploaded/privately owned track
        val isUploadedTrack = isUploadedHint || playlistId?.startsWith(UPLOADED_TRACKS_PLAYLIST_PREFIX) == true
        Timber.tag(TAG).d("Content type detection (preliminary):")
        Timber.tag(TAG).d("  isUploadedTrack (from playlistId): $isUploadedTrack")

        val isLoggedIn = YouTube.cookie != null
        Timber.tag(TAG).d("Authentication status: ${if (isLoggedIn) "LOGGED_IN" else "ANONYMOUS"}")

        // Get signature timestamp (same as before for normal content)
        val signatureTimestamp = getSignatureTimestampOrNull(videoId)
        Timber.tag(logTag).d("Signature timestamp: ${signatureTimestamp.timestamp}")

        // Generate PoToken
        var poToken: PoTokenResult? = null
        val sessionId = if (isLoggedIn) YouTube.dataSyncId else YouTube.visitorData
        if (MAIN_CLIENT.useWebPoTokens && sessionId != null) {
            Timber.tag(logTag).d("Generating PoToken for WEB_REMIX with sessionId")
            try {
                poToken = poTokenGenerator.getWebClientPoToken(videoId, sessionId)
                if (poToken != null) {
                    Timber.tag(logTag).d("PoToken generated successfully")
                }
            } catch (e: Exception) {
                Timber.tag(logTag).e(e, "PoToken generation failed: ${e.message}")
            }
        }

        // Try WEB_REMIX with signature timestamp and poToken (same as before)
        Timber.tag(logTag).d("Attempting to get player response using MAIN_CLIENT: ${MAIN_CLIENT.clientName}")
        var mainPlayerResponse = YouTube.player(videoId, playlistId, MAIN_CLIENT, signatureTimestamp.timestamp, poToken?.playerRequestPoToken).getOrThrow()

        // Debug uploaded track response
        if (isUploadedTrack) {
            Timber.tag(TAG).d("Uploaded track player response status: ${mainPlayerResponse.playabilityStatus.status}")
            Timber.tag(TAG).d("Uploaded track playability reason: ${mainPlayerResponse.playabilityStatus.reason}")
            Timber.tag(TAG).d("Uploaded track details: title=${mainPlayerResponse.videoDetails?.title}, videoId=${mainPlayerResponse.videoDetails?.videoId}")
            Timber.tag(TAG).d("Uploaded track streaming data null? ${mainPlayerResponse.streamingData == null}")
            Timber.tag(TAG).d("Uploaded track adaptive formats: ${mainPlayerResponse.streamingData?.adaptiveFormats?.size ?: 0}")
        }

        var usedAgeRestrictedClient: YouTubeClient? = null
        val wasOriginallyAgeRestricted: Boolean

        // Check if WEB_REMIX response indicates age-restricted or login-required
        val mainStatus = mainPlayerResponse.playabilityStatus.status
        val isAgeRestrictedFromResponse = mainStatus in listOf("AGE_CHECK_REQUIRED", "AGE_VERIFICATION_REQUIRED", "CONTENT_CHECK_REQUIRED")
        val isLoginRequired = mainStatus == "LOGIN_REQUIRED"

        // LOGIN_REQUIRED can mean age-restricted (for catalog) or auth needed (for uploaded tracks).
        // In both cases, WEB_CREATOR (which has loginRequired=true) is the right fallback to try.
        wasOriginallyAgeRestricted = isAgeRestrictedFromResponse || (isLoginRequired && !isUploadedTrack)

        if ((isAgeRestrictedFromResponse || isLoginRequired) && isLoggedIn) {
            if (isUploadedTrack) {
                Timber.tag(TAG).d("LOGIN_REQUIRED for uploaded track - trying WEB_CREATOR with full auth")
            } else {
                Timber.tag(logTag).d("Age-restricted detected, using WEB_CREATOR")
            }
            Timber.tag(TAG).i("Trying WEB_CREATOR for videoId=$videoId (uploaded=$isUploadedTrack)")
            val creatorResponse = YouTube.player(videoId, playlistId, WEB_CREATOR, null, null).getOrNull()
            if (creatorResponse?.playabilityStatus?.status == "OK") {
                Timber.tag(logTag).d("WEB_CREATOR works for content")
                mainPlayerResponse = creatorResponse
                usedAgeRestrictedClient = WEB_CREATOR
            }
        }

        // If we still don't have a valid response, throw

        val audioConfig = mainPlayerResponse.playerConfig?.audioConfig
        val videoDetails = mainPlayerResponse.videoDetails
        val playbackTracking = mainPlayerResponse.playbackTracking
        var format: PlayerResponse.StreamingData.Format? = null
        var streamUrl: String? = null
        var streamExpiresInSeconds: Int? = null
        var streamPlayerResponse: PlayerResponse? = null
        // Tracks the best private-track candidate (first authenticated client wins).
        // Set inferredAsPrivate when a 403 on validation reveals an undeclared private track.
        var privateCandidateStreamUrl: String? = null
        var privateCandidateFormat: PlayerResponse.StreamingData.Format? = null
        var privateCandidateExpiry: Int? = null
        var privateCandidateResponse: PlayerResponse? = null
        var inferredAsPrivate = false
        val retryMainPlayerResponse: PlayerResponse? = if (usedAgeRestrictedClient != null) mainPlayerResponse else null

        // Check current status
        val currentStatus = mainPlayerResponse.playabilityStatus.status
        val isAgeRestricted = currentStatus in listOf("AGE_CHECK_REQUIRED", "AGE_VERIFICATION_REQUIRED", "CONTENT_CHECK_REQUIRED") ||
            (!isUploadedTrack && currentStatus == "LOGIN_REQUIRED")

        if (isAgeRestricted) {
            Timber.tag(logTag).d("Content is still age-restricted (status: $currentStatus), will try fallback clients")
            Timber.tag(TAG)
                .i("Age-restricted content detected: videoId=$videoId, status=$currentStatus")
        }

        // Check if this is a privately owned track (uploaded song)
        val isPrivateTrack = isUploadedTrack ||
            mainPlayerResponse.videoDetails?.musicVideoType == MUSIC_VIDEO_TYPE_PRIVATELY_OWNED_TRACK

        // For private tracks with a successful WEB_CREATOR response: try its streams first (index -1)
        // For private tracks where WEB_REMIX already returned OK streams: try those first (index -1)
        // For private tracks where the main client failed: skip to TVHTML5 (index 1)
        // For age-restricted: skip main client, start with fallbacks
        // For normal content: standard order
        val startIndex = when {
            isPrivateTrack && usedAgeRestrictedClient != null -> -1  // WEB_CREATOR succeeded, try its streams
            isPrivateTrack && mainPlayerResponse.playabilityStatus.status == "OK" -> -1  // WEB_REMIX works for this upload
            isPrivateTrack -> 1  // TVHTML5 (main client could not serve this upload)
            isAgeRestricted -> 0
            else -> -1
        }

        for (clientIndex in (startIndex until STREAM_FALLBACK_CLIENTS.size)) {
            // reset for each client
            format = null
            streamUrl = null
            streamExpiresInSeconds = null

            // decide which client to use for streams and load its player response
            val client: YouTubeClient
            if (clientIndex == -1) {
                // try with streams from main client first (use retry response if available)
                client = MAIN_CLIENT
                streamPlayerResponse = retryMainPlayerResponse ?: mainPlayerResponse
                Timber.tag(logTag).d("Trying stream from MAIN_CLIENT: ${client.clientName}")
            } else {
                // after main client use fallback clients
                client = STREAM_FALLBACK_CLIENTS[clientIndex]
                Timber.tag(logTag).d("Trying fallback client ${clientIndex + 1}/${STREAM_FALLBACK_CLIENTS.size}: ${client.clientName}")

                if (client.loginRequired && !isLoggedIn && YouTube.cookie == null) {
                    // skip client if it requires login but user is not logged in
                    Timber.tag(logTag).d("Skipping client ${client.clientName} - requires login but user is not logged in")
                    continue
                }

                Timber.tag(logTag).d("Fetching player response for fallback client: ${client.clientName}")
                // Only pass poToken for clients that support it
                val clientPoToken = if (client.useWebPoTokens) poToken?.playerRequestPoToken else null
                // Skip signature timestamp for age-restricted (faster), use it for normal content
                val clientSigTimestamp = if (wasOriginallyAgeRestricted) null else signatureTimestamp.timestamp
                streamPlayerResponse =
                    YouTube.player(videoId, playlistId, client, clientSigTimestamp, clientPoToken).getOrNull()
            }

            // process current client response
            if (streamPlayerResponse?.playabilityStatus?.status == "OK") {
                Timber.tag(logTag).d("Player response status OK for client: ${if (clientIndex == -1) MAIN_CLIENT.clientName else STREAM_FALLBACK_CLIENTS[clientIndex].clientName}")

                // Skip NewPipe for age-restricted or privately-owned content (NewPipe doesn't use our auth)
                val isPrivateContent = isPrivateTrack ||
                    streamPlayerResponse.videoDetails?.musicVideoType == MUSIC_VIDEO_TYPE_PRIVATELY_OWNED_TRACK
                val skipNewPipe = wasOriginallyAgeRestricted || isPrivateContent
                val responseToUse = if (skipNewPipe) {
                    Timber.tag(logTag).d("Skipping NewPipe: ageRestricted=$wasOriginallyAgeRestricted, private=$isPrivateContent")
                    streamPlayerResponse
                } else {
                    // Try to get streams using newPipePlayer method
                    val newPipeResponse = YouTube.newPipePlayer(videoId, streamPlayerResponse)
                    newPipeResponse ?: streamPlayerResponse
                }

                format =
                    findFormat(
                        responseToUse,
                        audioQuality,
                        connectivityManager,
                    )

                if (format == null) {
                    Timber.tag(logTag).d("No suitable format found for client: ${if (clientIndex == -1) MAIN_CLIENT.clientName else STREAM_FALLBACK_CLIENTS[clientIndex].clientName}")
                    continue
                }

                Timber.tag(logTag).d("Format found: ${format.mimeType}, bitrate: ${format.bitrate}")

                streamUrl = findUrlOrNull(format, videoId, responseToUse, skipNewPipe = skipNewPipe)
                if (streamUrl == null) {
                    Timber.tag(logTag).d("Stream URL not found for format")
                    continue
                }

                // Apply n-transform for throttle parameter handling
                val currentClient = if (clientIndex == -1) {
                    usedAgeRestrictedClient ?: MAIN_CLIENT
                } else {
                    STREAM_FALLBACK_CLIENTS[clientIndex]
                }

                // Check if this is a privately owned track (from response or earlier detection)
                val isPrivatelyOwnedTrack = isPrivateTrack ||
                    streamPlayerResponse.videoDetails?.musicVideoType == MUSIC_VIDEO_TYPE_PRIVATELY_OWNED_TRACK
                val musicVideoType = streamPlayerResponse.videoDetails?.musicVideoType

                Timber.tag(TAG).d("=== N-TRANSFORM DECISION ===")
                Timber.tag(TAG).d("Content type analysis:")
                Timber.tag(TAG).d("  musicVideoType: $musicVideoType")
                Timber.tag(TAG).d("  isPrivatelyOwnedTrack: $isPrivatelyOwnedTrack")
                Timber.tag(TAG).d("  isUploadedTrack (from playlistId): $isUploadedTrack")
                Timber.tag(TAG).d("  wasOriginallyAgeRestricted: $wasOriginallyAgeRestricted")
                Timber.tag(TAG).d("Client analysis:")
                Timber.tag(TAG).d("  currentClient: ${currentClient.clientName}")
                Timber.tag(TAG).d("  useWebPoTokens: ${currentClient.useWebPoTokens}")

                // Apply n-transform and PoToken for web clients AND for private tracks
                val needsNTransform = currentClient.useWebPoTokens ||
                    currentClient.clientName in WEB_CLIENTS ||
                    isPrivatelyOwnedTrack

                Timber.tag(TAG).d("N-transform decision:")
                Timber.tag(TAG).d("  needsNTransform: $needsNTransform")
                Timber.tag(TAG).d("  Reason: useWebPoTokens=${currentClient.useWebPoTokens}, " +
                    "clientInList=${currentClient.clientName in WEB_CLIENTS}, " +
                    "isPrivatelyOwnedTrack=$isPrivatelyOwnedTrack")

                if (needsNTransform) {
                    try {
                        Timber.tag(TAG).d("Applying n-transform to stream URL...")
                        Timber.tag(TAG).d("  Original URL length: ${streamUrl.length}")
                        Timber.tag(TAG).d("  Original URL preview: ${streamUrl.take(100)}...")

                        val originalUrl = streamUrl
                        // Use CipherDeobfuscator for n-transform, fall back to NewPipe
                        streamUrl = CipherDeobfuscator.transformNParamInUrl(streamUrl)

                        if (originalUrl == streamUrl && skipNewPipe) {
                            // CipherDeobfuscator failed and NewPipe wasn't used for stream resolution
                            // (private/uploaded tracks) — try NewPipe's throttling deobfuscation as fallback
                            Timber.tag(TAG).d("  CipherDeobfuscator failed, trying NewPipe throttling deobfuscation...")
                            try {
                                // Ensure NewPipe Downloader is initialized
                                com.metrolist.innertube.NewPipeUtils
                                val newPipeUrl = com.metrolist.innertube.NewPipeExtractor.getThrottlingDeobfuscatedUrl(videoId, streamUrl)
                                if (newPipeUrl != null && newPipeUrl != streamUrl) {
                                    streamUrl = newPipeUrl
                                    Timber.tag(TAG).d("  N-transform via NewPipe succeeded")
                                } else {
                                    Timber.tag(TAG).d("  NewPipe returned null or unchanged URL")
                                }
                            } catch (e: Exception) {
                                Timber.tag(TAG).d("  NewPipe n-transform also failed: ${e.message}")
                            }
                        }

                        Timber.tag(TAG).d("  Transformed URL length: ${streamUrl.length}")
                        Timber.tag(TAG).d("  URL changed: ${originalUrl != streamUrl}")

                        // Append pot= parameter with streaming data poToken
                        val needsPoToken = (currentClient.useWebPoTokens || isPrivatelyOwnedTrack) && poToken?.streamingDataPoToken != null
                        Timber.tag(TAG).d("PoToken decision:")
                        Timber.tag(TAG).d("  needsPoToken: $needsPoToken")
                        Timber.tag(TAG).d("  hasStreamingDataPoToken: ${poToken?.streamingDataPoToken != null}")

                        if (needsPoToken) {
                            Timber.tag(TAG).d("Appending pot= parameter to stream URL")
                            val separator = if ("?" in streamUrl) "&" else "?"
                            streamUrl = "${streamUrl}${separator}pot=${Uri.encode(poToken.streamingDataPoToken)}"
                            Timber.tag(TAG).d("  Final URL length (with pot): ${streamUrl.length}")
                        }
                    } catch (e: Exception) {
                        Timber.tag(TAG).e(e, "N-transform or pot append failed: ${e.message}")
                        Timber.tag(TAG).e("Stack trace: ${e.stackTraceToString().take(500)}")
                        // Continue with original URL
                    }
                } else {
                    Timber.tag(TAG).d("Skipping n-transform (not required for this client/content)")
                }

                streamExpiresInSeconds = streamPlayerResponse.streamingData?.expiresInSeconds
                if (streamExpiresInSeconds == null) {
                    Timber.tag(logTag).d("Stream expiration time not found")
                    continue
                }

                Timber.tag(logTag).d("Stream expires in: $streamExpiresInSeconds seconds")

                // Skip validation for private tracks (cookie-based auth, not URL-verifiable)
                // or for the last fallback client
                val isPrivate = isPrivateTrack || isPrivatelyOwnedTrack
                val isLastClient = clientIndex == STREAM_FALLBACK_CLIENTS.size - 1

                if (isLastClient) {
                    Timber.tag(logTag).d("Using last fallback client without validation: ${currentClient.clientName}")
                    Timber.tag(TAG).i("Playback: client=${currentClient.clientName}, videoId=$videoId, private=$isPrivate")
                    break
                }

                if (isPrivate) {
                    // Private tracks can't be validated via HEAD request (cookie-based auth).
                    // Keep the first (highest-priority, usually WEB_REMIX) candidate — its
                    // authenticated stream URL is more likely to work than later unauthenticated ones.
                    if (privateCandidateStreamUrl == null) {
                        Timber.tag(logTag).d("Skipping validation for privately owned track, recording first candidate: ${currentClient.clientName}")
                        privateCandidateStreamUrl = streamUrl
                        privateCandidateFormat = format
                        privateCandidateExpiry = streamExpiresInSeconds
                        privateCandidateResponse = streamPlayerResponse
                    } else {
                        Timber.tag(logTag).d("Already have private candidate, skipping: ${currentClient.clientName}")
                    }
                    continue
                }

                val httpStatus = validateStatus(streamUrl)
                if (httpStatus != null && httpStatus in 200..299) {
                    // working stream found
                    Timber.tag(logTag).d("Stream validated successfully with client: ${currentClient.clientName}")
                    Timber.tag(TAG).i("Playback: client=${currentClient.clientName}, videoId=$videoId")
                    break
                } else if (httpStatus == 403 && isLoggedIn && privateCandidateStreamUrl == null) {
                    // 403 with no prior private candidate — this stream likely requires cookie auth
                    // even though the API didn't flag it as PRIVATELY_OWNED. Treat as private.
                    Timber.tag(logTag).d("403 on validation, inferring private track, recording candidate: ${currentClient.clientName}")
                    inferredAsPrivate = true
                    privateCandidateStreamUrl = streamUrl
                    privateCandidateFormat = format
                    privateCandidateExpiry = streamExpiresInSeconds
                    privateCandidateResponse = streamPlayerResponse
                    continue
                } else {
                    Timber.tag(logTag).d("Stream validation failed ($httpStatus) for client: ${currentClient.clientName}")
                }
            } else {
                Timber.tag(logTag).d("Player response status not OK: ${streamPlayerResponse?.playabilityStatus?.status}, reason: ${streamPlayerResponse?.playabilityStatus?.reason}")
            }
        }

        // If the loop exhausted without committing (streamUrl null) but accumulated a valid
        // private-track candidate, restore it rather than throwing.
        if (streamUrl == null && privateCandidateStreamUrl != null) {
            Timber.tag(logTag).d("Loop exhausted; restoring best private-track candidate (${privateCandidateResponse?.videoDetails?.videoId})")
            streamUrl = privateCandidateStreamUrl
            format = privateCandidateFormat
            streamExpiresInSeconds = privateCandidateExpiry
            streamPlayerResponse = privateCandidateResponse
        }

        if (streamPlayerResponse == null) {
            Timber.tag(logTag).e("Bad stream player response - all clients failed")
            if (isUploadedTrack) {
                Timber.tag(TAG).e("All clients failed for uploaded track videoId=$videoId")
            }
            throw Exception("Bad stream player response")
        }

        if (streamPlayerResponse.playabilityStatus.status != "OK") {
            val errorReason = streamPlayerResponse.playabilityStatus.reason
            // YouTube often surfaces generic reasons (e.g. "error 2000") for restricted or
            // unavailable streams; Metrolist cannot recover those without official playback.
            Timber.tag(logTag).e("Playability status not OK: $errorReason")
            if (isUploadedTrack) {
                Timber.tag(TAG).e("Playability not OK for uploaded track: status=${streamPlayerResponse.playabilityStatus.status}, reason=$errorReason")
            }
            throw PlaybackException(
                errorReason,
                null,
                PlaybackException.ERROR_CODE_REMOTE_ERROR
            )
        }

        if (streamExpiresInSeconds == null) {
            Timber.tag(logTag).e("Missing stream expire time")
            throw Exception("Missing stream expire time")
        }

        if (format == null) {
            Timber.tag(logTag).e("Could not find format")
            throw Exception("Could not find format")
        }

        if (streamUrl == null) {
            Timber.tag(logTag).e("Could not find stream url")
            throw Exception("Could not find stream url")
        }

        Timber.tag(logTag).d("Successfully obtained playback data with format: ${format.mimeType}, bitrate: ${format.bitrate}")
        if (isUploadedTrack) {
            Timber.tag(TAG).d("Got playback data for uploaded track: format=${format.mimeType}")
        }
        PlaybackData(
            audioConfig,
            videoDetails,
            playbackTracking,
            format,
            streamUrl,
            streamExpiresInSeconds,
            isPrivatelyOwned = isPrivateTrack || inferredAsPrivate ||
                streamPlayerResponse.videoDetails?.musicVideoType == MUSIC_VIDEO_TYPE_PRIVATELY_OWNED_TRACK,
        )
    }.onFailure { e ->
        Timber.tag(TAG).e(e, "Playback exception for videoId=$videoId")
        e.printStackTrace()
    }
    /**
     * Simple player response intended to use for metadata only.
     * Stream URLs of this response might not work so don't use them.
     */
    suspend fun playerResponseForMetadata(
        videoId: String,
        playlistId: String? = null,
    ): Result<PlayerResponse> {
        Timber.tag(logTag).d("Fetching metadata-only player response for videoId: $videoId using MAIN_CLIENT: ${MAIN_CLIENT.clientName}")
        return YouTube.player(videoId, playlistId, client = WEB_REMIX) // ANDROID_VR does not work with history
            .onSuccess { Timber.tag(logTag).d("Successfully fetched metadata") }
            .onFailure { Timber.tag(logTag).e(it, "Failed to fetch metadata") }
    }

    /**
     * Selects the best audio format from the player response's adaptive formats
     * based on the requested [audioQuality] and current network conditions.
     * Prefers Opus (WebM) streams when bitrates are otherwise equal.
     *
     * @return the chosen format, or `null` if no audio-only original format is available.
     */
    private fun findFormat(
        playerResponse: PlayerResponse,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
    ): PlayerResponse.StreamingData.Format? {
        Timber.tag(logTag).d("Finding format with audioQuality: $audioQuality, network metered: ${connectivityManager.isActiveNetworkMetered}")

        val adaptiveFormats = playerResponse.streamingData?.adaptiveFormats ?: return null

        val audioCapableFormats = adaptiveFormats.filter { it.isAudio }
        if (audioCapableFormats.isEmpty()) return null

        val maxBitrate = audioCapableFormats.maxOfOrNull { it.bitrate } ?: return null

        val targetBitrate = when (audioQuality) {
            AudioQuality.VERY_HIGH -> maxBitrate.toDouble()
            AudioQuality.HIGH -> minOf(maxBitrate.toDouble(), 256000.0)
            AudioQuality.LOW -> minOf(maxBitrate.toDouble(), 128000.0)
            AudioQuality.AUTO -> {
                if (connectivityManager.isActiveNetworkMetered) {
                    minOf(maxBitrate.toDouble(), 128000.0)
                } else {
                    maxBitrate.toDouble()
                }
            }
        }

        Timber.tag(logTag).d("Finding format: maxBitrate=$maxBitrate, targetBitrate=$targetBitrate")

        val format = when (audioQuality) {
            AudioQuality.VERY_HIGH -> {
                val opus338 = audioCapableFormats.find { it.itag == 338 }
                if (opus338 != null) {
                    Timber.tag(logTag).d("Selected Opus itag 338: bitrate=${opus338.bitrate}")
                    return opus338
                }

                val opus141 = audioCapableFormats.find { it.itag == 141 }
                if (opus141 != null) {
                    Timber.tag(logTag).d("Selected AAC itag 141: bitrate=${opus141.bitrate}")
                    return opus141
                }

                audioCapableFormats
                    .filter { it.isOriginal }
                    .maxByOrNull { it.bitrate }
                    ?: audioCapableFormats.maxByOrNull { it.bitrate }
            }

            else -> {
                val cappedFormats = audioCapableFormats.filter { it.bitrate <= targetBitrate }
                val format = cappedFormats
                    .filter { it.isOriginal }
                    .maxByOrNull { it.bitrate }
                    ?: cappedFormats.maxByOrNull { it.bitrate }
                    ?: audioCapableFormats
                        .filter { it.isOriginal }
                        .minByOrNull { kotlin.math.abs(it.bitrate - targetBitrate) }
                    ?: audioCapableFormats.maxByOrNull { it.bitrate }

                if (format != null) {
                    Timber.tag(logTag).d("Selected format: ${format.mimeType}, bitrate: ${format.bitrate}")
                } else {
                    Timber.tag(logTag).d("No suitable audio format found")
                }

                format
            }
        }

        if (format != null && audioQuality == AudioQuality.VERY_HIGH) {
            Timber.tag(logTag).d("Selected format: ${format.mimeType}, bitrate: ${format.bitrate}")
        } else if (format == null) {
            Timber.tag(logTag).d("No suitable audio format found")
        }

        return format
    }
    /**
     * Checks if the stream url returns a successful status.
     * If this returns true the url is likely to work.
     * If this returns false the url might cause an error during playback.
     * Returns the HTTP status code, or null on network error.
     */
    private fun validateStatus(url: String, includeAuth: Boolean = false): Int? {
        Timber.tag(logTag).d("Validating stream URL status")
        try {
            val requestBuilder = okhttp3.Request.Builder()
                .head()
                .url(url)

            if (includeAuth) {
                YouTube.cookie?.let { cookie ->
                    requestBuilder.addHeader("Cookie", cookie)
                }
            }

            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                Timber.tag(logTag).d("Stream URL validation result: ${if (response.isSuccessful) "Success" else "Failed"} (${response.code})")
                return response.code
            }
        } catch (e: Exception) {
            Timber.tag(logTag).e(e, "Stream URL validation failed with exception")
            reportException(e)
        }
        return null
    }
    /**
     * Result of attempting to obtain a signature timestamp from NewPipe.
     * If the video is age-restricted, [timestamp] will be `null` and [isAgeRestricted] will be `true`.
     */
    data class SignatureTimestampResult(
        val timestamp: Int?,
        val isAgeRestricted: Boolean
    )

    /**
     * Retrieves the signature timestamp required for player requests via NewPipe.
     * Detects age-restricted content early if NewPipe reports it.
     */
    private fun getSignatureTimestampOrNull(videoId: String): SignatureTimestampResult {
        Timber.tag(logTag).d("Getting signature timestamp for videoId: $videoId")
        com.metrolist.innertube.NewPipeUtils  // Ensure NewPipe downloader is initialized before use
        val result = NewPipeExtractor.getSignatureTimestamp(videoId)
        return result.fold(
            onSuccess = { timestamp ->
                Timber.tag(logTag).d("Signature timestamp obtained: $timestamp")
                SignatureTimestampResult(timestamp, isAgeRestricted = false)
            },
            onFailure = { error ->
                val isAgeRestricted = error.message?.contains("age-restricted", ignoreCase = true) == true ||
                    error.cause?.message?.contains("age-restricted", ignoreCase = true) == true
                if (isAgeRestricted) {
                    Timber.tag(logTag).d("Age-restricted content detected from NewPipe")
                    Timber.tag(TAG).i("Age-restricted detected early via NewPipe: videoId=$videoId")
                } else {
                    Timber.tag(logTag).e(error, "Failed to get signature timestamp")
                    reportException(error)
                }
                SignatureTimestampResult(null, isAgeRestricted)
            }
        )
    }

    /**
     * Resolves a playable stream URL for the given [format].
     *
     * Tries, in order:
     * 1. The format's direct URL (if present).
     * 2. Custom cipher deobfuscation of `signatureCipher`.
     * 3. NewPipe signature deobfuscation (skipped when [skipNewPipe] is `true`,
     *    e.g. for age-restricted or privately-owned content that requires our auth).
     * 4. NewPipe `StreamInfo` as a last resort.
     *
     * @return the resolved URL, or `null` if all methods fail.
     */
    private suspend fun findUrlOrNull(
        format: PlayerResponse.StreamingData.Format,
        videoId: String,
        playerResponse: PlayerResponse,
        skipNewPipe: Boolean = false
    ): String? {
        Timber.tag(logTag).d("Finding stream URL for format: ${format.mimeType}, videoId: $videoId, skipNewPipe: $skipNewPipe")

        // First check if format already has a URL
        if (!format.url.isNullOrEmpty()) {
            Timber.tag(logTag).d("Using URL from format directly")
            return format.url
        }

        // Try custom cipher deobfuscation for signatureCipher formats
        val signatureCipher = format.signatureCipher ?: format.cipher
        if (!signatureCipher.isNullOrEmpty()) {
            Timber.tag(logTag).d("Format has signatureCipher, using custom deobfuscation")
            val customDeobfuscatedUrl = CipherDeobfuscator.deobfuscateStreamUrl(signatureCipher, videoId)
            if (customDeobfuscatedUrl != null) {
                Timber.tag(logTag).d("Stream URL obtained via custom cipher deobfuscation")
                return customDeobfuscatedUrl
            }
            Timber.tag(logTag).d("Custom cipher deobfuscation failed")
        }

        // Always try NewPipe signature deobfuscation - it doesn't need auth,
        // it just applies the cipher algorithm from player.js.
        // This is critical for privately-owned tracks where skipNewPipe is true.
        val deobfuscatedUrl = NewPipeExtractor.getStreamUrl(format, videoId)
        if (deobfuscatedUrl != null) {
            Timber.tag(logTag).d("Stream URL obtained via NewPipe deobfuscation")
            return deobfuscatedUrl
        }

        // Skip StreamInfo fallback for age-restricted or private content
        // (StreamInfo fetch may fail without auth for these)
        if (skipNewPipe) {
            Timber.tag(logTag).d("Skipping StreamInfo fallback for age-restricted/private content")
            return null
        }

        // Fallback: try to get URL from StreamInfo
        Timber.tag(logTag).d("Trying StreamInfo fallback for URL")
        val streamUrls = YouTube.getNewPipeStreamUrls(videoId)
        if (streamUrls.isNotEmpty()) {
            val streamUrl = streamUrls.find { it.first == format.itag }?.second
            if (streamUrl != null) {
                Timber.tag(logTag).d("Stream URL obtained from StreamInfo")
                return streamUrl
            }

            // If exact itag not found, try to find any audio stream
            val audioStream = streamUrls.find { urlPair ->
                playerResponse.streamingData?.adaptiveFormats?.any {
                    it.itag == urlPair.first && it.isAudio
                } == true
            }?.second

            if (audioStream != null) {
                Timber.tag(logTag).d("Audio stream URL obtained from StreamInfo (different itag)")
                return audioStream
            }
        }

        Timber.tag(logTag).e("Failed to get stream URL")
        return null
    }

    /** Evicts cached data for [videoId], forcing a fresh player request on next playback. */
    fun forceRefreshForVideo(videoId: String) {
        Timber.tag(logTag).d("Force refreshing for videoId: $videoId")
    }
}
