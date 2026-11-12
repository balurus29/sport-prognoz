package com.sportprognoz.app.predict

import com.sportprognoz.app.data.Competitor
import com.sportprognoz.app.data.Match
import com.sportprognoz.app.data.MatchExtras
import com.sportprognoz.app.data.ModelType
import com.sportprognoz.app.data.Sport
import com.sportprognoz.app.data.TeamStats
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

data class Outcome(val label: String, val prob: Double, val bookOdds: Double? = null) {
    /** «Справедливый» коэффициент без маржи */
    val fairOdds: Double get() = if (prob > 0.001) 1 / prob else 999.0
    /** Перевес ставки: > 0 — ставка выгоднее, чем оценивает модель */
    val edge: Double? get() = bookOdds?.let { prob * it - 1 }
}

data class Section(val title: String, val items: List<Outcome>, val note: String? = null)

data class Prediction(
    val p1: Double,
    val draw: Double?,
    val p2: Double,
    val expectedScore: String,
    val sections: List<Section>,
    val confidence: String,
    val pick: String,
    val analysis: List<String>,
    val valueBets: List<Outcome>,
)

/** Коэффициенты, которые пользователь ввёл вручную из своей букмекерской конторы */
data class UserOdds(val o1: Double?, val ox: Double?, val o2: Double?)

object Predictor {

    private class Core(
        val p1: Double,
        val draw: Double?,
        val p2: Double,
        val expected: String,
        val sections: List<Section>,
        val notes: List<String>,
        val quality: Int, // 0..3 — насколько полные данные
    )

    fun predict(m: Match, stats: Map<String, TeamStats>, extras: MatchExtras?, user: UserOdds?): Prediction {
        val sH = stats[m.home.id]
        val sA = stats[m.away.id]
        val core = when (m.league.model) {
            ModelType.GOALS -> goals(m, stats, sH, sA)
            ModelType.POINTS -> points(m, stats, sH, sA)
            ModelType.SETS -> sets(m, sH, sA)
            ModelType.FIGHT -> fight(m, sH, sA)
        }

        // коэффициенты: введённые вручную важнее, чем от ESPN
        val book = HashMap<String, Double>()
        m.odds?.let { o ->
            // в НХЛ линия «на победу» у американских букмекеров включает овертайм
            val hockeyMl = m.league.sport == Sport.HOCKEY && o.draw == null
            o.home?.let { book[if (hockeyMl) "П1 (вкл. ОТ)" else "П1"] = it }
            o.draw?.let { book["Х"] = it }
            o.away?.let { book[if (hockeyMl) "П2 (вкл. ОТ)" else "П2"] = it }
            if (o.overUnder != null) {
                o.over?.let { book["ТБ ${line(o.overUnder)}"] = it }
                o.under?.let { book["ТМ ${line(o.overUnder)}"] = it }
            }
        }
        user?.o1?.let { book["П1"] = it }
        user?.ox?.let { book["Х"] = it }
        user?.o2?.let { book["П2"] = it }

        val sections = core.sections.map { sec ->
            sec.copy(items = sec.items.map { it.copy(bookOdds = book[it.label]) })
        }
        val valueBets = sections.flatMap { it.items }
            .filter { (it.edge ?: -1.0) >= 0.03 && it.prob >= 0.2 }
            .sortedByDescending { it.edge }

        val maxP = max(core.p1, core.p2)
        val confidence = when {
            core.quality >= 2 && maxP >= 0.62 -> "Высокая"
            core.quality >= 1 && maxP >= 0.5 -> "Средняя"
            else -> "Низкая"
        }

        return Prediction(
            p1 = core.p1,
            draw = core.draw,
            p2 = core.p2,
            expectedScore = core.expected,
            sections = sections,
            confidence = confidence,
            pick = pick(m, core, sections, valueBets),
            analysis = analysis(m, core, extras, book),
            valueBets = valueBets,
        )
    }

    // =============== Футбол, хоккей: Пуассон ===============

