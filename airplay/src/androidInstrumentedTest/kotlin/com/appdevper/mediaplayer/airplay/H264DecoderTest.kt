package com.appdevper.mediaplayer.airplay

import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataInputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

/**
 * Mirroring H.264 (red 320x240, encoded on the host with FFmpeg, starting SPS/PPS/IDR like an
 * iPhone stream) through MediaCodec to a Surface on a real device or emulator.
 */
@RunWith(AndroidJUnit4::class)
class H264DecoderTest {
    private val thread = HandlerThread("images").apply { start() }
    private val reader = ImageReader.newInstance(320, 240, ImageFormat.YUV_420_888, 4)
    private val sizes = CopyOnWriteArrayList<Pair<Int, Int>>()
    private val decoder = H264Decoder { w, h -> sizes += w to h }
    private val firstPicture = CountDownLatch(1)
    @Volatile private var lumaAtCenter = -1

    init {
        reader.setOnImageAvailableListener({ r ->
            r.acquireLatestImage()?.use { image ->
                val y = image.planes[0]
                lumaAtCenter = y.buffer.get(120 * y.rowStride + 160 * y.pixelStride).toInt() and 0xff
                firstPicture.countDown()
            }
        }, Handler(thread.looper))
    }

    @After
    fun tearDown() {
        decoder.release()
        reader.close()
        thread.quitSafely()
    }

    private fun accessUnits(): List<ByteArray> {
        val stream = DataInputStream(javaClass.classLoader!!.getResourceAsStream("red_320x240.h264au"))
        return buildList { while (stream.available() > 0) add(ByteArray(stream.readInt()).also(stream::readFully)) }
    }

    @Test
    fun decodesToTheSurface() {
        decoder.setSurface(reader.surface)
        accessUnits().forEachIndexed { i, au -> decoder.decode(au, i * 33_000_000L) }
        assertTrue(firstPicture.await(10, TimeUnit.SECONDS), "no picture reached the surface")
        assertTrue(lumaAtCenter in 70..95, "red has Y≈81, got $lumaAtCenter")
        assertTrue(320 to 240 in sizes, "reported sizes $sizes")
    }

    @Test
    fun aSurfaceThatShowsUpLateStillGetsThePicture() {
        // The stream starts before the Mirror screen exists; the keyframe is long gone by then.
        accessUnits().forEachIndexed { i, au -> decoder.decode(au, i * 33_000_000L) }
        Thread.sleep(300)
        decoder.setSurface(reader.surface)
        assertTrue(firstPicture.await(10, TimeUnit.SECONDS), "buffered GOP wasn't replayed")
        assertTrue(lumaAtCenter in 70..95, "red has Y≈81, got $lumaAtCenter")
    }
}
