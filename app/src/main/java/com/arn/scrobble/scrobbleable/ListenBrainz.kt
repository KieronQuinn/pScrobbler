package com.arn.scrobble.scrobbleable

import com.arn.scrobble.LFMRequester
import com.arn.scrobble.Stuff
import de.umass.lastfm.Result
import de.umass.lastfm.Track
import de.umass.lastfm.scrobble.ScrobbleData
import de.umass.lastfm.scrobble.ScrobbleResult
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URL


class ListenBrainz : Scrobblable() {
    override var apiRoot = Stuff.LISTENBRAINZ_API_ROOT

    private val client
        get() = if (tlsNoVerify) LFMRequester.okHttpClientTlsNoVerify else LFMRequester.okHttpClient

    private fun submitListens(
        scrobbleDatas: List<ScrobbleData>,
        listenType: String
    ): ScrobbleResult {
        val payload = JSONArray()
        scrobbleDatas.forEach {
            val payloadTrack = JSONObject()
                .put(
                    "track_metadata", JSONObject()
                        .put("artist_name", it.artist ?: "")
                        .put("release_name", it.album ?: "")
                        .put("track_name", it.track ?: "")
                )
            if (listenType != "playing_now")
                payloadTrack.put("listened_at", it.timestamp)
            payload.put(payloadTrack)
        }
        val data = JSONObject()
            .put("listen_type", listenType)
            .put("payload", payload)

        val url = URL("${apiRoot}1/submit-listens")

        try {
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "token $token")
                .post(
                    data.toString().toRequestBody("application/json".toMediaType())
                )
                .build()

            return client.newCall(request).execute()
                .use { response ->
                    var errMsg = ""
                    val bodyJson = JSONObject(response.body!!.string())
                    if (bodyJson.has("status"))
                        errMsg = bodyJson.getString("status")
                    else if (bodyJson.has("error")) {
                        errMsg = bodyJson.getString("error")
                    }

                    ScrobbleResult.createHttp200OKResult(
                        response.code,
                        response.message, errMsg
                    )
                }

        } catch (e: Exception) {
            e.printStackTrace()

            return if (Thread.interrupted())
                ScrobbleResult.createHttp200OKResult(
                    200,
                    e.message,
                    "ok"
                ) //suppress err notification
            else
                ScrobbleResult.createHttp200OKResult(0, e.message, "")
        }
    }

    override fun updateNowPlaying(scrobbleData: ScrobbleData) =
        submitListens(listOf(scrobbleData), "playing_now")

    override fun scrobble(scrobbleData: ScrobbleData) =
        submitListens(listOf(scrobbleData), "single")

    override fun scrobble(scrobbleDatas: MutableList<ScrobbleData>) =
        submitListens(scrobbleDatas, "import")

    override fun loveOrUnlove(track: Track, love: Boolean): Result {
        throw NotImplementedError("Not implemented")
    }

    fun recents(username: String, limit: Int): JSONObject? {
        val url = URL("${apiRoot}1/user/$username/listens?count=$limit")
        try {
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "token $token")
                // this works even without the token or an incorrect token
                .build()

            return client.newCall(request).execute().use { response ->
                if (response.isSuccessful)
                    JSONObject(response.body!!.string())
                else
                    null
            }
        } catch (e: IOException) {
        }

        return null
    }

    fun username(): String? {
        val url = URL("${apiRoot}1/validate-token")

        var username: String? = null
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "token $token")
            .build()
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val respJson = JSONObject(response.body!!.string())
                if (respJson.getBoolean("valid"))
                    username = respJson.getString("user_name")
            }
        }

        return username
    }

}