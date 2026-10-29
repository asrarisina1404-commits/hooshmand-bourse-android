package ir.hooshmandbourse

import android.app.Activity
import android.os.Bundle
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.*
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.*
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

data class Share(
    val code: String, val symbol: String, val last: Double, val close: Double,
    val yesterday: Double, val volume: Double, val baseVolume: Double,
    val value: Double, val buyIVol: Double, val sellIVol: Double,
    val buyICount: Double, val sellICount: Double,
    var fiveDayReturn: Double = 0.0, var volumeRatio: Double = 0.0,
    var score: Int = 0, var reasons: List<String> = emptyList()
) {
    val netRetailValue: Double get() = (buyIVol - sellIVol) * close
    val buyerPower: Double get() = if (sellICount > 0 && buyICount > 0)
        (buyIVol / buyICount) / max(1.0, sellIVol / sellICount) else 0.0
}

class MainActivity : Activity() {
    private val pool = Executors.newSingleThreadExecutor()
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var refresh: Button
    private val green = Color.rgb(20, 130, 80)
    private val navy = Color.rgb(20, 39, 58)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = navy
        window.navigationBarColor = navy
        val scroll = ScrollView(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 34, 32, 36)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(Color.rgb(246, 248, 250))
        }
        scroll.addView(content)
        setContentView(scroll)
        header()
        showIntro()
        loadMarket()
    }

    private fun tv(text: String, size: Float = 15f, color: Int = Color.DKGRAY, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            gravity = Gravity.RIGHT
            textDirection = View.TEXT_DIRECTION_RTL
            if (bold) setTypeface(null, Typeface.BOLD)
            setPadding(0, 8, 0, 8)
        }

    private fun header() {
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 22, 24, 22)
            setBackgroundColor(navy)
        }
        top.addView(tv("هوشمند بورس", 24f, Color.WHITE, true))
        top.addView(tv("دیده‌بان هوشمند بورس و فرابورس ایران", 14f, Color.LTGRAY))
        status = tv("در حال آماده‌سازی…", 13f, Color.WHITE)
        top.addView(status)
        refresh = Button(this).apply {
            text = "به‌روزرسانی و تحلیل بازار"
            setOnClickListener { loadMarket() }
        }
        top.addView(refresh)
        content.addView(top)
        content.addView(tv("سهام منتخب امروز", 20f, navy, true))
        content.addView(tv("حداکثر ۵ نماد برتر بر اساس جریان پول حقیقی، قدرت خریدار، حجم و روند کوتاه‌مدت.", 13f))
    }

    private fun showIntro() {
        content.addView(tv("این نسخه آزمایشی است. تا زمانی که دریافت داده واقعی تأیید نشود، هیچ نمادی به‌عنوان سیگنال قطعی نمایش داده نمی‌شود.", 13f, Color.rgb(150, 90, 0)))
    }

    private fun loadMarket() {
        refresh.isEnabled = false
        status.text = "در حال دریافت داده‌های بازار از TSETMC…"
        pool.execute {
            try {
                val marketJson = getJson("https://cdn.tsetmc.com/api/ClosingPrice/GetMarketWatch?market=0&paperTypes[0]=1&paperTypes[1]=2&paperTypes[2]=3&paperTypes[3]=4&paperTypes[4]=5&paperTypes[5]=6&paperTypes[6]=7&paperTypes[7]=8&paperTypes[8]=9&withBestLimits=false&hEven=0&RefID=0")
                val marketRows = findArray(marketJson, "marketwatch")
                    ?: throw Exception("پاسخ دیده‌بان بازار ساختار مورد انتظار را ندارد.")
                val clientJson = getJson("https://cdn.tsetmc.com/api/ClientType/GetClientTypeAll")
                val clientRows = findArray(clientJson, "clientTypeAllDto") ?: JSONArray()
                val clients = HashMap<String, JSONObject>()
                for (i in 0 until clientRows.length()) {
                    val o = clientRows.optJSONObject(i) ?: continue
                    val code = firstString(o, "insCode", "InsCode", "instrumentCode")
                    if (code.isNotBlank()) clients[code] = o
                }
                if (marketRows.length() == 0) throw Exception("فهرست بازار خالی است؛ سرویس TSETMC داده‌ای برنگرداند.")
                val candidates = ArrayList<Share>()
                for (i in 0 until marketRows.length()) {
                    val source = marketRows.optJSONObject(i) ?: continue
                    val o = flattenMarketRow(source)
                    val code = firstString(o, "insCode", "InsCode", "instrumentCode")
                    val symbol = firstString(o, "lVal18AFC", "symbol", "Symbol", "lVal30")
                    if (code.isBlank() || symbol.isBlank()) continue
                    val close = firstNum(o, "pc", "pClosing", "close")
                    val last = firstNum(o, "pl", "pDrCotVal", "last")
                    val yday = firstNum(o, "py", "priceYesterday", "yesterday")
                    val vol = firstNum(o, "qTotTran5J", "volume", "vol")
                    val base = firstNum(o, "baseVol", "baseVolume")
                    val value = firstNum(o, "qTotCap", "value")
                    if (close <= 0 || vol <= 0 || value <= 0) continue
                    val c = clients[code] ?: JSONObject()
                    candidates.add(Share(
                        code, symbol, last, close, yday, vol, base, value,
                        firstNum(c, "buy_I_Volume"), firstNum(c, "sell_I_Volume"),
                        firstNum(c, "buy_CountI"), firstNum(c, "sell_CountI")
                    ))
                }
                if (candidates.isEmpty()) throw Exception("پاسخ بازار دریافت شد، اما مشخصات نمادها و قیمت‌ها از آن استخراج نشد. احتمالاً ساختار پاسخ TSETMC تغییر کرده است.")
                // Avoid thousands of per-symbol requests: fetch history for the 35 most liquid candidates only.
                val liquid = candidates.sortedByDescending { it.value }.take(35)
                for (s in liquid) {
                    try {
                        val history = getJson("https://cdn.tsetmc.com/api/ClosingPrice/GetClosingPriceDailyList/${s.code}/30")
                        val rows = findArray(history, "closingPriceDaily")
                        if (rows != null && rows.length() >= 6) {
                            val recent = rows.optJSONObject(0) ?: JSONObject()
                            val older = rows.optJSONObject(min(4, rows.length() - 1)) ?: JSONObject()
                            val r = firstNum(recent, "pClosing", "pc")
                            val old = firstNum(older, "pClosing", "pc")
                            if (old > 0) s.fiveDayReturn = (r / old - 1.0) * 100.0
                            val volumes = ArrayList<Double>()
                            for (j in 1 until min(21, rows.length())) {
                                val v = firstNum(rows.optJSONObject(j) ?: JSONObject(), "qTotTran5J", "volume")
                                if (v > 0) volumes.add(v)
                            }
                            val avg = volumes.average()
                            if (avg > 0) s.volumeRatio = s.volume / avg
                        }
                    } catch (_: Exception) { /* history may be temporarily unavailable */ }
                }
                val scored = liquid.map { score(it) }.filter { it.score >= 55 }
                    .sortedByDescending { it.score }.take(5)
                runOnUiThread {
                    render(scored, candidates.size)
                    status.text = "داده دریافت شد • ${candidates.size} نماد بررسی شد"
                    refresh.isEnabled = true
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "دریافت داده ناموفق بود"
                    refresh.isEnabled = true
                    content.addView(tv("خطا: ${e.message ?: "اتصال یا پاسخ نامعتبر"}", 14f, Color.RED))
                    content.addView(tv("ممکن است سرویس TSETMC از IP خارج از ایران در دسترس نباشد یا ساختار API تغییر کرده باشد. در این وضعیت برنامه نباید سیگنال ساختگی تولید کند.", 13f))
                }
            }
        }
    }

    private fun score(s: Share): Share {
        var p = 0
        val why = ArrayList<String>()
        val totalRetail = s.buyIVol + s.sellIVol
        val netPct = if (totalRetail > 0) (s.buyIVol - s.sellIVol) / totalRetail * 100 else 0.0
        if (netPct >= 20) { p += 20; why.add("ورود خالص پول حقیقی") }
        else if (netPct >= 5) { p += 10; why.add("برتری نسبی خرید حقیقی") }
        if (s.buyerPower >= 1.5) { p += 15; why.add("قدرت خریدار حقیقی بالا") }
        else if (s.buyerPower >= 1.1) { p += 8; why.add("قدرت خریدار بهتر از فروشنده") }
        if (s.volumeRatio >= 2.0) { p += 15; why.add("حجم بیش از دو برابر میانگین") }
        else if (s.volumeRatio >= 1.3) { p += 8; why.add("افزایش حجم معاملات") }
        if (s.fiveDayReturn in 1.0..12.0) { p += 15; why.add("روند مثبت پنج‌روزه") }
        else if (s.fiveDayReturn > 0) { p += 7; why.add("بازده مثبت کوتاه‌مدت") }
        if (s.last >= s.close && s.close >= s.yesterday && s.yesterday > 0) { p += 10; why.add("قیمت پایانی و آخرین معامله مثبت") }
        if (s.baseVolume > 0 && s.volume >= s.baseVolume) { p += 10; why.add("حجم بالاتر از حجم مبنا") }
        if (s.value > 0) p += 5
        s.score = p
        s.reasons = why
        return s
    }

    private fun render(list: List<Share>, scanned: Int) {
        content.removeViews(3, max(0, content.childCount - 3))
        content.addView(tv("نتیجه تحلیل: $scanned نماد بررسی شد", 14f, navy, true))
        if (list.isEmpty()) {
            content.addView(tv("در این نوبت نمادی از آستانه امتیاز عبور نکرد. این نتیجه بهتر از نمایش فرصت ضعیف است.", 15f))
            return
        }
        list.forEachIndexed { idx, s ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(22, 16, 22, 16)
                setBackgroundColor(Color.WHITE)
                elevation = 3f
            }
            card.addView(tv("${idx + 1}. ${s.symbol}     امتیاز ${s.score}/100", 19f, green, true))
            card.addView(tv("ورود خالص پول حقیقی: ${formatMoney(s.netRetailValue)} ریال", 13f))
            card.addView(tv("قدرت خریدار/فروشنده: ${"%.2f".format(Locale.US, s.buyerPower)}", 13f))
            card.addView(tv("تغییر پنج‌روزه: ${"%.1f".format(Locale.US, s.fiveDayReturn)}٪  •  نسبت حجم: ${"%.1f".format(Locale.US, s.volumeRatio)}", 13f))
            card.addView(tv("دلایل امتیاز: ${s.reasons.joinToString("، ")}", 13f))
            card.addView(tv("این رتبه‌بندی غربالگری اولیه است؛ پیش از معامله، نمودار هفتگی، اطلاعیه‌ها و وضعیت صف/نقدشوندگی را بررسی کن.", 12f, Color.rgb(130, 80, 0)))
            val lp = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 18 }
            content.addView(card, lp)
        }
    }

    private fun getJson(url: String): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection)
        conn.connectTimeout = 12000
        conn.readTimeout = 15000
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 Android HooshmandBourse/0.1")
        conn.setRequestProperty("Accept", "application/json")
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream.bufferedReader().use { it.readText() }
            if (code !in 200..299) throw Exception("HTTP $code")
            if (body.contains("دسترسی شما") || body.contains("مسدود")) throw Exception("دسترسی به API مسدود شده است")
            return JSONObject(body)
        } finally { conn.disconnect() }
    }

    // Market-watch records commonly contain instrument and closingPrice as nested objects.
    // Flatten nested fields so symbol, instrument code and prices can be read consistently.
    private fun flattenMarketRow(source: JSONObject): JSONObject {
        val result = JSONObject()
        val keys = source.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = source.opt(key)
            if (value !is JSONObject && value !is JSONArray) result.put(key, value)
        }
        val nestedKeys = source.keys()
        while (nestedKeys.hasNext()) {
            val nestedValue = source.opt(nestedKeys.next())
            if (nestedValue is JSONObject) {
                val childKeys = nestedValue.keys()
                while (childKeys.hasNext()) {
                    val childKey = childKeys.next()
                    val childValue = nestedValue.opt(childKey)
                    if (!result.has(childKey) || result.opt(childKey) == JSONObject.NULL ||
                        result.opt(childKey).toString() == "0" || result.opt(childKey).toString().isBlank()) {
                        result.put(childKey, childValue)
                    }
                }
            }
        }
        return result
    }

    private fun findArray(root: JSONObject, key: String): JSONArray? {
        val keys = root.keys()
        while (keys.hasNext()) {
            val currentKey = keys.next()
            val value = root.opt(currentKey)
            if (currentKey.equals(key, ignoreCase = true) && value is JSONArray) return value
            if (value is JSONObject) findArray(value, key)?.let { return it }
            if (value is JSONArray) {
                for (i in 0 until value.length()) {
                    val child = value.optJSONObject(i) ?: continue
                    findArray(child, key)?.let { return it }
                }
            }
        }
        return null
    }

    private fun firstString(o: JSONObject, vararg keys: String): String {
        for (k in keys) {
            val v = o.opt(k)
            if (v != null && v != JSONObject.NULL && v.toString().isNotBlank() && v.toString() != "0") return v.toString()
        }
        return ""
    }

    private fun firstNum(o: JSONObject, vararg keys: String): Double {
        for (k in keys) {
            val v = o.opt(k)
            val n = when (v) {
                is Number -> v.toDouble()
                is String -> v.replace(",", "").toDoubleOrNull() ?: 0.0
                else -> 0.0
            }
            if (n != 0.0) return n
        }
        return 0.0
    }

    private fun formatMoney(n: Double): String = String.format(Locale.US, "%,.0f", n)
}
