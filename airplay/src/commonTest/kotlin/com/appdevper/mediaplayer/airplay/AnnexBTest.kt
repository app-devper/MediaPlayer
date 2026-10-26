package com.appdevper.mediaplayer.airplay

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class AnnexBTest {

    @Test
    fun splitsNalUnitsWithThreeAndFourByteStartCodes() {
        val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1f)
        val pps = byteArrayOf(0x68, 0xce.toByte(), 0x3c, 0x80.toByte())
        val idr = byteArrayOf(0x65, 0x88.toByte(), 0x84.toByte(), 0x00, 0x00, 0x02, 0x10) // contains 00 00 02: not a start code
        val au = byteArrayOf(0, 0, 0, 1) + sps + byteArrayOf(0, 0, 1) + pps + byteArrayOf(0, 0, 0, 1) + idr
        val nals = AnnexB.nalUnits(au)
        assertEquals(listOf(AnnexB.NAL_SPS, AnnexB.NAL_PPS, AnnexB.NAL_IDR), nals.map(AnnexB::type))
        assertContentEquals(sps, nals[0])
        assertContentEquals(pps, nals[1])
        assertContentEquals(idr, nals[2])
        assertContentEquals(byteArrayOf(0, 0, 0, 1) + sps, AnnexB.withStartCode(sps))
    }

    @Test
    fun noStartCodeMeansNoNals() {
        assertEquals(0, AnnexB.nalUnits(byteArrayOf(1, 2, 3, 4)).size)
        assertEquals(0, AnnexB.nalUnits(ByteArray(0)).size)
    }

    @Test
    fun volumeCurve() {
        assertEquals(0f, airPlayGain(-144f))
        assertEquals(0f, airPlayGain(-30f))
        assertEquals(1f, airPlayGain(0f))
        assertEquals(0.25f, airPlayGain(-15f))
    }
}
