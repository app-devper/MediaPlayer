package app.devper.appplayer.airplay

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The sender's DACP service, faked on localhost: an mDNS responder and its HTTP control port. */
class DacpRemoteTest {
    private val localhost = InetAddress.getByName("127.0.0.1")
    private val requests = CopyOnWriteArrayList<String>()
    @Volatile private var status = "204 No Content"

    private val http = ServerSocket(0, 50, localhost)
    private val httpThread = thread(isDaemon = true) {
        while (!http.isClosed) {
            val s = runCatching { http.accept() }.getOrNull() ?: break
            s.use {
                val lines = it.getInputStream().bufferedReader().let { r -> generateSequence { r.readLine() }.takeWhile { l -> l.isNotEmpty() }.toList() }
                requests += lines.joinToString("\n")
                it.getOutputStream().write("HTTP/1.1 $status\r\nContent-Length: 0\r\n\r\n".toByteArray())
            }
        }
    }

    @Volatile private var advertisedPort = http.localPort
    private val mdns = DatagramSocket(0, localhost)
    private val queries = CopyOnWriteArrayList<ByteArray>()
    private val mdnsThread = thread(isDaemon = true) {
        val buf = ByteArray(1500)
        while (!mdns.isClosed) {
            val p = DatagramPacket(buf, buf.size)
            runCatching { mdns.receive(p) }.getOrNull() ?: break
            val q = p.data.copyOf(p.length).also { queries += it }
            // Answer: the question echoed back as the SRV owner name (compressed pointer), port as advertised.
            val reply = srvReply(q, advertisedPort)
            mdns.send(DatagramPacket(reply, reply.size, p.address, p.port))
        }
    }

    @AfterTest
    fun tearDown() {
        http.close()
        mdns.close()
    }

    private fun remote() = DacpRemote("C674262D939888D7", "12252432", localhost, mdnsPort = mdns.localPort, multicast = false, timeoutMs = 1000)

    @Test
    fun findsThePortAndSendsTheCommandWithItsActiveRemote() = runBlocking<Unit> {
        val r = remote()
        assertEquals(DacpRemote.Result.Done, r.send(AirPlayRemoteCommand.Next))
        assertEquals(DacpRemote.Result.Done, r.send(AirPlayRemoteCommand.Previous))
        assertTrue(requests[0].startsWith("GET /ctrl-int/1/nextitem HTTP/1.1"), requests[0])
        assertTrue("Active-Remote: 12252432" in requests[0], requests[0])
        assertTrue(requests[1].startsWith("GET /ctrl-int/1/previtem "), requests[1])
        assertEquals(1, queries.size, "the port is looked up once")
        // The question is for this sender's service.
        assertTrue(String(queries[0], Charsets.ISO_8859_1).contains("iTunes_Ctrl_C674262D939888D7"))
    }

    @Test
    fun aRefusedCommandIsFalse() = runBlocking<Unit> {
        status = "403 Forbidden"
        assertEquals(DacpRemote.Result.Refused, remote().send(AirPlayRemoteCommand.PlayPause))
    }

    @Test
    fun aStalePortIsLookedUpAgain() = runBlocking<Unit> {
        val r = remote()
        assertEquals(DacpRemote.Result.Done, r.send(AirPlayRemoteCommand.Next))
        // The sender's control port moved (its app restarted): the old one refuses connections.
        val moved = ServerSocket(0, 50, localhost)
        val old = http.localPort
        http.close()
        advertisedPort = moved.localPort
        thread(isDaemon = true) {
            moved.accept().use { s ->
                s.getInputStream().bufferedReader().readLine()?.let { requests += it }
                s.getOutputStream().write("HTTP/1.1 204 No Content\r\n\r\n".toByteArray())
            }
        }
        assertEquals(DacpRemote.Result.Done, r.send(AirPlayRemoteCommand.Next))
        assertEquals(2, queries.size)
        assertTrue(requests.last().startsWith("GET /ctrl-int/1/nextitem"))
        assertTrue(old != moved.localPort)
        moved.close()
    }

    @Test
    fun aCommandThatWentOutIsNeverSentTwice() = runBlocking<Unit> {
        // The sender acts on it but answers too late: it must not be resent (Next would skip twice).
        val slow = ServerSocket(0, 50, localhost)
        advertisedPort = slow.localPort
        val got = CopyOnWriteArrayList<String>()
        thread(isDaemon = true) {
            while (!slow.isClosed) {
                val s = runCatching { slow.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { s.use { got += it.getInputStream().bufferedReader().readLine(); Thread.sleep(3_000) } }
            }
        }
        assertEquals(DacpRemote.Result.Refused, remote().send(AirPlayRemoteCommand.Next))
        Thread.sleep(300)
        assertEquals(listOf("GET /ctrl-int/1/nextitem HTTP/1.1"), got.toList())
        slow.close()
    }

    @Test
    fun commandsGoOutOneAtATimeInOrder() = runBlocking<Unit> {
        val r = remote()
        val jobs = listOf(AirPlayRemoteCommand.Next, AirPlayRemoteCommand.Previous, AirPlayRemoteCommand.PlayPause)
            .map { c -> async(Dispatchers.Default) { r.send(c) }.also { delay(20) } }
        jobs.awaitAll()
        assertEquals(listOf("nextitem", "previtem", "playpause"), requests.map { it.substringAfter("/ctrl-int/1/").substringBefore(' ') })
        assertEquals(1, queries.size)
    }

    @Test
    fun noAnswerMeansFalse() = runBlocking<Unit> {
        mdns.close()
        assertEquals(DacpRemote.Result.Unreachable, remote().send(AirPlayRemoteCommand.Next))
        assertTrue(requests.isEmpty())
    }

    private fun srvReply(query: ByteArray, port: Int): ByteArray {
        fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
        val question = query.copyOfRange(12, query.size)
        val target = byteArrayOf(5) + "phone".toByteArray() + byteArrayOf(5) + "local".toByteArray() + byteArrayOf(0)
        val answer = byteArrayOf(0xc0.toByte(), 12) + u16(33) + u16(0x8001) + byteArrayOf(0, 0, 0, 120) +
            u16(6 + target.size) + u16(0) + u16(0) + u16(port) + target
        return u16(0) + u16(0x8400) + u16(1) + u16(1) + u16(0) + u16(0) + question + answer
    }
}
