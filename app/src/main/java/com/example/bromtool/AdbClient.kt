package com.example.bromtool

import android.content.Context
import android.hardware.usb.*
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

private const val A_CNXN = 0x4e584e43
private const val A_AUTH = 0x48545541
private const val A_OPEN = 0x4e45504f
private const val A_OKAY = 0x59414b4f
private const val A_VERSION = 0x01000000
private const val MAX_PAYLOAD = 4096
private const val AUTH_TOKEN = 1
private const val AUTH_SIGNATURE = 2
private const val AUTH_RSAPUBLICKEY = 3

private val SHA1_DER_PREFIX = byteArrayOf(
    0x30,0x21,0x30,0x09,0x06,0x05,0x2b,0x0e,0x03,0x02,0x1a,0x05,0x00,0x04,0x14
)

object AdbClient {

    fun isAdbInterface(itf: UsbInterface) =
        itf.interfaceClass == 0xFF && itf.interfaceSubclass == 0x42 && itf.interfaceProtocol == 0x01

    private fun le32(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte()
    )
    private fun rdLe32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off+1].toInt() and 0xFF) shl 8) or
        ((b[off+2].toInt() and 0xFF) shl 16) or ((b[off+3].toInt() and 0xFF) shl 24)

    private fun checksum(data: ByteArray): Int {
        var sum = 0
        for (b in data) sum += (b.toInt() and 0xFF)
        return sum
    }

    private fun packMsg(cmd: Int, arg0: Int, arg1: Int, data: ByteArray): ByteArray {
        val header = le32(cmd) + le32(arg0) + le32(arg1) + le32(data.size) + le32(checksum(data)) + le32(cmd.inv())
        return header + data
    }

    private fun getOrCreateKeyPair(ctx: Context): Pair<PrivateKey, PublicKey> {
        val priv = File(ctx.filesDir, "adbkey.priv")
        val pub = File(ctx.filesDir, "adbkey.pub")
        if (priv.exists() && pub.exists()) {
            val kf = KeyFactory.getInstance("RSA")
            return kf.generatePrivate(PKCS8EncodedKeySpec(priv.readBytes())) to
                   kf.generatePublic(X509EncodedKeySpec(pub.readBytes()))
        }
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val kp = kpg.generateKeyPair()
        priv.writeBytes(kp.private.encoded)
        pub.writeBytes(kp.public.encoded)
        return kp.private to kp.public
    }

    private fun encodePublicKey(pub: RSAPublicKey): ByteArray {
        val n = pub.modulus
        val e = pub.publicExponent
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0 = n.mod(r32)
        val n0inv = r32.subtract(n0.modInverse(r32)).mod(r32)
        val r = BigInteger.ONE.shiftLeft(2048)
        val rr = r.multiply(r).mod(n)

        fun toLeBytes(v: BigInteger, len: Int): ByteArray {
            var bytes = v.toByteArray()
            if (bytes.size > 1 && bytes[0].toInt() == 0) bytes = bytes.copyOfRange(1, bytes.size)
            val out = ByteArray(len)
            val copyLen = minOf(bytes.size, len)
            for (i in 0 until copyLen) out[i] = bytes[bytes.size - 1 - i]
            return out
        }

        val out = ArrayList<Byte>()
        out.addAll(le32(64).toList())
        out.addAll(le32(n0inv.toInt()).toList())
        out.addAll(toLeBytes(n, 256).toList())
        out.addAll(toLeBytes(rr, 256).toList())
        out.addAll(le32(e.toInt()).toList())
        return out.toByteArray()
    }

    private fun signToken(priv: PrivateKey, token: ByteArray): ByteArray {
        val digestInfo = SHA1_DER_PREFIX + token
        val keySize = 256
        val ps = ByteArray(keySize - 3 - digestInfo.size) { 0xFF.toByte() }
        val block = byteArrayOf(0x00, 0x01) + ps + byteArrayOf(0x00) + digestInfo
        val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, priv)
        return cipher.doFinal(block)
    }

    private class Pipe(val conn: UsbDeviceConnection, val epIn: UsbEndpoint, val epOut: UsbEndpoint) {
        fun send(b: ByteArray) {
            var off = 0
            while (off < b.size) {
                val chunk = minOf(16384, b.size - off)
                conn.bulkTransfer(epOut, b.copyOfRange(off, off + chunk), chunk, 3000)
                off += chunk
            }
        }
        fun recvExact(n: Int, timeoutMs: Int): ByteArray? {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(maxOf(epIn.maxPacketSize, 16384))
            val end = System.currentTimeMillis() + timeoutMs
            while (out.size() < n && System.currentTimeMillis() < end) {
                val r = conn.bulkTransfer(epIn, buf, buf.size, 500)
                if (r > 0) out.write(buf, 0, r)
            }
            return if (out.size() >= n) out.toByteArray() else null
        }
        fun recvMsg(timeoutMs: Int): Triple<Int, Int, ByteArray>? {
            val hdr = recvExact(24, timeoutMs) ?: return null
            val cmd = rdLe32(hdr, 0)
            val arg0 = rdLe32(hdr, 4)
            val len = rdLe32(hdr, 12)
            val data = if (len > 0) (recvExact(len, timeoutMs) ?: return null) else ByteArray(0)
            return Triple(cmd, arg0, data)
        }
    }

    fun reboot(usb: UsbManager, dev: UsbDevice, ctx: Context, mode: String, log: (String) -> Unit): Boolean {
        val itf = (0 until dev.interfaceCount).map { dev.getInterface(it) }.firstOrNull { isAdbInterface(it) }
            ?: run { log("Interface ADB tidak ditemukan"); return false }
        var epI: UsbEndpoint? = null
        var epO: UsbEndpoint? = null
        for (e in 0 until itf.endpointCount) {
            val ep = itf.getEndpoint(e)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == UsbConstants.USB_DIR_IN) epI = ep else epO = ep
            }
        }
        if (epI == null || epO == null) { log("Endpoint ADB tidak ketemu"); return false }
        val conn = usb.openDevice(dev) ?: run { log("Gagal open device ADB"); return false }
        if (!conn.claimInterface(itf, true)) { conn.close(); log("Gagal claim interface ADB"); return false }
        val pipe = Pipe(conn, epI, epO)

        try {
            val (priv, pub) = getOrCreateKeyPair(ctx)
            pipe.send(packMsg(A_CNXN, A_VERSION, MAX_PAYLOAD, "host::\u0000".toByteArray()))
            var msg = pipe.recvMsg(5000) ?: run { log("Tidak ada balasan CNXN"); return false }
            var (cmd, arg0, data) = msg

            if (cmd == A_AUTH && arg0 == AUTH_TOKEN) {
                log("AUTH diminta, menandatangani token...")
                val sig = signToken(priv, data)
                pipe.send(packMsg(A_AUTH, AUTH_SIGNATURE, 0, sig))
                msg = pipe.recvMsg(5000) ?: run { log("Tidak ada balasan setelah signature"); return false }
                cmd = msg.first

                if (cmd == A_AUTH) {
                    log("Key belum dikenal, mengirim public key...")
                    val blob = encodePublicKey(pub as RSAPublicKey)
                    log("Panjang blob key: ${blob.size} byte (harus 532)")
                    val b64 = android.util.Base64.encodeToString(blob, android.util.Base64.NO_WRAP)
                    log("Panjang base64: ${b64.length} karakter")
                    val keyStr = (b64 + " bromtool@android\u0000").toByteArray()
                    pipe.send(packMsg(A_AUTH, AUTH_RSAPUBLICKEY, 0, keyStr))
                    log("Key terkirim, menunggu balasan (10 detik)...")
                    val m2 = pipe.recvMsg(10000)
                    if (m2 == null) {
                        log("TIDAK ADA BALASAN SAMA SEKALI setelah key dikirim (device diam)")
                        return false
                    }
                    msg = m2
                    cmd = m2.first
                    log("Balasan setelah kirim key: cmd=0x%08X arg0=0x%08X dataLen=%d".format(m2.first, m2.second, m2.third.size))
                }
            }

            if (cmd != A_CNXN) { log("Autentikasi ADB gagal, cmd akhir=0x%08X (bukan CNXN)".format(cmd)); return false }
            log("ADB terautentikasi, CNXN diterima")

            pipe.send(packMsg(A_OPEN, 1, 0, "reboot:$mode\u0000".toByteArray()))
            val resp = pipe.recvMsg(5000) ?: run { log("Tidak ada balasan OPEN"); return false }
            return if (resp.first == A_OKAY) { log("Perintah reboot:$mode terkirim (OKAY)"); true }
            else { log("OPEN ditolak (cmd=0x%08X)".format(resp.first)); false }
        } catch (e: Exception) {
            log("Exception ADB: ${e.message}")
            return false
        } finally {
            try { conn.releaseInterface(itf) } catch (_: Exception) {}
            conn.close()
        }
    }

    /** Autentikasi lalu jalankan satu perintah shell (mis. "reboot -p", "svc power shutdown"). */
    fun shell(usb: UsbManager, dev: UsbDevice, ctx: Context, command: String, log: (String) -> Unit): Boolean {
        val itf = (0 until dev.interfaceCount).map { dev.getInterface(it) }.firstOrNull { isAdbInterface(it) }
            ?: run { log("Interface ADB tidak ditemukan"); return false }
        var epI: UsbEndpoint? = null
        var epO: UsbEndpoint? = null
        for (e in 0 until itf.endpointCount) {
            val ep = itf.getEndpoint(e)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == UsbConstants.USB_DIR_IN) epI = ep else epO = ep
            }
        }
        if (epI == null || epO == null) { log("Endpoint ADB tidak ketemu"); return false }
        val conn = usb.openDevice(dev) ?: run { log("Gagal open device ADB"); return false }
        if (!conn.claimInterface(itf, true)) { conn.close(); log("Gagal claim interface ADB"); return false }
        val pipe = Pipe(conn, epI, epO)

        try {
            val (priv, pub) = getOrCreateKeyPair(ctx)
            pipe.send(packMsg(A_CNXN, A_VERSION, MAX_PAYLOAD, "host::\u0000".toByteArray()))
            var msg = pipe.recvMsg(5000) ?: run { log("Tidak ada balasan CNXN"); return false }
            var (cmd, arg0, data) = msg

            if (cmd == A_AUTH && arg0 == AUTH_TOKEN) {
                log("AUTH diminta, menandatangani token...")
                val sig = signToken(priv, data)
                pipe.send(packMsg(A_AUTH, AUTH_SIGNATURE, 0, sig))
                msg = pipe.recvMsg(5000) ?: run { log("Tidak ada balasan setelah signature"); return false }
                cmd = msg.first

                if (cmd == A_AUTH) {
                    log("Key belum dikenal. CEK LAYAR TARGET, izinkan USB debugging!")
                    val blob = encodePublicKey(pub as RSAPublicKey)
                    val b64 = android.util.Base64.encodeToString(blob, android.util.Base64.NO_WRAP)
                    val keyStr = (b64 + " bromtool@android\u0000").toByteArray()
                    pipe.send(packMsg(A_AUTH, AUTH_RSAPUBLICKEY, 0, keyStr))
                    msg = pipe.recvMsg(30000) ?: run { log("Timeout menunggu izin di layar target"); return false }
                    cmd = msg.first
                }
            }
            if (cmd != A_CNXN) { log("Autentikasi ADB gagal (cmd=0x%08X)".format(cmd)); return false }
            log("ADB terautentikasi")

            pipe.send(packMsg(A_OPEN, 1, 0, "shell:$command\u0000".toByteArray()))
            val resp = pipe.recvMsg(5000) ?: run { log("Tidak ada balasan OPEN shell"); return false }
            if (resp.first != A_OKAY) { log("OPEN shell ditolak (cmd=0x%08X)".format(resp.first)); return false }
            log("Perintah shell \"$command\" terkirim")

            // Baca output beberapa saat (opsional, untuk log)
            val end = System.currentTimeMillis() + 2000
            while (System.currentTimeMillis() < end) {
                val m = pipe.recvMsg(500) ?: break
                if (m.third.isNotEmpty()) log("OUT: " + String(m.third).trim())
            }
            return true
        } catch (e: Exception) {
            log("Exception ADB shell: ${e.message}")
            return false
        } finally {
            try { conn.releaseInterface(itf) } catch (_: Exception) {}
            conn.close()
        }
    }
}
