package com.appdevper.mediaplayer.airplay

import org.junit.Assume.assumeTrue
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.math.BigInteger
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.Socket
import java.nio.file.Files
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.EdECPublicKey
import java.security.interfaces.XECPublicKey
import java.security.spec.EdECPoint
import java.security.spec.EdECPublicKeySpec
import java.security.spec.NamedParameterSpec
import java.security.spec.XECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Starts the real receiver through JNI and does what an iPhone does before mirroring:
 * GET /info, pair-setup and both pair-verify steps (X25519 + Ed25519 + AES-CTR), with JDK crypto only.
 */
class AirPlayReceiverE2ETest {

    private val receiver = assertNotNull(createAirPlayReceiver())
    private val keyFile = Files.createTempFile("airplay", ".pem").also { Files.delete(it) }

    @AfterTest
    fun tearDown() {
        receiver.stop()
        Files.deleteIfExists(keyFile)
    }

    private fun start(): Int {
        assumeTrue("needs a multicast-capable LAN interface", hasLan())
        receiver.start(AirPlayConfig("Test Receiver", byteArrayOf(2, 0x4d, 0x50, 1, 2, 3), keyFile.toString(), 1280, 720, 30))
        return assertNotNull(receiver.port)
    }

    @Test
    fun infoAndPairingLikeAnIPhone() {
        val port = start()
        Rtsp("127.0.0.1", port).use { c ->
            val (status, info) = c.request("GET", "/info")
            assertEquals(200, status)
            val text = String(info, Charsets.ISO_8859_1)
            assertTrue(text.startsWith("bplist00"), "binary plist expected")
            assertTrue("Test Receiver" in text)
            assertTrue("02:4d:50:01:02:03" in text)

            // pair-setup: our Ed25519 key in, the receiver's out
            val ed = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
            val edPub = (ed.public as EdECPublicKey).raw()
            val (setupStatus, serverEd) = c.request("POST", "/pair-setup", edPub)
            assertEquals(200, setupStatus)
            assertEquals(32, serverEd.size)

            // pair-verify 1: ECDH + the receiver's signature over (its key, our key), AES-CTR encrypted
            val x = KeyPairGenerator.getInstance("X25519").generateKeyPair()
            val xPub = (x.public as XECPublicKey).raw()
            val (v1Status, v1) = c.request("POST", "/pair-verify", byteArrayOf(1, 0, 0, 0) + xPub + edPub)
            assertEquals(200, v1Status)
            val serverX = v1.copyOfRange(0, 32)
            val shared = KeyAgreement.getInstance("X25519").run {
                init(x.private)
                doPhase(x25519Public(serverX), true)
                generateSecret()
            }
            val ctr = Cipher.getInstance("AES/CTR/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(sha512("Pair-Verify-AES-Key", shared), "AES"), IvParameterSpec(sha512("Pair-Verify-AES-IV", shared)))
            }
            val serverSig = ctr.update(v1.copyOfRange(32, 96))
            val verified = Signature.getInstance("Ed25519").run {
                initVerify(ed25519Public(serverEd))
                update(serverX + xPub)
                verify(serverSig)
            }
            assertTrue(verified, "receiver's pair-verify signature")

            // pair-verify 2: our signature over (our key, its key), continuing the same CTR stream
            val ourSig = Signature.getInstance("Ed25519").run {
                initSign(ed.private)
                update(xPub + serverX)
                sign()
            }
            val (v2Status, _) = c.request("POST", "/pair-verify", byteArrayOf(0, 0, 0, 0) + ctr.update(ourSig))
            assertEquals(200, v2Status)
            assertEquals(200, c.request("GET", "/info").first) // session still open
        }
    }

    @Test
    fun aForgedSignatureEndsTheConnection() {
        val port = start()
        Rtsp("127.0.0.1", port).use { c ->
            val edPub = (KeyPairGenerator.getInstance("Ed25519").generateKeyPair().public as EdECPublicKey).raw()
            c.request("POST", "/pair-setup", edPub)
            val xPub = (KeyPairGenerator.getInstance("X25519").generateKeyPair().public as XECPublicKey).raw()
            c.request("POST", "/pair-verify", byteArrayOf(1, 0, 0, 0) + xPub + edPub)
            c.request("POST", "/pair-verify", byteArrayOf(0, 0, 0, 0) + ByteArray(64))
            assertNull(runCatching { c.request("GET", "/info") }.getOrNull()?.takeIf { it.first != 0 })
        }
    }

