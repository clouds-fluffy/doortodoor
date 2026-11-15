package com.example.doortodoor

/*
 * 문앞까지 · 시설 데이터와 추천 엔진
 * 한빛대학교병원은 검증용으로 만든 가상의 병원입니다. 좌표·거리·시간은 임의로 정한 값입니다.
 * 웹 프로토타입(prototype/index.html)의 데이터·계산과 같은 결과가 나오도록 옮겼습니다.
 */

data class HBuilding(val id: String, val name: String, val x: Float, val y: Float, val w: Float, val h: Float)

data class HNode(
    val id: String, val x: Float, val y: Float,
    val name: String? = null,
    val stop: String? = null,                 // "lot" 주차장, "drop" 승하차 구역, "both" 둘 다
    val zone: Map<String, String> = emptyMap() // 조건별 구역 안내 문구
)

data class HEdge(
    val a: String, val b: String, val mode: String, val len: Int,
    val under: Boolean = false, val stairs: Boolean = false,
    val elevator: Boolean = false, val ramp: Boolean = false,
    val say: String? = null
)

data class HEntrance(val node: String, val name: String, val feat: List<String>)

data class HDest(val id: String, val zone: String, val name: String, val desc: String, val node: String, val inside: String)

data class Cond(
    val id: String, val name: String, val desc: String, val stops: List<String>,
    val byWalk: Boolean = false, val avoidStairs: Boolean = false, val noStairs: Boolean = false
)

data class Candidate(
    val id: String, val node: HNode, val total: Double, val walkCost: Double,
    val driveNodes: List<String>, val walkNodes: List<String>, val walkEdges: List<HEdge>,
    val walkM: Int, val driveM: Int,
    val stairs: Boolean, val elevator: Boolean, val ramp: Boolean
)

data class Recommendation(val best: Candidate, val alt: Candidate?)

