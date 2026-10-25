package com.canonscan

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Turns the raw rows written by the native scanner into a Bitmap, JPEG or PDF. */
object ScanImage {
    fun rawToBitmap(raw: File, width: Int, rows: Int, channels: Int, lineSize: Int): Bitmap {
        val bmp = Bitmap.createBitmap(width, rows, Bitmap.Config.ARGB_8888)
        val line = ByteArray(lineSize)
        val px = IntArray(width)
        RandomAccessFile(raw, "r").use { f ->
            for (y in 0 until rows) {
                f.readFully(line)
                if (channels == 3) {
                    for (x in 0 until width) {
                        val r = line[x * 3].toInt() and 0xff
                        val g = line[x * 3 + 1].toInt() and 0xff
                        val b = line[x * 3 + 2].toInt() and 0xff
                        px[x] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
                    }
                } else {
                    for (x in 0 until width) {
                        val v = line[x].toInt() and 0xff
                        px[x] = (0xff shl 24) or (v shl 16) or (v shl 8) or v
                    }
                }
                bmp.setPixels(px, 0, width, 0, y, width, 1)
            }
        }
        return bmp
    }

    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    /** Saves to Pictures/CanonScan via MediaStore (no storage permission needed on Android 10+). */
    fun saveJpeg(ctx: Context, bmp: Bitmap): Uri? {
        val v = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "scan_${stamp()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/CanonScan")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: return null
        ctx.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        return uri
    }

    /** Saves a single-page PDF to Downloads/CanonScan. Page size is the bitmap size at 72 dpi-equivalent points. */
    fun savePdf(ctx: Context, bmp: Bitmap, dpi: Int): Uri? {
        val wPt = (bmp.width * 72f / dpi).toInt().coerceAtLeast(1)
        val hPt = (bmp.height * 72f / dpi).toInt().coerceAtLeast(1)
        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(wPt, hPt, 1).create())
        page.canvas.drawBitmap(bmp, null, android.graphics.Rect(0, 0, wPt, hPt), null)
        doc.finishPage(page)
        val v = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "scan_${stamp()}.pdf")
            put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/CanonScan")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
        if (uri != null) ctx.contentResolver.openOutputStream(uri)?.use { doc.writeTo(it) }
        doc.close()
        return uri
    }

    fun saveText(ctx: Context, name: String, text: String): Uri? {
        val v = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/CanonScan")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: return null
        ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
        return uri
    }
}