    @Test
    fun restartsWithTheSameIdentity() {
        val port = start()
        val first = Rtsp("127.0.0.1", port).use { it.request("POST", "/pair-setup", ByteArray(32)).second }
        receiver.stop()
        assertNull(receiver.port)
        val again = start()
        val second = Rtsp("127.0.0.1", again).use { it.request("POST", "/pair-setup", ByteArray(32)).second }
        assertContentEquals(first, second) // senders keep recognising this receiver
    }

    // region helpers

    private fun hasLan() = NetworkInterface.getNetworkInterfaces().asSequence().any { nif ->
        nif.isUp && !nif.isLoopback && nif.supportsMulticast() && nif.inetAddresses.asSequence().any { it is Inet4Address }
    }

    private fun sha512(salt: String, secret: ByteArray) =
        MessageDigest.getInstance("SHA-512").digest(salt.toByteArray() + secret).copyOf(16)

    /** Raw keys are little-endian; the JDK wants BigIntegers. */
    private fun ByteArray.littleEndian() = BigInteger(1, reversedArray())

    private fun BigInteger.littleEndian32(): ByteArray {
        val be = toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        return (ByteArray(32 - be.size) + be).reversedArray()
    }

    private fun XECPublicKey.raw() = u.littleEndian32()

    private fun EdECPublicKey.raw(): ByteArray = point.y.littleEndian32().also {
        if (point.isXOdd) it[31] = (it[31].toInt() or 0x80).toByte()
    }

    private fun x25519Public(raw: ByteArray) =
        KeyFactory.getInstance("X25519").generatePublic(XECPublicKeySpec(NamedParameterSpec.X25519, raw.littleEndian()))

    private fun ed25519Public(raw: ByteArray): java.security.PublicKey {
        val xOdd = raw[31].toInt() and 0x80 != 0
        val y = raw.copyOf().also { it[31] = (it[31].toInt() and 0x7f).toByte() }.littleEndian()
        return KeyFactory.getInstance("Ed25519").generatePublic(EdECPublicKeySpec(NamedParameterSpec.ED25519, EdECPoint(xOdd, y)))
    }

    /** Just enough RTSP/1.0 over one connection (the receiver keeps pairing state per connection). */
    private class Rtsp(host: String, port: Int) : AutoCloseable {
        private val socket = Socket(host, port).apply { soTimeout = 5_000 }
        private val input = DataInputStream(socket.getInputStream())
        private var cseq = 0

        fun request(method: String, path: String, body: ByteArray = ByteArray(0)): Pair<Int, ByteArray> {
            // Like iOS: a GET /info with a Content-Type only asks for the TXT records.
            val type = if (body.isNotEmpty()) "Content-Type: application/octet-stream\r\n" else ""
            val head = "$method $path RTSP/1.0\r\nCSeq: ${++cseq}\r\nUser-Agent: AirPlay/600.8.41\r\n" +
                "${type}Content-Length: ${body.size}\r\n\r\n"
            socket.getOutputStream().apply { write(head.toByteArray() + body); flush() }
            val lines = mutableListOf<String>()
            while (true) {
                val line = readLine() ?: return 0 to ByteArray(0)
                if (line.isEmpty()) break
                lines += line
            }
            val status = lines.first().split(" ")[1].toInt()
            val length = lines.firstOrNull { it.startsWith("Content-Length", ignoreCase = true) }?.substringAfter(':')?.trim()?.toInt() ?: 0
            return status to ByteArray(length).also { input.readFully(it) }
        }

        private fun readLine(): String? {
            val out = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) return null
                if (b == '\n'.code) return out.toString(Charsets.ISO_8859_1).trimEnd('\r')
                out.write(b)
            }
        }

        override fun close() = socket.close()
    }

    // endregion
}
