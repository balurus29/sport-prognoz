package com.sportprognoz.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * Бесплатный открытый API ESPN (без ключа).
 * Источник легко заменить: остальной код работает только с моделями из Models.kt.
 */
object EspnApi {
    private const val BASE = "https://site.api.espn.com/apis"
    private val dateFmt = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val standingsCache = HashMap<String, Map<String, TeamStats>>()

    // ---------- сеть ----------

    private fun get(url: String): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) SportPrognoz/1.0")
        c.setRequestProperty("Accept", "application/json")
        try {
            val code = c.responseCode
            if (code !in 200..299) throw IOException("сервер ответил $code")
            val text = c.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(text)
        } finally {
            c.disconnect()
        }
    }

    // ---------- матчи ----------

    /** Матчи на дату по местному времени телефона (ESPN группирует по времени США, поэтому берём два дня) */
    suspend fun matches(league: League, day: LocalDate): List<Match> = coroutineScope {
        val prev = async(Dispatchers.IO) {
            runCatching { scoreboard(league, day.minusDays(1)) }.getOrDefault(emptyList())
        }
        val cur = async(Dispatchers.IO) { scoreboard(league, day) }
        val zone = ZoneId.systemDefault()
        (prev.await() + cur.await())
            .distinctBy { it.id }
            .filter { m -> m.start?.atZoneSameInstant(zone)?.toLocalDate()?.let { it == day } ?: true }
            .sortedBy { it.start }
    }

    private fun scoreboard(league: League, day: LocalDate): List<Match> {
        val root = get("$BASE/site/v2/sports/${league.espnSport}/${league.espnLeague}/scoreboard?dates=${day.format(dateFmt)}&limit=500")
        val events = root.optJSONArray("events") ?: JSONArray()
        val out = ArrayList<Match>()
        for (i in 0 until events.length()) {
            val ev = events.getJSONObject(i)
            val comps = ArrayList<JSONObject>()
            ev.optJSONArray("competitions")?.let { a ->
                for (j in 0 until a.length()) comps.add(a.getJSONObject(j))
            }
            // теннис: матчи лежат внутри группировок турнира
            ev.optJSONArray("groupings")?.let { g ->
                for (j in 0 until g.length()) {
                    val gc = g.getJSONObject(j).optJSONArray("competitions") ?: continue
                    for (k in 0 until gc.length()) comps.add(gc.getJSONObject(k))
                }
            }
            for (c in comps) parseCompetition(league, ev, c)?.let { out.add(it) }
        }
        return out
    }

    private fun parseCompetition(league: League, ev: JSONObject, c: JSONObject): Match? {
        val arr = c.optJSONArray("competitors") ?: return null
        val cs = (0 until arr.length()).mapNotNull { parseCompetitor(arr.getJSONObject(it), it) }
        if (cs.size < 2) return null
        val home = cs.firstOrNull { it.home } ?: cs[0]
        val away = cs.firstOrNull { it !== home } ?: return null

        val status = c.optJSONObject("status") ?: ev.optJSONObject("status")
        val type = status?.optJSONObject("type")
        val eventId = s(ev, "id") ?: return null
        val id = s(c, "id") ?: eventId
        val tournament = if (league.sport == Sport.TENNIS || league.sport == Sport.MMA) s(ev, "name") else null

        return Match(
            id = "$eventId-$id",
            eventId = eventId,
            league = league,
            start = parseTime(s(c, "date")) ?: parseTime(s(ev, "date")),
            rawDate = s(c, "date") ?: s(ev, "date"),
            state = s(type, "state") ?: "pre",
            statusText = s(type, "shortDetail") ?: "",
            tournament = tournament,
            home = home,
            away = away,
            odds = parseOdds(c),
        )
    }

    private fun parseCompetitor(o: JSONObject, index: Int): Competitor? {
        val team = o.optJSONObject("team")
        val ath = o.optJSONObject("athlete")
        val roster = o.optJSONObject("roster")
        val name = s(team, "displayName") ?: s(ath, "displayName") ?: s(roster, "displayName") ?: return null
        val homeAway = s(o, "homeAway")
        val rank = o.optJSONObject("curatedRank")?.optInt("current", 0)?.takeIf { it in 1..98 }
        return Competitor(
            id = s(team, "id") ?: s(ath, "id") ?: s(o, "id") ?: name,
            name = name,
            short = s(team, "abbreviation") ?: s(ath, "shortName") ?: name,
            home = homeAway == "home" || (homeAway == null && index == 0),
            score = s(o, "score"),
            record = o.optJSONArray("records")?.optJSONObject(0)?.let { s(it, "summary") },
            form = s(o, "form"),
            rank = rank,
            winner = if (o.has("winner")) o.optBoolean("winner") else null,
        )
    }

    private fun parseOdds(c: JSONObject): Odds? {
        val o = c.optJSONArray("odds")?.optJSONObject(0) ?: return null
        fun line(side: String, oldKey: String): Double? {
            d(o.optJSONObject(oldKey), "moneyLine")?.let { return it }
            val m = o.optJSONObject("moneyline")?.optJSONObject(side) ?: return null
            return d(m.optJSONObject("close"), "odds") ?: d(m.optJSONObject("open"), "odds")
        }
        fun total(side: String): Double? {
            d(o, "${side}Odds")?.let { return it }
            val t = o.optJSONObject("total")?.optJSONObject(side) ?: return null
            return d(t.optJSONObject("close"), "odds") ?: d(t.optJSONObject("open"), "odds")
        }
        val res = Odds(
            provider = s(o.optJSONObject("provider"), "name"),
            details = s(o, "details"),
            overUnder = d(o, "overUnder"),
            home = line("home", "homeTeamOdds")?.let { americanToDecimal(it) },
            draw = line("draw", "drawOdds")?.let { americanToDecimal(it) },
            away = line("away", "awayTeamOdds")?.let { americanToDecimal(it) },
            over = total("over")?.let { americanToDecimal(it) },
            under = total("under")?.let { americanToDecimal(it) },
        )
        return if (res.home == null && res.away == null && res.overUnder == null) null else res
    }

    // ---------- турнирная таблица ----------

    suspend fun standings(league: League): Map<String, TeamStats> = withContext(Dispatchers.IO) {
        standingsCache[league.key]?.let { return@withContext it }
        val map = HashMap<String, TeamStats>()
        runCatching {
            collectEntries(get("$BASE/v2/sports/${league.espnSport}/${league.espnLeague}/standings"), map)
        }
        if (map.isNotEmpty()) standingsCache[league.key] = map
        map
    }

    private fun collectEntries(o: JSONObject, map: MutableMap<String, TeamStats>) {
        o.optJSONObject("standings")?.optJSONArray("entries")?.let { arr ->
            for (i in 0 until arr.length()) {
                parseEntry(arr.getJSONObject(i))?.let { (id, st) -> map[id] = st }
            }
        }
        o.optJSONArray("children")?.let { ch ->
            for (i in 0 until ch.length()) collectEntries(ch.getJSONObject(i), map)
        }
    }

    private fun parseEntry(e: JSONObject): Pair<String, TeamStats>? {
        val id = s(e.optJSONObject("team"), "id") ?: s(e.optJSONObject("athlete"), "id") ?: return null
        val stats = e.optJSONArray("stats") ?: return null
        val m = HashMap<String, Double>()
        for (i in 0 until stats.length()) {
            val st = stats.getJSONObject(i)
            val n = s(st, "name") ?: s(st, "type") ?: continue
            d(st, "value")?.let { m[n] = it }
        }
        val w = m["wins"] ?: 0.0
        val l = m["losses"] ?: 0.0
        val extra = (m["ties"] ?: 0.0) + (m["otLosses"] ?: m["overtimeLosses"] ?: 0.0)
        val gp = m["gamesPlayed"] ?: (w + l + extra)
        if (gp <= 0) return null
        val pf = m["pointsFor"] ?: m["avgPointsFor"]?.times(gp) ?: m["runsScored"]
        val pa = m["pointsAgainst"] ?: m["avgPointsAgainst"]?.times(gp) ?: m["runsAllowed"]
        return id to TeamStats(gp = gp, pf = pf, pa = pa, wins = w, losses = l)
    }

    // ---------- детали матча: форма, личные встречи, травмы ----------

    suspend fun extras(m: Match): MatchExtras = withContext(Dispatchers.IO) {
        val root = get("$BASE/site/v2/sports/${m.league.espnSport}/${m.league.espnLeague}/summary?event=${m.eventId}")

        fun games(key: String, teamId: String): List<String> {
            val arr = root.optJSONArray(key) ?: return emptyList()
            for (i in 0 until arr.length()) {
                val g = arr.getJSONObject(i)
                if (s(g.optJSONObject("team"), "id") != teamId) continue
                val evs = g.optJSONArray("events") ?: return emptyList()
                return (0 until evs.length()).map { j ->
                    val e = evs.getJSONObject(j)
                    val r = when (s(e, "gameResult")) {
                        "W" -> "В"; "L" -> "П"; "D", "T" -> "Н"; else -> "·"
                    }
                    val opp = s(e.optJSONObject("opponent"), "displayName").orEmpty()
                    val sc = s(e, "score").orEmpty()
                    val atVs = when (s(e, "atVs")) { "@" -> "в гостях у"; "vs" -> "дома с"; else -> "—" }
                    "$r $sc $atVs $opp".replace(Regex("\\s+"), " ").trim()
                }
            }
            return emptyList()
        }

        fun injuries(teamId: String): List<String> {
            val arr = root.optJSONArray("injuries") ?: return emptyList()
            for (i in 0 until arr.length()) {
                val g = arr.getJSONObject(i)
                if (s(g.optJSONObject("team"), "id") != teamId) continue
                val list = g.optJSONArray("injuries") ?: return emptyList()
                return (0 until list.length()).mapNotNull { j ->
                    val inj = list.getJSONObject(j)
                    val who = s(inj.optJSONObject("athlete"), "displayName") ?: return@mapNotNull null
                    val st = s(inj, "status")
                    if (st != null) "$who ($st)" else who
                }
            }
            return emptyList()
        }

        MatchExtras(
            homeLast = games("lastFiveGames", m.home.id),
            awayLast = games("lastFiveGames", m.away.id),
            h2h = games("headToHeadGames", m.home.id),
            homeInjuries = injuries(m.home.id),
            awayInjuries = injuries(m.away.id),
        )
    }

    // ---------- утилиты ----------

    private fun s(o: JSONObject?, k: String): String? {
        if (o == null || !o.has(k) || o.isNull(k)) return null
        return o.optString(k).ifBlank { null }
    }

    private fun d(o: JSONObject?, k: String): Double? {
        if (o == null || !o.has(k) || o.isNull(k)) return null
        return when (val v = o.opt(k)) {
            is Number -> v.toDouble().takeIf { !it.isNaN() }
            is String -> if (v.equals("EVEN", true)) 100.0 else v.replace("+", "").trim().toDoubleOrNull()
            else -> null
        }
    }

    /** ESPN отдаёт время по-разному: "2026-11-12T19:45Z", "...T19:45:00Z", "...T19:45+0000" */
    private val timeFormats = listOf(
        DateTimeFormatter.ISO_OFFSET_DATE_TIME,
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mmX"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssX"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mmXX"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSX"),
    )

    private fun parseTime(t: String?): OffsetDateTime? {
        if (t.isNullOrBlank()) return null
        for (f in timeFormats) {
            runCatching { return OffsetDateTime.parse(t.trim(), f) }
        }
        return runCatching { java.time.Instant.parse(t.trim()).atOffset(java.time.ZoneOffset.UTC) }.getOrNull()
    }

    /** Американский формат (-150 / +130) → десятичный (1.67 / 2.30) */
    fun americanToDecimal(ml: Double): Double? = when {
        ml == 0.0 -> null
        ml > 0 -> 1 + ml / 100
        else -> 1 + 100 / abs(ml)
    }
}
