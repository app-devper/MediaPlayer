package app.devper.appplayer.airplay

import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_FLAG_LOW_DELAY
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264
import org.bytedeco.ffmpeg.global.avcodec.av_new_packet
import org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc
import org.bytedeco.ffmpeg.global.avcodec.av_packet_free
import org.bytedeco.ffmpeg.global.avcodec.av_packet_unref
import org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder
import org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_open2
import org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_frame
import org.bytedeco.ffmpeg.global.avcodec.avcodec_send_packet
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGRA
import org.bytedeco.ffmpeg.global.avutil.av_frame_alloc
import org.bytedeco.ffmpeg.global.avutil.av_frame_free
import org.bytedeco.ffmpeg.global.avutil.av_frame_get_buffer
import org.bytedeco.ffmpeg.global.swscale.sws_alloc_context
import org.bytedeco.ffmpeg.global.swscale.sws_freeContext
import org.bytedeco.ffmpeg.global.swscale.sws_scale_frame
import org.bytedeco.ffmpeg.swscale.SwsContext
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Mirroring video → BGRA frames with FFmpeg's H.264 decoder, on its own thread. */
internal class FfmpegVideoDecoder(private val onFrame: (VideoFrame) -> Unit) {
    private sealed interface Msg {
        class Frame(val au: ByteArray) : Msg
        data object Reset : Msg
        data object Quit : Msg
    }

    private val queue = LinkedBlockingQueue<Msg>()
    private val thread = Thread(::run, "airplay-video").apply { isDaemon = true; start() }

    private var ctx: AVCodecContext? = null
    private var packet: AVPacket? = null
    private var frame: AVFrame? = null
    private var sws: SwsContext? = null
    private var bgra: AVFrame? = null
    private var waitingForKeyframe = true

    fun decode(au: ByteArray) {
        if (queue.size > MAX_QUEUED) {
            queue.removeIf { it is Msg.Frame }
            queue.offer(Msg.Reset) // restart cleanly at the next keyframe
        }
        queue.offer(Msg.Frame(au))
    }

    fun reset() = queue.offer(Msg.Reset)

    fun release() {
        queue.offer(Msg.Quit)
        thread.join(1_000)
    }

    private fun run() {
        while (true) {
            when (val msg = queue.poll(50, TimeUnit.MILLISECONDS)) {
                is Msg.Frame -> runCatching { decodeFrame(msg.au) }
                Msg.Reset -> close()
                Msg.Quit -> {
                    close()
                    return
                }
                null -> Unit
            }
        }
    }

    private fun open(): Boolean {
        val codec = avcodec_find_decoder(AV_CODEC_ID_H264) ?: return false
        val c = avcodec_alloc_context3(codec) ?: return false
        c.flags(c.flags() or AV_CODEC_FLAG_LOW_DELAY)
        if (avcodec_open2(c, codec, null as AVDictionary?) < 0) {
            avcodec_free_context(c)
            return false
        }
        ctx = c
        packet = av_packet_alloc()
        frame = av_frame_alloc()
        return true
    }

    private fun decodeFrame(au: ByteArray) {
        if (waitingForKeyframe) {
            if (AnnexB.nalUnits(au).none { AnnexB.type(it) == AnnexB.NAL_IDR || AnnexB.type(it) == AnnexB.NAL_SPS }) return
            waitingForKeyframe = false
        }
        if (ctx == null && !open()) return
        val c = ctx!!
        val p = packet!!
        if (av_new_packet(p, au.size) < 0) return
        p.data().put(au, 0, au.size)
        val sent = avcodec_send_packet(c, p)
        av_packet_unref(p)
        if (sent < 0) return
        val f = frame!!
        while (avcodec_receive_frame(c, f) == 0) emit(f)
    }

    private fun emit(f: AVFrame) {
        val w = f.width()
        val h = f.height()
        if (w <= 0 || h <= 0) return
        val s = sws ?: sws_alloc_context()?.also { sws = it } ?: return
        val out = bgra?.takeIf { it.width() == w && it.height() == h } ?: run {
            bgra?.let { av_frame_free(it) }
            av_frame_alloc().also {
                it.format(AV_PIX_FMT_BGRA); it.width(w); it.height(h)
                if (av_frame_get_buffer(it, 0) < 0) return
                bgra = it
            }
        }
        if (sws_scale_frame(s, out, f) < 0) return
        // Rows may be padded (linesize > width * 4): copy them packed.
        val row = w * 4
        val stride = out.linesize(0)
        val src = out.data(0)
        val pixels = ByteArray(row * h)
        if (stride == row) {
            src.get(pixels, 0, pixels.size)
        } else {
            for (y in 0 until h) src.position(y.toLong() * stride).get(pixels, y * row, row)
            src.position(0)
        }
        onFrame(VideoFrame(w, h, pixels))
    }

    private fun close() {
        ctx?.let { avcodec_free_context(it) }
        packet?.let { av_packet_free(it) }
        frame?.let { av_frame_free(it) }
        sws?.let { sws_freeContext(it) }
        bgra?.let { av_frame_free(it) }
        bgra = null
        ctx = null
        packet = null
        frame = null
        sws = null
        waitingForKeyframe = true
    }

    private companion object {
        const val MAX_QUEUED = 30
    }
}
