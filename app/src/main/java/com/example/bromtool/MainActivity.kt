
package com.example.bromtool

import android.app.PendingIntent
import android.content.*
import android.hardware.usb.*
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Menu
import android.view.MenuItem
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

private const val ACTION_PERM = "com.example.bromtool.USB_PERMISSION"
private const val MTK_VID = 0x0E8D

// State di luar Activity: selamat dari pembuatan ulang Activity
private object S {
    val logBuf = StringBuilder()
    @Volatile var conn: UsbDeviceConnection? = null
    @Volatile var dev: UsbDevice? = null
    @Volatile var dataIf: UsbInterface? = null
    @Volatile var commIf: UsbInterface? = null
    @Volatile var epIn: UsbEndpoint? = null
    @Volatile var epOut: UsbEndpoint? = null
    @Volatile var attempting = false
    @Volatile var state = 0 // 0 idle, 1 sukses (hijau), 2 gagal (merah)
    @Volatile var statusText = "Menunggu device..."
    @Volatile var hw = ""
    @Volatile var pending: String? = null
    @Volatile var pendingAdbMode: String? = null
    @Volatile var asked = ""
    @Volatile var failedName = ""
}

class MainActivity : AppCompatActivity() {

    private lateinit var log: TextView
    private lateinit var status: TextView
    private lateinit var root: LinearLayout
    private lateinit var usb: UsbManager
    @Volatile private var alive = true

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        usb = getSystemService(Context.USB_SERVICE) as UsbManager

