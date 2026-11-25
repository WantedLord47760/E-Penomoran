package com.jetgpt.spaceman

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : Activity() {
    companion object {
        private const val REQUEST_CAPTURE = 1601
        private const val PREFS = "jetgpt_autocapture"
    }

    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val projectionManager by lazy {
        getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var statusView: TextView
    private lateinit var countView: TextView
    private lateinit var averageView: TextView
    private lateinit var medianView: TextView
    private lateinit var lowView: TextView
    private lateinit var over2View: TextView
    private lateinit var over3View: TextView
    private lateinit var over5View: TextView
    private lateinit var nextView: TextView
    private lateinit var historyView: TextView
    private lateinit var manualInput: EditText

    private val refreshTask = object : Runnable {
        override fun run() {
            if (!isFinishing) {
                refreshDashboard()
                handler.postDelayed(this, 2500L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(10, 14, 28)
        window.navigationBarColor = Color.rgb(10, 14, 28)
        window.decorView.setBackgroundColor(Color.rgb(10, 14, 28))
        buildDashboard()
        refreshDashboard()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refreshTask)
        handler.post(refreshTask)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshTask)
        super.onPause()
    }

    private fun buildDashboard() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(10, 14, 28))
            isFillViewport = true
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(28))
        }
        scroll.addView(column)
        setContentView(scroll)

        column.addView(label("JETGPT SPACEMAN", 25f, Color.WHITE, true))
        column.addView(label("AUTO CAPTURE CALO11  ·  OCR LAYAR", 11f, Color.rgb(151, 164, 190), true).apply {
            setPadding(0, dp(5), 0, dp(18))
        })

        val statusCard = card()
        statusCard.addView(label("STATUS PEMANTAUAN", 11f, Color.rgb(151, 164, 190), true))
        statusView = label("Belum dimulai", 17f, Color.rgb(245, 196, 91), true).apply {
            setPadding(0, dp(9), 0, 0)
        }
        statusCard.addView(statusView)
        column.addView(statusCard)

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        controls.addView(actionButton("MULAI PANTAU LAYAR", Color.rgb(35, 122, 111)) {
            try {
                startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_CAPTURE)
            } catch (e: Exception) {
                setStatus("Tidak dapat meminta izin screen capture: " + (e.message ?: "error"))
            }
        }, LinearLayout.LayoutParams(0, dp(54), 1f).apply { rightMargin = dp(6) })
        controls.addView(actionButton("HENTIKAN", Color.rgb(76, 83, 105)) {
            val intent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_STOP
            }
            startService(intent)
            setStatus("Perintah menghentikan pemantauan dikirim.")
        }, LinearLayout.LayoutParams(0, dp(54), 1f).apply { leftMargin = dp(6) })
        column.addView(controls)

        column.addView(label(
            "Setelah memberi izin, pindah ke CALO11 lalu buka INFO → STATS → HASIL TERAKHIR. Biarkan panel hasil terlihat.",
            12f, Color.rgb(151, 164, 190), false
        ).apply { setPadding(dp(2), dp(12), dp(2), dp(18)) })

        val nextCard = card()
        nextCard.addView(label("RINGKASAN STATISTIK TERKINI", 11f, Color.rgb(151, 164, 190), true))
        nextView = label("Belum ada data yang cukup", 17f, Color.rgb(64, 211, 170), true).apply {
            setPadding(0, dp(10), 0, dp(6))
        }
        nextCard.addView(nextView)
        nextCard.addView(label(
            "Ringkasan ini memakai 20 hasil terakhir dan akan berubah setelah ronde baru berhasil tersimpan. Ini acuan historis, bukan angka pasti ronde berikutnya.",
            12f, Color.rgb(164, 174, 195), false
        ))
        column.addView(nextCard)

        val statsCard = card()
        statsCard.addView(label("DATABASE LOKAL", 11f, Color.rgb(151, 164, 190), true))
        countView = statRow(statsCard, "Jumlah data tersimpan", "0 ronde")
        averageView = statRow(statsCard, "Rata-rata historis", "—")
        medianView = statRow(statsCard, "Median historis", "—")
        lowView = statRow(statsCard, "Di bawah 1,50x", "—")
        over2View = statRow(statsCard, "Mencapai 2,00x", "—")
        over3View = statRow(statsCard, "Mencapai 3,00x", "—")
        over5View = statRow(statsCard, "Mencapai 5,00x", "—")
        column.addView(statsCard)

        val manualCard = card()
        manualCard.addView(label("INPUT MANUAL", 11f, Color.rgb(151, 164, 190), true))
        manualInput = EditText(this).apply {
            hint = "Contoh: 2,35"
            setHintTextColor(Color.rgb(120, 132, 158))
            setTextColor(Color.WHITE)
            textSize = 16f
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(Color.rgb(25, 32, 53), dp(10))
        }
        manualCard.addView(manualInput, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(50)
        ).apply { topMargin = dp(10) })
        manualCard.addView(actionButton("SIMPAN MULTIPLIER", Color.rgb(48, 103, 161)) {
            saveManualValue()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
            topMargin = dp(10)
        })
        column.addView(manualCard)

        val historyCard = card()
        historyCard.addView(label("RIWAYAT RONDE TERSIMPAN", 11f, Color.rgb(151, 164, 190), true))
        historyView = label("Belum ada ronde tersimpan.", 13f, Color.WHITE, false).apply {
            setPadding(0, dp(12), 0, 0)
        }
        historyCard.addView(historyView)
        column.addView(historyCard)

        column.addView(label(
            "Privasi: gambar layar diproses di perangkat dan tidak dikirim ke server. OCR hanya membaca teks yang terlihat; hasil buram atau perubahan tampilan dapat menyebabkan angka terlewat.",
            11f, Color.rgb(125, 138, 165), false
        ).apply { setPadding(dp(2), dp(14), dp(2), 0) })
    }

    private fun refreshDashboard() {
        val records = readRecords()
        val values = records.mapNotNull { record ->
            record.optDouble("value").takeIf { it.isFinite() && it >= 1.0 }
        }
        val sorted = values.sorted()
        val count = values.size
        countView.text = count.toString() + " ronde"

        if (count == 0) {
            averageView.text = "—"
            medianView.text = "—"
            lowView.text = "—"
            over2View.text = "—"
            over3View.text = "—"
            over5View.text = "—"
            nextView.text = "Belum ada data yang cukup"
            historyView.text = "Belum ada ronde tersimpan."
        } else {
            val average = values.average()
            val median = median(sorted)
            averageView.text = format(average) + "x"
            medianView.text = format(median) + "x"
            lowView.text = percent(values.count { it < 1.5 }, count)
            over2View.text = percent(values.count { it >= 2.0 }, count)
            over3View.text = percent(values.count { it >= 3.0 }, count)
            over5View.text = percent(values.count { it >= 5.0 }, count)
            val recentValues = values.takeLast(20)
            val recentMedian = median(recentValues.sorted())
            val latestValue = values.last()
            nextView.text =
                "Median 20 ronde terakhir: " + format(recentMedian) + "x" +
                "\nHasil terbaru: " + format(latestValue) + "x" +
                "\nSampel: " + count + " ronde  ·  ≥2,00x: " +
                percent(recentValues.count { it >= 2.0 }, recentValues.size)
            val recent = records.takeLast(20).asReversed().mapNotNull {
                val v = it.optDouble("value")
                if (v.isFinite() && v >= 1.0) format(v) + "x" else null
            }
            historyView.text = recent.joinToString("     ") + "\n\nTerbaru ditampilkan lebih dahulu."
        }

        val savedStatus = prefs.getString(ScreenCaptureService.PREF_STATUS, "Belum dimulai")
        if (!savedStatus.isNullOrBlank()) statusView.text = savedStatus
    }

    private fun saveManualValue() {
        val raw = manualInput.text.toString().trim().replace(',', '.')
        val value = raw.toDoubleOrNull()
        if (value == null || !value.isFinite() || value < 1.0 || value > 5000.0) {
            setStatus("Masukkan multiplier yang valid, minimal 1,00x.")
            return
        }
        val records = JSONArray()
        readRecords().forEach { records.put(it) }
        records.put(JSONObject().apply {
            put("value", value)
            put("timestamp", System.currentTimeMillis())
            put("source", "Manual")
        })
        trimAndSave(records)
        manualInput.setText("")
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(manualInput.windowToken, 0)
        setStatus("Multiplier " + format(value) + "x berhasil disimpan.")
        refreshDashboard()
    }

    private fun readRecords(): List<JSONObject> {
        return try {
            val array = JSONArray(prefs.getString(ScreenCaptureService.PREF_CAPTURED, "[]") ?: "[]")
            (0 until array.length()).mapNotNull { array.optJSONObject(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun trimAndSave(array: JSONArray) {
        val trimmed = JSONArray()
        val start = (array.length() - 2000).coerceAtLeast(0)
        for (i in start until array.length()) trimmed.put(array.get(i))
        prefs.edit().putString(ScreenCaptureService.PREF_CAPTURED, trimmed.toString()).apply()
    }

    private fun statRow(parent: LinearLayout, title: String, initial: String): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
        }
        row.addView(label(title, 14f, Color.rgb(210, 218, 233), false),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val value = label(initial, 15f, Color.WHITE, true)
        row.addView(value)
        parent.addView(row)
        val divider = View(this).apply { setBackgroundColor(Color.rgb(48, 57, 79)) }
        parent.addView(divider, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
        ))
        return value
    }

    private fun card(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = rounded(Color.rgb(20, 27, 46), dp(18))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        }
    }

    private fun actionButton(text: String, color: Int, action: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = rounded(color, dp(13))
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }
    }

    private fun rounded(color: Int, radius: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
        }
    }

    private fun setStatus(message: String) {
        statusView.text = message
        prefs.edit().putString(ScreenCaptureService.PREF_STATUS, message).apply()
    }

    private fun median(sorted: List<Double>): Double {
        if (sorted.isEmpty()) return 0.0
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0 else sorted[mid]
    }

    private fun percent(part: Int, total: Int): String {
        if (total == 0) return "—"
        return String.format(Locale.US, "%.1f%%", part * 100.0 / total)
    }

    private fun format(value: Double): String =
        String.format(Locale.US, "%.2f", value).replace('.', ',')

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    @Deprecated("Deprecated in Android API, retained for compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            setStatus("Izin screen capture tidak diberikan.")
            return
        }
        val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_START
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent)
        else startService(serviceIntent)
        setStatus("Izin diterima. Pindah ke CALO11 → INFO → STATS → HASIL TERAKHIR.")
    }
}
