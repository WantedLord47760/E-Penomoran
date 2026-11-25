package com.jetgpt.stockity

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : Activity() {
    companion object {
        private const val REQ_CAPTURE=2501
        private const val REQ_NOTIF=2502
        private const val PREFS="jetgpt_stockity_ai"
        private val BG=Color.rgb(7,15,28)
        private val PANEL=Color.rgb(16,29,47)
        private val LINE=Color.rgb(39,57,79)
        private val MUTED=Color.rgb(151,169,191)
        private val TEAL=Color.rgb(32,211,189)
        private val GREEN=Color.rgb(28,205,139)
        private val RED=Color.rgb(255,120,111)
        private val YELLOW=Color.rgb(255,200,87)
    }
    private val prefs by lazy { getSharedPreferences(PREFS,Context.MODE_PRIVATE) }
    private val projectionManager by lazy { getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager }
    private val handler=Handler(Looper.getMainLooper())
    private lateinit var status:TextView
    private lateinit var signal:TextView
    private lateinit var score:TextView
    private lateinit var summary:TextView
    private lateinit var metrics:TextView
    private lateinit var reasons:TextView
    private lateinit var history:TextView

    private val refresh=object:Runnable {
        override fun run() { if(!isFinishing) { refreshUi(); handler.postDelayed(this,1000L) } }
    }

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor=BG; window.navigationBarColor=BG; window.decorView.setBackgroundColor(BG)
        buildUi(); refreshUi()
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),REQ_NOTIF)
    }
    override fun onResume() { super.onResume(); handler.removeCallbacks(refresh); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
    override fun onDestroy() { handler.removeCallbacks(refresh); super.onDestroy() }

    private fun buildUi() {
        val scroll=ScrollView(this).apply { setBackgroundColor(BG); isFillViewport=true }
        val col=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(17),dp(22),dp(17),dp(28)) }
        scroll.addView(col); setContentView(scroll)
        col.addView(text("JETGPT  /  MARKET LAB",11f,TEAL,true))
        col.addView(text("STOCKITY AI",28f,Color.WHITE,true).apply{setPadding(0,dp(5),0,dp(2))})
        col.addView(text("AUTO CHART SCANNER  ·  CRYPTO IDX",11f,MUTED,true).apply{setPadding(0,0,0,dp(15))})

        val statusCard=card()
        statusCard.addView(text("STATUS PEMANTAUAN",11f,MUTED,true))
        status=text("Belum dimulai",17f,YELLOW,true).apply{setPadding(0,dp(9),0,0)}
        statusCard.addView(status); col.addView(statusCard)

        val buttons=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
        buttons.addView(button("MULAI SCAN LAYAR",TEAL,BG){startCapture()},LinearLayout.LayoutParams(0,dp(54),1f).apply{rightMargin=dp(6)})
        buttons.addView(button("HENTIKAN",Color.rgb(39,59,83),Color.WHITE){
            startService(Intent(this,StockityCaptureService::class.java).setAction(StockityCaptureService.ACTION_STOP))
            setStatus("Perintah menghentikan pemindaian dikirim.")
        },LinearLayout.LayoutParams(0,dp(54),1f).apply{leftMargin=dp(6)})
        col.addView(buttons)
        col.addView(text(
            "Tekan MULAI SCAN LAYAR, izinkan perekaman layar Android, lalu pindah ke Stockity dan tampilkan grafik candlestick Crypto IDX. Pemindaian berjalan di latar belakang; sinyal BUY/SELL dikirim sebagai notifikasi.",
            12f,MUTED,false
        ).apply{setPadding(dp(3),dp(12),dp(3),dp(15))})

        val signalCard=card()
        signalCard.addView(text("01  ·  SINYAL TERKINI",11f,MUTED,true))
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(0,dp(10),0,dp(4))}
        signal=text("WAIT",36f,YELLOW,true)
        row.addView(signal,LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
        val scoreCol=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.END}
        score=text("—",26f,Color.WHITE,true); scoreCol.addView(score)
        scoreCol.addView(text("SKOR KONFIRMASI",9f,MUTED,true)); row.addView(scoreCol)
        signalCard.addView(row)
        summary=text("Belum ada grafik yang dianalisis.",12f,Color.rgb(195,208,225),false)
        signalCard.addView(summary); col.addView(signalCard)

        val metricCard=card()
        metricCard.addView(text("02  ·  PEMBACAAN GRAFIK",11f,MUTED,true))
        metrics=text("Arah visual                         —\nCandlestick terdeteksi       —\n6 candle terakhir                —\nVolatilitas                         —\nPembaruan terakhir            —",13f,Color.WHITE,false)
        metrics.setPadding(0,dp(10),0,0); metricCard.addView(metrics); col.addView(metricCard)

        val reasonCard=card()
        reasonCard.addView(text("03  ·  ALASAN SINYAL",11f,MUTED,true))
        reasons=text("Belum ada analisis. Mulai pemindaian dan tampilkan grafik Stockity.",12f,Color.rgb(213,225,241),false)
        reasons.setPadding(0,dp(10),0,0); reasonCard.addView(reasons); col.addView(reasonCard)

        val historyCard=card()
        historyCard.addView(text("04  ·  RIWAYAT SINYAL",11f,MUTED,true))
        history=text("Belum ada sinyal BUY/SELL tersimpan.",12f,Color.rgb(213,225,241),false)
        history.setPadding(0,dp(10),0,0); historyCard.addView(history); col.addView(historyCard)

        val info=card()
        info.addView(text("CARA PAKAI",11f,MUTED,true))
        info.addView(text(
            "1. Pilih Crypto IDX di Stockity.\n2. Tekan MULAI SCAN LAYAR.\n3. Setujui dialog berbagi layar Android.\n4. Pindah kembali ke Stockity dan biarkan grafik terlihat.\n5. Notifikasi BUY/SELL akan muncul bila aturan visual terpenuhi. Buka JetGPT untuk melihat rincian.",
            12f,Color.rgb(213,225,241),false
        ).apply{setPadding(0,dp(10),0,dp(12))})
        info.addView(text(
            "Privasi: analisis dilakukan di HP. Aplikasi tidak meminta kata sandi, tidak mengirim transaksi, dan tidak menekan tombol BUY/SELL.",
            11f,MUTED,false
        ))
        col.addView(info)
        col.addView(text(
            "PENTING: ini prototipe pembacaan visual, bukan koneksi data harga resmi. Skor adalah kecocokan aturan visual, bukan probabilitas menang. Tema, warna candle, zoom, dan tata letak chart dapat memengaruhi deteksi. Uji hanya di akun demo.",
            11f,MUTED,false
        ).apply{setPadding(dp(3),dp(5),dp(3),0)})
    }

    private fun refreshUi() {
        status.text=prefs.getString(StockityCaptureService.PREF_STATUS,"Belum dimulai") ?: "Belum dimulai"
        val raw=prefs.getString(StockityCaptureService.PREF_ANALYSIS,null)
        if(!raw.isNullOrBlank()) try {
            val j=JSONObject(raw)
            val s=j.optString("signal","WAIT")
            signal.text=s
            signal.setTextColor(when(s){"BUY"->GREEN;"SELL"->RED;else->YELLOW})
            score.text=if(j.optBoolean("detected",false)) j.optInt("score").toString()+"/100" else "—"
            summary.text=j.optString("summary","Menunggu grafik.")
            val time=j.optLong("updatedAt",0L)
            val timeText=if(time>0) SimpleDateFormat("HH:mm:ss",Locale.getDefault()).format(Date(time)) else "—"
            metrics.text="Arah visual                         "+j.optString("trend","—")+
                "\nCandlestick terdeteksi       "+j.optInt("candleCount",0)+
                "\n6 candle terakhir                "+j.optInt("greenLastSix",0)+" hijau · "+j.optInt("redLastSix",0)+" merah"+
                "\nVolatilitas                         "+j.optString("volatility","—")+
                "\nPembaruan terakhir            "+timeText
            val arr=j.optJSONArray("reasons") ?: JSONArray()
            val lines=mutableListOf<String>()
            for(i in 0 until arr.length()) lines.add("• "+arr.optString(i))
            reasons.text=lines.joinToString("\n").ifBlank{"Belum ada rincian."}
        } catch(_:Exception) { summary.text="Data analisis sedang diperbarui." }

        history.text=try {
            val arr=JSONArray(prefs.getString(StockityCaptureService.PREF_HISTORY,"[]") ?: "[]")
            if(arr.length()==0) "Belum ada sinyal BUY/SELL tersimpan."
            else {
                val lines=mutableListOf<String>()
                for(i in arr.length()-1 downTo 0) {
                    val item=arr.optJSONObject(i) ?: continue
                    val t=item.optLong("time",0L)
                    val stamp=if(t>0) SimpleDateFormat("HH:mm:ss",Locale.getDefault()).format(Date(t)) else "--:--:--"
                    lines.add(stamp+"   "+item.optString("signal")+"   skor "+item.optInt("score"))
                }
                lines.take(12).joinToString("\n")
            }
        } catch(_:Exception){"Riwayat belum dapat dibaca."}
    }

    private fun startCapture() {
        try { startActivityForResult(projectionManager.createScreenCaptureIntent(),REQ_CAPTURE) }
        catch(e:Exception) { setStatus("Tidak dapat meminta izin layar: "+(e.message ?: "kesalahan Android")) }
    }

    @Deprecated("Android screen capture permission result")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode!=REQ_CAPTURE) return
        if(resultCode!=RESULT_OK || data==null) { setStatus("Izin berbagi layar tidak diberikan."); return }
        val i=Intent(this,StockityCaptureService::class.java).apply {
            action=StockityCaptureService.ACTION_START
            putExtra(StockityCaptureService.EXTRA_RESULT_CODE,resultCode)
            putExtra(StockityCaptureService.EXTRA_RESULT_DATA,data)
        }
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
        setStatus("Izin diterima. Sekarang pindah ke Stockity dan tampilkan grafik Crypto IDX.")
    }

    private fun setStatus(message:String) {
        status.text=message
        prefs.edit().putString(StockityCaptureService.PREF_STATUS,message).apply()
    }

    private fun card()=LinearLayout(this).apply {
        orientation=LinearLayout.VERTICAL
        setPadding(dp(16),dp(16),dp(16),dp(16))
        background=rounded(PANEL,dp(18))
        layoutParams=LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{bottomMargin=dp(13)}
    }
    private fun text(value:String,size:Float,color:Int,bold:Boolean)=TextView(this).apply {
        text=value; textSize=size; setTextColor(color); if(bold) typeface=Typeface.DEFAULT_BOLD
        gravity=Gravity.START or Gravity.CENTER_VERTICAL; setLineSpacing(dp(2).toFloat(),1f)
    }
    private fun button(value:String,bg:Int,fg:Int,action:()->Unit)=Button(this).apply {
        text=value; setTextColor(fg); textSize=12f; typeface=Typeface.DEFAULT_BOLD
        isAllCaps=false; background=rounded(bg,dp(13)); setPadding(dp(7),0,dp(7),0)
        setOnClickListener{action()}
    }
    private fun rounded(color:Int,radius:Int)=GradientDrawable().apply{setColor(color);cornerRadius=radius.toFloat()}
    private fun dp(v:Int)=(v*resources.displayMetrics.density).roundToInt()
}
