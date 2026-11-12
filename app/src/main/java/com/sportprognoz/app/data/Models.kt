package com.sportprognoz.app.data

import java.time.OffsetDateTime

/** Какой математической моделью считать прогноз */
enum class ModelType {
    GOALS,   // футбол, хоккей — распределение Пуассона по голам
    POINTS,  // баскетбол, бейсбол — нормальное распределение по очкам
    SETS,    // теннис, волейбол — вероятность выиграть сет → счёт по сетам
    FIGHT,   // MMA — только исход
}

enum class Sport(val title: String, val emoji: String, val available: Boolean = true) {
    FOOTBALL("Футбол", "⚽"),
    HOCKEY("Хоккей", "🏒"),
    BASKETBALL("Баскетбол", "🏀"),
    TENNIS("Теннис", "🎾"),
    VOLLEYBALL("Волейбол", "🏐"),
    BASEBALL("Бейсбол", "⚾"),
    MMA("MMA", "🥊"),
    // Для этих видов нет надёжного бесплатного источника — подключим платный API
    ESPORTS("Киберспорт", "🎮", available = false),
    TABLE_TENNIS("Наст. теннис", "🏓", available = false),
}

data class League(
    val sport: Sport,
    val espnSport: String,
    val espnLeague: String,
    val title: String,
    val model: ModelType,
    /** Преимущество своей площадки: множитель голов (GOALS), очки (POINTS) или прибавка к вероятности (SETS) */
    val homeAdv: Double,
    /** Средний результат команды за игру, если нет турнирной таблицы */
    val defaultAvg: Double = 0.0,
    val sdMargin: Double = 0.0,
    val sdTotal: Double = 0.0,
    val totalStep: Double = 0.0,
    /** Сколько сетов нужно выиграть (теннис 2, волейбол 3) */
    val setsToWin: Int = 0,
) {
    val key: String get() = "$espnSport/$espnLeague"
}

data class Competitor(
    val id: String,
    val name: String,
    val short: String,
    val home: Boolean,
    val score: String?,
    val record: String?,
    val form: String?,
    val rank: Int?,
    val winner: Boolean?,
)

/** Коэффициенты уже в десятичном (европейском) формате */
data class Odds(
    val provider: String?,
    val details: String?,
    val overUnder: Double?,
    val home: Double?,
    val draw: Double?,
    val away: Double?,
    val over: Double?,
    val under: Double?,
)

data class Match(
    val id: String,
    val eventId: String,
    val league: League,
    val start: OffsetDateTime?,
    /** Дата в том виде, как её прислал источник — для диагностики */
    val rawDate: String?,
    val state: String,          // pre / in / post
    val statusText: String,
    val tournament: String?,
    val home: Competitor,
    val away: Competitor,
    val odds: Odds?,
)

data class TeamStats(
    val gp: Double,
    val pf: Double?,
    val pa: Double?,
    val wins: Double,
    val losses: Double,
)

data class MatchExtras(
    val homeLast: List<String>,
    val awayLast: List<String>,
    val h2h: List<String>,
    val homeInjuries: List<String>,
    val awayInjuries: List<String>,
)
