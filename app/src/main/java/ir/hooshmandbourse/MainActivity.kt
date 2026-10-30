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
    var sma20: Double = 0.0, var sma50: Double = 0.0, var rsi14: Double = 0.0,
    var macdBullish: Boolean = false, var weeklyTrend: Boolean = false,
    var breakout20: Boolean = false, var historyReady: Boolean = false,
    var clientDataReady: Boolean = false,
    var resistance: Double = 0.0, var support: Double = 0.0,
    var entryTrigger: Double = 0.0, var stopLoss: Double = 0.0,
    var targetPrice: Double = 0.0, var rewardRisk: Double = 0.0,
    var score: Int = 0, var reasons: List<String> = emptyList()
) {
    val netRetailValue: Double get() = (buyIVol - sellIVol) * close
    val buyerPower: Double get() = if (sellICount > 0 && buyICount > 0)
        (buyIVol / buyICount) / (sellIVol / sellICount) else 0.0
}

class MainActivity : Activity() {
    private val pool = Executors.newSingleThreadExecutor()
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var refresh: Button
    private val green = Color.rgb(0, 205, 145)
    private val navy = Color.rgb(8, 20, 42)
    private val surface = Color.rgb(15, 32, 62)
    private val muted = Color.rgb(170, 190, 220)
    private val amber = Color.rgb(245, 177, 55)
    private val page = Color.rgb(5, 14, 31)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = navy
        window.navigationBarColor = navy
        val scroll = ScrollView(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 34, 32, 36)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(page)
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
        content.addView(tv("دیده‌بان و فرصت‌های معاملاتی", 20f, Color.WHITE, true))
        content.addView(tv("تحلیل میان‌مدت • فیلتر محافظه‌کارانه • حداکثر ۵ فرصت خرید", 13f, muted))
    }

    private fun showIntro() {
        content.addView(tv("سیگنال‌ها بر پایه داده‌های دریافتی و شروط فنی هستند؛ هیچ امتیازی تضمین سود نیست.", 13f, amber))
    }

    private fun loadMarket() {
        refresh.isEnabled = false
        status.text = "در حال دریافت داده‌های بازار از TSETMC…"
        pool.execute {
            try {
                // The CDN GetMarketWatch endpoint can return an empty marketwatch array.
                // Use the legacy full-market snapshot instead; its rows are documented CSV-like records.
                val clientRows = try {
                    val clientJson = getJson("https://cdn.tsetmc.com/api/ClientType/GetClientTypeAll")
                    findArray(clientJson, "clientTypeAllDto") ?: JSONArray()
                } catch (_: Exception) { JSONArray() }
                val clients = HashMap<String, JSONObject>()
                for (i in 0 until clientRows.length()) {
                    val o = clientRows.optJSONObject(i) ?: continue
                    val code = firstString(o, "insCode", "InsCode", "instrumentCode")
                    if (code.isNotBlank()) clients[code] = o
                }
                val candidates = loadLegacyMarketRows().map { s ->
                    val c = clients[s.code] ?: JSONObject()
                    s.copyClientData(
                        firstNum(c, "buy_I_Volume"), firstNum(c, "sell_I_Volume"),
                        firstNum(c, "buy_CountI"), firstNum(c, "sell_CountI")
                    )
                }.toMutableList()
                if (candidates.isEmpty()) throw Exception("دیده‌بان جایگزین TSETMC نیز نمادی برنگرداند؛ احتمالاً دسترسی شبکه یا سرویس بازار محدود است.")
                // Fetch enough sessions to assess the medium-term trend, not just a five-day bounce.
                // Deep-check a broader liquid universe. The remaining symbols are not claimed to have full technical validation.
                val liquid = candidates.sortedByDescending { it.value }.take(80)
                for (s in liquid) {
                    // Reject thin or implausible client-type samples before using buyer power.
                    s.clientDataReady = s.buyIVol > 0 && s.sellIVol > 0 &&
                        s.buyICount >= 5 && s.sellICount >= 5 &&
                        s.buyerPower.isFinite() && s.buyerPower in 0.7..3.5
                    try {
                        val history = getJson("https://cdn.tsetmc.com/api/ClosingPrice/GetClosingPriceDailyList/${s.code}/80")
                        val rows = findArray(history, "closingPriceDaily")
                        if (rows != null && rows.length() >= 55) {
                            val daily = (0 until rows.length()).mapNotNull { i ->
                                rows.optJSONObject(i)?.let { row ->
                                    val c = firstNum(row, "pClosing", "pc")
                                    if (c > 0) row else null
                                }
                            }
                            val closesNewestFirst = daily.map { firstNum(it, "pClosing", "pc") }
                            if (closesNewestFirst.size >= 55) {
                                val closes = closesNewestFirst.reversed()
                                val latest = closes.last()
                                s.sma20 = closes.takeLast(20).average()
                                s.sma50 = closes.takeLast(50).average()
                                val fiveAgo = closes[closes.size - 6]
                                if (fiveAgo > 0) s.fiveDayReturn = (latest / fiveAgo - 1.0) * 100.0
                                val weekly = closesNewestFirst.filterIndexed { index, _ -> index % 5 == 0 }.take(5)
                                s.weeklyTrend = weekly.size >= 3 && weekly[0] > weekly[1] && weekly[1] > weekly[2]
                                val gains = ArrayList<Double>()
                                val losses = ArrayList<Double>()
                                for (i in closes.size - 14 until closes.size) {
                                    val change = closes[i] - closes[i - 1]
                                    gains.add(max(0.0, change))
                                    losses.add(max(0.0, -change))
                                }
                                val avgGain = gains.average()
                                val avgLoss = losses.average()
                                s.rsi14 = if (avgLoss == 0.0) 100.0 else 100.0 - (100.0 / (1.0 + avgGain / avgLoss))
                                val volumes = (1 until min(21, daily.size)).mapNotNull { i ->
                                    val v = firstNum(daily[i], "qTotTran5J", "volume")
                                    if (v > 0) v else null
                                }
                                val avgVol = volumes.average()
                                if (avgVol > 0) s.volumeRatio = s.volume / avgVol
                                val priorRows = daily.drop(1).take(20)
                                val priorHigh = priorRows.maxOfOrNull { firstNum(it, "priceMax", "pmax") } ?: 0.0
                                val recentRows = daily.drop(1).take(10)
                                val recentLow = recentRows.mapNotNull { row ->
                                    val low = firstNum(row, "priceMin", "pmin")
                                    if (low > 0) low else null
                                }.minOrNull() ?: 0.0
                                s.resistance = priorHigh
                                s.support = recentLow
                                s.breakout20 = priorHigh > 0 && s.close > priorHigh
                                s.entryTrigger = if (priorHigh > 0) priorHigh * 1.005 else 0.0
                                s.stopLoss = if (recentLow > 0) recentLow * 0.99 else 0.0
                                if (s.entryTrigger > s.stopLoss && s.stopLoss > 0) {
                                    s.targetPrice = s.entryTrigger + 2.0 * (s.entryTrigger - s.stopLoss)
                                    s.rewardRisk = 2.0
                                }
                                val ema12 = ema(closes, 12)
                                val ema26 = ema(closes, 26)
                                val macdLine = ema12 - ema26
                                val signalLine = macdSignal(closes)
                                s.macdBullish = macdLine > signalLine
                                s.historyReady = s.sma20 > 0 && s.sma50 > 0 && s.rsi14 > 0
                            }
                        }
                    } catch (_: Exception) { /* missing history must not be treated as a buy signal */ }
                }
                val analyzed = liquid.map { score(it) }
                val scored = analyzed.filter { isBuyCandidate(it) }
                    .sortedByDescending { it.score }.take(5)
                val ready = analyzed.filter { isReadyForEntry(it) && !isBuyCandidate(it) }
                    .sortedByDescending { it.score }.take(5)
                val watchlist = analyzed.filter {
                    val totalRetail = it.buyIVol + it.sellIVol
                    val netPct = if (totalRetail > 0) (it.buyIVol - it.sellIVol) / totalRetail * 100 else -100.0
                    it.historyReady && it.clientDataReady && it.score >= 45 &&
                        netPct >= 0.0 && it.buyerPower in 0.9..3.5 &&
                        it.rsi14 in 40.0..70.0 && !isBuyCandidate(it)
                }.sortedByDescending { it.score }.take(8)
                runOnUiThread {
                    render(scored, ready, watchlist, candidates.size, liquid.size)
                    status.text = "داده دریافت شد • ${candidates.size} نماد شناسایی شد • ${liquid.size} نماد تحلیل عمیق شد"
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
        if (s.historyReady && s.close > s.sma20 && s.sma20 > s.sma50) { p += 20; why.add("روند میان‌مدت صعودی: قیمت بالای میانگین ۲۰ و میانگین ۲۰ بالای ۵۰") }
        if (s.rsi14 in 50.0..68.0) { p += 10; why.add("قدرت نسبی مناسب بدون ورود به محدوده اشباع خرید") }
        if (s.macdBullish) { p += 10; why.add("شتاب صعودی MACD") }
        if (s.weeklyTrend) { p += 10; why.add("تأیید روند هفتگی") }
        if (netPct >= 20) { p += 15; why.add("ورود خالص قابل‌توجه پول حقیقی") }
        else if (netPct >= 8) { p += 10; why.add("برتری خرید حقیقی") }
        else if (netPct < 0) { p = max(0, p - 15); why.add("هشدار: خروج خالص پول حقیقی؛ امتیاز کاهشی اعمال شد") }
        if (s.clientDataReady && s.buyerPower >= 1.5) { p += 10; why.add("قدرت خریدار حقیقی بالا") }
        else if (s.clientDataReady && s.buyerPower >= 1.15) { p += 6; why.add("قدرت خریدار بهتر از فروشنده") }
        else if (!s.clientDataReady) { why.add("داده حقیقی/حقوقی کم‌حجم یا نامعتبر؛ قدرت خریدار قابل اتکا نیست") }
        if (s.volumeRatio in 1.3..3.5) { p += 10; why.add("افزایش حجم معقول نسبت به میانگین") }
        if (s.breakout20) { p += 5; why.add("عبور قیمت پایانی از سقف ۲۰ روزه") }
        if (s.last >= s.close && s.close >= s.yesterday && s.yesterday > 0) { p += 5; why.add("تثبیت قیمت پایانی و آخرین معامله در محدوده مثبت") }
        if (s.baseVolume > 0 && s.volume >= s.baseVolume) { p += 5; why.add("حجم بالاتر از حجم مبنا") }
        if (s.value >= 5_000_000_000.0) { p += 5; why.add("ارزش معاملات روزانه مناسب") }
        if (s.rsi14 > 72.0) { p = min(p, 55); why.add("هشدار: RSI وارد محدوده داغ شده") }
        s.score = min(100, p)
        s.reasons = why
        return s
    }

    private fun isBuyCandidate(s: Share): Boolean {
        val totalRetail = s.buyIVol + s.sellIVol
        val netPct = if (totalRetail > 0) (s.buyIVol - s.sellIVol) / totalRetail * 100 else 0.0
        return s.historyReady && s.clientDataReady &&
            s.close > s.sma20 && s.sma20 > s.sma50 &&
            s.weeklyTrend && s.rsi14 in 50.0..68.0 &&
            s.macdBullish && s.volumeRatio >= 1.3 && s.breakout20 &&
            netPct >= 8.0 && s.buyerPower in 1.1..3.0 &&
            s.value >= 5_000_000_000.0 && s.rewardRisk >= 2.0 && s.score >= 70
    }

    private fun isReadyForEntry(s: Share): Boolean {
        val totalRetail = s.buyIVol + s.sellIVol
        val netPct = if (totalRetail > 0) (s.buyIVol - s.sellIVol) / totalRetail * 100 else -100.0
        return s.historyReady && s.clientDataReady &&
            s.close > s.sma20 && s.sma20 > s.sma50 && s.weeklyTrend &&
            s.rsi14 in 48.0..68.0 && s.macdBullish &&
            s.volumeRatio >= 1.2 && !s.breakout20 &&
            s.resistance > 0 && s.close >= s.resistance * 0.97 &&
            netPct >= 5.0 && s.buyerPower in 1.1..3.0 &&
            s.value >= 5_000_000_000.0 && s.rewardRisk >= 2.0
    }

    private fun ema(values: List<Double>, period: Int): Double {
        if (values.size < period) return 0.0
        val k = 2.0 / (period + 1.0)
        var result = values.take(period).average()
        for (i in period until values.size) result = values[i] * k + result * (1.0 - k)
        return result
    }

    private fun macdSignal(closesOldestFirst: List<Double>): Double {
        if (closesOldestFirst.size < 35) return 0.0
        val macdValues = ArrayList<Double>()
        for (end in 26..closesOldestFirst.size) {
            val slice = closesOldestFirst.take(end)
            macdValues.add(ema(slice, 12) - ema(slice, 26))
        }
        return ema(macdValues, 9)
    }

    private fun render(list: List<Share>, ready: List<Share>, watchlist: List<Share>, scanned: Int, deeplyScanned: Int) {
        content.removeViews(3, max(0, content.childCount - 3))
        content.addView(tv("خلاصه بازار  •  $scanned نماد شناسایی‌شده  •  $deeplyScanned تحلیل عمیق", 13f, muted, true))
        addSection("خرید معتبر", "شکست مقاومت با تأیید روند، حجم و جریان پول", list.size, green)
        if (list.isEmpty()) content.addView(tv("فعلاً هیچ نمادی تمام شروط خرید را ندارد؛ از ورود عجولانه خودداری کن.", 14f, amber))
        else list.forEachIndexed { idx, item -> addShareCard(item, idx + 1, "خرید معتبر", green) }

        addSection("آماده ورود پس از تأیید", "نزدیک مقاومت است؛ فقط پس از شکست و تثبیت بررسی شود", ready.size, Color.rgb(70, 145, 255))
        if (ready.isEmpty()) content.addView(tv("نمادی در آستانه شکست معتبر شناسایی نشد.", 14f, muted))
        else ready.forEachIndexed { idx, item -> addShareCard(item, idx + 1, "آماده ورود", Color.rgb(70, 145, 255)) }

        addSection("صرفاً زیر نظر", "شرایط اولیه دارد اما هنوز نقطه ورود مناسبی نیست", watchlist.size, amber)
        if (watchlist.isEmpty()) content.addView(tv("نماد واجد شرایط زیر نظر با داده کافی پیدا نشد.", 14f, muted))
        else watchlist.forEachIndexed { idx, item -> addShareCard(item, idx + 1, "زیر نظر", amber) }
        content.addView(tv("سطوح حمایت و مقاومت، برآوردهای تکنیکال از داده‌های روزانه‌اند؛ قبل از معامله با نمودار و اطلاعیه‌ها تطبیق بده. حد ضرر قطعی یا تضمین‌شده نیست.", 12f, amber))
    }

    private fun addSection(title: String, subtitle: String, count: Int, color: Int) {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 14, 18, 14)
            setBackgroundColor(surface)
        }
        panel.addView(tv("$title   •   $count نماد", 19f, color, true))
        panel.addView(tv(subtitle, 12f, muted))
        val lp = LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = 18
            bottomMargin = 8
        }
        content.addView(panel, lp)
    }

    private fun addShareCard(s: Share, index: Int, label: String, labelColor: Int) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 14, 18, 14)
            setBackgroundColor(surface)
            elevation = 3f
        }
        card.addView(tv("$index. ${s.symbol}     ${s.score}/100  •  $label", 18f, labelColor, true))
        card.addView(tv("پول خالص حقیقی: ${formatMoney(s.netRetailValue)} ریال    |    قدرت خریدار: ${"%.2f".format(Locale.US, s.buyerPower)}", 12f, Color.WHITE))
        card.addView(tv("تغییر ۵روزه: ${"%.1f".format(Locale.US, s.fiveDayReturn)}٪    |    حجم: ${"%.1f".format(Locale.US, s.volumeRatio)} برابر میانگین", 12f, muted))
        card.addView(tv("RSI: ${"%.1f".format(Locale.US, s.rsi14)}    |    روند هفتگی: ${if (s.weeklyTrend) "صعودی" else "تأییدنشده"}    |    MACD: ${if (s.macdBullish) "مثبت" else "منفی"}", 12f, muted))
        if (s.resistance > 0) card.addView(tv("مقاومت: ${formatMoney(s.resistance)}    |    حمایت: ${formatMoney(s.support)}", 12f, Color.WHITE))
        if (s.entryTrigger > 0 && s.stopLoss > 0) {
            card.addView(tv("تریگر ورود: ${formatMoney(s.entryTrigger)}    |    حد ضرر برآوردی: ${formatMoney(s.stopLoss)}", 12f, Color.WHITE))
            card.addView(tv("هدف محاسباتی ۲R: ${formatMoney(s.targetPrice)}    |    نسبت سود به زیان: ۱ به ۲", 12f, green, true))
        } else {
            card.addView(tv("سطوح ورود/حد ضرر به علت ناکافی بودن داده معتبر محاسبه نشد.", 12f, amber))
        }
        card.addView(tv("دلایل امتیاز: ${s.reasons.joinToString("، ")}", 12f, muted))
        val lp = LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = 10
        }
        content.addView(card, lp)
    }


    // Reads the legacy TSETMC MarketWatch snapshot. The third @ section contains
    // semicolon-separated rows: insCode, ISIN, symbol, name, time, pf, pc, pl,
    // trades, volume, value, low, high, yesterday, EPS, baseVolume, ...
    private fun loadLegacyMarketRows(): List<Share> {
        val raw = getText("https://old.tsetmc.com/tsev2/data/MarketWatchInit.aspx?h=0&r=0")
        val sections = raw.split("@")
        if (sections.size < 3) throw Exception("ساختار پاسخ دیده‌بان جایگزین معتبر نیست.")
        val rows = sections[2].split(";")
        val result = ArrayList<Share>()
        for (line in rows) {
            val cols = line.trim().split(",")
            if (cols.size < 16) continue
            val code = cols[0].trim()
            val symbol = cols[2].trim().removeSuffix("3").trim()
            if (code.isBlank() || symbol.isBlank()) continue
            val close = cols[6].toDoubleOrNull() ?: 0.0
            val last = cols[7].toDoubleOrNull() ?: 0.0
            val volume = cols[9].toDoubleOrNull() ?: 0.0
            val value = cols[10].toDoubleOrNull() ?: 0.0
            val yesterday = cols[13].toDoubleOrNull() ?: 0.0
            val baseVolume = cols[15].toDoubleOrNull() ?: 0.0
            // TSETMC MarketWatchInit columns: flow=17, yVal=22.
            // Keep only ordinary shares (yVal=300) listed on TSE/IFB (flow=1 or 2).
            // This excludes ETFs/funds, rights, options, bonds and other instruments.
            val flow = cols.getOrNull(17)?.toIntOrNull() ?: -1
            val yVal = cols.getOrNull(22)?.toIntOrNull() ?: -1
            if (yVal != 300 || flow !in setOf(1, 2)) continue
            if (close <= 0.0 || volume <= 0.0 || value <= 0.0) continue
            result.add(Share(code, symbol, last, close, yesterday, volume, baseVolume, value, 0.0, 0.0, 0.0, 0.0))
        }
        return result
    }

    private fun Share.copyClientData(buyVol: Double, sellVol: Double, buyCount: Double, sellCount: Double): Share =
        Share(code, symbol, last, close, yesterday, volume, baseVolume, value,
            buyVol, sellVol, buyCount, sellCount, fiveDayReturn, volumeRatio,
            sma20, sma50, rsi14, macdBullish, weeklyTrend, breakout20,
            historyReady, true, resistance, support, entryTrigger, stopLoss,
            targetPrice, rewardRisk, score, reasons)

    private fun getText(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection)
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
        conn.setRequestProperty("Accept", "text/plain,*/*")
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream.bufferedReader().use { it.readText() }
            if (code !in 200..299) throw Exception("HTTP $code در دریافت دیده‌بان")
            if (body.contains("دسترسی شما") || body.contains("مسدود") || body.contains("General Error Detected"))
                throw Exception("دسترسی به دیده‌بان TSETMC مسدود شده است")
            return body
        } finally { conn.disconnect() }
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
