package com.canonscan

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/**
 * Single-screen scanner UI for the Canon PIXMA MP280/MP287 over USB OTG.
 * Plain framework views on purpose: no AndroidX/Compose dependencies to resolve.
 */
class MainActivity : Activity() {

    private lateinit var usb: UsbManager
    private lateinit var status: TextView
    private lateinit var preview: ImageView
    private lateinit var btnScan: Button
    private lateinit var btnCancel: Button
    private lateinit var btnJpeg: Button
    private lateinit var btnPdf: Button
    private lateinit var dpiGroup: RadioGroup
    private lateinit var colorGroup: RadioGroup
    private lateinit var verbose: CheckBox

    private var transport: UsbTransport? = null
    private var lastBitmap: Bitmap? = null
    private var lastDpi = 300
    @Volatile private var scanning = false

    private val permissionAction = "com.canonscan.USB_PERMISSION"

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                permissionAction -> {
                    val dev = usbExtra(i)
                    if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && dev != null) connect(dev)
                    else log("USB permission was denied.")
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val dev = usbExtra(i)
                    if (dev != null && dev.deviceId == transport?.device?.deviceId) {
                        if (scanning) NativeScanner.cancel()
                        transport?.close(); transport = null
                        log("Printer unplugged.")
                        refreshButtons()
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun usbExtra(i: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else i.getParcelableExtra(UsbManager.EXTRA_DEVICE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usb = getSystemService(Context.USB_SERVICE) as UsbManager
        buildUi()

        val f = IntentFilter(permissionAction).apply { addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(receiver, f)

        log("Canon Scan 0.1 — Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}).\n" +
            "Connect the MP287 with a USB OTG cable, then tap Connect.")
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) usbExtra(intent)?.let { askPermission(it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) usbExtra(intent)?.let { askPermission(it) }
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        if (scanning) NativeScanner.cancel()
        transport?.close()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- USB

    private fun onConnectClicked() {
        val devs = UsbTransport.canonDevices(usb)
        if (devs.isEmpty()) {
            log("No Canon USB device found.\n• Use a USB OTG cable/adapter.\n• Printer must be powered on.\n• Try another cable.")
            return
        }
        askPermission(devs.first())
    }

    private fun askPermission(dev: UsbDevice) {
        if (usb.hasPermission(dev)) { connect(dev); return }
        val pi = PendingIntent.getBroadcast(
            this, 0, Intent(permissionAction).setPackage(packageName), PendingIntent.FLAG_MUTABLE)
        log("Asking for USB permission for %04x:%04x …".format(dev.vendorId, dev.productId))
        usb.requestPermission(dev, pi)
    }

    private fun connect(dev: UsbDevice) {
        transport?.close(); transport = null
        val t = UsbTransport.open(usb, dev)
        if (t == null) {
            log("Could not open the device or claim its interface.\n" + UsbTransport.describe(dev))
        } else {
            transport = t
            val known = dev.vendorId == 0x04A9 && dev.productId == 0x1746
            log("Connected to %04x:%04x%s\n%s".format(dev.vendorId, dev.productId,
                if (known) " (PIXMA MP280 series — supported)" else " — USB ID is NOT in this build's list; scanning will report ENODEV. Please send the diagnostics file.",
                UsbTransport.describe(dev)))
        }
        refreshButtons()
    }

    // --------------------------------------------------------------- Scan

    private fun onScanClicked() {
        val t = transport ?: return
        if (scanning) return
        val dpi = when (dpiGroup.checkedRadioButtonId) { 1 -> 75; 2 -> 150; else -> 300 }
        val mode = if (colorGroup.checkedRadioButtonId == 11) 1 else 0
        lastDpi = dpi
        scanning = true; refreshButtons()
        log("Scanning at $dpi dpi (${if (mode == 0) "colour" else "grey"}) — the scan head will move. Please wait…")
        val raw = File(cacheDir, "scan.raw")
        val dbg = if (verbose.isChecked) 10 else 3

        Thread {
            val out = IntArray(6)
            val rc = NativeScanner.scan(t, t.device.vendorId, t.device.productId, t.serial,
                dpi, mode, 0.0, 0.0, 0.0, 0.0, raw.absolutePath, dbg, out)
            var bmp: Bitmap? = null
            var msg: String
            if (out[0] > 0 && out[1] > 0 && out[3] > 0) {
                try { bmp = ScanImage.rawToBitmap(raw, out[0], out[1], out[2], out[3]) } catch (e: Throwable) { msg = "Image error: $e" }
            }
            msg = when {
                rc == 0 && bmp != null -> "Scan complete: ${out[0]} × ${out[1]} px."
                bmp != null -> "Scan stopped early (${NativeScanner.strerror(rc)}); got ${out[1]} of ${out[5]} rows."
                else -> "Scan failed: ${NativeScanner.strerror(rc)} (code $rc).\n" +
                    "If the printer shows an error light (e.g. E05), fix that first — scanning is blocked until it clears."
            }
            runOnUiThread {
                scanning = false
                if (bmp != null) { lastBitmap = bmp; preview.setImageBitmap(bmp) }
                log(msg); refreshButtons()
            }
        }.start()
    }

    private fun saveJpeg() {
        val b = lastBitmap ?: return
        Thread {
            val u = ScanImage.saveJpeg(this, b)
            runOnUiThread { log(if (u != null) "Saved JPEG to Pictures/CanonScan." else "Could not save JPEG.") }
        }.start()
    }

    private fun savePdf() {
        val b = lastBitmap ?: return
        Thread {
            val u = ScanImage.savePdf(this, b, lastDpi)
            runOnUiThread { log(if (u != null) "Saved PDF to Downloads/CanonScan." else "Could not save PDF.") }
        }.start()
    }

    private fun saveDiagnostics() {
        Thread {
            val sb = StringBuilder()
            sb.append("Canon Scan diagnostics\nAndroid ${Build.VERSION.RELEASE} API ${Build.VERSION.SDK_INT}, ${Build.MANUFACTURER} ${Build.MODEL}\n\n")
            val devs = UsbTransport.canonDevices(usb)
            if (devs.isEmpty()) sb.append("No Canon USB devices visible.\n")
            devs.forEach { sb.append(UsbTransport.describe(it)).append('\n') }
            sb.append("\n--- logcat (CanonScan) ---\n")
            try {
                val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "1500", "-s", "CanonScan:*"))
                sb.append(p.inputStream.bufferedReader().readText())
            } catch (e: Exception) { sb.append("logcat unavailable: $e\n") }
            val u = ScanImage.saveText(this, "canonscan_diagnostics.txt", sb.toString())
            runOnUiThread { log(if (u != null) "Saved Downloads/CanonScan/canonscan_diagnostics.txt — send this file." else "Could not save diagnostics.") }
        }.start()
    }

    // ----------------------------------------------------------------- UI

    private fun log(s: String) { status.text = s }

    private fun refreshButtons() {
        btnScan.isEnabled = transport != null && !scanning
        btnCancel.isEnabled = scanning
        btnJpeg.isEnabled = lastBitmap != null && !scanning
        btnPdf.isEnabled = lastBitmap != null && !scanning
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text; isAllCaps = false; setOnClickListener { onClick() }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(16), dp(16), dp(16), dp(16)) }

        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(TextView(this).apply { text = "Canon PIXMA MP287 — Scan"; textSize = 22f })
        left.addView(button("Connect printer") { onConnectClicked() })

        left.addView(TextView(this).apply { text = "Resolution"; setPadding(0, dp(12), 0, 0) })
        dpiGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        listOf(1 to "75", 2 to "150", 3 to "300").forEach { (id, label) ->
            dpiGroup.addView(RadioButton(this).apply { this.id = id; text = "$label dpi" })
        }
        dpiGroup.check(3); left.addView(dpiGroup)

        left.addView(TextView(this).apply { text = "Mode" })
        colorGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        colorGroup.addView(RadioButton(this).apply { id = 10; text = "Colour" })
        colorGroup.addView(RadioButton(this).apply { id = 11; text = "Grey" })
        colorGroup.check(10); left.addView(colorGroup)

        verbose = CheckBox(this).apply { text = "Verbose USB log (for diagnostics)" }
        left.addView(verbose)

        btnScan = button("Scan") { onScanClicked() }.also { left.addView(it) }
        btnCancel = button("Cancel") { NativeScanner.cancel() }.also { left.addView(it) }
        btnJpeg = button("Save as JPEG") { saveJpeg() }.also { left.addView(it) }
        btnPdf = button("Save as PDF") { savePdf() }.also { left.addView(it) }
        left.addView(button("Save diagnostics file") { saveDiagnostics() })

        status = TextView(this).apply { setPadding(0, dp(12), 0, 0); setTextIsSelectable(true) }
        left.addView(ScrollView(this).apply { addView(status) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        preview = ImageView(this).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(0xFFEEEEEE.toInt()) }
        val right = LinearLayout(this).apply { gravity = Gravity.CENTER; addView(preview, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) }

        root.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        root.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.4f).apply { leftMargin = dp(16) })
        setContentView(root)
        refreshButtons()
    }
}
