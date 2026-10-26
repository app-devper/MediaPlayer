package com.appdevper.mediaplayer.airplay

import com.appdevper.mediaplayer.airplay.AirPlayTestClient.Companion.bplist
import org.junit.Assume.assumeTrue
import java.net.Inet4Address
import java.net.NetworkInterface
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * AirPlay video (what YouTube and other apps' AirPlay button use) through the real receiver:
 * only a sender that paired may drive it, and its requests reach [AirPlayVideoControl].
 */
class AirPlayVideoE2ETest {

    private val calls = CopyOnWriteArrayList<String>()
    @Volatile private var info: AirPlayVideoInfo? = null

    private val receiver = assertNotNull(createAirPlayReceiver()).apply {
        video = object : AirPlayVideoControl {
            override fun play(url: String, startSeconds: Float) { calls += "play $url @$startSeconds" }
            override fun seek(seconds: Float) { calls += "seek $seconds" }
            override fun setRate(rate: Float) { calls += "rate $rate" }
            override fun stop() { calls += "stop" }
            override fun info() = info
        }
    }
    private val keyFile = Files.createTempFile("airplay", ".pem").also { Files.delete(it) }

    @AfterTest
    fun tearDown() {
        receiver.stop()
        receiver.video = null
        Files.deleteIfExists(keyFile)
    }

    private fun lanAddress(): String? = NetworkInterface.getNetworkInterfaces().asSequence()
        .filter { it.isUp && !it.isLoopback && it.supportsMulticast() }
        .flatMap { it.inetAddresses.asSequence() }
        .firstOrNull { it is Inet4Address }?.hostAddress

    private fun start(): Int {
        assumeTrue("needs a multicast-capable LAN interface", lanAddress() != null)
        receiver.start(AirPlayConfig("Video Test", byteArrayOf(2, 0x4d, 0x50, 9, 9, 9), keyFile.toString()))
        return assertNotNull(receiver.port)
    }

    private val playBody = bplist(
        listOf(
            "Content-Location" to "http://192.168.1.50:8080/clip.mp4",
            "Start-Position-Seconds" to 12.5,
            "uuid" to "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE",
        ),
    )

    @Test
    fun anUnpairedClientCannotPlayOrControl() {
        val port = start()
        AirPlayTestClient("127.0.0.1", port).use { c ->
            assertEquals(403, c.http("POST", "/play", playBody, "application/x-apple-binary-plist").status)
        }
        AirPlayTestClient("127.0.0.1", port).use { c ->
            assertEquals(403, c.http("POST", "/scrub?position=5").status)
        }
        assertTrue(calls.isEmpty(), "nothing reached the player: $calls")
    }

    @Test
    fun aPairedSenderPlaysAndControlsVideo() {
        val port = start()
        AirPlayTestClient("127.0.0.1", port).use { raop ->
            assertTrue(raop.pairVerify())
            AirPlayTestClient("127.0.0.1", port).use { video ->
                assertEquals(200, video.http("POST", "/play", playBody, "application/x-apple-binary-plist").status)
                assertEquals(200, video.http("POST", "/scrub?position=42.0").status)
                assertEquals(200, video.http("POST", "/rate?value=0.000000").status)
                assertEquals(200, video.http("POST", "/scrub?nothing").status) // used to crash (NULL + 1)

                // Progress for the sender comes from our player.
                assertEquals(200, video.http("GET", "/playback-info").status) // nothing loaded yet: empty answer
                info = AirPlayVideoInfo(durationSeconds = 300.0, positionSeconds = 42.0, playing = true)
                val progress = video.http("GET", "/playback-info")
                assertEquals(200, progress.status)
                assertTrue("<key>duration</key>" in progress.text && "300" in progress.text, progress.text)
                assertTrue("<key>position</key>" in progress.text && "42" in progress.text, progress.text)

                assertEquals(200, video.http("POST", "/stop").status)
            }
        }
        assertEquals(
            listOf("play http://192.168.1.50:8080/clip.mp4 @12.5", "seek 42.0", "rate 0.0", "seek 0.0", "stop"),
            calls.toList(),
        )
    }

    @Test
    fun aSenderThatPairsOnTheVideoConnectionCanPlay() {
        // What YouTube does: pair-setup/pair-verify on the AirPlay connection itself, then /play.
        val port = start()
        AirPlayTestClient("127.0.0.1", port).use { video ->
            assertTrue(video.pairVerify(sessionId = AirPlayTestClient.SESSION))
            assertEquals(200, video.http("POST", "/play", playBody, "application/x-apple-binary-plist").status)
        }
        assertEquals(listOf("play http://192.168.1.50:8080/clip.mp4 @12.5"), calls.toList())
    }

    @Test
    fun aSenderCanSwapTheItem() {
        // YouTube Music switching between video and song: playlistRemove, then playlistInsert with the new item.
        val port = start()
        fun action(type: String, item: List<Pair<String, Any>>) =
            bplist(listOf("type" to type, "params" to listOf("item" to item)))
        AirPlayTestClient("127.0.0.1", port).use { video ->
            assertTrue(video.pairVerify(sessionId = AirPlayTestClient.SESSION))
            assertEquals(200, video.http("POST", "/play", playBody, "application/x-apple-binary-plist").status)
            info = AirPlayVideoInfo(durationSeconds = 300.0, positionSeconds = 13.0, playing = true)
            val remove = action("playlistRemove", listOf("uuid" to "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE"))
            assertEquals(200, video.http("POST", "/action", remove, "application/x-apple-binary-plist").status)
            val insert = action(
                "playlistInsert",
                listOf("uuid" to "11111111-2222-3333-4444-555555555555", "Content-Location" to "http://192.168.1.50:8080/song.m4a"),
            )
            assertEquals(200, video.http("POST", "/action", insert, "application/x-apple-binary-plist").status)
            assertEquals(200, video.http("POST", "/scrub?position=13.0").status)
        }
        assertEquals(
            listOf("play http://192.168.1.50:8080/clip.mp4 @12.5", "play http://192.168.1.50:8080/song.m4a @0.0", "seek 13.0"),
            calls.toList(),
        )
    }

    @Test
    fun theLocalHlsServerOnlyAnswersThisDevice() {
        val port = start()
        val lan = assertNotNull(lanAddress())
        // Same machine, but through the LAN address: looks like another device on the network.
        AirPlayTestClient(lan, port).use { c ->
            assertEquals(403, c.http("GET", "/master.m3u8", sessionId = null).status)
        }
        AirPlayTestClient("127.0.0.1", port).use { c ->
            assertTrue(c.http("GET", "/master.m3u8", sessionId = null).status != 403)
        }
    }
}
