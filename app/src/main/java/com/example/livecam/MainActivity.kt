package com.example.livecam

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.graphics.drawable.GradientDrawable
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Rational
import android.util.Size
import android.view.Surface
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.ExtendableBuilder
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class Frame(val nv12: ByteArray, val w: Int, val h: Int, val tsNs: Long)
class Stab(val cw: Int, val ch: Int, val ox: IntArray, val oy: IntArray)

class MainActivity : ComponentActivity() {
    private val windowNs = 1_500_000_000L
    private val yellow = Color.parseColor("#FFD60A")
    private val lock = Any()
    private val ring = ArrayDeque<Frame>()
    private var pre: List<Frame> = emptyList()
    private var post: ArrayList<Frame>? = null
    private var postEndNs = 0L
    private var vidFrames: List<Frame>? = null
    private var stillOk = false
    @Volatile private var rotation = 0
    private val camExec = Executors.newSingleThreadExecutor()
    private val ioExec = Executors.newSingleThreadExecutor()
    private lateinit var imageCapture: ImageCapture
    private lateinit var previewView: PreviewView
    private lateinit var stillFile: File
    private lateinit var shutter: FrameLayout
    private lateinit var inner: View
    private lateinit var liveBadge: TextView
    private lateinit var flashBtn: TextView
    private lateinit var mp4Btn: TextView
    private lateinit var ratioBtn: TextView
    private lateinit var hdBtn: TextView
    private lateinit var stabBtn: TextView
    private lateinit var nightBtn: TextView
    private var hd = true
    private var stab = true
    private var night = false
    private val capExec = Executors.newSingleThreadExecutor()
    private val nightExec = Executors.newSingleThreadExecutor()
    private var ratioIdx = 3
    private val ratioNames = arrayOf("4:3", "16:9", "1:1", "Penuh")
    private lateinit var thumbView: ImageView
    private lateinit var focusRing: View
    private var camera: Camera? = null
    private var lens = CameraSelector.LENS_FACING_BACK
    private var flashMode = ImageCapture.FLASH_MODE_OFF
    private var saveMp4 = true
    private var pinched = false

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) startCamera() else { toast("Izin kamera diperlukan"); finish() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
    private fun oval(fill: Int, stroke: Int = 0, sw: Int = 0) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(fill); if (sw > 0) setStroke(sw, stroke)
    }
    private fun chip(t: String) = TextView(this).apply {
        text = t; setTextColor(Color.WHITE); textSize = 14f; gravity = Gravity.CENTER
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(0x66000000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        stillFile = File(cacheDir, "still.jpg")
        val prefs = getSharedPreferences("p", 0)
        saveMp4 = prefs.getBoolean("mp4", true)
        ratioIdx = prefs.getInt("ratio", 3)
        hd = prefs.getBoolean("hd", true)
        stab = prefs.getBoolean("stab", true)

        previewView = PreviewView(this)
        liveBadge = chip("● LIVE").apply { setTextColor(yellow) }
        flashBtn = chip("⚡ Mati").apply { setOnClickListener { cycleFlash() } }
        val flip = chip("🔄").apply {
            setOnClickListener {
                lens = if (lens == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
                startCamera()
            }
        }
        mp4Btn = chip("").apply {
            setOnClickListener { saveMp4 = !saveMp4; prefs.edit().putBoolean("mp4", saveMp4).apply(); updateMp4() }
        }
        ratioBtn = chip("").apply {
            setOnClickListener { ratioIdx = (ratioIdx + 1) % 4; prefs.edit().putInt("ratio", ratioIdx).apply(); updateRatio(); startCamera() }
        }
        hdBtn = chip("").apply { setOnClickListener { hd = !hd; prefs.edit().putBoolean("hd", hd).apply(); updateChips(); startCamera() } }
        stabBtn = chip("").apply { setOnClickListener { stab = !stab; prefs.edit().putBoolean("stab", stab).apply(); updateChips(); startCamera() } }
        nightBtn = chip("").apply { setOnClickListener { night = !night; updateChips(); startCamera() } }
        updateMp4(); updateRatio(); updateChips()
        thumbView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = oval(0x44FFFFFF, Color.WHITE, dp(2))
            outlineProvider = ViewOutlineProvider.BACKGROUND; clipToOutline = true
            setOnClickListener { startActivity(Intent(this@MainActivity, ViewerActivity::class.java)) }
        }
        val ringV = View(this).apply { background = oval(0, Color.WHITE, dp(4)) }
        inner = View(this).apply { background = oval(Color.WHITE) }
        shutter = FrameLayout(this).apply {
            addView(ringV, FrameLayout.LayoutParams(dp(78), dp(78), Gravity.CENTER))
            addView(inner, FrameLayout.LayoutParams(dp(62), dp(62), Gravity.CENTER))
            setOnClickListener { capture() }
        }
        focusRing = View(this).apply { background = oval(0, yellow, dp(2)); alpha = 0f }

        val top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(flashBtn)
            addView(ratioBtn, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
            addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(liveBadge)
            addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(flip)
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(previewView, FrameLayout.LayoutParams(-1, -1))
            addView(focusRing, FrameLayout.LayoutParams(dp(70), dp(70)))
            addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = dp(40); marginStart = dp(16); marginEnd = dp(16) })
            addView(LinearLayout(this@MainActivity).apply {
                gravity = Gravity.CENTER
                addView(hdBtn)
                addView(stabBtn, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
                addView(nightBtn, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
            }, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = dp(90) })
            addView(thumbView, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM or Gravity.START).apply { marginStart = dp(32); bottomMargin = dp(55) })
            addView(shutter, FrameLayout.LayoutParams(dp(78), dp(78), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(44) })
            addView(mp4Btn, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { marginEnd = dp(24); bottomMargin = dp(60) })
        }
        setContentView(root)

        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val c = camera ?: return true
                pinched = true
                c.cameraControl.setZoomRatio((c.cameraInfo.zoomState.value?.zoomRatio ?: 1f) * d.scaleFactor)
                return true
            }
        })
        previewView.setOnTouchListener { v, e ->
            scale.onTouchEvent(e)
            if (e.action == MotionEvent.ACTION_DOWN) pinched = false
            if (e.action == MotionEvent.ACTION_UP && !pinched) { focusAt(e.x, e.y); v.performClick() }
            true
        }

        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else permLauncher.launch(Manifest.permission.CAMERA)
    }

    override fun onResume() { super.onResume(); refreshThumb() }

    private fun refreshThumb() = ioExec.execute {
        val u = listLive(contentResolver).firstOrNull()
        val bm = if (u != null) thumb(contentResolver, u) else null
        runOnUiThread { if (bm != null) thumbView.setImageBitmap(bm) }
    }

    private fun setOn(tv: TextView, on: Boolean, label: String) { tv.text = label; tv.setTextColor(if (on) yellow else Color.WHITE) }
    private fun updateChips() {
        setOn(hdBtn, hd, if (hd) "HD: ON" else "HD: OFF")
        setOn(stabBtn, stab, if (stab) "Stabil: ON" else "Stabil: OFF")
        setOn(nightBtn, night, if (night) "🌙 Malam: ON" else "🌙 Malam: OFF")
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    @kotlin.OptIn(ExperimentalCamera2Interop::class)
    private fun <T> tune(b: ExtendableBuilder<T>, info: CameraInfo) {
        val ci = Camera2CameraInfo.from(info)
        val ext = Camera2Interop.Extender(b)
        fun has(k: CameraCharacteristics.Key<IntArray>, v: Int) = ci.getCameraCharacteristic(k)?.contains(v) == true
        if (has(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY))
            ext.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
        if (has(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES, CaptureRequest.EDGE_MODE_HIGH_QUALITY))
            ext.setCaptureRequestOption(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
        if (stab && has(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON))
            ext.setCaptureRequestOption(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
        if (night) {
            val r = ci.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            val pick = r?.filter { it.lower in 8..15 }?.minByOrNull { it.lower } ?: r?.minByOrNull { it.lower }
            if (pick != null) ext.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, pick)
        }
    }

    private fun updateRatio() { ratioBtn.text = "▭ " + ratioNames[ratioIdx] }

    private fun updateMp4() { mp4Btn.text = if (saveMp4) "🎞 MP4: ON" else "🎞 MP4: OFF" }

    private fun cycleFlash() {
        flashMode = when (flashMode) {
            ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
            ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
            else -> ImageCapture.FLASH_MODE_OFF
        }
        flashBtn.text = when (flashMode) { ImageCapture.FLASH_MODE_AUTO -> "⚡ Auto"; ImageCapture.FLASH_MODE_ON -> "⚡ Nyala"; else -> "⚡ Mati" }
        imageCapture.flashMode = flashMode
    }

    private fun focusAt(x: Float, y: Float) {
        val pt = previewView.meteringPointFactory.createPoint(x, y)
        camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(pt).build())
        focusRing.animate().cancel()
        focusRing.x = x - dp(35); focusRing.y = y - dp(35); focusRing.alpha = 1f
        focusRing.animate().alpha(0f).setStartDelay(600).setDuration(300).start()
    }

    private fun badge(t: String, c: Int) = runOnUiThread { liveBadge.text = t; liveBadge.setTextColor(c) }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val sel = CameraSelector.Builder().requireLensFacing(lens).build()
            if (!provider.hasCamera(sel)) { toast("Kamera tidak tersedia"); return@addListener }
            val info = sel.filter(provider.availableCameraInfos).firstOrNull()
            val wide = ratioIdx == 1 || ratioIdx == 3
            val strat = if (wide) AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY else AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
            val rs = ResolutionSelector.Builder().setAspectRatioStrategy(strat).build()
            val sz = if (wide) (if (hd) Size(1280, 720) else Size(640, 360)) else (if (hd) Size(960, 720) else Size(640, 480))
            val rsAnalysis = ResolutionSelector.Builder().setAspectRatioStrategy(strat)
                .setResolutionStrategy(ResolutionStrategy(sz, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build()
            val capSize = if (wide) Size(3840, 2160) else Size(3264, 2448)
            val rsCap = ResolutionSelector.Builder().setAspectRatioStrategy(strat)
                .setResolutionStrategy(ResolutionStrategy(capSize, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build()
            val pb = Preview.Builder().setResolutionSelector(rs)
            val icb = ImageCapture.Builder().setResolutionSelector(if (hd || night) rsCap else rs)
                .setCaptureMode(if (hd || night) ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY else ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setFlashMode(flashMode)
            val ab = ImageAnalysis.Builder().setResolutionSelector(rsAnalysis).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            if (info != null) { tune(pb, info); tune(icb, info); tune(ab, info) }
            val preview = pb.build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            imageCapture = icb.build()
            val analysis = ab.build().also { it.setAnalyzer(camExec, ::onFrame) }
            val pw = if (previewView.width > 0) previewView.width else resources.displayMetrics.widthPixels
            val ph = if (previewView.height > 0) previewView.height else resources.displayMetrics.heightPixels
            val ratio = when (ratioIdx) { 0 -> Rational(3, 4); 1 -> Rational(9, 16); 2 -> Rational(1, 1); else -> Rational(pw, ph) }
            previewView.scaleType = if (ratioIdx == 3) PreviewView.ScaleType.FILL_CENTER else PreviewView.ScaleType.FIT_CENTER
            val vp = ViewPort.Builder(ratio, previewView.display?.rotation ?: Surface.ROTATION_0).setScaleType(ViewPort.FILL_CENTER).build()
            val group = UseCaseGroup.Builder().setViewPort(vp).addUseCase(preview).addUseCase(imageCapture).addUseCase(analysis).build()
            synchronized(lock) { ring.clear() }
            provider.unbindAll()
            camera = provider.bindToLifecycle(this, sel, group)
            camera?.let { c ->
                val er = c.cameraInfo.exposureState.exposureCompensationRange
                c.cameraControl.setExposureCompensationIndex(if (night && er.upper > 0) (er.upper * 2 + 2) / 3 else 0)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onFrame(img: ImageProxy) {
        val f = toFrame(img)
        rotation = img.imageInfo.rotationDegrees
        img.close()
        synchronized(lock) {
            ring.addLast(f)
            while (ring.size > 1 && f.tsNs - ring.first().tsNs > windowNs) ring.removeFirst()
            val p = post ?: return
            if (p.isEmpty()) postEndNs = f.tsNs + windowNs
            p.add(f)
            if (f.tsNs >= postEndNs) { vidFrames = pre + p; maybeFinish() }
        }
    }

    private fun capture() {
        synchronized(lock) {
            if (post != null || ring.isEmpty()) return
            pre = ring.toList(); post = ArrayList(); stillOk = false; vidFrames = null
        }
        shutter.isEnabled = false
        inner.animate().scaleX(0.82f).scaleY(0.82f).setDuration(90).withEndAction { inner.animate().scaleX(1f).scaleY(1f).setDuration(90).start() }.start()
        badge("● TAHAN…", Color.parseColor("#FF453A"))
        if (night || hd) {
            badge(if (night) "● TAHAN 6 DETIK" else "● TAHAN 2 DETIK", Color.parseColor("#FF453A"))
            nightExec.execute {
                val ok = try { stackShots(if (night) 6 else 3, night, stillFile) } catch (e: Throwable) { false }
                if (ok) synchronized(lock) { stillOk = true; maybeFinish() }
                else { synchronized(lock) { post = null }; reset("Gagal memproses foto") }
            }
        } else
        imageCapture.takePicture(ImageCapture.OutputFileOptions.Builder(stillFile).build(), camExec,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(r: ImageCapture.OutputFileResults) { synchronized(lock) { stillOk = true; maybeFinish() } }
                override fun onError(e: ImageCaptureException) { synchronized(lock) { post = null }; reset("Gagal memotret") }
            })
    }

    private fun maybeFinish() {
        val frames = vidFrames ?: return
        if (!stillOk) return
        val shutterTs = frames[pre.size].tsNs - frames[0].tsNs
        val rot = rotation
        vidFrames = null; post = null
        badge("● MENYIMPAN…", yellow)
        ioExec.execute {
            try {
                val mp4 = File(cacheDir, "clip.mp4")
                encode(frames, mp4, rot, if (stab) stabPlan(frames) else null, hd)
                val ts = System.currentTimeMillis()
                saveMotionPhoto(stillFile, mp4, shutterTs / 1000, ts)
                if (saveMp4) saveVideo(mp4, ts)
                refreshThumb()
                reset("Tersimpan di Pictures/LiveCam")
            } catch (e: Exception) { reset("Gagal: ${e.message}") }
        }
    }

    private fun reset(msg: String) = runOnUiThread { toast(msg); shutter.isEnabled = true; liveBadge.text = "● LIVE"; liveBadge.setTextColor(yellow) }

    private fun toFrame(img: ImageProxy): Frame {
        val cr = img.cropRect
        val l = cr.left and 1.inv(); val t = cr.top and 1.inv()
        val w = cr.width() and 15.inv(); val h = cr.height() and 15.inv()
        val out = ByteArray(w * h * 3 / 2); var p = 0
        val y = img.planes[0]; val u = img.planes[1]; val v = img.planes[2]
        if (y.pixelStride == 1 && u.pixelStride == 2 && v.pixelStride == 2) {
            val yb = y.buffer.duplicate(); val ub = u.buffer.duplicate(); val vb = v.buffer.duplicate()
            for (r in t until t + h) { yb.position(r * y.rowStride + l); yb.get(out, p, w); p += w }
            for (r in t / 2 until (t + h) / 2) {
                val off = r * u.rowStride + l
                val n = minOf(w, ub.limit() - off)
                ub.position(off); ub.get(out, p, n)
                if (n < w) out[p + w - 1] = vb.get(r * v.rowStride + l + w - 2)
                p += w
            }
        } else {
            for (r in t until t + h) { val b = r * y.rowStride; for (c in l until l + w) out[p++] = y.buffer.get(b + c * y.pixelStride) }
            for (r in t / 2 until (t + h) / 2) for (c in l / 2 until (l + w) / 2) {
                out[p++] = u.buffer.get(r * u.rowStride + c * u.pixelStride)
                out[p++] = v.buffer.get(r * v.rowStride + c * v.pixelStride)
            }
        }
        return Frame(out, w, h, img.imageInfo.timestamp)
    }

    private fun pad(raw: ByteArray, w: Int, h: Int, stride: Int, sh: Int): ByteArray {
        val o = ByteArray(stride * sh * 3 / 2)
        for (r in 0 until h) System.arraycopy(raw, r * w, o, r * stride, w)
        val src = w * h; val dst = stride * sh
        for (r in 0 until h / 2) System.arraycopy(raw, src + r * w, o, dst + r * stride, w)
        return o
    }

    private fun cropNv12(f: Frame, ox: Int, oy: Int, cw: Int, ch: Int): ByteArray {
        val out = ByteArray(cw * ch * 3 / 2)
        for (r in 0 until ch) System.arraycopy(f.nv12, (oy + r) * f.w + ox, out, r * cw, cw)
        val src = f.w * f.h; val dst = cw * ch
        for (r in 0 until ch / 2) System.arraycopy(f.nv12, src + (oy / 2 + r) * f.w + ox, out, dst + r * cw, cw)
        return out
    }

    // ---- stabilizer digital: estimasi gerak sub-piksel, jalur dihaluskan, jendela potong digeser ----
    private fun sad(a: IntArray, b: IntArray, sw: Int, sh: Int, dx: Int, dy: Int, step: Int, margin: Int): Long {
        var s = 0L
        var y = margin
        while (y < sh - margin) {
            var x = margin
            while (x < sw - margin) { s += Math.abs(a[y * sw + x] - b[(y + dy) * sw + x + dx]); x += step }
            y += step
        }
        return s
    }

    private fun yDown(f: Frame, k: Int): IntArray {
        val sw = f.w / k; val sh = f.h / k; val o = IntArray(sw * sh)
        val st = if (k > 2) 2 else 1
        val cnt = (k / st) * (k / st)
        for (y in 0 until sh) for (x in 0 until sw) {
            var s = 0; var j = 0
            while (j < k) { var i = 0; while (i < k) { s += f.nv12[(y * k + j) * f.w + x * k + i].toInt() and 255; i += st }; j += st }
            o[y * sw + x] = s / cnt
        }
        return o
    }

    private fun parab(a: Double, b: Double, c: Double): Double {
        val d = a - 2 * b + c
        return if (d <= 0.0) 0.0 else ((a - c) / (2 * d)).coerceIn(-0.5, 0.5)
    }

    private fun stabPlan(fr: List<Frame>): Stab? {
        val w = fr[0].w; val h = fr[0].h
        if (fr.size < 5 || fr.any { it.w != w || it.h != h }) return null
        val n = fr.size
        val mx = (w * 0.08).toInt(); val my = (h * 0.08).toInt()
        val cw = (w - 2 * mx) and 15.inv(); val ch = (h - 2 * my) and 15.inv()
        val c8 = fr.map { yDown(it, 8) }; val c2 = fr.map { yDown(it, 2) }
        val w8 = w / 8; val h8 = h / 8; val w2 = w / 2; val h2 = h / 2
        val px = DoubleArray(n); val py = DoubleArray(n)
        for (i in 1 until n) {
            var best = Long.MAX_VALUE; var bx = 0; var by = 0
            for (dy in -4..4) for (dx in -4..4) {
                val s = sad(c8[i - 1], c8[i], w8, h8, dx, dy, 1, 5)
                if (s < best) { best = s; bx = dx; by = dy }
            }
            val cx = bx * 4; val cy = by * 4
            var bs = Long.MAX_VALUE; var rx = cx; var ry = cy
            for (dy in -3..3) for (dx in -3..3) {
                val s = sad(c2[i - 1], c2[i], w2, h2, cx + dx, cy + dy, 2, 21)
                if (s < bs) { bs = s; rx = cx + dx; ry = cy + dy }
            }
            val s0 = bs.toDouble()
            val fx = parab(sad(c2[i - 1], c2[i], w2, h2, rx - 1, ry, 2, 21).toDouble(), s0, sad(c2[i - 1], c2[i], w2, h2, rx + 1, ry, 2, 21).toDouble())
            val fy = parab(sad(c2[i - 1], c2[i], w2, h2, rx, ry - 1, 2, 21).toDouble(), s0, sad(c2[i - 1], c2[i], w2, h2, rx, ry + 1, 2, 21).toDouble())
            px[i] = px[i - 1] + (rx + fx) * 2; py[i] = py[i - 1] + (ry + fy) * 2
        }
        val rad = 10
        val ox = IntArray(n); val oy = IntArray(n)
        for (i in 0 until n) {
            var a = 0.0; var b = 0.0; var ws = 0.0
            for (k in maxOf(0, i - rad)..minOf(n - 1, i + rad)) {
                val wt = (rad + 1 - Math.abs(k - i)).toDouble()
                a += px[k] * wt; b += py[k] * wt; ws += wt
            }
            val dx = (px[i] - a / ws).toInt().coerceIn(-mx, mx)
            val dy = (py[i] - b / ws).toInt().coerceIn(-my, my)
            ox[i] = (mx + dx).coerceIn(0, w - cw) and 1.inv()
            oy[i] = (my + dy).coerceIn(0, h - ch) and 1.inv()
        }
        return Stab(cw, ch, ox, oy)
    }

    // ---- foto multi-frame: pilih jepretan tersharp, sejajarkan, rata-ratakan, lalu pertajam ----
    class Shot(val jpeg: ByteArray, val rot: Int, val cl: Int, val ct: Int, val cw: Int, val ch: Int)

    private fun takeShot(): Shot? {
        val latch = CountDownLatch(1)
        var shot: Shot? = null
        imageCapture.takePicture(capExec, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(img: ImageProxy) {
                try {
                    val b = img.planes[0].buffer
                    val a = ByteArray(b.remaining()); b.get(a)
                    val cr = img.cropRect
                    shot = Shot(a, img.imageInfo.rotationDegrees, cr.left, cr.top, cr.width(), cr.height())
                } catch (e: Exception) {
                } finally { img.close(); latch.countDown() }
            }
            override fun onError(e: ImageCaptureException) { latch.countDown() }
        })
        latch.await(20, TimeUnit.SECONDS)
        return shot
    }

    private fun decodeShot(s: Shot): Bitmap? {
        val bo = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(s.jpeg, 0, s.jpeg.size, bo)
        var sample = 1
        while ((bo.outWidth / sample) * (bo.outHeight / sample) > 9_000_000) sample *= 2
        val full = BitmapFactory.decodeByteArray(s.jpeg, 0, s.jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val l = (s.cl / sample).coerceIn(0, full.width - 2); val t = (s.ct / sample).coerceIn(0, full.height - 2)
        val cw = minOf(s.cw / sample, full.width - l); val ch = minOf(s.ch / sample, full.height - t)
        val m = Matrix().apply { postRotate(s.rot.toFloat()) }
        val r = Bitmap.createBitmap(full, l, t, cw, ch, m, true)
        if (r !== full) full.recycle()
        return r
    }

    private fun luma(px: IntArray, w: Int, h: Int, f: Int): IntArray {
        val sw = w / f; val sh = h / f; val o = IntArray(sw * sh)
        val st = if (f > 2) 2 else 1
        val cnt = (f / st) * (f / st)
        for (y in 0 until sh) for (x in 0 until sw) {
            var s = 0; var j = 0
            while (j < f) { var i = 0; while (i < f) { val p = px[(y * f + j) * w + x * f + i]; s += (((p shr 16) and 255) + ((p shr 8) and 255) * 2 + (p and 255)) shr 2; i += st }; j += st }
            o[y * sw + x] = s / cnt
        }
        return o
    }

    private fun sharpness(l: IntArray, w: Int, h: Int): Double {
        var s = 0L; var n = 0; var y = 1
        while (y < h - 1) {
            var x = 1
            while (x < w - 1) { val i = y * w + x; s += Math.abs(4 * l[i] - l[i - 1] - l[i + 1] - l[i - w] - l[i + w]); n++; x += 2 }
            y += 2
        }
        return s.toDouble() / maxOf(1, n)
    }

    private fun align(r8: IntArray, r2: IntArray, f8: IntArray, f2: IntArray, w: Int, h: Int): Pair<Int, Int> {
        var best = Long.MAX_VALUE; var bx = 0; var by = 0
        for (dy in -8..8) for (dx in -8..8) {
            val s = sad(r8, f8, w / 8, h / 8, dx, dy, 2, 9)
            if (s < best) { best = s; bx = dx; by = dy }
        }
        best = Long.MAX_VALUE
        val cx = bx * 4; val cy = by * 4
        var rx = cx; var ry = cy
        for (dy in -4..4) for (dx in -4..4) {
            val s = sad(r2, f2, w / 2, h / 2, cx + dx, cy + dy, 4, 40)
            if (s < best) { best = s; rx = cx + dx; ry = cy + dy }
        }
        return Pair(rx * 2, ry * 2)
    }

    private fun refineFull(ref: IntArray, px: IntArray, w: Int, h: Int, dx: Int, dy: Int): Pair<Int, Int> {
        val half = minOf(300, w / 4, h / 4)
        val cx = w / 2; val cy = h / 2
        var best = Long.MAX_VALUE; var bx = dx; var by = dy
        for (ey in -2..2) for (ex in -2..2) {
            var s = 0L
            var y = cy - half
            while (y < cy + half) {
                var x = cx - half
                while (x < cx + half) {
                    val xx = x + dx + ex; val yy = y + dy + ey
                    if (xx >= 0 && xx < w && yy >= 0 && yy < h) s += Math.abs(((ref[y * w + x] shr 8) and 255) - ((px[yy * w + xx] shr 8) and 255))
                    x += 2
                }
                y += 2
            }
            if (s < best) { best = s; bx = dx + ex; by = dy + ey }
        }
        return Pair(bx, by)
    }

    private fun accumulate(px: IntArray, w: Int, h: Int, dx: Int, dy: Int, sr: ShortArray, sg: ShortArray, sb: ShortArray, cnt: ByteArray) {
        for (y in 0 until h) {
            val fy = y + dy
            if (fy < 0 || fy >= h) continue
            for (x in 0 until w) {
                val fx = x + dx
                if (fx < 0 || fx >= w) continue
                val p = px[fy * w + fx]; val i = y * w + x
                sr[i] = (sr[i] + ((p shr 16) and 255)).toShort()
                sg[i] = (sg[i] + ((p shr 8) and 255)).toShort()
                sb[i] = (sb[i] + (p and 255)).toShort()
                cnt[i]++
            }
        }
    }

    private fun boxBlur(src: IntArray, w: Int, h: Int, r: Int): IntArray {
        val tmp = IntArray(w * h); val out = IntArray(w * h); val d = 2 * r + 1
        for (y in 0 until h) {
            val b = y * w; var s = 0
            for (x in -r..r) s += src[b + x.coerceIn(0, w - 1)]
            for (x in 0 until w) { tmp[b + x] = s / d; s += src[b + minOf(w - 1, x + r + 1)] - src[b + maxOf(0, x - r)] }
        }
        for (x in 0 until w) {
            var s = 0
            for (y in -r..r) s += tmp[y.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) { out[y * w + x] = s / d; s += tmp[minOf(h - 1, y + r + 1) * w + x] - tmp[maxOf(0, y - r) * w + x] }
        }
        return out
    }

    // kurangi kabut (auto-levels), kontras ringan, warna, lalu pertajam (unsharp mask dengan coring)
    private fun enhance(p: IntArray, w: Int, h: Int, night: Boolean) {
        val n = w * h
        val hist = IntArray(256)
        var i = 0
        while (i < n) { val c = p[i]; hist[(((c shr 16) and 255) * 77 + ((c shr 8) and 255) * 150 + (c and 255) * 29) shr 8]++; i += 3 }
        val total = hist.sum()
        var acc = 0; var lo = 0; var hi = 255
        for (v in 0..255) { acc += hist[v]; if (acc >= total * 0.003) { lo = v; break } }
        acc = 0
        for (v in 255 downTo 0) { acc += hist[v]; if (acc >= total * 0.003) { hi = v; break } }
        val lo2 = (lo * 0.6).toInt()
        val hi2 = (hi + (255 - hi) * 0.25).toInt().coerceAtLeast(lo2 + 160)
        val amount = if (night) 0.1 else 0.25
        val gamma = if (night) 0.8 else 1.0
        val lut = IntArray(256) { v ->
            var f = ((v - lo2).toDouble() / (hi2 - lo2)).coerceIn(0.0, 1.0)
            f = Math.pow(f, gamma)
            f = f * (1 - amount) + f * f * (3 - 2 * f) * amount
            (f * 255).toInt().coerceIn(0, 255)
        }
        val sat = if (night) 1.05 else 1.12
        val lum = IntArray(n)
        i = 0
        while (i < n) {
            val c = p[i]
            var r = lut[(c shr 16) and 255]; var g = lut[(c shr 8) and 255]; var b = lut[c and 255]
            val gr = (r * 77 + g * 150 + b * 29) shr 8
            r = (gr + (r - gr) * sat).toInt().coerceIn(0, 255)
            g = (gr + (g - gr) * sat).toInt().coerceIn(0, 255)
            b = (gr + (b - gr) * sat).toInt().coerceIn(0, 255)
            p[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            lum[i] = (r * 77 + g * 150 + b * 29) shr 8
            i++
        }
        val blur = boxBlur(lum, w, h, 2)
        val amt = if (night) 0.45 else 0.8
        i = 0
        while (i < n) {
            var d = lum[i] - blur[i]
            d = if (d > 2) d - 2 else if (d < -2) d + 2 else 0
            val add = (d * amt).toInt()
            if (add != 0) {
                val c = p[i]
                val r = (((c shr 16) and 255) + add).coerceIn(0, 255)
                val g = (((c shr 8) and 255) + add).coerceIn(0, 255)
                val b = ((c and 255) + add).coerceIn(0, 255)
                p[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            i++
        }
    }

    private fun stackShots(n: Int, night: Boolean, out: File): Boolean {
        val shots = ArrayList<Shot>()
        for (k in 0 until n) takeShot()?.let { shots.add(it) }
        if (shots.isEmpty()) return false
        badge("● MEMPROSES…", yellow)
        var w = 0; var h = 0
        val usable = ArrayList<Shot>(); val l8 = ArrayList<IntArray>(); val l2 = ArrayList<IntArray>(); val sc = ArrayList<Double>()
        for (s in shots) {
            val bm = decodeShot(s) ?: continue
            if (w == 0) { w = bm.width; h = bm.height }
            if (bm.width != w || bm.height != h) { bm.recycle(); continue }
            val px = IntArray(w * h); bm.getPixels(px, 0, w, 0, 0, w, h); bm.recycle()
            val a2 = luma(px, w, h, 2)
            l2.add(a2); l8.add(luma(px, w, h, 8)); sc.add(sharpness(a2, w / 2, h / 2)); usable.add(s)
        }
        if (usable.isEmpty()) return false
        val refI = sc.indices.maxByOrNull { sc[it] }!!
        val order = listOf(refI) + usable.indices.filter { it != refI && sc[it] >= sc[refI] * 0.7 }
        val sr = ShortArray(w * h); val sg = ShortArray(w * h); val sb = ShortArray(w * h); val cnt = ByteArray(w * h)
        var refPx: IntArray? = null
        for (idx in order) {
            val bm = decodeShot(usable[idx]) ?: continue
            val px = IntArray(w * h); bm.getPixels(px, 0, w, 0, 0, w, h); bm.recycle()
            var dx = 0; var dy = 0
            if (idx == refI) refPx = px
            else {
                val a = align(l8[refI], l2[refI], l8[idx], l2[idx], w, h)
                val f = refineFull(refPx!!, px, w, h, a.first, a.second)
                dx = f.first; dy = f.second
            }
            accumulate(px, w, h, dx, dy, sr, sg, sb, cnt)
        }
        refPx = null
        val o = IntArray(w * h)
        for (i in o.indices) {
            val c = maxOf(1, cnt[i].toInt())
            o[i] = (0xFF shl 24) or ((sr[i] / c) shl 16) or ((sg[i] / c) shl 8) or (sb[i] / c)
        }
        enhance(o, w, h, night)
        val res = Bitmap.createBitmap(o, w, h, Bitmap.Config.ARGB_8888)
        out.outputStream().use { res.compress(Bitmap.CompressFormat.JPEG, 97, it) }
        return true
    }

    private fun encode(frames: List<Frame>, out: File, rot: Int, st: Stab?, hdq: Boolean) {
        val cw = st?.cw ?: frames[0].w; val ch = st?.ch ?: frames[0].h
        val fmt = MediaFormat.createVideoFormat("video/avc", cw, ch).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
            setInteger(MediaFormat.KEY_BIT_RATE, if (hdq) 8_000_000 else 4_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType("video/avc")
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start()
        val inF = codec.inputFormat
        val stride = if (inF.containsKey(MediaFormat.KEY_STRIDE)) inF.getInteger(MediaFormat.KEY_STRIDE) else cw
        val sh = if (inF.containsKey(MediaFormat.KEY_SLICE_HEIGHT)) inF.getInteger(MediaFormat.KEY_SLICE_HEIGHT) else ch
        out.delete()
        val mux = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply { setOrientationHint(rot) }
        var track = -1; var started = false
        val info = MediaCodec.BufferInfo()
        fun drain(end: Boolean) {
            while (true) {
                val i = codec.dequeueOutputBuffer(info, if (end) 10_000 else 0)
                when {
                    i == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!end) return
                    i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = mux.addTrack(codec.outputFormat); mux.start(); started = true }
                    i >= 0 -> {
                        val b = codec.getOutputBuffer(i)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && started) mux.writeSampleData(track, b, info)
                        codec.releaseOutputBuffer(i, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }
        fun nextInput(): Int { var i: Int; while (codec.dequeueInputBuffer(10_000).also { i = it } < 0) drain(false); return i }
        val t0 = frames[0].tsNs
        for ((n, f) in frames.withIndex()) {
            val i = nextInput()
            val raw = if (st != null) cropNv12(f, st.ox[n], st.oy[n], cw, ch) else f.nv12
            val data = if (stride == cw && sh == ch) raw else pad(raw, cw, ch, stride, sh)
            codec.getInputBuffer(i)!!.apply { clear(); put(data) }
            codec.queueInputBuffer(i, 0, data.size, (f.tsNs - t0) / 1000, 0)
            drain(false)
        }
        codec.queueInputBuffer(nextInput(), 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        drain(true)
        codec.stop(); codec.release(); mux.stop(); mux.release()
    }

    private fun saveMotionPhoto(jpg: File, mp4: File, ptsUs: Long, ts: Long): Uri {
        val j = jpg.readBytes(); val v = mp4.readBytes()
        var pos = 2
        while (pos + 4 < j.size && j[pos] == 0xFF.toByte() && (j[pos + 1] == 0xE0.toByte() || j[pos + 1] == 0xE1.toByte()))
            pos += 2 + (((j[pos + 2].toInt() and 0xFF) shl 8) or (j[pos + 3].toInt() and 0xFF))
        val xmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description rdf:about="" xmlns:GCamera="http://ns.google.com/photos/1.0/camera/" xmlns:Container="http://ns.google.com/photos/1.0/container/" xmlns:Item="http://ns.google.com/photos/1.0/container/item/" GCamera:MotionPhoto="1" GCamera:MotionPhotoVersion="1" GCamera:MotionPhotoPresentationTimestampUs="$ptsUs"><Container:Directory><rdf:Seq><rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Length="0" Item:Padding="0"/></rdf:li><rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="${v.size}" Item:Padding="0"/></rdf:li></rdf:Seq></Container:Directory></rdf:Description></rdf:RDF></x:xmpmeta>"""
        val payload = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.ISO_8859_1) + xmp.toByteArray(Charsets.UTF_8)
        val len = payload.size + 2
        val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), len.toByte()) + payload
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "MVIMG_$ts.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/LiveCam")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)!!
        contentResolver.openOutputStream(uri)!!.use {
            it.write(j, 0, pos); it.write(app1); it.write(j, pos, j.size - pos); it.write(v)
        }
        return uri
    }

    private fun saveVideo(mp4: File, ts: Long) {
        val cv = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "LIVE_$ts.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/LiveCam")
        }
        val u = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv)!!
        contentResolver.openOutputStream(u)!!.use { out -> mp4.inputStream().use { it.copyTo(out) } }
    }
}
