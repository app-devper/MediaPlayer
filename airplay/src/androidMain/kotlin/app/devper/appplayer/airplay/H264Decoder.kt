package app.devper.appplayer.airplay

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Mirroring video → a Surface through MediaCodec, on its own thread. Keeps the access units since
 * the last keyframe so a Surface that shows up late (or comes back) starts at once instead of
 * waiting for the sender's next keyframe, which can be seconds away on a still screen.
 */
internal class H264Decoder(private val onSize: (Int, Int) -> Unit) {
    private sealed interface Msg {
        class Frame(val au: ByteArray, val ptsUs: Long) : Msg
        class SetSurface(val surface: Surface?) : Msg
        data object Reset : Msg
        data object Quit : Msg
    }

    private val queue = LinkedBlockingQueue<Msg>()
    private val thread = Thread(::run, "airplay-video").apply { isDaemon = true; start() }

    private var surface: Surface? = null
    private var codec: MediaCodec? = null
    private var codecSps: ByteArray? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private val gop = ArrayList<Msg.Frame>()
    private val info = MediaCodec.BufferInfo()

    fun decode(au: ByteArray, ptsNs: Long) {
        // Falling far behind (a stalled surface) is worse than skipping: drop to the next keyframe.
        if (queue.size > MAX_QUEUED) queue.removeIf { it is Msg.Frame }
        queue.offer(Msg.Frame(au, ptsNs / 1000))
    }

    fun setSurface(surface: Surface?) = queue.offer(Msg.SetSurface(surface))

    /** Mirroring stopped: forget the stream (the next one starts with fresh SPS/PPS). */
    fun reset() = queue.offer(Msg.Reset)

    fun release() {
        queue.offer(Msg.Quit)
        thread.join(1_000)
    }

    private fun run() {
        while (true) {
            when (val msg = queue.poll(20, TimeUnit.MILLISECONDS)) {
                is Msg.Frame -> onFrame(msg)
                is Msg.SetSurface -> onSurface(msg.surface)
                Msg.Reset -> {
                    releaseCodec()
                    gop.clear()
                    sps = null
                    pps = null
                }
                Msg.Quit -> {
                    releaseCodec()
                    return
                }
                null -> Unit
            }
            drain()
        }
    }

    private fun onFrame(frame: Msg.Frame) {
        var keyframe = false
        for (nal in AnnexB.nalUnits(frame.au)) {
            when (AnnexB.type(nal)) {
                AnnexB.NAL_SPS -> sps = nal
                AnnexB.NAL_PPS -> pps = nal
                AnnexB.NAL_IDR -> keyframe = true
            }
        }
        if (keyframe) gop.clear()
        if (keyframe || gop.isNotEmpty()) {
            gop += frame
            if (gop.size > MAX_GOP) gop.clear() // a sender that never sends keyframes: give up buffering
        }
        val c = codec
        when {
            c == null -> startCodec()
            !sps.contentEquals(codecSps) -> { releaseCodec(); startCodec() } // new resolution (rotation)
            else -> feed(c, frame)
        }
    }

    private fun onSurface(next: Surface?) {
        surface = next
        val c = codec
        when {
            next == null -> releaseCodec()
            c == null -> startCodec()
            Build.VERSION.SDK_INT >= 23 -> runCatching { c.setOutputSurface(next) }.onFailure { releaseCodec(); startCodec() }
            else -> { releaseCodec(); startCodec() }
        }
    }

    private fun startCodec() {
        val out = surface ?: return
        val sps = sps ?: return
        val pps = pps ?: return
        if (gop.isEmpty()) return
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(AnnexB.withStartCode(sps)))
            setByteBuffer("csd-1", ByteBuffer.wrap(AnnexB.withStartCode(pps)))
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4 * 1024 * 1024)
            if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
        }
        val c = runCatching {
            MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(format, out, null, 0)
                start()
            }
        }.getOrNull() ?: return
        codec = c
        codecSps = sps
        gop.forEach { feed(c, it) }
    }

    private fun feed(c: MediaCodec, frame: Msg.Frame) {
        val index = runCatching { c.dequeueInputBuffer(INPUT_TIMEOUT_US) }.getOrDefault(-1)
        if (index < 0) return
        val buffer = c.getInputBuffer(index) ?: return
        if (frame.au.size > buffer.capacity()) {
            c.queueInputBuffer(index, 0, 0, frame.ptsUs, 0)
            return
        }
        buffer.clear()
        buffer.put(frame.au)
        c.queueInputBuffer(index, 0, frame.au.size, frame.ptsUs, 0)
    }

    private fun drain() {
        val c = codec ?: return
        while (true) {
            val index = runCatching { c.dequeueOutputBuffer(info, 0) }.getOrDefault(MediaCodec.INFO_TRY_AGAIN_LATER)
            when {
                index >= 0 -> c.releaseOutputBuffer(index, true) // show as soon as decoded: lowest latency
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> reportSize(c.outputFormat)
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
            }
        }
    }

    private fun reportSize(format: MediaFormat) {
        val hasCrop = format.containsKey("crop-left") && format.containsKey("crop-right")
        val width = if (hasCrop) format.getInteger("crop-right") - format.getInteger("crop-left") + 1 else format.getInteger(MediaFormat.KEY_WIDTH)
        val height = if (hasCrop) format.getInteger("crop-bottom") - format.getInteger("crop-top") + 1 else format.getInteger(MediaFormat.KEY_HEIGHT)
        onSize(width, height)
    }

    private fun releaseCodec() {
        codec?.let { c -> runCatching { c.stop() }; runCatching { c.release() } }
        codec = null
        codecSps = null
    }

    private companion object {
        const val MAX_QUEUED = 30
        const val MAX_GOP = 600
        const val INPUT_TIMEOUT_US = 10_000L
    }
}
