package app.devper.appplayer.airplay

import app.devper.appplayer.airplay.AirPlayTestClient.Companion.ed25519Public
import app.devper.appplayer.airplay.AirPlayTestClient.Companion.raw
import app.devper.appplayer.airplay.AirPlayTestClient.Companion.x25519Public
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
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
        AirPlayTestClient("127.0.0.1", port).use { c ->
            val info = c.rtsp("GET", "/info")
            assertEquals(200, info.status)
            val text = info.text
            assertTrue(text.startsWith("bplist00"), "binary plist expected")
            assertTrue("Test Receiver" in text)
            assertTrue("02:4d:50:01:02:03" in text)

            // pair-setup: our Ed25519 key in, the receiver's out
            val ed = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
            val edPub = (ed.public as EdECPublicKey).raw()
            val setup = c.rtsp("POST", "/pair-setup", edPub)
            assertEquals(200, setup.status)
            val serverEd = setup.body
            assertEquals(32, serverEd.size)

            // pair-verify 1: ECDH + the receiver's signature over (its key, our key), AES-CTR encrypted
            val x = KeyPairGenerator.getInstance("X25519").generateKeyPair()
            val xPub = (x.public as XECPublicKey).raw()
            val verify1 = c.rtsp("POST", "/pair-verify", byteArrayOf(1, 0, 0, 0) + xPub + edPub)
            assertEquals(200, verify1.status)
            val v1 = verify1.body
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
            assertEquals(200, c.rtsp("POST", "/pair-verify", byteArrayOf(0, 0, 0, 0) + ctr.update(ourSig)).status)
            assertEquals(200, c.rtsp("GET", "/info").status) // session still open
        }
    }

    @Test
    fun onlyAVerifiedSenderOffersRemoteControl() = runBlocking<Unit> {
        val port = start()
        val offered = CopyOnWriteArrayList<AirPlayEvent>()
        val collecting = launch(Dispatchers.Default) {
            receiver.events.collect { if (it == AirPlayEvent.RemoteControlAvailable) offered += it }
        }
        delay(100)
        val dacp = mapOf("DACP-ID" to "C674262D939888D7", "Active-Remote" to "12252432")
        // An unpaired host on the LAN can't make our next/previous go to it.
        AirPlayTestClient("127.0.0.1", port).use { c -> c.rtsp("OPTIONS", "*", extraHeaders = dacp) }
        delay(300)
        assertTrue(offered.isEmpty(), "an unverified connection offered remote control")
        // A sender that completed pair-verify does.
        AirPlayTestClient("127.0.0.1", port).use { c ->
            assertTrue(c.pairVerify())
            c.rtsp("OPTIONS", "*", extraHeaders = dacp)
            withTimeout(3_000) { while (offered.isEmpty()) delay(20) }
        }
        collecting.cancel()
    }

    @Test
    fun aForgedSignatureEndsTheConnection() {
        val port = start()
        AirPlayTestClient("127.0.0.1", port).use { c ->
            val edPub = (KeyPairGenerator.getInstance("Ed25519").generateKeyPair().public as EdECPublicKey).raw()
            c.rtsp("POST", "/pair-setup", edPub)
            val xPub = (KeyPairGenerator.getInstance("X25519").generateKeyPair().public as XECPublicKey).raw()
            c.rtsp("POST", "/pair-verify", byteArrayOf(1, 0, 0, 0) + xPub + edPub)
            c.rtsp("POST", "/pair-verify", byteArrayOf(0, 0, 0, 0) + ByteArray(64))
            assertEquals(0, c.rtsp("GET", "/info").status) // closed by the receiver
        }
    }

    @Test
    fun restartsWithTheSameIdentity() {
        val port = start()
        val first = AirPlayTestClient("127.0.0.1", port).use { it.rtsp("POST", "/pair-setup", ByteArray(32)).body }
        receiver.stop()
        assertNull(receiver.port)
        val again = start()
        val second = AirPlayTestClient("127.0.0.1", again).use { it.rtsp("POST", "/pair-setup", ByteArray(32)).body }
        assertContentEquals(first, second) // senders keep recognising this receiver
    }

    private fun sha512(salt: String, secret: ByteArray) =
        MessageDigest.getInstance("SHA-512").digest(salt.toByteArray() + secret).copyOf(16)

    private fun hasLan() = NetworkInterface.getNetworkInterfaces().asSequence().any { nif ->
        nif.isUp && !nif.isLoopback && nif.supportsMulticast() && nif.inetAddresses.asSequence().any { it is Inet4Address }
    }
}
