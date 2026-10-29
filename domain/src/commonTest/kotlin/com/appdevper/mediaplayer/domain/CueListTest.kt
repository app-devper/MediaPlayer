package com.appdevper.mediaplayer.domain

import com.appdevper.mediaplayer.domain.model.SubtitleCue
import com.appdevper.mediaplayer.domain.subtitle.CueList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CueListTest {
    @Test
    fun cuesKeepStartOrderAndASeekBackDoesntAddThemTwice() {
        val list = CueList()
        list.add(1_000, 2_000, "one")
        list.add(3_000, 4_000, "three")
        list.add(2_000, 3_000, "two") // read out of order
        list.add(1_000, 2_000, "one") // read again after a seek back
        assertEquals(listOf("one", "two", "three"), list.cues.map(SubtitleCue::text))
        assertEquals("two", list.textAt(2_500))
        assertNull(list.textAt(4_500))
    }

    @Test
    fun aCueWithNoEndLastsUntilTheNextOneAndAtMostItsLimit() {
        val list = CueList(noEndMs = 5_000)
        list.add(1_000, -1, "open")
        assertEquals(SubtitleCue(1_000, 6_000, "open"), list.cues.single())
        list.add(2_500, 3_000, "next") // ends the open one
        assertEquals(SubtitleCue(1_000, 2_500, "open"), list.cues.first())
        assertEquals("next", list.textAt(2_600))
    }
}