        status = TextView(this).apply { setPadding(24, 24, 24, 24); textSize = 18f }
        val btn = Button(this).apply { text = "Handshake Manual" }
        log = TextView(this).apply { setPadding(24, 0, 24, 24) }

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(btn)
            addView(ScrollView(context).apply { addView(log) })
        }
        setContentView(root)
        log.text = synchronized(S.logBuf) { S.logBuf.toString() }
        applyState()

        registerReceiver(permReceiver, IntentFilter(ACTION_PERM), RECEIVER_NOT_EXPORTED)
        registerReceiver(attachReceiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED), RECEIVER_NOT_EXPORTED)
        registerReceiver(detachReceiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED), RECEIVER_NOT_EXPORTED)

        btn.setOnClickListener {
            if (S.conn != null) { unlock(); setState(0, "Dilepas manual. Menunggu device...") }
            S.failedName = ""; S.asked = ""
            scanAndTry()
        }

        (intent?.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE))?.let {
            print("Activity dibuka oleh attach:")
            dump(it)
            tryDevice(it)
        }
        if (S.conn == null) scanAndTry()

        // Pemantau USB: tangkap BROM/Preloader tanpa perlu cabut-colok ulang
        thread {
            while (alive) {
                try {
                    val present = usb.deviceList.values.map { it.deviceName }.toSet()
                    if (S.asked.isNotEmpty() && S.asked !in present) S.asked = ""
                    if (S.failedName.isNotEmpty() && S.failedName !in present) S.failedName = ""

                    if (S.conn == null && !S.attempting) {
                        val d = usb.deviceList.values.firstOrNull { isTarget(it) && it.deviceName != S.failedName }
                        if (d != null) {
                            if (usb.hasPermission(d)) handshakeAuto(d)
                            else tryDevice(d) // minta izin sendiri, jangan tunggu broadcast attach
                        }
                    }
                    Thread.sleep(150)
                } catch (_: Exception) {}
            }
        }
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        setIntent(i)
        i.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)?.let {
            print("Intent attach:")
            dump(it)
            tryDevice(it)
        }
    }

    // ---------- UI helper ----------
    private fun applyState() {
        val bg: Int; val fg: Int
        when (S.state) {
            1 -> { bg = 0xFF2E7D32.toInt(); fg = 0xFFFFFFFF.toInt() }
            2 -> { bg = 0xFFB71C1C.toInt(); fg = 0xFFFFFFFF.toInt() }
            else -> { bg = 0xFFFAFAFA.toInt(); fg = 0xFF444444.toInt() }
        }
        root.setBackgroundColor(bg)
        status.setTextColor(fg)
        log.setTextColor(fg)
        status.text = S.statusText
    }

    private fun print(m: String) {
        val line = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + " " + m
        val txt = synchronized(S.logBuf) {
            S.logBuf.append(line).append('\n')
            if (S.logBuf.length > 20000) S.logBuf.delete(0, 5000)
            S.logBuf.toString()
        }
        runOnUiThread { log.text = txt }
    }

    private fun setState(st: Int, text: String) {
        S.state = st; S.statusText = text
        runOnUiThread { applyState() }
    }

    private fun signalSuccess() {
        runOnUiThread { Toast.makeText(this, "HANDSHAKE BROM BERHASIL", Toast.LENGTH_LONG).show() }
        try {
            (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
                .vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300, 150, 600), -1))
        } catch (_: Exception) {}
        try {
            ToneGenerator(AudioManager.STREAM_ALARM, 100)
                .startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 700)
        } catch (_: Exception) {}
    }

    private fun startHeartbeat(c: UsbDeviceConnection, pid: Int) {
        thread {
            var t = 0
            while (S.conn === c) {
                Thread.sleep(1000); t++
                if (S.conn === c) setState(1,
                    "BROM TERHUBUNG (PID=0x%04X) %s\nTerkunci stabil %d detik".format(pid, S.hw, t))
            }
        }
    }

    // ---------- Deteksi & label mode ----------
    private fun hasCdcData(d: UsbDevice) = (0 until d.interfaceCount).any {
        d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_CDC_DATA
    }
    private fun isTarget(d: UsbDevice) = d.vendorId == MTK_VID && hasCdcData(d)

    private fun isFastboot(itf: UsbInterface) =
        itf.interfaceClass == 0xFF && itf.interfaceSubclass == 0x42 && itf.interfaceProtocol == 0x03

    private fun modeOf(d: UsbDevice): String {
        val ifs = (0 until d.interfaceCount).map { d.getInterface(it) }
        return when {
            d.vendorId == MTK_VID && ifs.any { it.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA } ->
                if (d.productId == 0x0003) "BROM MediaTek" else "Preloader/serial MediaTek"
            ifs.any { isFastboot(it) } -> "FASTBOOT"
            ifs.any { it.interfaceClass == 0xFF && it.interfaceSubclass == 0x42 && it.interfaceProtocol == 0x01 } -> "ADB (ROM/recovery/sideload)"
            ifs.any { it.interfaceClass == 6 } -> "MTP/PTP"
            ifs.any { it.interfaceClass == 8 } -> "Mass storage"
            ifs.any { it.interfaceClass == 0xFF } -> "Vendor-specific"
            else -> "Tidak dikenal"
        }
    }

    private fun dump(d: UsbDevice) = print("[%s] VID=0x%04X PID=0x%04X iface=[%s]".format(
        modeOf(d), d.vendorId, d.productId,
        (0 until d.interfaceCount).joinToString(",") {
            val i = d.getInterface(it)
            "0x%02X/%02X/%02X".format(i.interfaceClass, i.interfaceSubclass, i.interfaceProtocol)
        }))

    private fun scanAndTry() {
        if (S.conn != null) return
        val all = usb.deviceList.values.toList()
        if (all.isEmpty()) { print("Tidak ada device USB terdeteksi"); return }
        all.forEach { dump(it) }
        val dev = all.firstOrNull { isTarget(it) }
        if (dev == null) {
            val d = all.first()
            setState(0, "Terdeteksi: %s\nVID=0x%04X PID=0x%04X".format(modeOf(d), d.vendorId, d.productId))
            return
        }
        tryDevice(dev)
    }

    private fun tryDevice(dev: UsbDevice) {
        if (!isTarget(dev)) {
            setState(0, "Terdeteksi: %s\nVID=0x%04X PID=0x%04X".format(modeOf(dev), dev.vendorId, dev.productId))
            return
        }
        if (S.conn != null) { print("Sudah terkunci, abaikan device baru"); return }
        if (usb.hasPermission(dev)) handshakeAuto(dev)
        else if (S.asked != dev.deviceName) {
            S.asked = dev.deviceName
            print("Minta izin USB...")
            usb.requestPermission(dev,
                PendingIntent.getBroadcast(this, 0, Intent(ACTION_PERM).setPackage(packageName),
                    PendingIntent.FLAG_MUTABLE))
        }
    }

    private val attachReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val d: UsbDevice? = i.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            if (d != null) { print("USB attached:"); dump(d); tryDevice(d) } else scanAndTry()
        }
    }

    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val d: UsbDevice? = i.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            print("USB detached VID=0x%04X PID=0x%04X".format(d?.vendorId ?: 0, d?.productId ?: 0))
            if (d != null && d.deviceName == S.asked) S.asked = ""
            if (d != null && d.deviceName == S.failedName) S.failedName = ""
            val ld = S.dev
            if (ld == null || d == null || d.deviceName == ld.deviceName) {
                val wasLocked = S.conn != null
                unlock()
                setState(0, if (wasLocked) "Device PUTUS setelah handshake. Menunggu lagi..."
                            else "Device lepas. Menunggu device...")
            }
        }
    }

    private val permReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val d: UsbDevice? = i.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            val granted = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            val p = S.pending
            val pa = S.pendingAdbMode
            if (d != null && granted && p != null && !isTarget(d)) { S.pending = null; fastbootCmd(d, p) }
            else if (d != null && granted && pa != null) { S.pendingAdbMode = null; doAdbReboot(d, pa) }
            else if (d != null && granted) handshakeAuto(d)
            else print("Izin USB ditolak")
        }
    }

    private fun unlock() {
        try { S.dataIf?.let { S.conn?.releaseInterface(it) } } catch (_: Exception) {}
        try { S.commIf?.let { S.conn?.releaseInterface(it) } } catch (_: Exception) {}
        try { S.conn?.close() } catch (_: Exception) {}
        S.conn = null; S.dev = null; S.dataIf = null; S.commIf = null
        S.epIn = null; S.epOut = null; S.hw = ""
    }

    // ---------- Handshake ----------
    private fun handshakeAuto(dev: UsbDevice) {
        if (S.attempting || S.conn != null) return
        S.attempting = true
        thread {
            try {
                var ok = false
                var lastErr = ""
                for (attempt in 1..3) {
                    val (success, err) = attemptHandshake(dev)
                    if (success) { ok = true; break }
                    lastErr = err
                    print("Percobaan $attempt gagal: $err")
                    if (!usb.deviceList.containsKey(dev.deviceName)) break
                    Thread.sleep(100)
                }
                if (!ok) {
                    S.failedName = dev.deviceName
                    print("Gagal handshake: $lastErr")
                    setState(2, "GAGAL HANDSHAKE\n$lastErr")
                }
            } catch (e: Exception) {
                print("Exception: ${e.message}")
            } finally {
                S.attempting = false
            }
        }
    }

    private fun readN(conn: UsbDeviceConnection, ep: UsbEndpoint, n: Int, timeoutMs: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(maxOf(ep.maxPacketSize, 64))
        val end = System.currentTimeMillis() + timeoutMs
        while (out.size() < n && System.currentTimeMillis() < end) {
            val r = conn.bulkTransfer(ep, buf, buf.size, 50)
            if (r > 0) out.write(buf, 0, r)
        }
        return if (out.size() >= n) out.toByteArray() else null
    }

    private fun attemptHandshake(dev: UsbDevice): Pair<Boolean, String> {
        var dataIf: UsbInterface? = null
        var commIf: UsbInterface? = null
        var epI: UsbEndpoint? = null
        var epO: UsbEndpoint? = null
        for (n in 0 until dev.interfaceCount) {
            val itf = dev.getInterface(n)
            if (itf.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA) {
                dataIf = itf
                for (e in 0 until itf.endpointCount) {
                    val ep = itf.getEndpoint(e)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        if (ep.direction == UsbConstants.USB_DIR_IN) epI = ep else epO = ep
                    }
                }
            } else if (itf.interfaceClass == UsbConstants.USB_CLASS_COMM) {
                commIf = itf
            }
        }
        if (dataIf == null || epI == null || epO == null) return false to "Endpoint bulk tidak ketemu"
        val inEp = epI
        val outEp = epO

        val conn = usb.openDevice(dev) ?: return false to "Gagal open device"
        if (!conn.claimInterface(dataIf, true)) { conn.close(); return false to "Gagal claim interface data" }
        if (commIf != null) {
            conn.claimInterface(commIf, true)
            val a = conn.controlTransfer(0x21, 0x22, 0x03, commIf.id, null, 0, 100)
            val b = conn.controlTransfer(0x21, 0x20, 0, commIf.id,
                byteArrayOf(0x00, 0xC2.toByte(), 0x01, 0x00, 0x00, 0x00, 0x08), 7, 100)
            print("CDC setup: ctrl_line=$a line_coding=$b")
        }

        fun release() {
            try { conn.releaseInterface(dataIf) } catch (_: Exception) {}
            try { commIf?.let { conn.releaseInterface(it) } } catch (_: Exception) {}
            conn.close()
        }

        // Tahap 1: A0 berulang sampai dibalas 5F (maks 3 detik)
        val t0 = System.currentTimeMillis()
        var got5f = false
        var tries = 0
        var lastRx = "timeout"
        val rbuf = ByteArray(maxOf(inEp.maxPacketSize, 64))
        while (System.currentTimeMillis() - t0 < 3000) {
            tries++
            if (tries % 10 == 0 && !usb.deviceList.containsKey(dev.deviceName)) break
            conn.bulkTransfer(outEp, byteArrayOf(0xA0.toByte()), 1, 50)
            val r = conn.bulkTransfer(inEp, rbuf, rbuf.size, 50)
            if (r >= 1) {
                lastRx = "%02X".format(rbuf[0])
                if (tries < 6) print("RX: " + rbuf.copyOf(r).joinToString(" ") { "%02X".format(it) } +
                    " [" + String(rbuf, 0, r, Charsets.ISO_8859_1).filter { it.code in 32..126 } + "]")
                if (rbuf[0] == 0x5F.toByte()) { got5f = true; break }
            }
        }
        print("TX A0 -> RX $lastRx (x$tries) ${if (got5f) "OK" else "GAGAL"}")
        if (!got5f) { release(); return false to "tidak ada balasan 5F (terakhir: $lastRx)" }

        // Tahap 2: sisa urutan
        fun step(tx: Int, rxExp: Int): Boolean {
            conn.bulkTransfer(outEp, byteArrayOf(tx.toByte()), 1, 500)
            val r = readN(conn, inEp, 1, 500)
            val got = r?.get(0)?.toInt()?.and(0xFF)
            val ok = got == rxExp
            print("TX %02X -> RX %s (harus %02X) %s".format(tx, got?.let { "%02X".format(it) } ?: "timeout", rxExp, if (ok) "OK" else "GAGAL"))
            return ok
        }
        for ((tx, rx) in listOf(0x0A to 0xF5, 0x50 to 0xAF, 0x05 to 0xFA)) {
            if (!step(tx, rx)) { release(); return false to "gagal di langkah 0x%02X".format(tx) }
        }

        // SUKSES: kunci koneksi + beri tanda
        S.conn = conn; S.dev = dev; S.dataIf = dataIf; S.commIf = commIf
        S.epIn = inEp; S.epOut = outEp
        print("*** HANDSHAKE BERHASIL (PID=0x%04X) ***".format(dev.productId))
        setState(1, "BROM TERHUBUNG (PID=0x%04X)".format(dev.productId))
        signalSuccess()
        startHeartbeat(conn, dev.productId)

        // Tahap 3: GET_HW_CODE (0xFD)
        conn.bulkTransfer(outEp, byteArrayOf(0xFD.toByte()), 1, 500)
        val echo = readN(conn, inEp, 1, 500)
        print("TX FD -> echo ${echo?.let { "%02X".format(it[0]) } ?: "timeout"}")
        val r = readN(conn, inEp, 4, 500)
        if (r != null) {
            val hw = ((r[0].toInt() and 0xFF) shl 8) or (r[1].toInt() and 0xFF)
            val st = ((r[2].toInt() and 0xFF) shl 8) or (r[3].toInt() and 0xFF)
            S.hw = "HW=0x%04X".format(hw)
            print("HW code: 0x%04X  status: 0x%04X".format(hw, st))
        } else {
            print("HW code gagal dibaca")
        }
        return true to ""
    }

    // ---------- Menu ----------
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Reboot target (fastboot)")
        menu.add(0, 2, 1, "Target ke bootloader")
        menu.add(0, 3, 2, "Target ke recovery")
        menu.add(0, 4, 3, "Reboot HP ini (root)")
        menu.add(0, 5, 4, "Scan ulang semua device")
        menu.add(0, 6, 5, "Reboot target (ADB) ke bootloader")
        menu.add(0, 7, 6, "Reboot target (ADB) ke recovery")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> fastbootAction("reboot")
            2 -> fastbootAction("reboot-bootloader")
            3 -> fastbootAction("reboot-recovery")
            4 -> AlertDialog.Builder(this)
                .setTitle("Reboot HP ini?")
                .setMessage("HP yang menjalankan aplikasi ini akan restart.")
                .setPositiveButton("Reboot") { _, _ -> rebootHost() }
                .setNegativeButton("Batal", null).show()
            5 -> { S.failedName = ""; S.asked = ""; scanAndTry() }
            6 -> adbRebootAction("bootloader")
            7 -> adbRebootAction("recovery")
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun findAdbDevice(): UsbDevice? = usb.deviceList.values.firstOrNull { d ->
        (0 until d.interfaceCount).any { AdbClient.isAdbInterface(d.getInterface(it)) }
    }

    private fun adbRebootAction(mode: String) {
        val dev = findAdbDevice()
        if (dev == null) { print("Tidak ada device ADB terhubung"); return }
        if (usb.hasPermission(dev)) doAdbReboot(dev, mode)
        else {
            S.pendingAdbMode = mode
            print("Minta izin USB untuk ADB...")
            usb.requestPermission(dev,
                PendingIntent.getBroadcast(this, 2, Intent(ACTION_PERM).setPackage(packageName),
                    PendingIntent.FLAG_MUTABLE))
        }
    }

    private fun doAdbReboot(dev: UsbDevice, mode: String) {
        print("Memulai reboot ADB ke $mode ...")
        thread { AdbClient.reboot(usb, dev, this, mode) { m -> print(m) } }
    }

    private fun fastbootAction(cmd: String) {
        val dev = usb.deviceList.values.firstOrNull { d ->
            (0 until d.interfaceCount).any { isFastboot(d.getInterface(it)) }
        }
        if (dev == null) { print("Tidak ada device fastboot terhubung"); return }
        if (usb.hasPermission(dev)) fastbootCmd(dev, cmd)
        else {
            S.pending = cmd
            print("Minta izin USB untuk fastboot...")
            usb.requestPermission(dev,
                PendingIntent.getBroadcast(this, 1, Intent(ACTION_PERM).setPackage(packageName),
                    PendingIntent.FLAG_MUTABLE))
        }
    }

    private fun fastbootCmd(dev: UsbDevice, cmd: String) {
        thread {
            try {
                val itf = (0 until dev.interfaceCount).map { dev.getInterface(it) }.first { isFastboot(it) }
                var epI: UsbEndpoint? = null
                var epO: UsbEndpoint? = null
                for (e in 0 until itf.endpointCount) {
                    val ep = itf.getEndpoint(e)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        if (ep.direction == UsbConstants.USB_DIR_IN) epI = ep else epO = ep
                    }
                }
                if (epI == null || epO == null) { print("Endpoint fastboot tidak ketemu"); return@thread }
                val conn = usb.openDevice(dev) ?: run { print("Gagal open device fastboot"); return@thread }
                if (!conn.claimInterface(itf, true)) { conn.close(); print("Gagal claim interface fastboot"); return@thread }
                val b = cmd.toByteArray()
                val w = conn.bulkTransfer(epO, b, b.size, 1000)
                print("FASTBOOT TX \"$cmd\" ($w byte)")
                val buf = ByteArray(512)
                val end = System.currentTimeMillis() + 4000
                var done = false
                while (!done && System.currentTimeMillis() < end) {
                    val r = conn.bulkTransfer(epI, buf, buf.size, 300)
                    if (r > 0) {
                        val t = String(buf, 0, r, Charsets.ISO_8859_1)
                        print("FASTBOOT RX: $t")
                        if (t.startsWith("OKAY") || t.startsWith("FAIL")) done = true
                    }
                }
                if (!done) print("Tidak ada balasan (device mungkin sudah restart)")
                try { conn.releaseInterface(itf) } catch (_: Exception) {}
                conn.close()
            } catch (e: Exception) {
                print("Exception fastboot: ${e.message}")
            }
        }
    }

    private fun rebootHost() {
        thread {
            try {
                print("Reboot HP ini via root...")
                Runtime.getRuntime().exec(arrayOf("su", "-c", "reboot"))
            } catch (e: Exception) {
                print("Gagal reboot (butuh root): ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        alive = false
        if (isFinishing) unlock()
        try { unregisterReceiver(permReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(attachReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(detachReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }
}
