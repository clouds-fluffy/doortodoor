package com.example.doortodoor

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * 문앞까지 · 화면
 *
 * - 추천 계산은 HospitalEngine.kt 의 Hospital.recommend() 가 합니다. (웹 프로토타입과 같은 결과)
 * - 화면 크기와 색은 Pleos Connect 디자인 가이드 값을 따랐습니다.
 *   (세로 앱 창 너비 840 기준: 큰 버튼 높이 100·모서리 32, 중간 버튼 높이 80·모서리 24, 창 좌우 여백 40)
 * - SDK 연동 전 단계입니다. 아래 "SDK 연동 지점" 함수들은 지금은 테스트 버튼으로만 호출됩니다.
 *   실제 연동 시 각 함수 안의 TODO 자리에 NaviHelper / FusedLocation / Vehicle SDK 호출을 넣으면 됩니다.
 */
class MainActivity : AppCompatActivity() {

    private enum class Screen { DESTINATION, CONDITION, RESULT, FAVORITES, APPROACH, DETAIL, ARRIVED, WALK, BOARD }

    private data class Favorite(val destId: String, val condId: String)

    private class Palette(
        val background: Int, val surfaceLow: Int, val textPrimary: Int, val textSecondary: Int,
        val buttonBasic: Int, val buttonFilled: Int, val onFilled: Int, val positive: Int, val active: Int
    )

    private val light = Palette(
        Color.rgb(0xF7, 0xF8, 0xFA), Color.rgb(0xED, 0xEE, 0xF2), Color.rgb(0x13, 0x14, 0x17), Color.rgb(0x68, 0x6A, 0x72),
        Color.rgb(0xE0, 0xE1, 0xE6), Color.rgb(0x31, 0x32, 0x36), Color.WHITE, Color.rgb(0x00, 0x64, 0xFF), Color.rgb(0x02, 0xC2, 0x65)
    )
    private val dark = Palette(
        Color.rgb(0x13, 0x14, 0x17), Color.rgb(0x31, 0x32, 0x36), Color.WHITE, Color.rgb(0xA4, 0xA8, 0xAE),
        Color.rgb(0x44, 0x46, 0x4E), Color.rgb(0xED, 0xEE, 0xF2), Color.rgb(0x13, 0x14, 0x17), Color.rgb(0x00, 0x82, 0xFF), Color.rgb(0x00, 0xE0, 0x74)
    )
    private val pal: Palette get() = if (nightMode) dark else light

    private var currentScreen = Screen.DESTINATION
    private var dest: HDest? = null
    private var cond: Cond? = null
    private var rec: Recommendation? = null
    private var isInside500m = false
    private var nightMode = false

    private val favorites = mutableListOf<Favorite>()
    private lateinit var prefs: SharedPreferences
    private lateinit var column: LinearLayout

    // ---------- 크기: 디자인 가이드 값(창 너비 840 기준)을 현재 창 너비에 맞춰 줄입니다 ----------
    // 가로로 넓은 창(두 창 너비)에서는 왼쪽에 약도, 오른쪽에 840 너비의 안내 칸을 둡니다
    private val wideLayout: Boolean
        get() = resources.configuration.screenWidthDp >= 1400

    private val scale: Float
        get() = if (wideLayout) 1f else (resources.configuration.screenWidthDp / 840f).coerceIn(0.55f, 1f)

    private fun px(designDp: Float): Int = (designDp * scale * resources.displayMetrics.density).roundToInt()

    private val sidePadding: Int
        get() = px(40f)

    private fun windowWidthPx(): Int = (resources.configuration.screenWidthDp * resources.displayMetrics.density).roundToInt()

    // 넓은 창에서 왼쪽 약도에 무엇을 그릴지 (차량 경로, 걸어갈 길, 핀)
    private var wideMapRequest = Triple(false, false, false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("doortodoor_settings", MODE_PRIVATE)
        nightMode = prefs.getBoolean("night_mode", false)
        supportActionBar?.hide()   // 앱 이름은 화면 안에 표시하므로 위쪽 제목 띠는 숨깁니다
        loadFavorites()
        showScreen(Screen.DESTINATION)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        showScreen(currentScreen)
    }

