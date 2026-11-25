package com.jetgpt.stockity

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
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class StockityCaptureService:Service() {
    companion object {
        const val ACTION_START="com.jetgpt.stockity.START_CAPTURE"
        const val ACTION_STOP="com.jetgpt.stockity.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE="projection_result_code"
        const val EXTRA_RESULT_DATA="projection_result_data"
        const val PREF_STATUS="scan_status"
        const val PREF_ANALYSIS="last_analysis"
        const val PREF_HISTORY="signal_history"
        private const val PREFS="jetgpt_stockity_ai"
        private const val FOREGROUND_CHANNEL="stockity_scan_channel"
        private const val SIGNAL_CHANNEL="stockity_signal_channel"
        private const val FOREGROUND_ID=2501
        private const val SIGNAL_ID=2502
    }
    private val prefs by lazy{getSharedPreferences(PREFS,Context.MODE_PRIVATE)}
    private val busy=AtomicBoolean(false)
    private var projection:MediaProjection?=null
    private var display:VirtualDisplay?=null
    private var reader:ImageReader?=null
    private var thread:HandlerThread?=null
    private var worker:Handler?=null
    private var lastFrame=0L
    private var stopping=false
    private var lastNotified="WAIT"
    private var lastSaved="WAIT"

    private val projectionCallback=object:MediaProjection.Callback(){
        override fun onStop(){
            if(!stopping){
                stopping=true
                setStatus("Berbagi layar dihentikan Android. Tekan MULAI SCAN untuk memulai lagi.")
                release(false); stopForegroundCompat(); stopSelf()
            }
        }
    }

    override fun onBind(intent:Intent?):IBinder?=null

    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        when(intent?.action){
            ACTION_STOP->{
                stopping=true; setStatus("Pemindaian dihentikan.")
                release(); stopForegroundCompat(); stopSelf(); return START_NOT_STICKY
            }
            ACTION_START->{
                if(projection!=null) return START_STICKY
                stopping=false; startForegroundNotification()
                val code=intent.getIntExtra(EXTRA_RESULT_CODE,0)
                val data:Intent?=if(Build.VERSION.SDK_INT>=33)
                    intent.getParcelableExtra(EXTRA_RESULT_DATA,Intent::class.java)
                else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                if(data==null){setStatus("Token izin berbagi layar tidak tersedia.");stopForegroundCompat();stopSelf();return START_NOT_STICKY}
                startProjection(code,data)
            }
        }
        return START_STICKY
    }

    private fun startForegroundNotification(){
        val nm=getSystemService(NotificationManager::class.java)
        if(Build.VERSION.SDK_INT>=26){
            nm.createNotificationChannel(NotificationChannel(FOREGROUND_CHANNEL,"Pemindaian Stockity",NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(SIGNAL_CHANNEL,"Sinyal BUY dan SELL",NotificationManager.IMPORTANCE_DEFAULT))
        }
        val pending=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder=if(Build.VERSION.SDK_INT>=26) Notification.Builder(this,FOREGROUND_CHANNEL) else Notification.Builder(this)
        val n=builder.setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("JETGPT STOCKITY AI aktif")
            .setContentText("Memindai grafik Stockity di layar")
            .setOngoing(true).setContentIntent(pending).build()
        if(Build.VERSION.SDK_INT>=29)
            startForeground(FOREGROUND_ID,n,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(FOREGROUND_ID,n)
    }

    private fun startProjection(code:Int,data:Intent){
        try{
            val mgr=getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection=mgr.getMediaProjection(code,data)
            val wm=getSystemService(WINDOW_SERVICE) as WindowManager
            val dm=DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            val w=dm.widthPixels; val h=dm.heightPixels; val dpi=dm.densityDpi
            thread=HandlerThread("Stockity-Scanner").also{it.start()}
            worker=Handler(thread!!.looper)
            reader=ImageReader.newInstance(w,h,PixelFormat.RGBA_8888,2)
            reader!!.setOnImageAvailableListener({src->
                val now=System.currentTimeMillis()
                if(now-lastFrame<2200L || !busy.compareAndSet(false,true)){
                    src.acquireLatestImage()?.close(); return@setOnImageAvailableListener
                }
                val image=src.acquireLatestImage()
                if(image==null){busy.set(false);return@setOnImageAvailableListener}
                lastFrame=now
                process(image)
            },worker)
            projection!!.registerCallback(projectionCallback,worker)
            display=projection!!.createVirtualDisplay(
                "JETGPT-Stockity-Scanner",w,h,dpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader!!.surface,null,worker
            )
            setStatus("Aktif. Pindah ke Stockity dan tampilkan grafik candlestick Crypto IDX.")
        }catch(e:Exception){
            setStatus("Gagal memulai pemindaian: "+(e.message ?: "kesalahan Android"))
            stopping=true; release(); stopForegroundCompat(); stopSelf()
        }
    }

    private fun process(image:Image){
        var bitmap:Bitmap?=null
        try{
            val plane=image.planes[0]; val buffer=plane.buffer
            val stride=plane.rowStride; val pixel=plane.pixelStride
            val padded=image.width+(stride-pixel*image.width)/pixel
            val full=Bitmap.createBitmap(padded,image.height,Bitmap.Config.ARGB_8888)
            buffer.rewind(); full.copyPixelsFromBuffer(buffer)
            bitmap=if(padded==image.width) full else Bitmap.createBitmap(full,0,0,image.width,image.height).also{full.recycle()}
        }catch(e:Exception){
            setStatus("Gagal mengambil gambar layar: "+(e.message ?: "format tidak didukung"))
        }finally{image.close()}

        try{
            val frame=bitmap
            if(frame!=null){
                val result=StockityAnalyzer.scan(frame)
                if(result==null){
                    setStatus("Layar terbaca, tetapi grafik candlestick belum terdeteksi. Buka Stockity Crypto IDX dan pastikan grafik terlihat.")
                }else{
                    saveResult(result)
                    setStatus("Memantau Stockity · "+result.candleCount+" candle terdeteksi · "+result.signal)
                    notifyIfNeeded(result)
                    saveHistory(result)
                }
            }
        }catch(e:Exception){
            setStatus("Analisis visual gagal: "+(e.message ?: "kesalahan pemrosesan"))
        }finally{
            try{bitmap?.recycle()}catch(_:Exception){}
            busy.set(false)
        }
    }

    private fun saveResult(a:ScanResult){
        val why=JSONArray(); a.reasons.forEach{why.put(it)}
        val obj=JSONObject()
            .put("detected",true).put("signal",a.signal).put("score",a.score)
            .put("trend",a.trend).put("candleCount",a.candleCount)
            .put("greenLastSix",a.green).put("redLastSix",a.red)
            .put("volatility",a.volatility).put("updatedAt",a.time)
            .put("summary",a.summary).put("reasons",why)
        prefs.edit().putString(PREF_ANALYSIS,obj.toString()).apply()
    }

    private fun notifyIfNeeded(a:ScanResult){
        if(a.signal=="WAIT"){lastNotified="WAIT";return}
        if(a.signal==lastNotified)return
        lastNotified=a.signal
        val pending=PendingIntent.getActivity(this,1,Intent(this,MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder=if(Build.VERSION.SDK_INT>=26) Notification.Builder(this,SIGNAL_CHANNEL) else Notification.Builder(this)
        val n=builder.setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("JETGPT STOCKITY · "+a.signal)
            .setContentText("Crypto IDX · skor konfirmasi "+a.score+"/100 · "+a.trend)
            .setStyle(Notification.BigTextStyle().bigText(a.summary))
            .setContentIntent(pending).setAutoCancel(true).build()
        try{getSystemService(NotificationManager::class.java).notify(SIGNAL_ID,n)}
        catch(_:SecurityException){setStatus("Sinyal "+a.signal+" terdeteksi. Izin notifikasi tidak aktif; buka JetGPT untuk melihatnya.")}
    }

    private fun saveHistory(a:ScanResult){
        if(a.signal=="WAIT" || a.signal==lastSaved)return
        lastSaved=a.signal
        val arr=try{JSONArray(prefs.getString(PREF_HISTORY,"[]") ?: "[]")}catch(_:Exception){JSONArray()}
        arr.put(JSONObject().put("id",UUID.randomUUID().toString()).put("signal",a.signal)
            .put("score",a.score).put("trend",a.trend).put("time",a.time))
        val out=JSONArray(); val from=(arr.length()-40).coerceAtLeast(0)
        for(i in from until arr.length()) out.put(arr.get(i))
        prefs.edit().putString(PREF_HISTORY,out.toString()).apply()
    }

    private fun setStatus(value:String){prefs.edit().putString(PREF_STATUS,value).apply()}
    private fun stopForegroundCompat(){
        if(Build.VERSION.SDK_INT>=24) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
    }
    private fun release(stopProjection:Boolean=true){
        try{display?.release()}catch(_:Exception){}; display=null
        try{reader?.setOnImageAvailableListener(null,null)}catch(_:Exception){}
        try{reader?.close()}catch(_:Exception){}; reader=null
        val p=projection; projection=null
        if(p!=null){
            try{p.unregisterCallback(projectionCallback)}catch(_:Exception){}
            if(stopProjection) try{p.stop()}catch(_:Exception){}
        }
        thread?.quitSafely(); thread=null; worker=null
    }
    override fun onDestroy(){stopping=true;release();super.onDestroy()}
}