object Hospital {
    const val NAME = "한빛대학교병원(가상)"
    val BUILDINGS = listOf(
        HBuilding("main", "본관", 120f, 100f, 230f, 170f),
        HBuilding("cancer", "암센터", 400f, 100f, 230f, 170f),
        HBuilding("er", "응급센터", 70f, 472f, 170f, 56f)
    )
    // 순서를 유지합니다(같은 값일 때 먼저 나온 지점이 선택됨)
    val NODES: Map<String, HNode> = listOf(
        HNode("GATE", 30f, 440f, name = "정문 진입로"),
        HNode("E0", 150f, 440f),
        HNode("A", 250f, 440f),
        HNode("A1", 250f, 335f),
        HNode("B", 480f, 440f),
        HNode("B1", 480f, 335f),
        HNode("C", 750f, 440f),
        HNode("D2", 150f, 462f, name = "응급센터 앞 전용 구역", stop = "both", zone = mapOf("any" to "응급 전용 구역")),
        HNode("D0", 250f, 292f, name = "본관 정문 승하차 구역", stop = "drop", zone = mapOf("any" to "정문 앞 승하차 구역, 5분 정차")),
        HNode("P1", 150f, 335f, name = "본관 지하주차장", stop = "lot", zone = mapOf("near" to "A구역, 계단 옆", "elev" to "C구역, 엘리베이터 옆", "wheel" to "교통약자 구역, 엘리베이터 앞")),
        HNode("D1", 480f, 292f, name = "암센터 정문 승하차 구역", stop = "drop", zone = mapOf("any" to "정문 앞 승하차 구역, 5분 정차")),
        HNode("P2", 610f, 335f, name = "암센터 지하주차장", stop = "lot", zone = mapOf("near" to "A구역", "elev" to "B구역, 엘리베이터 옆", "wheel" to "교통약자 구역, 엘리베이터 앞")),
        HNode("P3", 750f, 372f, name = "지상주차장", stop = "lot", zone = mapOf("near" to "암센터 쪽 계단 앞", "elev" to "경사로 입구 쪽", "wheel" to "교통약자 구역, 경사로 입구")),
        HNode("M_ENT", 250f, 270f, name = "본관 정문"),
        HNode("M_B1", 150f, 270f, name = "본관 1층 로비"),
        HNode("C_ENT", 480f, 270f, name = "암센터 정문"),
        HNode("C_B1", 610f, 270f, name = "암센터 1층 로비"),
        HNode("RAMP", 700f, 285f),
        HNode("ER_ENT", 150f, 476f, name = "응급센터 입구")
    ).associateBy { it.id }
    val EDGES = listOf(
        HEdge("GATE", "E0", "drive", 120),
        HEdge("E0", "A", "drive", 80),
        HEdge("E0", "D2", "drive", 20),
        HEdge("A", "A1", "drive", 110),
        HEdge("A1", "D0", "drive", 40),
        HEdge("A1", "P1", "drive", 90, under = true),
        HEdge("A", "B", "drive", 230),
        HEdge("B", "B1", "drive", 110),
        HEdge("B1", "D1", "drive", 40),
        HEdge("B1", "P2", "drive", 130, under = true),
        HEdge("B", "C", "drive", 255),
        HEdge("C", "P3", "drive", 65),
        HEdge("D2", "ER_ENT", "walk", 8, say = "바로 앞 응급센터 입구로 들어가기"),
        HEdge("D0", "M_ENT", "walk", 10, say = "바로 앞 본관 정문으로 들어가기"),
        HEdge("P1", "M_B1", "walk", 20, stairs = true, say = "계단으로 1층 로비까지 올라가기"),
        HEdge("P1", "M_B1", "walk", 30, elevator = true, say = "엘리베이터로 1층 로비까지 올라가기"),
        HEdge("M_B1", "M_ENT", "walk", 40, say = "로비를 지나 안내데스크 쪽으로 40m"),
        HEdge("D1", "C_ENT", "walk", 10, say = "바로 앞 암센터 정문으로 들어가기"),
        HEdge("P2", "C_B1", "walk", 25, elevator = true, say = "엘리베이터로 1층 로비까지 올라가기"),
        HEdge("C_B1", "C_ENT", "walk", 30, say = "로비 왼쪽 접수창구까지 30m"),
        HEdge("P3", "C_B1", "walk", 60, stairs = true, say = "계단으로 암센터 옆문까지 60m"),
        HEdge("P3", "RAMP", "walk", 90, ramp = true, say = "경사로를 따라 90m 올라가기"),
        HEdge("RAMP", "C_B1", "walk", 80, ramp = true, say = "건물 옆길로 암센터 옆문까지 80m"),
        HEdge("M_ENT", "C_ENT", "walk", 230, say = "건물 안 연결 통로로 230m")
    )
    val ENTRANCES = listOf(
        HEntrance("M_B1", "본관 로비 출입구", listOf("elev", "stairs")),
        HEntrance("M_ENT", "본관 정문", listOf("drop")),
        HEntrance("C_ENT", "암센터 정문", listOf("drop")),
        HEntrance("C_B1", "암센터 로비 출입구", listOf("elev", "ramp"))
    )
    val DESTINATIONS = listOf(
        HDest("cancer", "cancer", "암센터", "암센터 1층 접수", "C_ENT", "접수 후 진료실 안내를 받으세요"),
        HDest("ortho", "main", "정형외과", "본관 2층", "M_ENT", "안내데스크 옆 엘리베이터로 2층"),
        HDest("er", "er", "응급센터", "24시간", "ER_ENT", "입구에서 접수")
    )

    // 조건: 같은 목적지라도 조건에 따라 가중치와 허용 정차 지점이 달라집니다
    val CONDS = listOf(
        Cond("near", "가장 가까운 입구", "계단이 있어도 괜찮아요", listOf("lot", "both"), byWalk = true),
        Cond("elev", "엘리베이터 가까이", "계단을 피하고 싶어요", listOf("lot", "both"), avoidStairs = true),
        Cond("wheel", "휠체어·유모차", "계단 없이, 완만한 길로", listOf("lot", "both"), noStairs = true),
        Cond("pickup", "빨리 태우고 나가기", "주차하지 않고 잠깐 세워요", listOf("drop", "both"))
    )

    fun destById(id: String): HDest? = DESTINATIONS.firstOrNull { it.id == id }
    fun condById(id: String): Cond? = CONDS.firstOrNull { it.id == id }

    private const val INF = Double.POSITIVE_INFINITY

