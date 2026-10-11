package com.example.livecam

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
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
import androidx.camera.core.Camera
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
import java.util.concurrent.Executors

class Frame(val nv12: ByteArray, val w: Int, val h: Int, val tsNs: Long)

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
        updateMp4(); updateRatio()
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
            val wide = ratioIdx == 1 || ratioIdx == 3
            val strat = if (wide) AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY else AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
            val rs = ResolutionSelector.Builder().setAspectRatioStrategy(strat).build()
            val rsAnalysis = ResolutionSelector.Builder().setAspectRatioStrategy(strat)
                .setResolutionStrategy(ResolutionStrategy(if (wide) Size(640, 360) else Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build()
            val preview = Preview.Builder().setResolutionSelector(rs).build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            imageCapture = ImageCapture.Builder().setResolutionSelector(rs).setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).setFlashMode(flashMode).build()
            val analysis = ImageAnalysis.Builder().setResolutionSelector(rsAnalysis)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build().also { it.setAnalyzer(camExec, ::onFrame) }
            val pw = if (previewView.width > 0) previewView.width else resources.displayMetrics.widthPixels
            val ph = if (previewView.height > 0) previewView.height else resources.displayMetrics.heightPixels
            val ratio = when (ratioIdx) { 0 -> Rational(3, 4); 1 -> Rational(9, 16); 2 -> Rational(1, 1); else -> Rational(pw, ph) }
            previewView.scaleType = if (ratioIdx == 3) PreviewView.ScaleType.FILL_CENTER else PreviewView.ScaleType.FIT_CENTER
            val vp = ViewPort.Builder(ratio, previewView.display?.rotation ?: Surface.ROTATION_0).setScaleType(ViewPort.FILL_CENTER).build()
            val group = UseCaseGroup.Builder().setViewPort(vp).addUseCase(preview).addUseCase(imageCapture).addUseCase(analysis).build()
            synchronized(lock) { ring.clear() }
            provider.unbindAll()
            camera = provider.bindToLifecycle(this, sel, group)
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
                encode(frames, mp4, rot)
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
        for (r in t until t + h) { val b = r * y.rowStride; for (c in l until l + w) out[p++] = y.buffer.get(b + c * y.pixelStride) }
        for (r in t / 2 until (t + h) / 2) for (c in l / 2 until (l + w) / 2) {
            out[p++] = u.buffer.get(r * u.rowStride + c * u.pixelStride)
            out[p++] = v.buffer.get(r * v.rowStride + c * v.pixelStride)
        }
        return Frame(out, w, h, img.imageInfo.timestamp)
    }

    private fun pad(f: Frame, stride: Int, sh: Int): ByteArray {
        val o = ByteArray(stride * sh * 3 / 2)
        for (r in 0 until f.h) System.arraycopy(f.nv12, r * f.w, o, r * stride, f.w)
        val src = f.w * f.h; val dst = stride * sh
        for (r in 0 until f.h / 2) System.arraycopy(f.nv12, src + r * f.w, o, dst + r * stride, f.w)
        return o
    }

    private fun encode(frames: List<Frame>, out: File, rot: Int) {
        val fmt = MediaFormat.createVideoFormat("video/avc", frames[0].w, frames[0].h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
            setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType("video/avc")
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start()
        val inF = codec.inputFormat
        val stride = if (inF.containsKey(MediaFormat.KEY_STRIDE)) inF.getInteger(MediaFormat.KEY_STRIDE) else frames[0].w
        val sh = if (inF.containsKey(MediaFormat.KEY_SLICE_HEIGHT)) inF.getInteger(MediaFormat.KEY_SLICE_HEIGHT) else frames[0].h
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
        for (f in frames) {
            val i = nextInput()
            val data = if (stride == f.w && sh == f.h) f.nv12 else pad(f, stride, sh)
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
        if (j[2] == 0xFF.toByte() && j[3] == 0xE1.toByte())
            pos = 4 + (((j[4].toInt() and 0xFF) shl 8) or (j[5].toInt() and 0xFF))
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
