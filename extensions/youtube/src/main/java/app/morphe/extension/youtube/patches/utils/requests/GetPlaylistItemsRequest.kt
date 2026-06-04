/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.utils.requests

import app.morphe.extension.shared.Logger
import app.morphe.extension.shared.requests.Requester
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException

object GetPlaylistItemsRequest {

    @JvmStatic
    fun fetch(playlistId: String, requestHeader: Map<String, String>): Map<String, String>? {
        val startTime = System.currentTimeMillis()
        Logger.printDebug { "Fetching playlist items for: $playlistId" }
        try {
            val requestBody = PlaylistRoutes.browsePlaylistBody(playlistId)
            val connection = PlaylistRoutes.getConnection(PlaylistRoutes.BROWSE_PLAYLIST, requestHeader)
            connection.setFixedLengthStreamingMode(requestBody.size)
            connection.outputStream.write(requestBody)
            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val json = Requester.parseJSONObject(connection) ?: return null
                return parseResponse(json)
            }
            Logger.printInfo { "Browse playlist failed with code: $responseCode" }
        } catch (ex: SocketTimeoutException) {
            Logger.printInfo({ "Connection timeout" }, ex)
        } catch (ex: IOException) {
            Logger.printInfo({ "Network error" }, ex)
        } catch (ex: Exception) {
            Logger.printException({ "fetch failed" }, ex)
        } finally {
            Logger.printDebug { "playlist items fetch took: ${System.currentTimeMillis() - startTime}ms" }
        }
        return null
    }

    private fun findPlaylistContents(sectionContents: JSONArray): JSONArray? {
        for (i in 0 until sectionContents.length()) {
            val section = sectionContents.getJSONObject(i)
            if (section.has("playlistVideoListRenderer")) {
                return section.getJSONObject("playlistVideoListRenderer").getJSONArray("contents")
            }
            if (section.has("itemSectionRenderer")) {
                val inner = section.getJSONObject("itemSectionRenderer").getJSONArray("contents")
                for (j in 0 until inner.length()) {
                    val innerItem = inner.getJSONObject(j)
                    if (innerItem.has("playlistVideoListRenderer")) {
                        return innerItem.getJSONObject("playlistVideoListRenderer").getJSONArray("contents")
                    }
                }
            }
        }
        return null
    }

    private fun parseResponse(json: JSONObject): Map<String, String>? {
        try {
            val contents = json.getJSONObject("contents")
            val columnRenderer = contents.optJSONObject("singleColumnBrowseResultsRenderer")
                ?: contents.optJSONObject("twoColumnBrowseResultsRenderer")
                ?: return null

            val sectionContents = columnRenderer
                .getJSONArray("tabs")
                .getJSONObject(0)
                .getJSONObject("tabRenderer")
                .getJSONObject("content")
                .getJSONObject("sectionListRenderer")
                .getJSONArray("contents")

            val playlistContents = findPlaylistContents(sectionContents) ?: return null

            val result = mutableMapOf<String, String>()
            for (i in 0 until playlistContents.length()) {
                val renderer = playlistContents.optJSONObject(i)
                    ?.optJSONObject("playlistVideoRenderer") ?: continue
                val videoId = renderer.optString("videoId").takeIf { it.isNotEmpty() } ?: continue
                val setVideoId = renderer.optString("setVideoId").takeIf { it.isNotEmpty() } ?: continue
                result[videoId] = setVideoId
            }

            return result.ifEmpty { null }
        } catch (e: JSONException) {
            Logger.printException({ "parseResponse failed" }, e)
        }
        return null
    }
}