    // ---------- 즐겨찾기 (목적지와 조건을 함께 저장) ----------
    private fun loadFavorites() {
        favorites.clear()
        try {
            val arr = JSONArray(prefs.getString("favorite_routes_v2", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val f = Favorite(o.getString("dest"), o.getString("cond"))
                if (Hospital.destById(f.destId) != null && Hospital.condById(f.condId) != null) favorites.add(f)
            }
        } catch (e: Exception) {
            favorites.clear()
        }
    }

    private fun saveFavorites() {
        val arr = JSONArray()
        favorites.forEach { arr.put(JSONObject().apply { put("dest", it.destId); put("cond", it.condId) }) }
        prefs.edit().putString("favorite_routes_v2", arr.toString()).apply()
    }

    private fun isFavorite(): Boolean {
        val d = dest ?: return false
        val c = cond ?: return false
        return favorites.any { it.destId == d.id && it.condId == c.id }
    }

    private fun toggleFavorite() {
        val d = dest ?: return
        val c = cond ?: return
        if (isFavorite()) favorites.removeAll { it.destId == d.id && it.condId == c.id }
        else favorites.add(Favorite(d.id, c.id))
        saveFavorites()
        showScreen(currentScreen)
    }

    private fun favoriteLabel(f: Favorite): String {
        val d = Hospital.destById(f.destId); val c = Hospital.condById(f.condId)
        return "${d?.name ?: ""} · ${c?.name ?: ""}"
    }

    // ---------- 화면 부품 ----------
    private fun rounded(color: Int, radiusDesignDp: Float): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = px(radiusDesignDp).toFloat() }

    private fun lp(topDesignDp: Float = 0f, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height).apply { topMargin = px(topDesignDp) }

