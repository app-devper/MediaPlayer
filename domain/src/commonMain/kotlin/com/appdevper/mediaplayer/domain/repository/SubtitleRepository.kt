package com.appdevper.mediaplayer.domain.repository

/** Fetches subtitle files. */
interface SubtitleRepository {
    /** The file's bytes; throws when it can't be read or is too large to be subtitles. */
    suspend fun fetch(url: String): ByteArray
}