    private fun goals(m: Match, all: Map<String, TeamStats>, sH: TeamStats?, sA: TeamStats?): Core {
        val lg = m.league
        val hockey = lg.sport == Sport.HOCKEY
        val rows = all.values.filter { it.gp > 0 && it.pf != null }
        val avg = if (rows.size >= 4) rows.sumOf { it.pf ?: 0.0 } / rows.sumOf { it.gp } else lg.defaultAvg

        fun rate(s: TeamStats?, c: Competitor): Pair<Double, Double> {
            val pf = s?.pf
            val pa = s?.pa
            if (s != null && pf != null && pa != null) {
                val w = s.gp / (s.gp + 6.0) // мало игр → тянем к среднему по лиге
                return Pair(1 + w * (pf / s.gp / avg - 1), 1 + w * (pa / s.gp / avg - 1))
            }
            val f = formScore(c.form) ?: recordScore(c.record) ?: 0.0
            return Pair(1 + 0.15 * f, 1 - 0.15 * f)
        }

        val (attH, defH) = rate(sH, m.home)
        val (attA, defA) = rate(sA, m.away)
        val lh = (avg * attH * defA * lg.homeAdv).coerceIn(0.15, 7.0)
        val la = (avg * attA * defH / lg.homeAdv).coerceIn(0.15, 7.0)

        val n = if (hockey) 14 else 11
        val mat = Array(n) { i -> DoubleArray(n) { j -> PMath.poisson(i, lh) * PMath.poisson(j, la) } }
        if (!hockey) { // в футболе низовые ничьи случаются чаще, чем даёт чистый Пуассон
            mat[0][0] *= 1.08
            mat[1][1] *= 1.05
        }
        val total = mat.sumOf { it.sum() }
        for (r in mat) for (j in r.indices) r[j] /= total

        var p1 = 0.0; var px = 0.0; var p2 = 0.0; var btts = 0.0
        for (i in 0 until n) for (j in 0 until n) {
            val p = mat[i][j]
            when {
                i > j -> p1 += p
                i == j -> px += p
                else -> p2 += p
            }
            if (i > 0 && j > 0) btts += p
        }
        fun sumIf(cond: (Int, Int) -> Boolean): Double {
            var s = 0.0
            for (i in 0 until n) for (j in 0 until n) if (cond(i, j)) s += mat[i][j]
            return s
        }

        val sections = ArrayList<Section>()
        sections += Section(
            if (hockey) "Исход (основное время)" else "Исход",
            listOf(Outcome("П1", p1), Outcome("Х", px), Outcome("П2", p2)),
        )
        sections += Section("Двойной шанс", listOf(
            Outcome("1Х", p1 + px), Outcome("12", p1 + p2), Outcome("Х2", px + p2),
        ))
        if (hockey) {
            val share = lh / (lh + la)
            sections += Section("Победа с учётом ОТ и буллитов", listOf(
                Outcome("П1 (вкл. ОТ)", p1 + px * share), Outcome("П2 (вкл. ОТ)", p2 + px * (1 - share)),
            ))
        }
        val lines = if (hockey) listOf(4.5, 5.5, 6.5) else listOf(1.5, 2.5, 3.5)
        sections += Section("Тоталы", lines.flatMap { l ->
            listOf(
                Outcome("ТБ ${line(l)}", sumIf { i, j -> i + j > l }),
                Outcome("ТМ ${line(l)}", sumIf { i, j -> i + j < l }),
            )
        })
        sections += Section("Индивидуальные тоталы", listOf(
            Outcome("ИТ1 Б ${line(if (hockey) 2.5 else 0.5)}", sumIf { i, _ -> i > (if (hockey) 2.5 else 0.5) }),
            Outcome("ИТ1 Б ${line(if (hockey) 3.5 else 1.5)}", sumIf { i, _ -> i > (if (hockey) 3.5 else 1.5) }),
            Outcome("ИТ2 Б ${line(if (hockey) 2.5 else 0.5)}", sumIf { _, j -> j > (if (hockey) 2.5 else 0.5) }),
            Outcome("ИТ2 Б ${line(if (hockey) 3.5 else 1.5)}", sumIf { _, j -> j > (if (hockey) 3.5 else 1.5) }),
        ))
        if (!hockey) {
            sections += Section("Обе забьют", listOf(Outcome("ОЗ — да", btts), Outcome("ОЗ — нет", 1 - btts)))
        }
        val dnb = if (p1 + p2 > 0) p1 / (p1 + p2) else 0.5
        sections += Section("Форы", listOf(
            Outcome("Ф1 (0)", dnb),
            Outcome("Ф2 (0)", 1 - dnb),
            Outcome("Ф1 (-1.5)", sumIf { i, j -> i - j >= 2 }),
            Outcome("Ф2 (+1.5)", sumIf { i, j -> i - j <= 1 }),
            Outcome("Ф1 (+1.5)", sumIf { i, j -> j - i <= 1 }),
            Outcome("Ф2 (-1.5)", sumIf { i, j -> j - i >= 2 }),
        ), note = "Фора 0: при ничьей ставка возвращается — вероятность указана без учёта ничьей")

        val scores = ArrayList<Outcome>()
        for (i in 0 until n) for (j in 0 until n) scores += Outcome("$i:$j", mat[i][j])
        sections += Section("Вероятный счёт", scores.sortedByDescending { it.prob }.take(6))

        val notes = ArrayList<String>()
        statLine(m.home.name, sH, if (hockey) "шайб" else "голов")?.let { notes += it }
        statLine(m.away.name, sA, if (hockey) "шайб" else "голов")?.let { notes += it }
        notes += "Модель Пуассона ждёт ${f2(lh)} : ${f2(la)} по голам (тотал ≈ ${f2(lh + la)})."
        val homeEdge = ((lg.homeAdv - 1) * 100).roundToInt()
        if (homeEdge > 0) notes += "Учтено преимущество своего поля: около +$homeEdge% к атаке хозяев."
        if (sH == null || sA == null) notes += "Нет полной турнирной статистики — прогноз опирается на форму, точность ниже."

        val q = (if (sH != null) 1 else 0) + (if (sA != null) 1 else 0) +
            (if (m.home.form != null || m.odds != null) 1 else 0)
        return Core(p1, px, p2, "${f2(lh)} : ${f2(la)}", sections, notes, q)
    }

