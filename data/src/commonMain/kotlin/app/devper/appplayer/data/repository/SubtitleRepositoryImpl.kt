package app.devper.appplayer.data.repository

import app.devper.appplayer.domain.repository.SubtitleRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import kotlinx.io.IOException
import kotlinx.io.readByteArray

/** Fetches subtitle files over HTTP, up to [MAX_BYTES] (a subtitle file is far smaller). */
class SubtitleRepositoryImpl(private val http: HttpClient) : SubtitleRepository {
    override suspend fun fetch(url: String): ByteArray {
        require(url.lowercase().let { it.startsWith("http://") || it.startsWith("https://") }) { "Not a web address: $url" }
        return http.prepareGet(url).execute { response ->
            if (!response.status.isSuccess()) throw IOException("The server answered ${response.status.value}")
            val body = response.bodyAsChannel().readRemaining(MAX_BYTES + 1).readByteArray()
            if (body.size > MAX_BYTES) throw IOException("Too large for subtitles")
            body
        }
    }

    private companion object {
        const val MAX_BYTES = 4L * 1024 * 1024
    }
}
