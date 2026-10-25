package com.canonscan

/** Bridge to libcanonscan.so (SANE pixma core). */
object NativeScanner {
    init { System.loadLibrary("canonscan") }

    /**
     * Runs a flatbed scan and writes raw interleaved 8-bit rows to [path].
     * [mode]: 0 = colour, 1 = grey. Area in mm; w/h <= 0 means full bed.
     * [out] = [width, rowsReceived, channels, lineSize, errorCode, expectedRows].
     * Returns 0 on success or a negative PIXMA error code.
     */
    external fun scan(
        transport: UsbTransport, vid: Int, pid: Int, serial: String?,
        dpi: Int, mode: Int, xMm: Double, yMm: Double, wMm: Double, hMm: Double,
        path: String, debugLevel: Int, out: IntArray
    ): Int

    external fun strerror(code: Int): String
    external fun cancel()
}