    // =============== Баскетбол, бейсбол: нормальное распределение ===============

    private fun points(m: Match, all: Map<String, TeamStats>, sH: TeamStats?, sA: TeamStats?): Core {
        val lg = m.league
        val baseball = lg.sport == Sport.BASEBALL
        val rows = all.values.filter { it.gp > 0 && it.pf != null && it.pa != null }
        val avg = if (rows.size >= 4) rows.sumOf { it.pf ?: 0.0 } / rows.sumOf { it.gp } else lg.defaultAvg

        fun offDef(s: TeamStats?, c: Competitor): Pair<Double, Double> {
            val pf = s?.pf
            val pa = s?.pa
            if (s != null && pf != null && pa != null) {
                val w = s.gp / (s.gp + 5.0)
                return Pair(avg + w * (pf / s.gp - avg), avg + w * (pa / s.gp - avg))
            }
            val f = recordScore(c.record) ?: formScore(c.form) ?: 0.0
            return Pair(avg * (1 + 0.04 * f), avg * (1 - 0.04 * f))
        }

        val (offH, defH) = offDef(sH, m.home)
        val (offA, defA) = offDef(sA, m.away)
        val expH = (offH + defA) / 2 + lg.homeAdv / 2
        val expA = (offA + defH) / 2 - lg.homeAdv / 2
        val mu = expH - expA
        val tot = expH + expA
        val p1 = PMath.normCdf(mu / lg.sdMargin)
        val p2 = 1 - p1

        fun over(l: Double) = 1 - PMath.normCdf((l - tot) / lg.sdTotal)
        fun hcapHome(h: Double) = PMath.normCdf((mu + h) / lg.sdMargin)

        val sections = ArrayList<Section>()
        sections += Section("Исход (с учётом ОТ)", listOf(Outcome("П1", p1), Outcome("П2", p2)))

        val center = m.odds?.overUnder ?: PMath.halfLine(tot)
        val totalLines = listOf(center - lg.totalStep, center, center + lg.totalStep)
        sections += Section("Тоталы", totalLines.flatMap { l ->
            listOf(Outcome("ТБ ${line(l)}", over(l)), Outcome("ТМ ${line(l)}", 1 - over(l)))
        })

        val hLines = if (baseball) listOf(-1.5, 1.5) else {
            val h0 = PMath.halfLine(-mu)
            listOf(h0 - 4, h0, h0 + 4)
        }
        sections += Section("Форы", hLines.flatMap { h ->
            listOf(
                Outcome("Ф1 (${signed(h)})", hcapHome(h)),
                Outcome("Ф2 (${signed(-h)})", 1 - hcapHome(h)),
            )
        })

        val unit = if (baseball) "ранов" else "очков"
        val notes = ArrayList<String>()
        statLine(m.home.name, sH, unit)?.let { notes += it }
        statLine(m.away.name, sA, unit)?.let { notes += it }
        notes += "Прогнозируемый счёт ${fScore(expH, baseball)} : ${fScore(expA, baseball)}, " +
            "разница ${f1(abs(mu))} в пользу ${if (mu >= 0) m.home.name else m.away.name}, тотал ≈ ${f1(tot)}."
        if (sH == null || sA == null) notes += "Нет полной статистики по очкам — прогноз упрощённый, точность ниже."

        val q = (if (sH != null) 1 else 0) + (if (sA != null) 1 else 0) + (if (m.odds != null) 1 else 0)
        return Core(p1, null, p2, "${fScore(expH, baseball)} : ${fScore(expA, baseball)}", sections, notes, q)
    }