    // 단위: 초(가정값)
    private fun edgeCost(e: HEdge, c: Cond): Double {
        if (e.mode == "drive") return e.len / 4.0 + (if (e.under) 80.0 else 0.0) // 구내 약 14km/h, 지하 진입·주차 80초
        if (c.byWalk) return e.len.toDouble()                                    // '가장 가까운 입구'는 걷는 거리(m)만 봅니다
        var cost = e.len.toDouble()                                              // 보행 약 1m/s
        if (e.stairs) { if (c.noStairs) return INF; if (c.avoidStairs) cost *= 8.0 }
        if (e.ramp && c.id == "wheel") cost *= 1.5
        if (e.elevator) cost += 40.0                                             // 엘리베이터 대기
        return cost
    }

    private class Search(val dist: Map<String, Double>, val prev: Map<String, Pair<String, HEdge>>)

    private fun dijkstra(start: String, mode: String, c: Cond): Search {
        val dist = LinkedHashMap<String, Double>()
        NODES.keys.forEach { dist[it] = INF }
        dist[start] = 0.0
        val prev = HashMap<String, Pair<String, HEdge>>()
        val done = HashSet<String>()
        while (true) {
            var u: String? = null
            for ((n, d) in dist) {
                if (n in done || d == INF) continue
                val cur = u
                if (cur == null || d < dist.getValue(cur)) u = n
            }
            val from = u ?: break
            done.add(from)
            for (e in EDGES) {
                if (e.mode != mode) continue
                val v = if (e.a == from) e.b else if (e.b == from) e.a else continue
                val d = dist.getValue(from) + edgeCost(e, c)
                if (d < dist.getValue(v)) { dist[v] = d; prev[v] = Pair(from, e) }
            }
        }
        return Search(dist, prev)
    }

    private fun trace(prev: Map<String, Pair<String, HEdge>>, end: String): Pair<List<String>, List<HEdge>> {
        val nodes = ArrayList<String>(); val edges = ArrayList<HEdge>()
        nodes.add(end)
        var n = end
        while (true) {
            val p = prev[n] ?: break
            edges.add(0, p.second); n = p.first; nodes.add(0, n)
        }
        return Pair(nodes, edges)
    }

    /** 목적지와 조건으로 주차장 또는 승하차 구역을 추천합니다. best가 추천, alt가 다른 선택지입니다. */
    fun recommend(dest: HDest, c: Cond): Recommendation? {
        val drive = dijkstra("GATE", "drive", c)
        val walk = dijkstra(dest.node, "walk", c)   // 보행 연결은 양방향
        val cands = ArrayList<Candidate>()
        for ((id, n) in NODES) {
            val stop = n.stop ?: continue
            if (stop !in c.stops) continue
            val total = drive.dist.getValue(id) + walk.dist.getValue(id)
            if (total.isInfinite() || total.isNaN()) continue
            val d = trace(drive.prev, id)
            val w = trace(walk.prev, id)
            val wEdges = w.second.reversed(); val wNodes = w.first.reversed()
            cands.add(
                Candidate(
                    id, n, total, walk.dist.getValue(id), d.first, wNodes, wEdges,
                    wEdges.sumOf { it.len }, d.second.sumOf { it.len },
                    wEdges.any { it.stairs }, wEdges.any { it.elevator }, wEdges.any { it.ramp }
                )
            )
        }
        if (cands.isEmpty()) return null
        // 기본은 차량 이동과 보행을 합친 예상 소요 시간, '가장 가까운 입구'는 걷는 거리가 짧은 순(같으면 소요 시간)
        val sorted = if (c.byWalk) cands.sortedWith(compareBy<Candidate> { it.walkCost }.thenBy { it.total })
        else cands.sortedBy { it.total }
        return Recommendation(sorted[0], sorted.getOrNull(1))
    }

    fun zoneOf(n: HNode, c: Cond): String = n.zone["any"] ?: n.zone[c.id] ?: ""

    fun feat(r: Candidate): String = when {
        r.stairs -> "계단 있음"
        r.elevator -> "엘리베이터 이용"
        r.ramp -> "경사로 이용"
        else -> "계단 없음"
    }

    /** 추천 경로가 지나는 출입구(정차 지점에서 가장 먼저 만나는 출입구) */
    fun entranceOnRoute(r: Candidate): String? {
        val k = r.walkNodes.indexOf(r.id)
        var best: String? = null; var bd = Int.MAX_VALUE
        for (e in ENTRANCES) {
            val j = r.walkNodes.indexOf(e.node)
            if (j >= 0 && kotlin.math.abs(j - k) < bd) { bd = kotlin.math.abs(j - k); best = e.node }
        }
        return best
    }
}
