package com.canonscan

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager

/**
 * Raw USB access for the native pixma core. The three transfer methods are called from C
 * through JNI (names and signatures must stay in sync with io/pixma_io_android.c).
 */
class UsbTransport private constructor(
    private val conn: UsbDeviceConnection,
    private val iface: UsbInterface,
    private val epIn: UsbEndpoint,
    private val epOut: UsbEndpoint,
    private val epIntr: UsbEndpoint?,
    val device: UsbDevice,
) {
    // Called from native code.
    fun bulkWrite(buf: ByteArray, len: Int, timeoutMs: Int): Int =
        conn.bulkTransfer(epOut, buf, len, timeoutMs)

    fun bulkRead(buf: ByteArray, len: Int, timeoutMs: Int): Int =
        conn.bulkTransfer(epIn, buf, len, timeoutMs)

    fun interruptRead(buf: ByteArray, len: Int, timeoutMs: Int): Int =
        epIntr?.let { conn.bulkTransfer(it, buf, len, timeoutMs) } ?: -1

    val serial: String? get() = try { conn.serial } catch (e: SecurityException) { null }

    fun close() {
        try { conn.releaseInterface(iface) } catch (_: Exception) {}
        conn.close()
    }

    companion object {
        const val CANON_VID = 0x04A9

        fun canonDevices(usb: UsbManager): List<UsbDevice> =
            usb.deviceList.values.filter { it.vendorId == CANON_VID }

        /** Human-readable dump of every interface/endpoint, for diagnostics. */
        fun describe(dev: UsbDevice): String = buildString {
            append("Device %04x:%04x  %s\n".format(dev.vendorId, dev.productId, dev.productName ?: "?"))
            for (i in 0 until dev.interfaceCount) {
                val f = dev.getInterface(i)
                append("  iface %d alt %d class=0x%02x sub=0x%02x proto=0x%02x\n".format(
                    f.id, f.alternateSetting, f.interfaceClass, f.interfaceSubclass, f.interfaceProtocol))
                for (e in 0 until f.endpointCount) {
                    val ep = f.getEndpoint(e)
                    val dir = if (ep.direction == UsbConstants.USB_DIR_IN) "IN " else "OUT"
                    val type = when (ep.type) {
                        UsbConstants.USB_ENDPOINT_XFER_BULK -> "bulk"
                        UsbConstants.USB_ENDPOINT_XFER_INT -> "interrupt"
                        UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "control"
                        else -> "iso"
                    }
                    append("    ep 0x%02x %s %s max=%d\n".format(ep.address, dir, type, ep.maxPacketSize))
                }
            }
        }

        /**
         * Opens [dev] and claims the vendor-specific interface that has bulk IN and bulk OUT
         * (this is the interface SANE's pixma backend talks to for scanning). Permission must
         * already be granted. Returns null on failure.
         */
        fun open(usb: UsbManager, dev: UsbDevice): UsbTransport? {
            var best: UsbInterface? = null
            for (i in 0 until dev.interfaceCount) {
                val f = dev.getInterface(i)
                val hasIn = (0 until f.endpointCount).any {
                    val e = f.getEndpoint(it)
                    e.type == UsbConstants.USB_ENDPOINT_XFER_BULK && e.direction == UsbConstants.USB_DIR_IN
                }
                val hasOut = (0 until f.endpointCount).any {
                    val e = f.getEndpoint(it)
                    e.type == UsbConstants.USB_ENDPOINT_XFER_BULK && e.direction == UsbConstants.USB_DIR_OUT
                }
                if (!hasIn || !hasOut) continue
                // Prefer vendor-specific (0xff); keep the first printer-class (7) as fallback.
                if (f.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC) { best = f; break }
                if (best == null) best = f
            }
            val f = best ?: return null
            var epIn: UsbEndpoint? = null
            var epOut: UsbEndpoint? = null
            var epIntr: UsbEndpoint? = null
            for (e in 0 until f.endpointCount) {
                val ep = f.getEndpoint(e)
                when {
                    ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.direction == UsbConstants.USB_DIR_IN -> if (epIn == null) epIn = ep
                    ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.direction == UsbConstants.USB_DIR_OUT -> if (epOut == null) epOut = ep
                    ep.type == UsbConstants.USB_ENDPOINT_XFER_INT && ep.direction == UsbConstants.USB_DIR_IN -> if (epIntr == null) epIntr = ep
                }
            }
            val conn = usb.openDevice(dev) ?: return null
            if (!conn.claimInterface(f, true)) { conn.close(); return null }
            return UsbTransport(conn, f, epIn!!, epOut!!, epIntr, dev)
        }
    }
}