    // =============== Теннис, волейбол: сеты ===============

    private fun sets(m: Match, sH: TeamStats?, sA: TeamStats?): Core {
        val k = m.league.setsToWin
        val (pRaw, q) = ratingProb(m, sH, sA)
        val p = (pRaw + m.league.homeAdv).coerceIn(0.03, 0.97)
        val s = PMath.setFromMatch(p, k)

        val homeScores = (0 until k).map { j -> Outcome("$k:$j", PMath.setScoreProb(s, k, j)) }
        val awayScores = (0 until k).map { j -> Outcome("$j:$k", PMath.setScoreProb(1 - s, k, j)) }
        val all = homeScores + awayScores
        fun totalSets(o: Outcome): Int = o.label.split(":").sumOf { it.toInt() }

        val sections = ArrayList<Section>()
        sections += Section("Исход", listOf(Outcome("П1", p), Outcome("П2", 1 - p)))
        sections += Section("Точный счёт по сетам", all.sortedByDescending { it.prob })
        val lines = if (k == 2) listOf(2.5) else listOf(3.5, 4.5)
        sections += Section("Тотал сетов", lines.flatMap { l ->
            val o = all.filter { totalSets(it) > l }.sumOf { it.prob }
            listOf(Outcome("ТБ ${line(l)}", o), Outcome("ТМ ${line(l)}", 1 - o))
        })
        val f1m15 = homeScores.filter { k - it.label.split(":")[1].toInt() >= 2 }.sumOf { it.prob }
        val f2m15 = awayScores.filter { k - it.label.split(":")[0].toInt() >= 2 }.sumOf { it.prob }
        sections += Section("Фора по сетам", listOf(
            Outcome("Ф1 (-1.5)", f1m15), Outcome("Ф2 (+1.5)", 1 - f1m15),
            Outcome("Ф2 (-1.5)", f2m15), Outcome("Ф1 (+1.5)", 1 - f2m15),
        ))

        val notes = ArrayList<String>()
        notes += "Шанс взять отдельный сет: ${m.home.name} ${pct(s)}, ${m.away.name} ${pct(1 - s)}."
        if (m.league.sport == Sport.TENNIS) notes += "Расчёт для матча до двух выигранных сетов (на мужских турнирах «Большого шлема» играют до трёх)."
        if (q == 0) notes += "О соперниках мало данных (нет рейтинга и статистики побед) — прогноз близок к 50/50, лучше пропустить."

        val best = all.maxBy { it.prob }
        return Core(p, null, 1 - p, best.label, sections, notes, q)
    }

