package com.example.livecam

import android.content.ContentResolver
import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import java.io.File

fun listLive(cr: ContentResolver): List<Uri> {
    val res = ArrayList<Uri>()
    val base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    cr.query(base, arrayOf(MediaStore.Images.Media._ID), "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
        arrayOf("Pictures/LiveCam%"), "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC")?.use {
        while (it.moveToNext()) res.add(ContentUris.withAppendedId(base, it.getLong(0)))
    }
    return res
}

fun thumb(cr: ContentResolver, uri: Uri): Bitmap? {
    try {
        val o = BitmapFactory.Options().apply { inSampleSize = 8 }
        var bm = cr.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, o) } ?: return null
        val deg = cr.openInputStream(uri)!!.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) { 6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f }
        }
        if (deg != 0f) bm = Bitmap.createBitmap(bm, 0, 0, bm.width, bm.height, Matrix().apply { postRotate(deg) }, true)
        return bm
    } catch (e: Exception) { return null }
}

// ambil bagian MP4 yang menempel di akhir file JPEG (mulai dari penanda "ftyp")
fun extractMp4(cr: ContentResolver, uri: Uri, out: File): Boolean {
    val b = cr.openInputStream(uri)!!.use { it.readBytes() }
    var i = b.size - 4
    while (i >= 4) {
        if (b[i] == 0x66.toByte() && b[i + 1] == 0x74.toByte() && b[i + 2] == 0x79.toByte() && b[i + 3] == 0x70.toByte()) break
        i--
    }
    if (i < 4) return false
    out.outputStream().use { it.write(b, i - 4, b.size - (i - 4)) }
    return true
}
