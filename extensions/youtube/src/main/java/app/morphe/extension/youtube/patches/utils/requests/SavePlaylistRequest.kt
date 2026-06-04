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
import java.util.Objects
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class SavePlaylistRequest private constructor(
    private val playlistId: String,
    private val libraryId: String,
    private val requestHeader: Map<String, String>,
) {
    private val future: Future<Boolean> = Utils.submitOnBackgroundThread {
        fetch(playlistId, libraryId, requestHeader)
    }

    val result: Boolean?
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
        val cache: MutableMap<String, SavePlaylistRequest> = Utils.createSizeRestrictedMap(50)

        @JvmStatic
        fun clear() {
            synchronized(cache) { cache.clear() }
        }

        @JvmStatic
        fun fetchRequestIfNeeded(
            playlistId: String,
            libraryId: String,
            requestHeader: Map<String, String>,
        ) {
            Objects.requireNonNull(playlistId)
            synchronized(cache) {
                cache[libraryId] = SavePlaylistRequest(playlistId, libraryId, requestHeader)
            }
        }

        @JvmStatic
        fun getRequestForLibraryId(libraryId: String): SavePlaylistRequest? {
            synchronized(cache) { return cache[libraryId] }
        }

        private fun handleConnectionError(toastMessage: String, ex: Exception?) {
            Logger.printInfo({ toastMessage }, ex)
        }

        private fun sendRequest(
            playlistId: String,
            libraryId: String,
            requestHeader: Map<String, String>,
        ): JSONObject? {
            Objects.requireNonNull(playlistId)
            Objects.requireNonNull(libraryId)
            val startTime = System.currentTimeMillis()
            Logger.printDebug { "Fetching save playlist request, playlistId: $playlistId, libraryId: $libraryId" }
            try {
                val requestBody = PlaylistRoutes.savePlaylistBody(playlistId, libraryId)
                val connection = PlaylistRoutes.getConnection(PlaylistRoutes.EDIT_PLAYLIST, requestHeader)
                connection.setFixedLengthStreamingMode(requestBody.size)
                connection.outputStream.write(requestBody)
                val responseCode = connection.responseCode
                if (responseCode == 200) return Requester.parseJSONObject(connection)
                handleConnectionError("Save playlist failed with code: $responseCode", null)
            } catch (ex: SocketTimeoutException) {
                handleConnectionError("Connection timeout", ex)
            } catch (ex: IOException) {
                handleConnectionError("Network error", ex)
            } catch (ex: Exception) {
                Logger.printException({ "sendRequest failed" }, ex)
            } finally {
                Logger.printDebug { "playlistId: $playlistId libraryId: $libraryId took: ${System.currentTimeMillis() - startTime}ms" }
            }
            return null
        }

        private fun parseResponse(json: JSONObject): Boolean? {
            try {
                return json.getString("status") == "STATUS_SUCCEEDED"
            } catch (e: JSONException) {
                Logger.printException({ "parseResponse failed: $json" }, e)
            }
            return null
        }

        private fun fetch(
            playlistId: String,
            libraryId: String,
            requestHeader: Map<String, String>,
        ): Boolean? {
            val json = sendRequest(playlistId, libraryId, requestHeader)
            if (json != null) return parseResponse(json)
            return null
        }
    }
}