    // =============== MMA ===============

    private fun fight(m: Match, sH: TeamStats?, sA: TeamStats?): Core {
        val (p, q) = ratingProb(m, sH, sA)
        val sections = listOf(Section("Исход боя", listOf(Outcome("П1", p), Outcome("П2", 1 - p))))
        val notes = ArrayList<String>()
        if (q == 0) notes += "Нет рекордов бойцов — прогноз близок к 50/50."
        notes += "Способ победы (KO/сабмишн/решение) появится после подключения платного источника статистики."
        return Core(p, null, 1 - p, "—", sections, notes, q)
    }

    /** Вероятность победы первого участника по рекордам, рейтингу и форме */
    private fun ratingProb(m: Match, sH: TeamStats?, sA: TeamStats?): Pair<Double, Int> {
        val ests = ArrayList<Double>()
        var q = 0
        val wH = winPct(sH, m.home.record)
        val wA = winPct(sA, m.away.record)
        if (wH != null && wA != null) { ests += PMath.log5(wH, wA); q++ }
        if (m.home.rank != null || m.away.rank != null) {
            val a = (m.home.rank ?: 150).toDouble()
            val b = (m.away.rank ?: 150).toDouble()
            ests += 1 / (1 + (a / b).pow(0.6))
            q++
        }
        val fH = formScore(m.home.form)
        val fA = formScore(m.away.form)
        if (fH != null && fA != null) ests += 0.5 + 0.15 * (fH - fA)
        val p = if (ests.isEmpty()) 0.5 else ests.average()
        return p.coerceIn(0.03, 0.97) to q
    }

    // =============== Итоговый вывод и разбор ===============

    private fun pick(m: Match, core: Core, sections: List<Section>, value: List<Outcome>): String {
        value.firstOrNull()?.let { v ->
            return "Ценная ставка: ${v.label} по кф ${f2(v.bookOdds ?: 0.0)} (модель ${pct(v.prob)}, перевес +${pct(v.edge ?: 0.0)})"
        }
        if (core.p1 >= 0.55) return "П1 — ${m.home.name} (${pct(core.p1)})"
        if (core.p2 >= 0.55) return "П2 — ${m.away.name} (${pct(core.p2)})"
        if (core.draw != null) {
            val dc = sections.firstOrNull { it.title == "Двойной шанс" }?.items
                ?.filter { it.label != "12" }?.maxByOrNull { it.prob }
            if (dc != null && dc.prob >= 0.7) return "${dc.label} (${pct(dc.prob)})"
        }
        val tot = sections.firstOrNull { it.title.startsWith("Тотал") }?.items?.maxByOrNull { it.prob }
        if (tot != null && tot.prob >= 0.62) return "${tot.label} (${pct(tot.prob)})"
        return "Явного фаворита нет — матч лучше пропустить"
    }

