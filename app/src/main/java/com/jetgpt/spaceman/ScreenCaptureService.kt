package com.jetgpt.spaceman

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

class ScreenCaptureService : Service() {
    companion object {
        const val ACTION_START = "com.jetgpt.spaceman.START_CAPTURE"
        const val ACTION_STOP = "com.jetgpt.spaceman.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "projection_result_code"
        const val EXTRA_RESULT_DATA = "projection_result_data"
        const val PREF_STATUS = "capture_status"
        const val PREF_CAPTURED = "captured_rounds"
        private const val PREF_LAST_SNAPSHOT = "last_snapshot"
        private const val CHANNEL_ID = "jetgpt_capture_channel"
        private const val NOTIFICATION_ID = 1601
        private val MULTIPLIER = Regex("""(?i)(?<![0-9])([0-9]{1,4}(?:[.,][0-9]{1,3})?)\s*[x×]""")
    }

    private val prefs by lazy {
        getSharedPreferences("jetgpt_autocapture", Context.MODE_PRIVATE)
    }
    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    private val processing = AtomicBoolean(false)
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var worker: HandlerThread? = null
    private var workerHandler: Handler? = null
    private var lastOcrAt = 0L
    private var stopping = false
    private var pendingNewValues: List<Double> = emptyList()
    private var pendingNewCount = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopping = true
                setStatus("Pemantauan dihentikan.")
                releaseCapture()
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (projection != null) return START_STICKY
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                if (data == null) {
                    setStatus("Gagal: data izin layar tidak tersedia.")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startAsForeground()
                startProjection(resultCode, data)
            }
        }
        return START_STICKY
    }

    private fun startAsForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "JetGPT Auto Capture",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        val notification = builder
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("JetGPT sedang memantau layar")
            .setContentText("Buka CALO11 → INFO → STATS → HASIL TERAKHIR")
            .setOngoing(true)
            .setContentIntent(openApp)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startProjection(resultCode: Int, data: Intent) {
        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, data)
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi

            worker = HandlerThread("JetGPT-OCR").also { it.start() }
            workerHandler = Handler(worker!!.looper)
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            imageReader!!.setOnImageAvailableListener({ reader ->
                val now = System.currentTimeMillis()
                if (now - lastOcrAt < 1700L || !processing.compareAndSet(false, true)) {
                    reader.acquireLatestImage()?.close()
                    return@setOnImageAvailableListener
                }
                val image = reader.acquireLatestImage()
                if (image == null) {
                    processing.set(false)
                    return@setOnImageAvailableListener
                }
                lastOcrAt = now
                processImage(image)
            }, workerHandler)

            projection!!.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    if (!stopping) setStatus("Berbagi layar dihentikan oleh Android.")
                    stopSelf()
                }
            }, workerHandler)

            virtualDisplay = projection!!.createVirtualDisplay(
                "JetGPT-Spaceman-OCR",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface,
                null,
                workerHandler
            )
            setStatus("Aktif. Buka CALO11 → INFO → STATS → HASIL TERAKHIR.")
        } catch (e: Exception) {
            setStatus("Gagal memulai OCR: " + (e.message ?: "kesalahan sistem"))
            stopping = true
            releaseCapture()
            stopForegroundCompat()
            stopSelf()
        }
    }

    private fun processImage(image: Image) {
        val bitmap = try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width
            val paddedWidth = image.width + rowPadding / pixelStride
            val full = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
            buffer.rewind()
            full.copyPixelsFromBuffer(buffer)
            val cropped = Bitmap.createBitmap(full, 0, 0, image.width, image.height)
            full.recycle()
            cropped
        } catch (_: Exception) {
            image.close()
            processing.set(false)
            return
        }
        image.close()

        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result -> inspectText(result, bitmap.width, bitmap.height) }
            .addOnFailureListener { setStatus("OCR gagal membaca layar. Pastikan panel tidak buram.") }
            .addOnCompleteListener {
                bitmap.recycle()
                processing.set(false)
            }
    }

    private fun inspectText(result: com.google.mlkit.vision.text.Text, screenWidth: Int, screenHeight: Int) {
        val lines = result.textBlocks.flatMap { it.lines }
        val heading = lines.firstOrNull {
            it.text.replace("İ", "I").contains("HASIL TERAKHIR", ignoreCase = true)
        }
        if (heading == null) {
            setStatus("Layar belum menampilkan panel CALO11. Pindah ke INFO → STATS → HASIL TERAKHIR.")
            return
        }

        val headingBottom = heading.boundingBox?.bottom ?: (screenHeight * 0.22).toInt()
        val located = mutableListOf<Triple<Int, Int, Double>>()
        for (line in lines) {
            val box = line.boundingBox ?: continue
            if (box.centerY() <= headingBottom || box.centerY() >= screenHeight * 0.90) continue
            MULTIPLIER.findAll(line.text).forEach { match ->
                val value = parseMultiplier(match.groupValues[1])
                if (value != null && value >= 1.0 && value <= 5000.0) {
                    located.add(Triple(box.centerY(), box.centerX(), value))
                }
            }
        }

        // CALO11 displays results in a multi-column grid. Group by visual row,
        // then read each row from left to right so OCR box jitter doesn't scramble order.
        val rowTolerance = maxOf(12, (screenHeight * 0.02).toInt())
        val rows = mutableListOf<MutableList<Triple<Int, Int, Double>>>()
        for (item in located.sortedBy { it.first }) {
            val lastRow = rows.lastOrNull()
            val averageY = lastRow?.map { it.first }?.average()
            if (lastRow != null && averageY != null &&
                kotlin.math.abs(item.first - averageY) <= rowTolerance
            ) {
                lastRow.add(item)
            } else {
                rows.add(mutableListOf(item))
            }
        }
        val values = rows.flatMap { row ->
            row.sortedBy { it.second }.map { it.third }
        }.take(60)

        if (values.size < 8) {
            pendingNewValues = emptyList()
            pendingNewCount = 0
            setStatus("Panel ditemukan, tetapi baru " + values.size + " multiplier terbaca. Tampilkan daftar lebih jelas.")
            return
        }

        val previous = readSnapshot()
        if (previous == null || previous.size < 8) {
            saveCaptured(values.asReversed(), "Impor awal dari panel hasil")
            saveSnapshot(values)
            setStatus("Berhasil membaca dan menyimpan " + values.size + " hasil awal CALO11.")
            return
        }

        val added = findNewLeadingValues(previous, values)
        if (added.isNotEmpty()) {
            if (samePrefix(pendingNewValues, added)) {
                pendingNewCount += 1
            } else {
                pendingNewValues = added
                pendingNewCount = 1
            }

            if (pendingNewCount < 2) {
                setStatus("Terlihat " + added.size + " hasil baru. Memastikan pembacaan sebelum menyimpan...")
                return
            }

            saveCaptured(added.asReversed(), "Ronde baru dari OCR")
            saveSnapshot(values)
            pendingNewValues = emptyList()
            pendingNewCount = 0
            setStatus(added.size.toString() + " multiplier baru berhasil disimpan otomatis.")
            return
        }

        pendingNewValues = emptyList()
        pendingNewCount = 0
        if (kotlin.math.abs(values.first() - previous.first()) <= 0.04) {
            setStatus("OCR membaca " + values.size + " multiplier. Belum ada hasil baru; statistik menunggu ronde berikutnya.")
        } else {
            setStatus(
                "OCR membaca " + values.size +
                    " angka, tetapi urutan belum cocok. Pastikan daftar HASIL TERAKHIR berada di posisi paling atas."
            )
        }
    }

    private fun parseMultiplier(raw: String): Double? {
        val value = raw.trim()
        val normalized = when {
            value.contains(',') -> value.replace(".", "").replace(',', '.')
            Regex("""^[0-9]{1,3}\.[0-9]{3}$""").matches(value) -> value.replace(".", "")
            else -> value
        }
        return normalized.toDoubleOrNull()
    }

    // Align the visible, newest-first OCR list with the previous snapshot.
    // A few missing OCR boxes are allowed, because some CALO11 pills can be missed.
    private fun findNewLeadingValues(previous: List<Double>, current: List<Double>): List<Double> {
        if (previous.size < 8 || current.size < 10) return emptyList()
        if (kotlin.math.abs(current.first() - previous.first()) <= 0.04) return emptyList()

        var bestShift = 0
        var bestMatches = 0
        var bestScore = 0.0
        val maxShift = min(6, current.size - 8)

        for (shift in 1..maxShift) {
            val tail = current.drop(shift)
            var previousCursor = 0
            var matches = 0

            for (value in tail) {
                var matchedIndex = -1
                var smallestDifference = 0.06
                val searchEnd = min(previous.size, previousCursor + 8)
                for (index in previousCursor until searchEnd) {
                    val difference = kotlin.math.abs(previous[index] - value)
                    if (difference <= 0.05 && difference < smallestDifference) {
                        smallestDifference = difference
                        matchedIndex = index
                    }
                }
                if (matchedIndex >= 0) {
                    matches += 1
                    previousCursor = matchedIndex + 1
                }
            }

            val score = matches.toDouble() / tail.size.toDouble()
            if (matches >= 8 && score >= 0.70 &&
                (score > bestScore || (score == bestScore && matches > bestMatches))
            ) {
                bestShift = shift
                bestMatches = matches
                bestScore = score
            }
        }

        return if (bestShift > 0) current.take(bestShift) else emptyList()
    }

    private fun samePrefix(first: List<Double>, second: List<Double>): Boolean {
        if (first.size != second.size || first.isEmpty()) return false
        return first.indices.all {
            kotlin.math.abs(first[it] - second[it]) <= 0.05
        }
    }

    private fun readSnapshot(): List<Double>? {
        val raw = prefs.getString(PREF_LAST_SNAPSHOT, null) ?: return null
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { array.optDouble(it).takeIf { v -> v.isFinite() } }
        } catch (_: Exception) {
            null
        }
    }

    private fun saveSnapshot(values: List<Double>) {
        val array = JSONArray()
        values.forEach { array.put(it) }
        prefs.edit().putString(PREF_LAST_SNAPSHOT, array.toString()).apply()
    }

    private fun saveCaptured(values: List<Double>, note: String) {
        if (values.isEmpty()) return
        val array = try {
            JSONArray(prefs.getString(PREF_CAPTURED, "[]") ?: "[]")
        } catch (_: Exception) {
            JSONArray()
        }
        val now = System.currentTimeMillis()
        values.forEachIndexed { index, value ->
            array.put(JSONObject().apply {
                put("id", UUID.randomUUID().toString())
                put("value", value)
                put("timestamp", now + index)
                put("source", "OCR")
                put("note", note)
            })
        }
        val trimmed = JSONArray()
        val start = (array.length() - 2000).coerceAtLeast(0)
        for (i in start until array.length()) trimmed.put(array.get(i))
        prefs.edit().putString(PREF_CAPTURED, trimmed.toString()).apply()
    }

    private fun setStatus(status: String) {
        prefs.edit().putString(PREF_STATUS, status).apply()
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
    }

    private fun releaseCapture() {
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { imageReader?.setOnImageAvailableListener(null, null) } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        try { recognizer.close() } catch (_: Exception) {}
        worker?.quitSafely()
        worker = null
        workerHandler = null
    }

    override fun onDestroy() {
        stopping = true
        releaseCapture()
        super.onDestroy()
    }
}
