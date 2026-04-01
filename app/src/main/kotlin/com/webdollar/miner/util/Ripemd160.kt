package com.webdollar.miner.util

/**
 * Pure Kotlin RIPEMD-160 implementation.
 * Needed for WebDollar address derivation: RIPEMD160(SHA256(pubKey)).
 * Reference: https://homes.esat.kuleuven.be/~bosselae/ripemd160.html
 */
object Ripemd160 {

    fun hash(input: ByteArray): ByteArray {
        // Pad message to 512-bit boundary
        val msgLen = input.size.toLong()
        var buf = input.toMutableList()
        buf.add(0x80.toByte())
        while ((buf.size % 64) != 56) buf.add(0x00.toByte())
        // Append length in bits as little-endian 64-bit
        val bitLen = msgLen * 8
        for (i in 0 until 8) buf.add(((bitLen shr (i * 8)) and 0xff).toByte())

        // Initial state
        var h0 = 0x67452301.toInt()
        var h1 = 0xEFCDAB89.toInt()
        var h2 = 0x98BADCFE.toInt()
        var h3 = 0x10325476.toInt()
        var h4 = 0xC3D2E1F0.toInt()

        val msg = buf.toByteArray()

        // Process each 64-byte block
        var off = 0
        while (off < msg.size) {
            val x = IntArray(16) { i ->
                (msg[off + i * 4].toInt() and 0xff) or
                ((msg[off + i * 4 + 1].toInt() and 0xff) shl 8) or
                ((msg[off + i * 4 + 2].toInt() and 0xff) shl 16) or
                ((msg[off + i * 4 + 3].toInt() and 0xff) shl 24)
            }
            var al = h0; var bl = h1; var cl = h2; var dl = h3; var el = h4
            var ar = h0; var br = h1; var cr = h2; var dr = h3; var er = h4

            for (j in 0 until 80) {
                val ft: Int
                val kl: Int
                val kr: Int
                when {
                    j < 16  -> { ft = fl(bl, cl, dl); kl = 0x00000000.toInt(); kr = 0x50A28BE6.toInt() }
                    j < 32  -> { ft = gl(bl, cl, dl); kl = 0x5A827999.toInt(); kr = 0x5C4DD124.toInt() }
                    j < 48  -> { ft = hl(bl, cl, dl); kl = 0x6ED9EBA1.toInt(); kr = 0x6D703EF3.toInt() }
                    j < 64  -> { ft = il(bl, cl, dl); kl = 0x8F1BBCDC.toInt(); kr = 0x7A6D76E9.toInt() }
                    else    -> { ft = jl(bl, cl, dl); kl = 0xA953FD4E.toInt(); kr = 0x00000000.toInt() }
                }
                val frt: Int
                val kl2: Int
                val kr2: Int
                when {
                    j < 16  -> { frt = jl(br, cr, dr); kl2 = 0x50A28BE6.toInt(); kr2 = 0x00000000.toInt() }
                    j < 32  -> { frt = il(br, cr, dr); kl2 = 0x5C4DD124.toInt(); kr2 = 0x5A827999.toInt() }
                    j < 48  -> { frt = hl(br, cr, dr); kl2 = 0x6D703EF3.toInt(); kr2 = 0x6ED9EBA1.toInt() }
                    j < 64  -> { frt = gl(br, cr, dr); kl2 = 0x7A6D76E9.toInt(); kr2 = 0x8F1BBCDC.toInt() }
                    else    -> { frt = fl(br, cr, dr); kl2 = 0x00000000.toInt(); kr2 = 0xA953FD4E.toInt() }
                }

                val tl = rol(al + ft + x[RL[j]] + kl, SL[j]) + el
                al = el; el = dl; dl = rol(cl, 10); cl = bl; bl = tl

                val tr = rol(ar + frt + x[RR[j]] + kr2, SR[j]) + er
                ar = er; er = dr; dr = rol(cr, 10); cr = br; br = tr
            }

            val t = h1 + cl + dr
            h1 = h2 + dl + er
            h2 = h3 + el + ar
            h3 = h4 + al + br
            h4 = h0 + bl + cr
            h0 = t
            off += 64
        }

        val out = ByteArray(20)
        writeLE(h0, out, 0); writeLE(h1, out, 4); writeLE(h2, out, 8)
        writeLE(h3, out, 12); writeLE(h4, out, 16)
        return out
    }

    private fun fl(b: Int, c: Int, d: Int) = b xor c xor d
    private fun gl(b: Int, c: Int, d: Int) = (b and c) or (b.inv() and d)
    private fun hl(b: Int, c: Int, d: Int) = (b or c.inv()) xor d
    private fun il(b: Int, c: Int, d: Int) = (b and d) or (c and d.inv())
    private fun jl(b: Int, c: Int, d: Int) = b xor (c or d.inv())
    private fun rol(x: Int, n: Int) = (x shl n) or (x ushr (32 - n))
    private fun writeLE(v: Int, b: ByteArray, off: Int) {
        b[off] = v.toByte(); b[off+1] = (v shr 8).toByte()
        b[off+2] = (v shr 16).toByte(); b[off+3] = (v shr 24).toByte()
    }

    private val RL = intArrayOf(
        0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,
        7,4,13,1,10,6,15,3,12,0,9,5,2,14,11,8,
        3,10,14,4,9,15,8,1,2,7,0,6,13,11,5,12,
        1,9,11,10,0,8,12,4,13,3,7,15,14,5,6,2,
        4,0,5,9,7,12,2,10,14,1,3,8,11,6,15,13)
    private val RR = intArrayOf(
        5,14,7,0,9,2,11,4,13,6,15,8,1,10,3,12,
        6,11,3,7,0,13,5,10,14,15,8,12,4,9,1,2,
        15,5,1,3,7,14,6,9,11,8,12,2,10,0,4,13,
        8,6,4,1,3,11,15,0,5,12,2,13,9,7,10,14,
        12,15,10,4,1,5,8,7,6,2,13,14,0,3,9,11)
    private val SL = intArrayOf(
        11,14,15,12,5,8,7,9,11,13,14,15,6,7,9,8,
        7,6,8,13,11,9,7,15,7,12,15,9,11,7,13,12,
        11,13,6,7,14,9,13,15,14,8,13,6,5,12,7,5,
        11,12,14,15,14,15,9,8,9,14,5,6,8,6,5,12,
        9,15,5,11,6,8,13,12,5,12,13,14,11,8,5,6)
    private val SR = intArrayOf(
        8,9,9,11,13,15,15,5,7,7,8,11,14,14,12,6,
        9,13,15,7,12,8,9,11,7,7,12,7,6,15,13,11,
        9,7,15,11,8,6,6,14,12,13,5,14,13,13,7,5,
        15,5,8,11,14,14,6,14,6,9,12,9,12,5,15,8,
        8,5,12,9,12,5,14,6,8,13,6,5,15,13,11,11)
}
