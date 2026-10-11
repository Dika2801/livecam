package com.example.livecam

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.Executors

class Frame(val nv12: ByteArray, val w: Int, val h: Int, val tsNs: Long)

class MainActivity : ComponentActivity() {
    private val windowNs = 1_500_000_000L          // 1,5 detik sebelum & sesudah jepret
    private val lock = Any()
    private val ring = ArrayDeque<Frame>()
    private var pre: List<Frame> = emptyList()
    private var post: ArrayList<Frame>? = null     // != null berarti sedang menangkap
    private var postEndNs = 0L
    private var vidFrames: List<Frame>? = null
    private var stillOk = false
    @Volatile private var rotation = 0
    private val camExec = Executors.newSingleThreadExecutor()
    private val ioExec = Executors.newSingleThreadExecutor()
    private lateinit var imageCapture: ImageCapture
    private lateinit var shutter: Button
    private lateinit var stillFile: File

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) startCamera() else { toast("Izin kamera diperlukan"); finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val previewView = PreviewView(this)
        shutter = Button(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE); setStroke(12, Color.LTGRAY) }
            setOnClickListener { capture() }
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(previewView, FrameLayout.LayoutParams(-1, -1))
            addView(shutter, FrameLayout.LayoutParams(200, 200, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = 120 })
        }
        setContentView(root)
        stillFile = File(cacheDir, "still.jpg")
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else permLauncher.launch(Manifest.permission.CAMERA)
        this.previewView = previewView
    }
    private lateinit var previewView: PreviewView

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            imageCapture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(480, 640))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build().also { it.setAnalyzer(camExec, ::onFrame) }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    // ---- buffer frame terus-menerus (pre-roll) ----
    private fun onFrame(img: ImageProxy) {
        val f = Frame(toNv12(img), img.width, img.height, img.imageInfo.timestamp)
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
        imageCapture.takePicture(ImageCapture.OutputFileOptions.Builder(stillFile).build(), camExec,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(r: ImageCapture.OutputFileResults) { synchronized(lock) { stillOk = true; maybeFinish() } }
                override fun onError(e: ImageCaptureException) { synchronized(lock) { post = null }; reset("Gagal memotret") }
            })
    }

    // dipanggil di dalam lock
    private fun maybeFinish() {
        val frames = vidFrames ?: return
        if (!stillOk) return
        val shutterTs = frames[pre.size].tsNs - frames[0].tsNs
        val rot = rotation
        vidFrames = null; post = null
        ioExec.execute {
            try {
                val mp4 = File(cacheDir, "clip.mp4")
                encode(frames, mp4, rot)
                saveMotionPhoto(stillFile, mp4, shutterTs / 1000)
                reset("Live Photo tersimpan di Pictures/LiveCam")
            } catch (e: Exception) { reset("Gagal: ${e.message}") }
        }
    }

    private fun reset(msg: String) = runOnUiThread { toast(msg); shutter.isEnabled = true }
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    // ---- YUV_420_888 -> NV12 ----
    private fun toNv12(img: ImageProxy): ByteArray {
        val w = img.width; val h = img.height
        val out = ByteArray(w * h * 3 / 2); var p = 0
        val y = img.planes[0]; val u = img.planes[1]; val v = img.planes[2]
        for (r in 0 until h) { val b = r * y.rowStride; for (c in 0 until w) out[p++] = y.buffer.get(b + c * y.pixelStride) }
        for (r in 0 until h / 2) for (c in 0 until w / 2) {
            out[p++] = u.buffer.get(r * u.rowStride + c * u.pixelStride)
            out[p++] = v.buffer.get(r * v.rowStride + c * v.pixelStride)
        }
        return out
    }

    // ---- encode H.264 ke MP4 ----
    private fun encode(frames: List<Frame>, out: File, rot: Int) {
        val fmt = MediaFormat.createVideoFormat("video/avc", frames[0].w, frames[0].h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
            setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType("video/avc")
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start()
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
            codec.getInputBuffer(i)!!.apply { clear(); put(f.nv12) }
            codec.queueInputBuffer(i, 0, f.nv12.size, (f.tsNs - t0) / 1000, 0)
            drain(false)
        }
        codec.queueInputBuffer(nextInput(), 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        drain(true)
        codec.stop(); codec.release(); mux.stop(); mux.release()
    }

    // ---- gabungkan JPEG + MP4 jadi Google Motion Photo ----
    private fun saveMotionPhoto(jpg: File, mp4: File, ptsUs: Long) {
        val j = jpg.readBytes(); val v = mp4.readBytes()
        var pos = 2   // setelah SOI; lewati segmen EXIF bila ada
        if (j[2] == 0xFF.toByte() && j[3] == 0xE1.toByte())
            pos = 4 + (((j[4].toInt() and 0xFF) shl 8) or (j[5].toInt() and 0xFF))
        val xmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description rdf:about="" xmlns:GCamera="http://ns.google.com/photos/1.0/camera/" xmlns:Container="http://ns.google.com/photos/1.0/container/" xmlns:Item="http://ns.google.com/photos/1.0/container/item/" GCamera:MotionPhoto="1" GCamera:MotionPhotoVersion="1" GCamera:MotionPhotoPresentationTimestampUs="$ptsUs"><Container:Directory><rdf:Seq><rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Length="0" Item:Padding="0"/></rdf:li><rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="${v.size}" Item:Padding="0"/></rdf:li></rdf:Seq></Container:Directory></rdf:Description></rdf:RDF></x:xmpmeta>"""
        val payload = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.ISO_8859_1) + xmp.toByteArray(Charsets.UTF_8)
        val len = payload.size + 2
        val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), len.toByte()) + payload
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "MVIMG_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/LiveCam")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)!!
        contentResolver.openOutputStream(uri)!!.use {
            it.write(j, 0, pos); it.write(app1); it.write(j, pos, j.size - pos); it.write(v)
        }
    }
}
