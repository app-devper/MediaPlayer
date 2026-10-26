package com.appdevper.mediaplayer.airplay

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.math.BigInteger
import java.net.Socket
import java.nio.ByteBuffer
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

/**
 * A minimal AirPlay sender for tests: one TCP connection speaking RTSP/1.0 (pairing, like iOS on
 * its RAOP connection) or HTTP/1.1 (AirPlay video requests). JDK crypto only.
 */
internal class AirPlayTestClient(host: String, port: Int) : AutoCloseable {
    private val socket = Socket(host, port).apply { soTimeout = 5_000 }
    private val input = DataInputStream(socket.getInputStream())
    private var cseq = 0

    class Response(val status: Int, val body: ByteArray) {
        val text get() = String(body, Charsets.ISO_8859_1)
    }

    /**
     * RTSP request as on iOS's RAOP connection (CSeq), or with [sessionId] and no CSeq as iOS does
     * when it pairs on the AirPlay (video) connection itself. Status 0 = the receiver closed the connection.
     */
    fun rtsp(method: String, path: String, body: ByteArray = ByteArray(0), sessionId: String? = null): Response {
        // Like iOS: a GET /info with a Content-Type only asks for the TXT records.
        val headers = buildMap {
            if (sessionId == null) put("CSeq", (++cseq).toString()) else put("X-Apple-Session-ID", sessionId)
            put("User-Agent", "AirPlay/600.8.41")
            if (body.isNotEmpty()) put("Content-Type", "application/octet-stream")
        }
        return send("$method $path RTSP/1.0", headers, body)
    }

    /** HTTP request as on iOS's AirPlay (video) connection. */
    fun http(
        method: String,
        path: String,
        body: ByteArray = ByteArray(0),
        contentType: String? = null,
        sessionId: String? = SESSION,
        host: Boolean = true,
    ): Response {
        val headers = buildMap {
            put("User-Agent", "AirPlay/600.8.41")
            if (host) put("Host", "receiver")
            sessionId?.let { put("X-Apple-Session-ID", it) }
            contentType?.let { put("Content-Type", it) }
        }
        return send("$method $path HTTP/1.1", headers, body)
    }

    /** pair-setup + pair-verify as an iPhone does; returns whether the receiver accepted us. */
    fun pairVerify(sessionId: String? = null): Boolean {
        fun rtsp(method: String, path: String, body: ByteArray) = rtsp(method, path, body, sessionId)
        val ed = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val edPub = (ed.public as EdECPublicKey).raw()
        val serverEd = rtsp("POST", "/pair-setup", edPub).body
        val x = KeyPairGenerator.getInstance("X25519").generateKeyPair()
        val xPub = (x.public as XECPublicKey).raw()
        val v1 = rtsp("POST", "/pair-verify", byteArrayOf(1, 0, 0, 0) + xPub + edPub).body
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
        val serverOk = Signature.getInstance("Ed25519").run {
            initVerify(ed25519Public(serverEd))
            update(serverX + xPub)
            verify(serverSig)
        }
        val ourSig = Signature.getInstance("Ed25519").run {
            initSign(ed.private)
            update(xPub + serverX)
            sign()
        }
        return serverOk && rtsp("POST", "/pair-verify", byteArrayOf(0, 0, 0, 0) + ctr.update(ourSig)).status == 200
    }

    private fun send(requestLine: String, headers: Map<String, String>, body: ByteArray): Response {
        val head = requestLine + "\r\n" + headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" } +
            "Content-Length: ${body.size}\r\n\r\n"
        try {
            socket.getOutputStream().apply { write(head.toByteArray() + body); flush() }
            val lines = mutableListOf<String>()
            while (true) {
                val line = readLine() ?: return Response(0, ByteArray(0))
                if (line.isEmpty()) break
                lines += line
            }
            val status = lines.first().split(" ")[1].toInt()
            val length = lines.firstOrNull { it.startsWith("Content-Length", ignoreCase = true) }?.substringAfter(':')?.trim()?.toInt() ?: 0
            return Response(status, ByteArray(length).also { input.readFully(it) })
        } catch (_: java.io.IOException) {
            return Response(0, ByteArray(0))
        }
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

    companion object {
        const val SESSION = "11111111-2222-3333-4444-555555555555"

        private fun sha512(salt: String, secret: ByteArray) =
            MessageDigest.getInstance("SHA-512").digest(salt.toByteArray() + secret).copyOf(16)

        /** Raw keys are little-endian; the JDK wants BigIntegers. */
        private fun ByteArray.littleEndian() = BigInteger(1, reversedArray())

        private fun BigInteger.littleEndian32(): ByteArray {
            val be = toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
            return (ByteArray(32 - be.size) + be).reversedArray()
        }

        fun XECPublicKey.raw() = u.littleEndian32()

        fun EdECPublicKey.raw(): ByteArray = point.y.littleEndian32().also {
            if (point.isXOdd) it[31] = (it[31].toInt() or 0x80).toByte()
        }

        fun x25519Public(raw: ByteArray) =
            KeyFactory.getInstance("X25519").generatePublic(XECPublicKeySpec(NamedParameterSpec.X25519, raw.littleEndian()))

        fun ed25519Public(raw: ByteArray): java.security.PublicKey {
            val xOdd = raw[31].toInt() and 0x80 != 0
            val y = raw.copyOf().also { it[31] = (it[31].toInt() and 0x7f).toByte() }.littleEndian()
            return KeyFactory.getInstance("Ed25519").generatePublic(EdECPublicKeySpec(NamedParameterSpec.ED25519, EdECPoint(xOdd, y)))
        }

        /** A binary plist dictionary of strings, doubles and nested dictionaries (a List of pairs), as POST /play and /action carry. */
        fun bplist(entries: List<Pair<String, Any>>): ByteArray {
            fun string(s: String): ByteArray {
                val b = s.toByteArray(Charsets.US_ASCII)
                require(b.size < 256)
                return if (b.size < 15) byteArrayOf((0x50 or b.size).toByte()) + b else byteArrayOf(0x5f, 0x10, b.size.toByte()) + b
            }
            fun real(d: Double) = byteArrayOf(0x23) + ByteBuffer.allocate(8).putDouble(d).array()
            val objects = mutableListOf<ByteArray>()
            fun add(value: Any): Int {
                val index = objects.size
                objects += ByteArray(0) // placeholder, so a dictionary comes before its keys and values
                objects[index] = when (value) {
                    is Double -> real(value)
                    is List<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        val dict = value as List<Pair<String, Any>>
                        require(dict.size < 15)
                        val keys = dict.map { add(it.first) }
                        val values = dict.map { add(it.second) }
                        byteArrayOf((0xd0 or dict.size).toByte()) + (keys + values).map { it.toByte() }
                    }
                    else -> string(value.toString())
                }
                return index
            }
            add(entries)
            require(objects.size < 256)
            val out = ByteArrayOutputStream().apply { write("bplist00".toByteArray()) }
            val offsets = objects.map { o -> out.size().also { out.write(o) } }
            val tableOffset = out.size()
            offsets.forEach { out.write(ByteBuffer.allocate(2).putShort(it.toShort()).array()) }
            out.write(ByteArray(6))
            out.write(byteArrayOf(2, 1)) // offset int size, object ref size
            out.write(ByteBuffer.allocate(8).putLong(objects.size.toLong()).array())
            out.write(ByteBuffer.allocate(8).putLong(0).array())
            out.write(ByteBuffer.allocate(8).putLong(tableOffset.toLong()).array())
            return out.toByteArray()
        }
    }
}
