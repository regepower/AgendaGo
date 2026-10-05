package de.regepower.agendago.data

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Minimal NTLMv2 client for HTTP auth (MS-NLMP): type-1 message, type-2 parsing, type-3
 * response without MIC/signing, like curl does. Plain JVM, no Android dependency, so it can be
 * tested off-device against the MS-NLMP test vectors.
 */
object Ntlm {
    private val SIGNATURE = "NTLMSSP\u0000".toByteArray(Charsets.US_ASCII)

    /** UNICODE | OEM | REQUEST_TARGET | NTLM | ALWAYS_SIGN | EXTENDED_SESSIONSECURITY */
    private const val FLAGS_TYPE1 = 0x00088207

    /** UNICODE | REQUEST_TARGET | NTLM | ALWAYS_SIGN | EXTENDED_SESSIONSECURITY */
    private const val FLAGS_TYPE3 = 0x00088205
    private const val HEADER_TYPE3 = 64
    private const val FILETIME_EPOCH_MS = 11_644_473_600_000L
    private const val FILETIME_PER_MS = 10_000L

    class Challenge(
        val serverChallenge: ByteArray,
        val targetInfo: ByteArray,
    )

    fun type1(): String {
        val m = ByteArray(32)
        SIGNATURE.copyInto(m)
        le32(m, 8, 1)
        le32(m, 12, FLAGS_TYPE1)
        // Domain and workstation security buffers stay empty (offset 0).
        return Base64.getEncoder().encodeToString(m)
    }

    /** Parses the server's type-2 message (base64 from "WWW-Authenticate: NTLM …"); null if invalid. */
    fun parseType2(base64: String): Challenge? {
        val m =
            try {
                Base64.getDecoder().decode(base64.trim())
            } catch (e: IllegalArgumentException) {
                return null
            }
        if (m.size < 32 || !m.copyOfRange(0, 8).contentEquals(SIGNATURE) || readLe32(m, 8) != 2) return null
        val server = m.copyOfRange(24, 32)
        var info = ByteArray(0)
        if (m.size >= 48) {
            val len = readLe16(m, 40)
            val off = readLe32(m, 44)
            if (len > 0 && off >= 0 && off + len <= m.size) info = m.copyOfRange(off, off + len)
        }
        return Challenge(server, info)
    }

    /** Type-3 message with NTLMv2 and LMv2 responses. */
    fun type3(
        challenge: Challenge,
        user: String,
        domain: String,
        password: String,
        workstation: String,
        clientChallenge: ByteArray = ByteArray(8).also { SecureRandom().nextBytes(it) },
        timeMillis: Long = System.currentTimeMillis(),
    ): String {
        val key = ntowfV2(password, user, domain)
        val blob = blob(timeMillis, clientChallenge, challenge.targetInfo)
        val ntProof = hmacMd5(key, challenge.serverChallenge + blob)
        val nt = ntProof + blob
        val lm = hmacMd5(key, challenge.serverChallenge + clientChallenge) + clientChallenge

        val dom = utf16(domain)
        val usr = utf16(user)
        val ws = utf16(workstation)
        val m = ByteArray(HEADER_TYPE3 + lm.size + nt.size + dom.size + usr.size + ws.size)
        SIGNATURE.copyInto(m)
        le32(m, 8, 3)
        var off = HEADER_TYPE3
        for ((field, data) in listOf(12 to lm, 20 to nt, 28 to dom, 36 to usr, 44 to ws)) {
            secBuf(m, field, data.size, off)
            data.copyInto(m, off)
            off += data.size
        }
        secBuf(m, 52, 0, off) // no session key exchange
        le32(m, 60, FLAGS_TYPE3)
        return Base64.getEncoder().encodeToString(m)
    }

    fun ntowfV2(
        password: String,
        user: String,
        domain: String,
    ): ByteArray = hmacMd5(Md4.digest(utf16(password)), utf16(user.uppercase() + domain))

    /** NTLMv2 client "temp" structure (MS-NLMP 3.3.2). */
    fun blob(
        timeMillis: Long,
        clientChallenge: ByteArray,
        targetInfo: ByteArray,
    ): ByteArray {
        val b = ByteArray(28 + targetInfo.size + 4)
        b[0] = 1
        b[1] = 1
        val filetime = (timeMillis + FILETIME_EPOCH_MS) * FILETIME_PER_MS
        for (i in 0 until 8) b[8 + i] = (filetime ushr (8 * i)).toByte()
        clientChallenge.copyInto(b, 16)
        targetInfo.copyInto(b, 28)
        return b
    }

