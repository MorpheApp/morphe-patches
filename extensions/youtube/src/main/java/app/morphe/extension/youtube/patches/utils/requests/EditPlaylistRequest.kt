/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.utils.requests

import androidx.annotation.GuardedBy
import app.morphe.extension.shared.Logger
import app.morphe.extension.shared.Utils
import app.morphe.extension.shared.requests.Requester
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.Objects
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class EditPlaylistRequest private constructor(
    private val videoId: String,
    private val playlistId: String,
    private val setVideoId: String?,
    private val requestHeader: Map<String, String>,
) {
    private val future: Future<String> = Utils.submitOnBackgroundThread {
        fetch(videoId, playlistId, setVideoId, requestHeader)
    }

    val result: String?
        get() {
            try {
                return future[MAX_MILLISECONDS_TO_WAIT_FOR_FETCH.toLong(), TimeUnit.MILLISECONDS]
            } catch (ex: TimeoutException) {
                Logger.printInfo({ "getResult timed out" }, ex)
            } catch (ex: InterruptedException) {
                Logger.printException({ "getResult interrupted" }, ex)
                Thread.currentThread().interrupt()
            } catch (ex: ExecutionException) {
                Logger.printException({ "getResult failure" }, ex)
            }
            return null
        }

    companion object {
        private const val MAX_MILLISECONDS_TO_WAIT_FOR_FETCH = 20 * 1000

        @GuardedBy("itself")
        val cache: MutableMap<String, EditPlaylistRequest> = Utils.createSizeRestrictedMap(50);

        @JvmStatic
        fun clear() {
            synchronized(cache) { cache.clear() }
        }

        @JvmStatic
        fun clearVideoId(videoId: String) {
            synchronized(cache) { cache.remove(videoId) }
        }

        @JvmStatic
        fun fetchRequestIfNeeded(
            videoId: String,
            playlistId: String,
            setVideoId: String?,
            requestHeader: Map<String, String>,
        ) {
            Objects.requireNonNull(videoId)
            synchronized(cache) {
                if (!cache.containsKey(videoId)) {
                    cache[videoId] = EditPlaylistRequest(videoId, playlistId, setVideoId, requestHeader)
                }
            }
        }

        @JvmStatic
        fun getRequestForVideoId(videoId: String): EditPlaylistRequest? {
            synchronized(cache) { return cache[videoId] }
        }

        private fun handleConnectionError(toastMessage: String, ex: Exception?) {
            Logger.printInfo({ toastMessage }, ex)
        }

        private fun sendRequest(
            videoId: String,
            playlistId: String,
            setVideoId: String?,
            requestHeader: Map<String, String>,
        ): JSONObject? {
            Objects.requireNonNull(videoId)
            val startTime = System.currentTimeMillis()
            Logger.printDebug { "Fetching edit playlist request, videoId: $videoId, playlistId: $playlistId, setVideoId: $setVideoId" }
            try {
                val requestBody = PlaylistRoutes.editPlaylistBody(videoId, playlistId, setVideoId)
                val connection = PlaylistRoutes.getConnection(PlaylistRoutes.EDIT_PLAYLIST, requestHeader)
                connection.setFixedLengthStreamingMode(requestBody.size)
                connection.outputStream.write(requestBody)
                val responseCode = connection.responseCode
                if (responseCode == 200) return Requester.parseJSONObject(connection)
                handleConnectionError("Edit playlist failed with code: $responseCode", null)
            } catch (ex: SocketTimeoutException) {
                handleConnectionError("Connection timeout", ex)
            } catch (ex: IOException) {
                handleConnectionError("Network error", ex)
            } catch (ex: Exception) {
                Logger.printException({ "sendRequest failed" }, ex)
            } finally {
                Logger.printDebug { "video: $videoId took: ${System.currentTimeMillis() - startTime}ms" }
            }
            return null
        }

        private fun parseResponse(json: JSONObject, remove: Boolean): String? {
            try {
                if (json.getString("status") == "STATUS_SUCCEEDED") {
                    if (remove) return ""
                    val playlistEditResultsJSONObject = json.getJSONArray("playlistEditResults").get(0)
                    if (playlistEditResultsJSONObject is JSONObject) {
                        return playlistEditResultsJSONObject
                            .getJSONObject("playlistEditVideoAddedResultData")
                            .getString("setVideoId")
                    }
                }
            } catch (e: JSONException) {
                Logger.printException({ "parseResponse failed: $json" }, e)
            }
            return null
        }

        private fun fetch(
            videoId: String,
            playlistId: String,
            setVideoId: String?,
            requestHeader: Map<String, String>,
        ): String? {
            val json = sendRequest(videoId, playlistId, setVideoId, requestHeader)
            if (json != null) {
                return parseResponse(json, !setVideoId.isNullOrEmpty())
            }
            return null
        }
    }
}
