package com.example.livecam

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import java.io.File

class ViewerActivity : ComponentActivity() {
    private lateinit var items: List<Uri>
    private var idx = 0
    private lateinit var video: VideoView
    private lateinit var info: TextView
    private val clip by lazy { File(cacheDir, "view.mp4") }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun btn(t: String, a: () -> Unit) = TextView(this).apply {
        text = t; setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER
        setPadding(dp(16), dp(10), dp(16), dp(10))
        background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(0x66FFFFFF) }
        setOnClickListener { a() }
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        items = listLive(contentResolver)
        if (items.isEmpty()) { Toast.makeText(this, "Belum ada Live Photo", Toast.LENGTH_SHORT).show(); finish(); return }
        video = VideoView(this).apply { setOnPreparedListener { it.isLooping = true; it.start() } }
        info = TextView(this).apply { setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER }
        val nav = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(btn("‹") { go(1) })
            addView(info, LinearLayout.LayoutParams(0, -2, 1f))
            addView(btn("›") { go(-1) })
        }
        val share = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            addView(btn("Bagikan Video") { shareVideo() })
            addView(btn("Bagikan Foto") { sharePhoto() }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(nav); addView(share, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }) }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(video, FrameLayout.LayoutParams(-1, -1).apply { bottomMargin = dp(150) })
            addView(btn("✕ Tutup") { finish() }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { topMargin = dp(40); marginStart = dp(16) })
            addView(col, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = dp(40); marginStart = dp(16); marginEnd = dp(16) })
        })
        show()
    }

    private fun go(d: Int) { idx = (idx + d).coerceIn(0, items.size - 1); show() }

    private fun show() {
        info.text = "${idx + 1} / ${items.size}"
        if (extractMp4(contentResolver, items[idx], clip)) { video.setVideoURI(Uri.fromFile(clip)); video.start() }
        else Toast.makeText(this, "Bagian gerak tidak ditemukan", Toast.LENGTH_SHORT).show()
    }

    private fun shareVideo() {
        val u = FileProvider.getUriForFile(this, "$packageName.fp", clip)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"; putExtra(Intent.EXTRA_STREAM, u); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Bagikan video"))
    }

    private fun sharePhoto() {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"; putExtra(Intent.EXTRA_STREAM, items[idx]); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Bagikan foto"))
    }
}