    fun hmacMd5(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray =
        Mac.getInstance("HmacMD5").run {
            init(SecretKeySpec(key, "HmacMD5"))
            doFinal(data)
        }

    private fun utf16(s: String) = s.toByteArray(Charsets.UTF_16LE)

    private fun secBuf(
        m: ByteArray,
        at: Int,
        len: Int,
        off: Int,
    ) {
        le16(m, at, len)
        le16(m, at + 2, len)
        le32(m, at + 4, off)
    }

    private fun le16(
        m: ByteArray,
        at: Int,
        v: Int,
    ) {
        m[at] = v.toByte()
        m[at + 1] = (v ushr 8).toByte()
    }

    private fun le32(
        m: ByteArray,
        at: Int,
        v: Int,
    ) {
        for (i in 0 until 4) m[at + i] = (v ushr (8 * i)).toByte()
    }

    private fun readLe16(
        m: ByteArray,
        at: Int,
    ) = (m[at].toInt() and 0xFF) or ((m[at + 1].toInt() and 0xFF) shl 8)

    private fun readLe32(
        m: ByteArray,
        at: Int,
    ) = (0 until 4).fold(0) { acc, i -> acc or ((m[at + i].toInt() and 0xFF) shl (8 * i)) }
}

/** MD4 (RFC 1320); not offered by Android's crypto providers, needed for the NT hash. */
object Md4 {
    private val S1 = intArrayOf(3, 7, 11, 19)
    private val S2 = intArrayOf(3, 5, 9, 13)
    private val S3 = intArrayOf(3, 9, 11, 15)
    private val K2 = intArrayOf(0, 4, 8, 12, 1, 5, 9, 13, 2, 6, 10, 14, 3, 7, 11, 15)
    private val K3 = intArrayOf(0, 8, 4, 12, 2, 10, 6, 14, 1, 9, 5, 13, 3, 11, 7, 15)
    private const val C2 = 0x5A827999
    private const val C3 = 0x6ED9EBA1

    fun digest(input: ByteArray): ByteArray {
        val padLen = ((55 - input.size) % 64 + 64) % 64
        val buf = ByteArray(input.size + 1 + padLen + 8)
        input.copyInto(buf)
        buf[input.size] = 0x80.toByte()
        val bits = input.size.toLong() * 8
        for (i in 0 until 8) buf[buf.size - 8 + i] = (bits ushr (8 * i)).toByte()

        val h = intArrayOf(0x67452301, 0xEFCDAB89.toInt(), 0x98BADCFE.toInt(), 0x10325476)
        val x = IntArray(16)
        for (block in buf.indices step 64) {
            for (i in 0 until 16) {
                val o = block + 4 * i
                x[i] = (buf[o].toInt() and 0xFF) or ((buf[o + 1].toInt() and 0xFF) shl 8) or
                    ((buf[o + 2].toInt() and 0xFF) shl 16) or ((buf[o + 3].toInt() and 0xFF) shl 24)
            }
            val v = h.copyOf()
            for (i in 0 until 16) {
                v[target(i)] += x[i]
                step(v, i, S1) { b, c, d -> (b and c) or (b.inv() and d) }
            }
            for (i in 0 until 16) {
                v[target(i)] += x[K2[i]] + C2
                step(v, i, S2) { b, c, d -> (b and c) or (b and d) or (c and d) }
            }
            for (i in 0 until 16) {
                v[target(i)] += x[K3[i]] + C3
                step(v, i, S3) { b, c, d -> b xor c xor d }
            }
            for (i in 0 until 4) h[i] += v[i]
        }
        val out = ByteArray(16)
        for (i in 0 until 16) out[i] = (h[i / 4] ushr (8 * (i % 4))).toByte()
        return out
    }

    /** Register updated in step [i]: a, d, c, b, a, … */
    private fun target(i: Int) = (4 - i % 4) % 4

    private inline fun step(
        v: IntArray,
        i: Int,
        shifts: IntArray,
        f: (Int, Int, Int) -> Int,
    ) {
        val t = target(i)
        v[t] = Integer.rotateLeft(v[t] + f(v[(t + 1) % 4], v[(t + 2) % 4], v[(t + 3) % 4]), shifts[i % 4])
    }
}
