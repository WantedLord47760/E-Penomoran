package com.jetgpt.stockity

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class CandleBar(val x:Int, val centerY:Double, val height:Int, val green:Boolean)
data class ScanResult(
    val signal:String, val score:Int, val trend:String, val candleCount:Int,
    val green:Int, val red:Int, val volatility:String, val time:Long,
    val summary:String, val reasons:List<String>
)

object StockityAnalyzer {
    fun scan(bitmap:Bitmap):ScanResult? {
        val w=bitmap.width; val h=bitmap.height
        if(w<240 || h<480) return null
        val x0=(w*.10).toInt(); val x1=(w*.98).toInt()
        val y0=(h*.17).toInt(); val y1=(h*.73).toInt()
        val pixels=IntArray(w*h)
        bitmap.getPixels(pixels,0,w,0,0,w,h)
        val gc=IntArray(w); val rc=IntArray(w)
        val gy=DoubleArray(w); val ry=DoubleArray(w)
        val gmin=IntArray(w){Int.MAX_VALUE}; val gmax=IntArray(w){Int.MIN_VALUE}
        val rmin=IntArray(w){Int.MAX_VALUE}; val rmax=IntArray(w){Int.MIN_VALUE}
        for(x in x0 until x1) for(y in y0 until y1) {
            val p=pixels[y*w+x]
            val r=Color.red(p); val g=Color.green(p); val b=Color.blue(p)
            val hi=max(r,max(g,b)); val lo=min(r,min(g,b))
            if(hi<105 || hi-lo<48) continue
            if(g>95 && g-r>32 && g-b>12) {
                gc[x]++; gy[x]+=y.toDouble(); gmin[x]=min(gmin[x],y); gmax[x]=max(gmax[x],y)
            } else if(r>120 && r-g>32 && r-b>22) {
                rc[x]++; ry[x]+=y.toDouble(); rmin[x]=min(rmin[x],y); rmax[x]=max(rmax[x],y)
            }
        }
        val runs=mutableListOf<Pair<Int,Int>>()
        var start=-1; var last=-10
        for(x in x0 until x1) {
            if(gc[x]+rc[x]>=3) {
                if(start<0 || x-last>2) { if(start>=0) runs.add(start to last); start=x }
                last=x
            }
        }
        if(start>=0) runs.add(start to last)
        val bars=mutableListOf<CandleBar>()
        for((a,b) in runs) {
            val width=b-a+1
            if(width !in 2..30) continue
            var green=0; var red=0
            for(x in a..b) { green+=gc[x]; red+=rc[x] }
            val isGreen=green>=red
            val count=if(isGreen) green else red
            val other=if(isGreen) red else green
            if(count<10 || count.toDouble()/(count+other).toDouble()<.66) continue
            var sum=0.0; var top=Int.MAX_VALUE; var bottom=Int.MIN_VALUE
            for(x in a..b) {
                if(isGreen) { sum+=gy[x]; top=min(top,gmin[x]); bottom=max(bottom,gmax[x]) }
                else { sum+=ry[x]; top=min(top,rmin[x]); bottom=max(bottom,rmax[x]) }
            }
            val candleHeight=bottom-top+1
            if(candleHeight<5 || candleHeight>y1-y0) continue
            bars.add(CandleBar((a+b)/2,sum/count.toDouble(),candleHeight,isGreen))
        }
        val ordered=bars.sortedBy{it.x}
        if(ordered.size<6) return null

        val recent=ordered.takeLast(10)
        val first=recent.take(4).map{it.centerY}.average()
        val last=recent.takeLast(4).map{it.centerY}.average()
        val delta=first-last
        val threshold=(y1-y0)*.012
        val trend=when { delta>threshold->"BULLISH"; delta< -threshold->"BEARISH"; else->"SIDEWAYS" }
        val six=ordered.takeLast(6)
        val greens=six.count{it.green}; val reds=six.size-greens
        val lastBar=ordered.last(); val prev=ordered[ordered.size-2]
        val twoGreen=lastBar.green && prev.green
        val twoRed=!lastBar.green && !prev.green
        val heights=ordered.takeLast(8).map{it.height}.sorted()
        val medianHeight=heights[heights.size/2].coerceAtLeast(1)
        val spike=lastBar.height>medianHeight*2.7
        val buy=(greens.toDouble()/six.size*40).roundToInt()+
            (if(trend=="BULLISH")25 else 0)+(if(lastBar.green)15 else 0)+
            (if(twoGreen)10 else 0)+(if(greens>=5)10 else 0)
        val sell=(reds.toDouble()/six.size*40).roundToInt()+
            (if(trend=="BEARISH")25 else 0)+(if(!lastBar.green)15 else 0)+
            (if(twoRed)10 else 0)+(if(reds>=5)10 else 0)
        val signal=when {
            !spike && trend=="BULLISH" && greens>=4 && lastBar.green && buy>=72 && buy-sell>=18 -> "BUY"
            !spike && trend=="BEARISH" && reds>=4 && !lastBar.green && sell>=72 && sell-buy>=18 -> "SELL"
            else -> "WAIT"
        }
        val score=max(buy,sell).coerceIn(0,100)
        val vol=if(spike) "TINGGI / LONJAKAN" else "NORMAL"
        val reasons=listOf(
            "Candlestick berwarna terdeteksi: "+ordered.size+".",
            "6 candle terbaru: "+greens+" hijau dan "+reds+" merah.",
            "Arah visual: "+trend.lowercase()+".",
            "Candle terakhir: "+(if(lastBar.green) "hijau." else "merah."),
            "Filter volatilitas: "+vol.lowercase()+".",
            "Skor aturan BUY "+buy+"/100; SELL "+sell+"/100.",
            if(spike) "Sinyal ditahan karena candle terakhir terlalu besar." else "Sinyal menunggu keselarasan tren dan candle."
        )
        val summary=when(signal) {
            "BUY"->"Konfirmasi visual condong naik. Periksa grafik dan risiko sebelum mengambil keputusan."
            "SELL"->"Konfirmasi visual condong turun. Periksa grafik dan risiko sebelum mengambil keputusan."
            else->"Belum ada konfirmasi visual yang cukup kuat. Tunggu pembaruan candle berikutnya."
        }
        return ScanResult(signal,score,trend,ordered.size,greens,reds,vol,System.currentTimeMillis(),summary,reasons)
    }
}
