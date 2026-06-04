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

class GetPlaylistsRequest private constructor(
    private val playlistId: String,
    private val requestHeader: Map<String, String>,
) {
    private val future: Future<Array<Pair<String, String>>> = Utils.submitOnBackgroundThread {
        fetch(playlistId, requestHeader)
    }

    val playlists: Array<Pair<String, String>>?
        get() {
            try {
                return future[MAX_MILLISECONDS_TO_WAIT_FOR_FETCH.toLong(), TimeUnit.MILLISECONDS]
            } catch (ex: TimeoutException) {
                Logger.printInfo({ "getPlaylists timed out" }, ex)
            } catch (ex: InterruptedException) {
                Logger.printException({ "getPlaylists interrupted" }, ex)
                Thread.currentThread().interrupt()
            } catch (ex: ExecutionException) {
                Logger.printException({ "getPlaylists failure" }, ex)
            }
            return null
        }

    companion object {
        private const val MAX_MILLISECONDS_TO_WAIT_FOR_FETCH = 20 * 1000

        @GuardedBy("itself")
        val cache: MutableMap<String, GetPlaylistsRequest> = Utils.createSizeRestrictedMap(50);

        @JvmStatic
        fun clear() {
            synchronized(cache) { cache.clear() }
        }

        @JvmStatic
        fun fetchRequestIfNeeded(playlistId: String, requestHeader: Map<String, String>) {
            Objects.requireNonNull(playlistId)
            synchronized(cache) {
                if (!cache.containsKey(playlistId)) {
                    cache[playlistId] = GetPlaylistsRequest(playlistId, requestHeader)
                }
            }
        }

        @JvmStatic
        fun getRequestForPlaylistId(playlistId: String): GetPlaylistsRequest? {
            synchronized(cache) { return cache[playlistId] }
        }

        private fun handleConnectionError(toastMessage: String, ex: Exception?) {
            Logger.printInfo({ toastMessage }, ex)
        }

        private fun sendRequest(playlistId: String, requestHeader: Map<String, String>): JSONObject? {
            Objects.requireNonNull(playlistId)
            val startTime = System.currentTimeMillis()
            Logger.printDebug { "Fetching get playlists request, playlistId: $playlistId" }
            try {
                val requestBody = PlaylistRoutes.getPlaylistsBody(playlistId)
                val connection = PlaylistRoutes.getConnection(PlaylistRoutes.GET_PLAYLISTS, requestHeader)
                connection.setFixedLengthStreamingMode(requestBody.size)
                connection.outputStream.write(requestBody)
                val responseCode = connection.responseCode
                if (responseCode == 200) return Requester.parseJSONObject(connection)
                handleConnectionError("Get playlists failed with code: $responseCode", null)
            } catch (ex: SocketTimeoutException) {
                handleConnectionError("Connection timeout", ex)
            } catch (ex: IOException) {
                handleConnectionError("Network error", ex)
            } catch (ex: Exception) {
                Logger.printException({ "sendRequest failed" }, ex)
            } finally {
                Logger.printDebug { "playlist: $playlistId took: ${System.currentTimeMillis() - startTime}ms" }
            }
            return null
        }

        private fun parseResponse(json: JSONObject): Array<Pair<String, String>>? {
            try {
                val addToPlaylistRendererJsonObject = json.getJSONArray("contents").get(0)
                if (addToPlaylistRendererJsonObject is JSONObject) {
                    val playlistsJsonArray = addToPlaylistRendererJsonObject
                        .getJSONObject("addToPlaylistRenderer")
                        .getJSONArray("playlists")

                    val playlistsLength = playlistsJsonArray.length()
                    val playlists: Array<Pair<String, String>?> = arrayOfNulls(playlistsLength)

                    for (i in 0 until playlistsLength) {
                        val elementsJsonObject = playlistsJsonArray.get(i)
                        if (elementsJsonObject is JSONObject) {
                            val renderer = elementsJsonObject.getJSONObject("playlistAddToOptionRenderer")
                            val id = renderer.getString("playlistId")
                            val title = (renderer.getJSONObject("title")
                                .getJSONArray("runs")
                                .get(0) as JSONObject)
                                .getString("text")
                            playlists[i] = Pair(id, title)
                        }
                    }

                    val finalPlaylists = playlists.filterNotNull().toTypedArray()
                    if (finalPlaylists.isNotEmpty()) return finalPlaylists
                }
            } catch (e: JSONException) {
                Logger.printException({ "parseResponse failed: $json" }, e)
            }
            return null
        }

        private fun fetch(playlistId: String, requestHeader: Map<String, String>): Array<Pair<String, String>>? {
            val json = sendRequest(playlistId, requestHeader)
            if (json != null) return parseResponse(json)
            return null
        }
    }
}
