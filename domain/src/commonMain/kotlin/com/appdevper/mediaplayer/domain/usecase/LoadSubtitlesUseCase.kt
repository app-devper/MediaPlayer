package com.appdevper.mediaplayer.domain.usecase

import com.appdevper.mediaplayer.domain.model.Subtitle
import com.appdevper.mediaplayer.domain.model.SubtitleCue
import com.appdevper.mediaplayer.domain.repository.SubtitleRepository
import com.appdevper.mediaplayer.domain.subtitle.SubtitleParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Fetches and reads a subtitle file (off the main thread); fails when it can't be fetched or has no cues. */
class LoadSubtitlesUseCase(private val repo: SubtitleRepository) {
    suspend operator fun invoke(subtitle: Subtitle): Result<List<SubtitleCue>> =
        try {
            val cues = withContext(Dispatchers.Default) { SubtitleParser.parse(repo.fetch(subtitle.url)) }
            if (cues.isEmpty()) Result.failure(IllegalArgumentException("No subtitles in ${subtitle.label}")) else Result.success(cues)
        } catch (e: CancellationException) {
            throw e // cancelled (another pick): not a failure to show
        } catch (e: Throwable) {
            Result.failure(e)
        }
}