    private fun makeText(text: String, sizeDesignDp: Float, bold: Boolean, color: Int): TextView =
        TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_PX, px(sizeDesignDp).toFloat())
            setTextColor(color)
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            includeFontPadding = false
            setLineSpacing(px(sizeDesignDp * 0.28f).toFloat(), 1f)
        }

    private fun addText(text: String, size: Float, bold: Boolean = false, secondary: Boolean = false, top: Float = 16f): TextView {
        val v = makeText(text, size, bold, if (secondary) pal.textSecondary else pal.textPrimary)
        column.addView(v, lp(top))
        return v
    }

    /** 큰 버튼(높이 100, 모서리 32) */
    private fun addPrimaryButton(text: String, top: Float = 16f, onClick: () -> Unit) {
        val v = makeText(text, 32f, true, pal.onFilled).apply {
            gravity = Gravity.CENTER
            background = rounded(pal.buttonFilled, 32f)
            setPadding(px(40f), px(16f), px(40f), px(16f))
            isClickable = true
            setOnClickListener { onClick() }
        }
        column.addView(v, lp(top, px(100f)))
    }

    /** 중간 버튼(높이 80, 모서리 24) */
    private fun addSecondaryButton(text: String, top: Float = 16f, onClick: () -> Unit) {
        val v = makeText(text, 30f, true, pal.textPrimary).apply {
            gravity = Gravity.CENTER
            background = rounded(pal.buttonBasic, 24f)
            setPadding(px(32f), px(12f), px(32f), px(12f))
            isClickable = true
            setOnClickListener { onClick() }
        }
        column.addView(v, lp(top, px(80f)))
    }

    /** 글자만 있는 버튼(뒤로 가기 등) */
    private fun addTextButton(text: String, top: Float = 8f, onClick: () -> Unit) {
        val v = makeText(text, 24f, false, pal.textSecondary).apply {
            paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
            gravity = Gravity.CENTER_VERTICAL
            minHeight = px(64f)
            isClickable = true
            setOnClickListener { onClick() }
        }
        column.addView(v, lp(top))
    }

    /** 고르는 카드(최소 높이 100, 모서리 32): 제목과 설명 */
    private fun addChoiceCard(title: String, desc: String?, top: Float = 16f, onClick: () -> Unit) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(pal.surfaceLow, 32f)
            setPadding(px(40f), px(20f), px(40f), px(20f))
            minimumHeight = px(100f)
            isClickable = true
            setOnClickListener { onClick() }
        }
        box.addView(makeText(title, 30f, true, pal.textPrimary))
        if (!desc.isNullOrEmpty()) {
            box.addView(
                makeText(desc, 24f, false, pal.textSecondary),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(6f) }
            )
        }
        column.addView(box, lp(top))
    }

    /** 추천 지점 카드 */
    private fun addPickCard(title: String, sub: String?, top: Float = 16f) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(pal.surfaceLow, 32f)
            setPadding(px(40f), px(28f), px(40f), px(28f))
        }
        box.addView(makeText(title, 36f, true, pal.textPrimary))
        if (!sub.isNullOrEmpty()) {
            box.addView(
                makeText(sub, 26f, false, pal.textSecondary),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(8f) }
            )
        }
        column.addView(box, lp(top))
    }

    /** 숫자 두 개를 나란히: 걷는 거리 / 걸어갈 길 */
    private fun addFacts(a: Pair<String, String>, b: Pair<String, String>, top: Float = 12f) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(a, b).forEachIndexed { i, f ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(pal.surfaceLow, 24f)
                setPadding(px(32f), px(20f), px(32f), px(20f))
            }
            cell.addView(makeText(f.first, 32f, true, pal.textPrimary))
            cell.addView(
                makeText(f.second, 24f, false, pal.textSecondary),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(6f) }
            )
            row.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i == 1) leftMargin = px(12f) })
        }
        column.addView(row, lp(top))
    }

    /** 남는 높이를 차지하는 빈칸: 이 아래에 넣는 버튼은 화면 맨 아래에 붙습니다 */
    private fun addSpacer() {
        column.addView(View(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    /** 맨 위 줄: 앱 이름과 주간/야간 전환 */
    private fun addHeader() {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = px(80f) }
        row.addView(makeText("문앞까지", 32f, true, pal.textPrimary), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val toggle = makeText(if (nightMode) "주간 모드" else "야간 모드", 24f, true, pal.textPrimary).apply {
            gravity = Gravity.CENTER
            background = rounded(pal.buttonBasic, 16f)
            setPadding(px(24f), px(8f), px(24f), px(8f))
            minHeight = px(64f)
            isClickable = true
            setOnClickListener {
                nightMode = !nightMode
                prefs.edit().putBoolean("night_mode", nightMode).apply()
                showScreen(currentScreen)
            }
        }
        row.addView(toggle)
        column.addView(row, lp(0f))
        addText("${Hospital.NAME} · 약도는 실제 축척과 다름", 20f, secondary = true, top = 0f)
    }

    /** 약도 */
    private fun buildMap(showDriveRoute: Boolean, showWalkRoute: Boolean, showPins: Boolean): FacilityMapView {
        val target = dest?.zone ?: "none"
        val name = "map_" + (if (nightMode) "dark" else "light") + "_" + target
        val id = resources.getIdentifier(name, "drawable", packageName)
        val bitmap: Bitmap? = if (id != 0) BitmapFactory.decodeResource(resources, id) else null
        return FacilityMapView(this).apply {
            this.bitmap = bitmap
            this.night = nightMode
            this.best = rec?.best
            this.destNode = dest?.let { Hospital.NODES[it.node] }
            this.showDriveRoute = showDriveRoute
            this.showWalkRoute = showWalkRoute
            this.showPins = showPins
            this.colorDrive = pal.positive
            this.colorWalk = pal.active
            this.colorText = pal.textPrimary
            this.colorSub = pal.textSecondary
            this.colorChip = if (nightMode) Color.rgb(0x1D, 0x1E, 0x21) else Color.WHITE
            this.cornerPx = px(40f).toFloat()
        }
    }

    private fun addMap(showDriveRoute: Boolean, showWalkRoute: Boolean, showPins: Boolean) {
        if (wideLayout) {                      // 넓은 창: 왼쪽 약도에 그리도록 기록만 해 둡니다
            wideMapRequest = Triple(showDriveRoute, showWalkRoute, showPins)
            return
        }
        // 화면 전체가 아니라 이 앱에 주어진 창의 너비를 기준으로 합니다
        val w = windowWidthPx() - sidePadding * 2
        column.addView(buildMap(showDriveRoute, showWalkRoute, showPins), lp(16f, (w * 400f / 840f).roundToInt()))
    }

    // ---------- 화면 전환 ----------
    private fun showScreen(screen: Screen) {
        currentScreen = screen
        wideMapRequest = Triple(false, false, false)
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(sidePadding, px(8f), sidePadding, px(40f))
            setBackgroundColor(pal.background)
        }
        addHeader()

        val d = dest; val c = cond; val r = rec?.best

        when (screen) {

            // 1. 목적지 선택
            Screen.DESTINATION -> {
                addMap(showDriveRoute = false, showWalkRoute = false, showPins = false)
                addText("어디로 가세요?", 40f, bold = true, top = 28f)
                if (favorites.isNotEmpty()) {
                    addText("자주 가는 곳", 24f, secondary = true, top = 20f)
                    favorites.take(2).forEach { f ->
                        addChoiceCard("★ " + favoriteLabel(f), "한 번 눌러 바로 추천 받기", top = 12f) {
                            dest = Hospital.destById(f.destId); cond = Hospital.condById(f.condId)
                            goResult()
                        }
                    }
                    addText("전체 목적지", 24f, secondary = true, top = 24f)
                }
                Hospital.DESTINATIONS.forEach { item ->
                    addChoiceCard(item.name, item.desc, top = 12f) {
                        dest = item; cond = null; rec = null
                        showScreen(Screen.CONDITION)
                    }
                }
                if (favorites.isNotEmpty()) { addSpacer(); addTextButton("자주 가는 곳 관리", top = 12f) { showScreen(Screen.FAVORITES) } }
            }

            // 2. 오늘의 조건 선택
            Screen.CONDITION -> {
                addTextButton("목적지 다시 고르기") { dest = null; showScreen(Screen.DESTINATION) }
                addText("${d?.name ?: ""}까지,\n오늘은 어떻게 가세요?", 40f, bold = true, top = 8f)
                Hospital.CONDS.forEach { item ->
                    addChoiceCard(item.name, item.desc, top = 12f) { cond = item; goResult() }
                }
            }

            // 3. 추천 결과
            Screen.RESULT -> {
                addMap(showDriveRoute = true, showWalkRoute = true, showPins = true)
                addTextButton("조건 다시 고르기 · ${d?.name ?: ""}, ${c?.name ?: ""}") { showScreen(Screen.CONDITION) }
                if (r == null || c == null) {
                    addText("조건에 맞는 지점을 찾지 못했습니다.", 30f, bold = true)
                } else {
                    addPickCard(r.node.name ?: "", Hospital.zoneOf(r.node, c), top = 8f)
                    addFacts(Pair("${r.walkM}m", "걷는 거리"), Pair(Hospital.feat(r), "걸어갈 길"))
                    rec?.alt?.let { a -> addText("다른 선택지: ${a.node.name} (${a.walkM}m, ${Hospital.feat(a)})", 24f, secondary = true, top = 16f) }
                    val startLabel = when {
                        c.id == "pickup" -> "승하차 구역으로 안내 시작"
                        r.node.stop == "lot" -> "이 주차장으로 안내 시작"
                        else -> "이 구역으로 안내 시작"
                    }
                    addSpacer()
                    addPrimaryButton(startLabel, top = 28f) { startGuidance() }
                    addSecondaryButton(if (isFavorite()) "★ 자주 가는 곳에서 빼기" else "자주 가는 곳으로 저장") { toggleFavorite() }
                }
            }

            // 자주 가는 곳 관리
            Screen.FAVORITES -> {
                addText("자주 가는 곳", 40f, bold = true, top = 20f)
                if (favorites.isEmpty()) addText("저장한 곳이 없습니다.", 28f, secondary = true)
                favorites.toList().forEach { f ->
                    addPickCard(favoriteLabel(f), null, top = 20f)
                    addSecondaryButton("이 조합으로 추천 받기", top = 8f) {
                        dest = Hospital.destById(f.destId); cond = Hospital.condById(f.condId); goResult()
                    }
                    addTextButton("삭제", top = 0f) { favorites.remove(f); saveFavorites(); showScreen(Screen.FAVORITES) }
                }
                addSpacer()
                addSecondaryButton("처음으로", top = 28f) { resetAll() }
            }

            // 4. 주행 중: 길 안내는 기본 내비게이션이 합니다 (약도에 경로 선을 그리지 않습니다)
            Screen.APPROACH -> {
                addText("${d?.name ?: ""}, ${c?.name ?: ""}", 24f, secondary = true, top = 24f)
                addText("기본 내비게이션이\n안내하고 있습니다", 40f, bold = true, top = 8f)
                addText("병원 근처에 들어서면 도착 지점을 약도로 보여드립니다.", 28f, secondary = true)
                if (r != null && c != null) addPickCard(r.node.name ?: "", Hospital.zoneOf(r.node, c), top = 24f)
                addSpacer()
                addText("위치 SDK 연동 전이라 아래 버튼으로 거리를 흉내 냅니다.", 20f, secondary = true, top = 32f)
                addSecondaryButton("현재 거리 800m (테스트)", top = 8f) { onDistanceToHospitalChanged(800f) }
                addSecondaryButton("현재 거리 499m (테스트)") { onDistanceToHospitalChanged(499f) }
            }

            // 5. 시설 근처 상세 안내: 도착 지점만 표시
            Screen.DETAIL -> {
                addMap(showDriveRoute = false, showWalkRoute = false, showPins = true)
                addText("${d?.name ?: ""}, ${c?.name ?: ""}", 24f, secondary = true, top = 20f)
                addText("병원 근처에 들어왔습니다", 40f, bold = true, top = 8f)
                if (r != null && c != null) {
                    addPickCard(r.node.name ?: "", Hospital.zoneOf(r.node, c), top = 16f)
                    addFacts(Pair("${r.walkM}m", "내려서 걷는 거리"), Pair(Hospital.feat(r), "걸어갈 길"))
                }
                addSpacer()
                addText("도착 이벤트 연동 전이라 아래 버튼으로 도착을 흉내 냅니다.", 20f, secondary = true, top = 28f)
                addSecondaryButton("도착 (테스트)", top = 8f) { onDestinationArrived() }
            }

            // 6. 도착
            Screen.ARRIVED -> {
                addMap(showDriveRoute = false, showWalkRoute = true, showPins = true)
                addText("${d?.name ?: ""}, ${c?.name ?: ""}", 24f, secondary = true, top = 20f)
                addText("${r?.node?.name ?: ""}에\n도착했습니다", 40f, bold = true, top = 8f)
                if (r != null && c != null) addPickCard(Hospital.zoneOf(r.node, c), null, top = 16f)
                addText(if (c?.id == "pickup") "동승자가 문을 열고 타면 안내를 마칩니다." else "문을 열면 걸어갈 길을 알려드립니다.", 28f, secondary = true)
                addSpacer()
                addText("도어 SDK 연동 전이라 아래 버튼으로 문 열림을 흉내 냅니다.", 20f, secondary = true, top = 28f)
                addSecondaryButton("문 열림 (테스트)", top = 8f) { onDoorOpened() }
            }

            // 7. 주차 후: 걸어갈 길
            Screen.WALK -> {
                addMap(showDriveRoute = false, showWalkRoute = true, showPins = true)
                if (r != null) addText("내려서 ${d?.name ?: ""}까지 ${r.walkM}m, ${Hospital.feat(r)}", 24f, secondary = true, top = 20f)
                addText("걸어갈 길", 40f, bold = true, top = 8f)
                val steps = ArrayList<String>()
                r?.walkEdges?.forEach { e -> e.say?.let { steps.add(it) } }
                d?.inside?.let { steps.add(it) }
                steps.forEachIndexed { i, s -> addChoiceCardPlain("${i + 1}", s) }
                addSpacer()
                addSecondaryButton("처음으로", top = 28f) { resetAll() }
            }

            // 7'. 빨리 태우고 나가기: 문이 열리면 안내 종료
            Screen.BOARD -> {
                addText("${d?.name ?: ""}, ${c?.name ?: ""}", 24f, secondary = true, top = 24f)
                addText("문이 열렸습니다", 40f, bold = true, top = 8f)
                addPickCard("동승자가 타면 출발하세요", if (r != null && c != null) Hospital.zoneOf(r.node, c) else null)
                addText("걸어갈 길 안내 없이 여기서 안내를 마칩니다.", 28f, secondary = true)
                addSpacer()
                addSecondaryButton("처음으로", top = 28f) { resetAll() }
            }
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            setBackgroundColor(pal.background)
        }
        scroll.addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        if (!wideLayout) {
            setContentView(scroll)
            return
        }

        // 넓은 창: 왼쪽 전체가 약도 카드, 오른쪽이 안내 칸(너비 840). 창 사이 간격 20, 바깥 여백 40
        val panelW = px(840f)
        val map = buildMap(wideMapRequest.first, wideMapRequest.second, wideMapRequest.third)
        map.showLegend = true
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(pal.background)
        }
        root.addView(map, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
            setMargins(px(40f), px(40f), px(20f), px(40f))
        })
        root.addView(scroll, LinearLayout.LayoutParams(panelW, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
    }

    /** 걸어갈 길 한 단계: 번호와 문장 */
    private fun addChoiceCardPlain(number: String, text: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(pal.surfaceLow, 24f)
            setPadding(px(32f), px(20f), px(32f), px(20f))
            minimumHeight = px(88f)
        }
        row.addView(makeText(number, 30f, true, pal.textSecondary), LinearLayout.LayoutParams(px(44f), ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(makeText(text, 30f, true, pal.textPrimary), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        column.addView(row, lp(12f))
    }

    private fun goResult() {
        val d = dest; val c = cond
        rec = if (d != null && c != null) Hospital.recommend(d, c) else null
        showScreen(Screen.RESULT)
    }

    private fun resetAll() {
        dest = null; cond = null; rec = null; isInside500m = false
        showScreen(Screen.DESTINATION)
    }

    // =====================================================================
    // SDK 연동 지점 (지금은 테스트 버튼으로만 호출됩니다)
    // =====================================================================

    /** 안내 시작: 추천 지점을 기본 내비게이션의 목적지로 넘기고, 시설 반경 진입 알림을 등록합니다. */
    private fun startGuidance() {
        isInside500m = false
        // TODO NaviHelper: requestRoute() 로 rec.best.node (추천 주차장 입구 또는 승하차 구역)의 위도·경도·명칭을 전달
        // TODO FusedLocation: addProximityAlert() 로 시설 좌표와 반경 500m 등록
        showScreen(Screen.APPROACH)
    }

    /** 위치 변화: 실제 연동 시 FusedLocation 의 onTransition(ENTER) 에서 화면을 바꿉니다. */
    private fun onDistanceToHospitalChanged(distanceMeters: Float) {
        if (isInside500m) return
        if (distanceMeters <= 500f) {
            isInside500m = true
            showScreen(Screen.DETAIL)
        }
    }

    /** 도착: 실제 연동 시 NaviHelperEventListener.onDestinationArrived() 에서 호출합니다. */
    private fun onDestinationArrived() {
        showScreen(Screen.ARRIVED)
    }

    /** 문 열림: 실제 연동 시 Vehicle SDK Door 도메인의 registerDoorPosition() 콜백에서 호출합니다. */
    private fun onDoorOpened() {
        // TODO 안내 종료 시 removeProximityAlert(), unregisterDoorPosition() 등 해제
        showScreen(if (cond?.id == "pickup") Screen.BOARD else Screen.WALK)
    }
}

/**
 * 시설 약도.
 * 3D로 미리 렌더링한 병원 그림(res/drawable-nodpi/map_*.webp) 위에, 같은 카메라 시점으로 계산한
 * 경로·핀·이름을 면으로만 그립니다. 그림 파일이 없으면 단순한 평면 약도로 대신 그립니다.
 */
class FacilityMapView(context: Context) : View(context) {

    var bitmap: Bitmap? = null
    var night = false
    var best: Candidate? = null
    var destNode: HNode? = null
    var showDriveRoute = false
    var showWalkRoute = false
    var showPins = false
    var colorDrive = Color.BLUE
    var colorWalk = Color.GREEN
    var colorText = Color.BLACK
    var colorSub = Color.GRAY
    var colorChip = Color.WHITE
    var cornerPx = 0f
    var showLegend = false

    // 카드 안에서 병원 그림이 놓이는 영역 (너비에 맞추고 세로 가운데)
    private var mapW = 0f
    private var mapH = 0f
    private var mapTop = 0f

    // 3D 장면을 렌더링한 카메라의 투영 행렬 (열 우선 16개 값)
    private val m = floatArrayOf(2.240161f, -0.039641f, -0.007314f, -0.007289f, 0f, 3.07805f, -0.758795f, -0.75627f, -0.02496f, -3.55775f, -0.656403f, -0.654219f, -941.791142f, 1162.40302f, 1295.585624f, 1311.240913f)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val path = Path()
    private val rect = RectF()
    private val clip = Path()

    /** 평면 좌표(x, y)와 높이 z 를 화면 좌표로 옮깁니다. */
    private fun project(x: Float, y: Float, z: Float, out: FloatArray) {
        val cx = m[0] * x + m[4] * z + m[8] * y + m[12]
        val cy = m[1] * x + m[5] * z + m[9] * y + m[13]
        val cw = m[3] * x + m[7] * z + m[11] * y + m[15]
        out[0] = (cx / cw + 1f) / 2f * mapW
        out[1] = mapTop + (1f - cy / cw) / 2f * mapH
    }

    private val p0 = FloatArray(2)
    private val p1 = FloatArray(2)

    private fun polygon(canvas: Canvas, pts: FloatArray, z: Float, color: Int) {
        path.reset()
        var i = 0
        while (i < pts.size) {
            project(pts[i], pts[i + 1], z, p0)
            if (i == 0) path.moveTo(p0[0], p0[1]) else path.lineTo(p0[0], p0[1])
            i += 2
        }
        path.close()
        fill.color = color
        canvas.drawPath(path, fill)
    }

    private fun disc(canvas: Canvas, x: Float, y: Float, r: Float, z: Float, color: Int) {
        val pts = FloatArray(40)
        for (i in 0 until 20) {
            val t = (i / 20.0 * Math.PI * 2).toFloat()
            pts[i * 2] = x + cos(t) * r
            pts[i * 2 + 1] = y + sin(t) * r
        }
        polygon(canvas, pts, z, color)
    }

    /** 바닥에 붙은 띠: 구간마다 사각 면, 꺾이는 곳마다 둥근 면 */
    private fun ribbon(canvas: Canvas, ids: List<String>, w: Float, z: Float, color: Int) {
        val nodes = ids.mapNotNull { Hospital.NODES[it] }
        for (i in 0 until nodes.size - 1) {
            val a = nodes[i]; val b = nodes[i + 1]
            val l = hypot(b.x - a.x, b.y - a.y).let { if (it == 0f) 1f else it }
            val nx = -(b.y - a.y) / l * w / 2f; val ny = (b.x - a.x) / l * w / 2f
            polygon(canvas, floatArrayOf(a.x + nx, a.y + ny, b.x + nx, b.y + ny, b.x - nx, b.y - ny, a.x - nx, a.y - ny), z, color)
        }
        nodes.forEach { disc(canvas, it.x, it.y, w / 2f, z, color) }
    }

    private fun dots(canvas: Canvas, ids: List<String>, r: Float, gap: Float, z: Float, color: Int) {
        val nodes = ids.mapNotNull { Hospital.NODES[it] }
        var carry = 0f
        for (i in 0 until nodes.size - 1) {
            val a = nodes[i]; val b = nodes[i + 1]
            val l = hypot(b.x - a.x, b.y - a.y)
            if (l == 0f) continue
            var d = carry
            while (d <= l) {
                disc(canvas, a.x + (b.x - a.x) * d / l, a.y + (b.y - a.y) * d / l, r, z, color)
                d += gap
            }
            carry = d - l
        }
    }

    private fun pin(canvas: Canvas, n: HNode, color: Int, radius: Float) {
        disc(canvas, n.x + 5f, n.y + 3f, 9f, 2f, Color.argb(56, 0, 0, 0))
        project(n.x, n.y, 2f, p0); project(n.x, n.y, 26f, p1)
        path.reset()
        path.moveTo(p1[0] - radius * 0.55f, p1[1]); path.lineTo(p1[0] + radius * 0.55f, p1[1]); path.lineTo(p0[0], p0[1]); path.close()
        fill.color = color
        canvas.drawPath(path, fill)
        canvas.drawCircle(p1[0], p1[1], radius, fill)
        fill.color = Color.WHITE
        canvas.drawCircle(p1[0], p1[1], radius * 0.36f, fill)
    }

    private fun label(canvas: Canvas, text: String, x: Float, y: Float, z: Float, size: Float, bold: Boolean, color: Int, center: Boolean) {
        project(x, y, z, p0)
        textPaint.color = color
        textPaint.textSize = size
        textPaint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        textPaint.textAlign = if (center) Paint.Align.CENTER else Paint.Align.LEFT
        canvas.drawText(text, p0[0], p0[1] + size * 0.35f, textPaint)
    }

    private fun parkingLabel(canvas: Canvas, text: String, n: HNode, k: Float) {
        project(n.x - 52f, n.y + 44f, 0f, p0)
        val r = 12f * k
        fill.color = if (night) Color.rgb(0xA4, 0xA8, 0xAE) else Color.rgb(0x68, 0x6A, 0x72)
        canvas.drawCircle(p0[0] - 14f * k, p0[1] - 6f * k, r, fill)
        textPaint.color = if (night) Color.rgb(0x13, 0x14, 0x17) else Color.WHITE
        textPaint.textSize = 16f * k; textPaint.typeface = Typeface.DEFAULT_BOLD; textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("P", p0[0] - 14f * k, p0[1] - 0.5f * k, textPaint)
        textPaint.color = colorText; textPaint.textSize = 18f * k; textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(text, p0[0] + 4f * k, p0[1], textPaint)
    }

    /** 출입구 특징 아이콘 모양을 path 에 담습니다 */
    private fun iconPath(f: String, cx: Float, cy: Float, k: Float) {
        path.reset()
        when (f) {
            "elev" -> {
                path.moveTo(cx - 5 * k, cy - 1.5f * k); path.lineTo(cx + 5 * k, cy - 1.5f * k); path.lineTo(cx, cy - 7.5f * k); path.close()
                path.moveTo(cx - 5 * k, cy + 1.5f * k); path.lineTo(cx + 5 * k, cy + 1.5f * k); path.lineTo(cx, cy + 7.5f * k); path.close()
            }
            "stairs" -> {
                path.moveTo(cx - 7 * k, cy + 6 * k); path.lineTo(cx - 7 * k, cy + 2 * k); path.lineTo(cx - 2.5f * k, cy + 2 * k)
                path.lineTo(cx - 2.5f * k, cy - 2 * k); path.lineTo(cx + 2 * k, cy - 2 * k); path.lineTo(cx + 2 * k, cy - 6 * k)
                path.lineTo(cx + 7 * k, cy - 6 * k); path.lineTo(cx + 7 * k, cy + 6 * k); path.close()
            }
            "ramp" -> {
                path.moveTo(cx - 8 * k, cy + 5.5f * k); path.lineTo(cx + 8 * k, cy + 5.5f * k); path.lineTo(cx + 8 * k, cy - 4.5f * k); path.close()
            }
            else -> { // 승하차: 자동차 옆모습
                rect.set(cx - 8.5f * k, cy - 1.5f * k, cx + 8.5f * k, cy + 4f * k)
                path.addRoundRect(rect, 2f * k, 2f * k, Path.Direction.CW)
                rect.set(cx - 5f * k, cy - 6f * k, cx + 5f * k, cy + 1f * k)
                path.addRoundRect(rect, 2.5f * k, 2.5f * k, Path.Direction.CW)
                path.addCircle(cx - 4.6f * k, cy + 4.2f * k, 2.4f * k, Path.Direction.CW)
                path.addCircle(cx + 4.6f * k, cy + 4.2f * k, 2.4f * k, Path.Direction.CW)
            }
        }
    }

    /** 범례: 카드 아래쪽에 한 줄로 */
    private fun legend(canvas: Canvas, k: Float) {
        val items = listOf("elev" to "엘리베이터", "stairs" to "계단", "ramp" to "경사로", "drop" to "승하차", "drive" to "차량 경로", "walk" to "걸어갈 길")
        val size = 20f * k
        textPaint.textSize = size; textPaint.typeface = Typeface.DEFAULT; textPaint.textAlign = Paint.Align.LEFT
        var total = 0f
        items.forEach { total += 30f * k + textPaint.measureText(it.second) + 28f * k }
        total -= 28f * k
        var x = (width - total) / 2f
        val y = height - 48f * k
        items.forEach { (f, name) ->
            val cx = x + 11f * k
            when (f) {
                "drive" -> { fill.color = colorDrive; rect.set(x, y - 3f * k, x + 22f * k, y + 3f * k); canvas.drawRoundRect(rect, 3f * k, 3f * k, fill) }
                "walk" -> { fill.color = colorWalk; for (i in 0..2) canvas.drawCircle(x + 3f * k + i * 8f * k, y, 2.6f * k, fill) }
                else -> { fill.color = colorText; iconPath(f, cx, y, k * 1.15f); canvas.drawPath(path, fill) }
            }
            textPaint.color = colorSub
            canvas.drawText(name, x + 30f * k, y + size * 0.35f, textPaint)
            x += 30f * k + textPaint.measureText(name) + 28f * k
        }
    }

    /** 출입구 특징 배지: 엘리베이터·계단·경사로·승하차 */
    private fun entranceBadge(canvas: Canvas, e: HEntrance, hot: Boolean, k: Float) {
        val n = Hospital.NODES[e.node] ?: return
        project(n.x, n.y, 31f, p0)
        val w = (e.feat.size * 22f + 10f) * k; val h = 26f * k
        rect.set(p0[0] - w / 2f, p0[1] - h / 2f, p0[0] + w / 2f, p0[1] + h / 2f)
        fill.color = if (hot) colorWalk else colorChip
        canvas.drawRoundRect(rect, h / 2f, h / 2f, fill)
        fill.color = if (hot) Color.WHITE else colorText
        e.feat.forEachIndexed { i, f ->
            val cx = p0[0] - w / 2f + (16f + i * 22f) * k; val cy = p0[1]
            iconPath(f, cx, cy, k)
            canvas.drawPath(path, fill)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        val k = width / 840f
        mapW = width.toFloat()
        mapH = mapW * 400f / 840f
        mapTop = (height - mapH) / 2f

        clip.reset()
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        clip.addRoundRect(rect, cornerPx, cornerPx, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)

        fill.color = if (night) Color.rgb(0x1A, 0x1B, 0x1E) else Color.rgb(0xED, 0xEE, 0xF2)
        canvas.drawRect(rect, fill)

        val bmp = bitmap
        if (bmp != null) {
            rect.set(0f, mapTop, mapW, mapTop + mapH)
            canvas.drawBitmap(bmp, null, rect, bmpPaint)
        } else {
            // 그림 파일이 없을 때: 도로와 건물을 평면으로
            val road = if (night) Color.rgb(0x3A, 0x3D, 0x45) else Color.WHITE
            listOf(listOf("GATE", "E0", "A", "B", "C"), listOf("A", "A1", "D0"), listOf("A1", "P1"), listOf("B", "B1", "D1"), listOf("B1", "P2"), listOf("C", "P3"), listOf("E0", "D2"))
                .forEach { ribbon(canvas, it, 26f, 0f, road) }
            val wall = if (night) Color.rgb(0x5B, 0x5F, 0x6B) else Color.rgb(0xC9, 0xCC, 0xD4)
            Hospital.BUILDINGS.forEach { b ->
                polygon(canvas, floatArrayOf(b.x, b.y, b.x + b.w, b.y, b.x + b.w, b.y + b.h, b.x, b.y + b.h), 0f, wall)
            }
        }

        val r = best
        if (r != null && showDriveRoute) ribbon(canvas, r.driveNodes, 11f, 2.2f, colorDrive)
        if (r != null && showWalkRoute) dots(canvas, r.walkNodes, 4.2f, 13f, 2.4f, colorWalk)

        // 이름
        val roofZ = mapOf("main" to 68f, "cancer" to 86f, "er" to 25f)
        Hospital.BUILDINGS.forEach { b ->
            val z = if (bmp != null) (roofZ[b.id] ?: 0f) else 0f
            label(canvas, b.name, b.x + b.w / 2f, b.y + b.h / 2f + (if (b.id == "er") 10f else 0f), z, 22f * k, true, colorText, true)
        }
        Hospital.NODES["P1"]?.let { parkingLabel(canvas, "본관 지하", it, k) }
        Hospital.NODES["P2"]?.let { parkingLabel(canvas, "암센터 지하", it, k) }
        Hospital.NODES["P3"]?.let { parkingLabel(canvas, "지상", it, k) }
        label(canvas, "정문", 4f, 412f, 0f, 18f * k, false, colorSub, false)

        // 출입구 배지: 추천 경로가 지나는 출입구는 색으로 강조
        val hot = if (r != null && (showWalkRoute || showPins)) Hospital.entranceOnRoute(r) else null
        Hospital.ENTRANCES.forEach { entranceBadge(canvas, it, it.node == hot, k) }

        if (r != null && showPins) {
            destNode?.let { pin(canvas, it, colorWalk, 10f * k) }
            pin(canvas, r.node, colorDrive, 12f * k)
        }
        if (showLegend && height - (mapTop + mapH) > 90f * k) legend(canvas, k)
        canvas.restore()
    }
}
