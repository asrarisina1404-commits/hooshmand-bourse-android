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
    var score: Int = 0, var reasons: List<String> = emptyList(),
    var atr14Pct: Double = 0.0, var adx14: Double = 0.0,
    var obvRising: Boolean = false, var bollingerPosition: Double = 0.0,
    var buyIValue: Double = 0.0, var sellIValue: Double = 0.0
) {
    // Use the exchange-reported retail trade values; never estimate money flow from volume * close.
    val netRetailValue: Double get() = buyIValue - sellIValue
    val buyerPower: Double get() = if (sellICount > 0 && buyICount > 0 && buyIValue > 0 && sellIValue > 0)
        (buyIValue / buyICount) / (sellIValue / sellICount) else 0.0
}

class MainActivity : Activity() {
    // Market snapshot and up to five history requests run concurrently.
    private val pool = Executors.newFixedThreadPool(6)
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
        content.addView(tv("تحلیل میان‌مدت • فیلتر محافظه‌کارانه • نمایش حداکثر ۸ نماد برای بررسی", 13f, muted))
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
                        firstNum(c, "buy_CountI"), firstNum(c, "sell_CountI"),
                        firstNum(c, "buy_I_Value", "buy_IValue", "buyIValue"),
                        firstNum(c, "sell_I_Value", "sell_IValue", "sellIValue")
                    )
                }.toMutableList()
                if (candidates.isEmpty()) throw Exception("دیده‌بان جایگزین TSETMC نیز نمادی برنگرداند؛ احتمالاً دسترسی شبکه یا سرویس بازار محدود است.")
                // Fetch enough sessions to assess the medium-term trend, not just a five-day bounce.
                // Deep-check a broader liquid universe. The remaining symbols are not claimed to have full technical validation.
                val liquid = candidates.sortedByDescending { it.value }.take(80)
                val historyTasks = liquid.map { s ->
                    pool.submit {
                    // Reject thin or implausible client-type samples before using buyer power.
                    s.clientDataReady = s.buyIVol > 0 && s.sellIVol > 0 &&
                        s.buyIValue > 0 && s.sellIValue > 0 &&
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
                                // Build actual calendar-week closes from daily rows; ignore the still-forming latest week.
                                s.weeklyTrend = calculateWeeklyTrend(daily)
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
                                // A separate 55-session ceiling is used as a real technical
                                // objective; do not manufacture a 2R target from the stop distance.
                                val priorRows55 = daily.drop(1).take(55)
                                val nextResistance = priorRows55.maxOfOrNull { firstNum(it, "priceMax", "pmax") } ?: 0.0
                                val recentRows = daily.drop(1).take(10)
                                val recentLow = recentRows.mapNotNull { row ->
                                    val low = firstNum(row, "priceMin", "pmin")
                                    if (low > 0) low else null
                                }.minOrNull() ?: 0.0
                                s.resistance = priorHigh
                                s.support = recentLow

                                // Additional independent confirmation measures from daily OHLCV.
                                val highs = daily.map { firstNum(it, "priceMax", "pmax") }.reversed()
                                val lows = daily.map { firstNum(it, "priceMin", "pmin") }.reversed()
                                val chronologicalVolumes = daily.map { firstNum(it, "qTotTran5J", "volume") }.reversed()
                                s.atr14Pct = calculateAtrPercent(highs, lows, closes)
                                s.adx14 = calculateAdx(highs, lows, closes)
                                s.obvRising = isObvRising(closes, chronologicalVolumes)
                                val last20 = closes.takeLast(20)
                                val mean20 = last20.average()
                                val sd20 = kotlin.math.sqrt(last20.map { (it - mean20) * (it - mean20) }.average())
                                val lowerBand = mean20 - 2.0 * sd20
                                val upperBand = mean20 + 2.0 * sd20
                                s.bollingerPosition = if (upperBand > lowerBand)
                                    (latest - lowerBand) / (upperBand - lowerBand) else 0.5
                                s.breakout20 = priorHigh > 0 && s.close > priorHigh
                                s.entryTrigger = if (priorHigh > 0) priorHigh * 1.005 else 0.0
                                val atrDistance = latest * s.atr14Pct / 100.0
                                val supportStop = if (recentLow > 0) recentLow * 0.99 else 0.0
                                val atrStop = if (atrDistance > 0) s.entryTrigger - 2.0 * atrDistance else 0.0
                                val proposedStop = when {
                                    supportStop > 0 && atrStop > 0 -> min(supportStop, atrStop)
                                    supportStop > 0 -> supportStop
                                    atrStop > 0 -> atrStop
                                    else -> 0.0
                                }
                                // A stop must be below the planned entry; otherwise the trade plan is invalid.
                                s.stopLoss = if (s.entryTrigger > 0 && proposedStop > 0 &&
                                    proposedStop < s.entryTrigger * 0.995) proposedStop else 0.0
                                // Show a target only when a real prior resistance offers room above entry.
                                s.targetPrice = if (s.entryTrigger > 0 &&
                                    nextResistance > s.entryTrigger * 1.01) nextResistance else 0.0
                                s.rewardRisk = if (s.entryTrigger > 0 && s.stopLoss > 0 &&
                                    s.targetPrice > s.entryTrigger) {
                                    (s.targetPrice - s.entryTrigger) / (s.entryTrigger - s.stopLoss)
                                } else 0.0
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
                }
                historyTasks.forEach { it.get() }
                val analyzed = liquid.map { score(it) }
                val scored = analyzed.filter { isBuyCandidate(it) }
                    .sortedWith(compareByDescending<Share> { it.score }.thenByDescending { it.rewardRisk }.thenByDescending { it.value }).take(5)
                val ready = analyzed.filter { isReadyForEntry(it) && !isBuyCandidate(it) }
                    .sortedByDescending { it.score }.take(5)
                // First show symbols that meet the normal watchlist conditions.
                // If fewer than eight qualify, fill the remaining slots with the best-ranked
                // analyzed shares, clearly labeled as preliminary review only (never a buy signal).
                val qualifiedWatchlist = analyzed.filter {
                    val totalRetail = it.buyIValue + it.sellIValue
                    val netPct = if (totalRetail > 0) (it.buyIValue - it.sellIValue) / totalRetail * 100 else -100.0
                    it.historyReady && it.clientDataReady && it.score >= 45 &&
                        netPct >= 0.0 && it.buyerPower in 0.9..2.5 &&
                        it.rsi14 in 40.0..70.0 && !isBuyCandidate(it)
                }.sortedWith(compareByDescending<Share> { it.score }.thenByDescending { it.rewardRisk }.thenByDescending { it.value }).take(8)
                val watchlist = (qualifiedWatchlist + analyzed
                    .filter { candidate -> qualifiedWatchlist.none { it.code == candidate.code } &&
                        !isBuyCandidate(candidate) && !isReadyForEntry(candidate) }
                    .sortedWith(compareByDescending<Share> { it.historyReady }.thenByDescending { it.score }))
                    .distinctBy { it.code }.take(8)
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


    // Aggregate daily records by their actual exchange date into calendar weeks.
    // The newest bucket is excluded because it may be an unfinished trading week.
    private fun calculateWeeklyTrend(rowsNewestFirst: List<JSONObject>): Boolean {
        val weekly = sortedMapOf<String, Double>()
        val parser = java.text.SimpleDateFormat("yyyyMMdd", Locale.US)
        parser.isLenient = false
        for (row in rowsNewestFirst.asReversed()) {
            val rawDate = firstString(row, "dEven", "date", "tradeDate")
                .filter { it.isDigit() }
            if (rawDate.length < 8) continue
            val date = try { parser.parse(rawDate.substring(0, 8)) } catch (_: Exception) { null } ?: continue
            val calendar = Calendar.getInstance(Locale.US)
            calendar.time = date
            val key = String.format(
                Locale.US, "%04d-%02d",
                calendar.get(Calendar.YEAR), calendar.get(Calendar.WEEK_OF_YEAR)
            )
            val close = firstNum(row, "pClosing", "pc")
            if (close > 0.0) weekly[key] = close
        }
        val completedWeeks = weekly.values.toList().dropLast(1)
        if (completedWeeks.size < 3) return false
        val lastThree = completedWeeks.takeLast(3)
        return lastThree[0] < lastThree[1] && lastThree[1] < lastThree[2]
    }

    private fun score(s: Share): Share {
        var p = 0
        val why = ArrayList<String>()
        val totalRetail = s.buyIValue + s.sellIValue
        val netPct = if (totalRetail > 0) (s.buyIValue - s.sellIValue) / totalRetail * 100 else 0.0
        // Weighted score: trend 20, retail flow 20, volume 15,
        // breakout 15, momentum 10, liquidity 10, trade risk/reward 10.
        if (s.historyReady && s.close > s.sma20 && s.sma20 > s.sma50) { p += 10; why.add("روند روزانه میان‌مدت صعودی") }
        if (s.weeklyTrend) { p += 10; why.add("تأیید روند هفتگی") }
        if (netPct >= 20) { p += 12; why.add("ورود خالص قابل‌توجه پول حقیقی") }
        else if (netPct >= 8) { p += 8; why.add("برتری خرید حقیقی") }
        else if (netPct < 0) { p = max(0, p - 15); why.add("هشدار: خروج خالص پول حقیقی؛ امتیاز کاهشی اعمال شد") }
        if (s.clientDataReady && s.buyerPower in 1.15..2.5) { p += 8; why.add("قدرت خریدار بهتر از فروشنده و در محدوده قابل بررسی") }
        else if (s.clientDataReady && s.buyerPower > 2.5) { why.add("هشدار: قدرت خریدار غیرعادی؛ احتمال اختلال نمونه یا تمرکز معاملات، نیازمند بررسی") }
        else if (s.clientDataReady && s.buyerPower in 0.7..1.14) { why.add("قدرت خریدار برتری معناداری ندارد") }
        else if (!s.clientDataReady) { why.add("داده حقیقی/حقوقی کم‌حجم یا نامعتبر؛ قدرت خریدار قابل اتکا نیست") }
        if (s.volumeRatio in 1.3..3.5) { p += 8; why.add("افزایش حجم معقول نسبت به میانگین") }
        if (s.breakout20 && s.close >= s.last * 0.995) { p += 7; why.add("شکست سقف ۲۰روزه با تثبیت قیمت پایانی") } else if (s.breakout20) { why.add("شکست سقف بدون تثبیت کافی؛ نیازمند تأیید") }
        if (s.last >= s.close && s.close >= s.yesterday && s.yesterday > 0) { p += 5; why.add("تثبیت قیمت پایانی و آخرین معامله در محدوده مثبت") }
        if (s.baseVolume > 0 && s.volume >= s.baseVolume) { p += 5; why.add("حجم بالاتر از حجم مبنا") }
        if (s.value >= 5_000_000_000.0) { p += 10; why.add("ارزش معاملات روزانه مناسب") }
        if (s.rewardRisk >= 2.0) { p += 10; why.add("هدف تکنیکالی واقعی حداقل دو برابر ریسک برآوردی است") }
        else if (s.historyReady) { why.add("فاصله تا مقاومت بعدی برای نسبت سود به زیان ۲ کافی نیست") }
        if (s.adx14 >= 18.0 && s.adx14 <= 40.0) { p += 5; why.add("قدرت روند با ADX تأیید می‌شود") }
        else if (s.adx14 > 50.0) { p -= 4; why.add("هشدار: ADX بسیار بالا؛ احتمال دیررسیدن به روند") }
        if (s.obvRising) { p += 5; why.add("OBV صعودی؛ حجم با حرکت قیمت هم‌جهت است") }
        else if (s.historyReady) { p -= 3; why.add("OBV صعودی نیست؛ انباشت حجمی تأیید نشده") }
        if (s.atr14Pct in 0.5..7.5) { p += 2; why.add("نوسان ATR در محدوده قابل مدیریت") }
        else if (s.atr14Pct > 7.5) { p -= 5; why.add("هشدار: نوسان روزانه بالا بر اساس ATR") }
        if (s.bollingerPosition in 0.35..0.95) { p += 2; why.add("موقعیت قیمت در باند بولینگر مناسب است") }
        else if (s.bollingerPosition > 1.05) { p -= 5; why.add("هشدار: قیمت بیش از حد از باند بالایی بولینگر فاصله گرفته") }
        if (s.rsi14 > 72.0) { p = min(p, 55); why.add("هشدار: RSI وارد محدوده داغ شده") }
        // Do not let a high indicator score disguise an unusable entry/exit plan.
        val validTradePlan = s.entryTrigger > 0 && s.stopLoss > 0 &&
            s.targetPrice > s.entryTrigger && s.rewardRisk > 0.0
        if (!validTradePlan) {
            p = min(p, 64)
            why.add("برنامه معامله کامل نیست: هدف یا حد ضرر معتبر وجود ندارد؛ امتیاز خرید محدود شد")
        }
        s.score = min(100, max(0, p))
        s.reasons = why
        return s
    }

    private fun isBuyCandidate(s: Share): Boolean {
        val totalRetail = s.buyIValue + s.sellIValue
        val netPct = if (totalRetail > 0) (s.buyIValue - s.sellIValue) / totalRetail * 100 else 0.0
        return s.historyReady && s.clientDataReady &&
            s.close > s.sma20 && s.sma20 > s.sma50 &&
            s.weeklyTrend && s.rsi14 in 50.0..68.0 &&
            s.macdBullish && s.volumeRatio in 1.3..3.5 && s.breakout20 && s.close >= s.last * 0.995 &&
            s.adx14 >= 18.0 && s.adx14 <= 45.0 && s.obvRising &&
            s.atr14Pct in 0.5..7.5 && s.bollingerPosition in 0.35..1.05 &&
            netPct >= 8.0 && s.buyerPower in 1.1..2.5 &&
            s.value >= 5_000_000_000.0 && s.rewardRisk >= 2.0 && s.score >= 75
    }

    private fun isReadyForEntry(s: Share): Boolean {
        val totalRetail = s.buyIValue + s.sellIValue
        val netPct = if (totalRetail > 0) (s.buyIValue - s.sellIValue) / totalRetail * 100 else -100.0
        return s.historyReady && s.clientDataReady &&
            s.close > s.sma20 && s.sma20 > s.sma50 && s.weeklyTrend &&
            s.rsi14 in 48.0..68.0 && s.macdBullish &&
            s.volumeRatio >= 1.2 && !s.breakout20 &&
            s.adx14 >= 15.0 && s.obvRising && s.atr14Pct in 0.5..8.0 &&
            s.resistance > 0 && s.close >= s.resistance * 0.97 &&
            netPct >= 5.0 && s.buyerPower in 1.1..3.0 &&
            s.value >= 5_000_000_000.0 && s.rewardRisk >= 2.0
    }

    private fun calculateAtrPercent(highs: List<Double>, lows: List<Double>, closes: List<Double>): Double {
        if (highs.size < 15 || lows.size != highs.size || closes.size != highs.size) return 0.0
        val trueRanges = ArrayList<Double>()
        for (i in max(1, highs.size - 14) until highs.size) {
            val tr = max(highs[i] - lows[i], max(kotlin.math.abs(highs[i] - closes[i - 1]), kotlin.math.abs(lows[i] - closes[i - 1])))
            if (tr > 0) trueRanges.add(tr)
        }
        val last = closes.lastOrNull() ?: return 0.0
        return if (last > 0 && trueRanges.isNotEmpty()) trueRanges.average() / last * 100.0 else 0.0
    }

    private fun calculateAdx(highs: List<Double>, lows: List<Double>, closes: List<Double>): Double {
        if (highs.size < 30 || lows.size != highs.size || closes.size != highs.size) return 0.0
        val period = 14
        var smoothTr = 0.0
        var smoothPlusDm = 0.0
        var smoothMinusDm = 0.0
        for (i in 1..period) {
            val upMove = highs[i] - highs[i - 1]
            val downMove = lows[i - 1] - lows[i]
            val plusDm = if (upMove > downMove && upMove > 0) upMove else 0.0
            val minusDm = if (downMove > upMove && downMove > 0) downMove else 0.0
            val tr = max(highs[i] - lows[i], max(kotlin.math.abs(highs[i] - closes[i - 1]), kotlin.math.abs(lows[i] - closes[i - 1])))
            smoothTr += tr
            smoothPlusDm += plusDm
            smoothMinusDm += minusDm
        }
        val dx = ArrayList<Double>()
        fun appendDx() {
            if (smoothTr <= 0.0) return
            val plusDi = 100.0 * smoothPlusDm / smoothTr
            val minusDi = 100.0 * smoothMinusDm / smoothTr
            val sum = plusDi + minusDi
            if (sum > 0.0) dx.add(100.0 * kotlin.math.abs(plusDi - minusDi) / sum)
        }
        appendDx()
        for (i in period + 1 until highs.size) {
            val upMove = highs[i] - highs[i - 1]
            val downMove = lows[i - 1] - lows[i]
            val plusDm = if (upMove > downMove && upMove > 0) upMove else 0.0
            val minusDm = if (downMove > upMove && downMove > 0) downMove else 0.0
            val tr = max(highs[i] - lows[i], max(kotlin.math.abs(highs[i] - closes[i - 1]), kotlin.math.abs(lows[i] - closes[i - 1])))
            smoothTr = smoothTr - smoothTr / period + tr
            smoothPlusDm = smoothPlusDm - smoothPlusDm / period + plusDm
            smoothMinusDm = smoothMinusDm - smoothMinusDm / period + minusDm
            appendDx()
        }
        if (dx.size < period) return 0.0
        var adx = dx.take(period).average()
        for (i in period until dx.size) adx = (adx * (period - 1) + dx[i]) / period
        return adx
    }

    private fun isObvRising(closes: List<Double>, volumes: List<Double>): Boolean {
        if (closes.size < 15 || volumes.size != closes.size) return false
        var obv = 0.0
        val series = ArrayList<Double>()
        for (i in 1 until closes.size) {
            obv += when {
                closes[i] > closes[i - 1] -> volumes[i]
                closes[i] < closes[i - 1] -> -volumes[i]
                else -> 0.0
            }
            series.add(obv)
        }
        return series.size >= 11 && series.last() > series[series.size - 11]
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

        addSection("۸ نماد برتر برای بررسی", "فهرست تکمیلی رتبه‌بندی‌شده؛ موارد تکمیلی لزوماً شرایط خرید ندارند", watchlist.size, amber)
        if (watchlist.isEmpty()) content.addView(tv("نماد قابل بررسی دریافت نشد؛ کیفیت داده بازار کافی نیست.", 14f, muted))
        else watchlist.forEachIndexed { idx, item ->
            val qualified = item.historyReady && item.clientDataReady && item.score >= 45 &&
                !isBuyCandidate(item) && !isReadyForEntry(item)
            addShareCard(item, idx + 1, if (qualified) "زیر نظر" else "بررسی اولیه؛ بدون تأیید", amber)
        }
        content.addView(tv("ارزش خالص پول حقیقی فقط با فیلد ارزش خرید/فروش حقیقی معتبر محاسبه می‌شود؛ در نبود آن، برنامه سیگنال خرید صادر نمی‌کند.", 12f, amber))
        content.addView(tv("اندازه موقعیت: مبلغ ریسک مجاز ÷ فاصله قیمت ورود تا حد ضرر. تا تعیین سرمایه و ریسک مجاز، برنامه حجم خرید پیشنهادی نمی‌دهد.", 12f, amber))
        content.addView(tv("امتیاز ۱۰۰تایی رتبه‌بندی نسبی است و احتمال موفقیت یا تضمین سود نیست. بک‌تست آماری هنوز به داده تاریخی تعدیل‌شده و لحاظ صفوف و کارمزد نیاز دارد.", 12f, amber))
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

    private fun entryBlockers(s: Share): List<String> {
        val blockers = ArrayList<String>()
        val totalRetail = s.buyIValue + s.sellIValue
        val netPct = if (totalRetail > 0) (s.buyIValue - s.sellIValue) / totalRetail * 100 else -100.0
        if (!s.historyReady) blockers.add("سابقه قیمتی کافی یا معتبر دریافت نشده است.")
        if (!(s.close > s.sma20 && s.sma20 > s.sma50)) blockers.add("روند میان‌مدت هنوز صعودیِ تأییدشده نیست (قیمت و میانگین‌های ۲۰/۵۰).")
        if (!s.weeklyTrend) blockers.add("روند هفتگی صعودی تأیید نشده است.")
        if (!s.macdBullish) blockers.add("MACD هنوز شتاب صعودی را تأیید نمی‌کند.")
        if (s.rsi14 !in 50.0..68.0) blockers.add("RSI خارج از محدوده مطلوب ۵۰ تا ۶۸ است.")
        if (s.adx14 < 18.0) blockers.add("قدرت روند با ADX به حداقل ۱۸ نرسیده است.")
        if (!s.obvRising) blockers.add("OBV افزایش انباشت حجمی را تأیید نمی‌کند.")
        if (s.atr14Pct > 7.5) blockers.add("نوسان ATR بالاست و ریسک حد ضرر افزایش یافته است.")
        if (s.bollingerPosition > 1.05) blockers.add("قیمت از باند بالایی بولینگر بیش از حد فاصله گرفته است.")
        if (s.volumeRatio < 1.3) blockers.add("حجم معاملات هنوز حداقل ۱٫۳ برابر میانگین نیست.")
        if (!s.breakout20) blockers.add("شکست معتبر سقف ۲۰ روزه هنوز رخ نداده است.")
        if (!s.clientDataReady) blockers.add("داده خریداران و فروشندگان حقیقی برای تأیید کافی/معتبر نیست.")
        if (netPct < 8.0) blockers.add("ورود خالص پول حقیقی به حداقل ۸٪ نرسیده است یا خروج پول ثبت شده.")
        if (s.buyerPower > 2.5) blockers.add("قدرت خریدار غیرعادی است (بیش از ۲٫۵) و باید دستی بررسی شود.")
        else if (s.buyerPower < 1.1) blockers.add("قدرت خریدار حقیقی برتری کافی ندارد.")
        if (s.value < 5_000_000_000.0) blockers.add("ارزش معاملات روزانه به حداقل نقدشوندگی تعیین‌شده نرسیده است.")
        if (s.breakout20 && s.close < s.last * 0.995) blockers.add("قیمت پایانی شکست را تأیید نکرده؛ احتمال شکست ناموفق وجود دارد.")
        if (s.stopLoss <= 0) blockers.add("حد ضرر معتبر پایین‌تر از نقطه ورود محاسبه نشده؛ ریسک معامله قابل تعریف نیست.")
        if (s.targetPrice <= s.entryTrigger) blockers.add("مقاومت معتبر بالاتر از نقطه ورود پیدا نشد؛ هدف تکنیکالی تعیین نشده است.")
        else if (s.rewardRisk < 2.0) blockers.add("فاصله تا مقاومت بعدی برای نسبت سود به زیان حداقل ۱ به ۲ کافی نیست.")
        return blockers.distinct()
    }

    private fun addShareCard(s: Share, index: Int, label: String, labelColor: Int) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 14, 18, 14)
            setBackgroundColor(surface)
            elevation = 3f
        }
        card.addView(tv("$index. ${s.symbol}     ${s.score}/100  •  $label", 18f, labelColor, true))
        if (s.clientDataReady) {
            card.addView(tv("ارزش خالص خرید حقیقی: ${formatMoney(s.netRetailValue)} ریال    |    قدرت سرانه خرید: ${"%.2f".format(Locale.US, s.buyerPower)}", 12f, Color.WHITE))
        } else {
            card.addView(tv("ارزش معاملات حقیقی/تعداد خریداران معتبر نیست؛ جریان پول و قدرت خریدار قابل اتکا نیست.", 12f, amber))
        }
        card.addView(tv("تغییر ۵روزه: ${"%.1f".format(Locale.US, s.fiveDayReturn)}٪    |    حجم: ${"%.1f".format(Locale.US, s.volumeRatio)} برابر میانگین", 12f, muted))
        card.addView(tv("RSI: ${"%.1f".format(Locale.US, s.rsi14)}    |    روند هفتگی: ${if (s.weeklyTrend) "صعودی" else "تأییدنشده"}    |    MACD: ${if (s.macdBullish) "مثبت" else "منفی"}", 12f, muted))
        card.addView(tv("ADX: ${"%.1f".format(Locale.US, s.adx14)}    |    ATR: ${"%.1f".format(Locale.US, s.atr14Pct)}٪    |    OBV: ${if (s.obvRising) "صعودی" else "تأییدنشده"}", 12f, muted))
        card.addView(tv("جایگاه باند بولینگر: ${"%.2f".format(Locale.US, s.bollingerPosition)}  (بالاتر از ۱ یعنی بالای باند)", 11f, muted))
        if (s.resistance > 0) card.addView(tv("مقاومت: ${formatMoney(s.resistance)}    |    حمایت: ${formatMoney(s.support)}", 12f, Color.WHITE))
        if (s.entryTrigger > 0) {
            card.addView(tv("تریگر ورود برآوردی: ${formatMoney(s.entryTrigger)}", 12f, Color.WHITE))
        }
        if (s.stopLoss > 0) {
            card.addView(tv("حد ضرر برآوردی: ${formatMoney(s.stopLoss)}", 12f, Color.WHITE))
        } else {
            card.addView(tv("حد ضرر معتبر: تعیین نشده؛ ورود بر اساس این تحلیل مجاز نیست.", 12f, amber))
        }
        if (s.targetPrice > s.entryTrigger && s.rewardRisk > 0) {
            card.addView(tv("هدف تکنیکالی برآوردی: ${formatMoney(s.targetPrice)}    |    نسبت سود به زیان: ۱ به ${"%.2f".format(Locale.US, s.rewardRisk)}", 12f, green, true))
        } else {
            card.addView(tv("هدف تکنیکالی معتبر: تعیین نشده؛ مقاومت بالاتر با فاصله کافی پیدا نشد.", 12f, amber, true))
            card.addView(tv("نسبت سود به زیان: قابل محاسبه نیست (نه ۰ به‌عنوان نسبت واقعی).", 11f, amber))
        }
        card.addView(tv("هدف و حد ضرر تخمینی‌اند؛ شکاف قیمت، صف و کارمزد می‌تواند نسبت واقعی را تغییر دهد.", 11f, amber))
        if (label != "خرید معتبر") {
            val blockers = entryBlockers(s)
            card.addView(tv("چرا هنوز خرید نکنیم؟", 14f, amber, true))
            if (blockers.isEmpty()) {
                card.addView(tv("مانع اصلی در داده‌های فعلی دیده نشد؛ با این حال تا تأیید نهایی شکست و تثبیت، ورود قطعی نیست.", 12f, muted))
            } else {
                blockers.forEach { reason -> card.addView(tv("• $reason", 12f, muted)) }
            }
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

    private fun Share.copyClientData(
        buyVol: Double, sellVol: Double, buyCount: Double, sellCount: Double,
        buyValue: Double, sellValue: Double
    ): Share =
        Share(code, symbol, last, close, yesterday, volume, baseVolume, value,
            buyVol, sellVol, buyCount, sellCount, fiveDayReturn, volumeRatio,
            sma20, sma50, rsi14, macdBullish, weeklyTrend, breakout20,
            historyReady, true, resistance, support, entryTrigger, stopLoss,
            targetPrice, rewardRisk, score, reasons, atr14Pct, adx14, obvRising,
            bollingerPosition, buyValue, sellValue)

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
