package com.appdevper.mediaplayer.domain.usecase

import com.appdevper.mediaplayer.domain.model.Subtitle
import com.appdevper.mediaplayer.domain.model.SubtitleCue
import com.appdevper.mediaplayer.domain.repository.SubtitleRepository
import com.appdevper.mediaplayer.domain.subtitle.SubtitleParser

/** Fetches and reads a subtitle file; fails when it can't be fetched or has no cues. */
class LoadSubtitlesUseCase(private val repo: SubtitleRepository) {
    suspend operator fun invoke(subtitle: Subtitle): Result<List<SubtitleCue>> =
        runCatching { SubtitleParser.parse(repo.fetch(subtitle.url)) }
            .mapCatching { cues -> cues.ifEmpty { throw IllegalArgumentException("No subtitles in ${subtitle.label}") } }
}