    private fun analysis(m: Match, core: Core, ex: MatchExtras?, book: Map<String, Double>): List<String> {
        val a = ArrayList<String>()
        for (c in listOf(m.home, m.away)) {
            val parts = listOfNotNull(
                c.record?.let { "рекорд $it" },
                c.form?.let { "форма ${ruForm(it)}" },
                c.rank?.let { "рейтинг №$it" },
            )
            if (parts.isNotEmpty()) a += "${c.name}: ${parts.joinToString(", ")}."
        }
        a += core.notes
        if (ex != null) {
            if (ex.homeLast.isNotEmpty()) a += "Последние матчи ${m.home.name}: ${ex.homeLast.take(5).joinToString("; ")}."
            if (ex.awayLast.isNotEmpty()) a += "Последние матчи ${m.away.name}: ${ex.awayLast.take(5).joinToString("; ")}."
            if (ex.h2h.isNotEmpty()) a += "Личные встречи (со стороны ${m.home.name}): ${ex.h2h.take(5).joinToString("; ")}."
            if (ex.homeInjuries.isNotEmpty()) a += "Потери ${m.home.name}: ${ex.homeInjuries.take(6).joinToString(", ")}."
            if (ex.awayInjuries.isNotEmpty()) a += "Потери ${m.away.name}: ${ex.awayInjuries.take(6).joinToString(", ")}."
        }
        // сравнение с рынком: снимаем маржу букмекера
        val o1 = book["П1"]
        val o2 = book["П2"]
        if (o1 != null && o2 != null) {
            val ox = book["Х"]
            val inv = 1 / o1 + 1 / o2 + (ox?.let { 1 / it } ?: 0.0)
            val m1 = 1 / o1 / inv
            val m2 = 1 / o2 / inv
            val margin = (inv - 1) * 100
            a += "Рынок без маржи (${f1(margin)}%): П1 ${pct(m1)}" +
                (ox?.let { ", Х ${pct(1 / it / inv)}" } ?: "") + ", П2 ${pct(m2)}. " +
                "Модель: П1 ${pct(core.p1)}" + (core.draw?.let { ", Х ${pct(it)}" } ?: "") + ", П2 ${pct(core.p2)}."
            val diff = core.p1 - m1
            if (abs(diff) >= 0.07) {
                val who = if (diff > 0) m.home.name else m.away.name
                a += "Модель заметно выше рынка оценивает $who — проверьте новости о составах перед ставкой."
            }
        }
        return a
    }

    // =============== вспомогательное ===============

    private fun statLine(name: String, s: TeamStats?, unit: String): String? {
        val pf = s?.pf ?: return null
        val pa = s.pa ?: return null
        return "$name: забивает ${f2(pf / s.gp)} и пропускает ${f2(pa / s.gp)} $unit за игру (${s.gp.roundToInt()} игр)."
    }

    /** Форма «WWDLW» → от -1 до 1 */
    private fun formScore(form: String?): Double? {
        val v = form?.uppercase()?.mapNotNull {
            when (it) { 'W' -> 1.0; 'D', 'T' -> 0.0; 'L' -> -1.0; else -> null }
        } ?: return null
        return if (v.isEmpty()) null else v.average()
    }

    /** Рекорд «12-5-3» → от -1 до 1 */
    private fun recordScore(record: String?): Double? {
        val n = record?.split("-")?.mapNotNull { it.trim().toIntOrNull() } ?: return null
        if (n.size < 2 || n[0] + n[1] == 0) return null
        return (n[0] - n[1]).toDouble() / (n[0] + n[1] + (n.getOrNull(2) ?: 0))
    }

    private fun winPct(s: TeamStats?, record: String?): Double? {
        if (s != null && s.wins + s.losses > 0) return (s.wins + 2) / (s.wins + s.losses + 4)
        val n = record?.split("-")?.mapNotNull { it.trim().toIntOrNull() } ?: return null
        if (n.size < 2) return null
        return (n[0] + 2.0) / (n[0] + n[1] + 4.0)
    }

    private fun ruForm(f: String) = f.uppercase().map {
        when (it) { 'W' -> 'В'; 'L' -> 'П'; 'D', 'T' -> 'Н'; else -> it }
    }.joinToString("")

    fun pct(p: Double) = "${(p * 100).roundToInt()}%"
    fun f2(x: Double) = String.format(Locale.US, "%.2f", x)
    private fun f1(x: Double) = String.format(Locale.US, "%.1f", x)
    private fun line(x: Double) = f1(x)
    private fun signed(x: Double) = if (x > 0) "+${f1(x)}" else f1(x)
    private fun fScore(x: Double, decimals: Boolean) = if (decimals) f1(x) else x.roundToInt().toString()
}
