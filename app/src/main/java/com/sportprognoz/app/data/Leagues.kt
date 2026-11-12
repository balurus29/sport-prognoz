package com.sportprognoz.app.data

object Leagues {

    private fun football(code: String, title: String) =
        League(Sport.FOOTBALL, "soccer", code, title, ModelType.GOALS, homeAdv = 1.12, defaultAvg = 1.35)

    val all: List<League> = listOf(
        football("rus.1", "Россия. РПЛ"),
        football("eng.1", "Англия. АПЛ"),
        football("esp.1", "Испания. Ла Лига"),
        football("ita.1", "Италия. Серия А"),
        football("ger.1", "Германия. Бундеслига"),
        football("fra.1", "Франция. Лига 1"),
        football("ned.1", "Нидерланды. Эредивизи"),
        football("por.1", "Португалия. Лига"),
        football("tur.1", "Турция. Суперлига"),
        football("uefa.champions", "Лига чемпионов"),
        football("uefa.europa", "Лига Европы"),

        League(Sport.HOCKEY, "hockey", "nhl", "НХЛ", ModelType.GOALS, homeAdv = 1.05, defaultAvg = 3.05),

        League(Sport.BASKETBALL, "basketball", "nba", "НБА", ModelType.POINTS,
            homeAdv = 2.5, defaultAvg = 114.0, sdMargin = 12.5, sdTotal = 18.0, totalStep = 5.0),
        League(Sport.BASKETBALL, "basketball", "mens-college-basketball", "NCAA (муж.)", ModelType.POINTS,
            homeAdv = 3.5, defaultAvg = 73.0, sdMargin = 11.0, sdTotal = 15.0, totalStep = 4.0),

        League(Sport.TENNIS, "tennis", "atp", "ATP", ModelType.SETS, homeAdv = 0.0, setsToWin = 2),
        League(Sport.TENNIS, "tennis", "wta", "WTA", ModelType.SETS, homeAdv = 0.0, setsToWin = 2),

        League(Sport.VOLLEYBALL, "volleyball", "womens-college-volleyball", "NCAA (жен.)", ModelType.SETS,
            homeAdv = 0.04, setsToWin = 3),
        League(Sport.VOLLEYBALL, "volleyball", "mens-college-volleyball", "NCAA (муж.)", ModelType.SETS,
            homeAdv = 0.04, setsToWin = 3),

        League(Sport.BASEBALL, "baseball", "mlb", "MLB", ModelType.POINTS,
            homeAdv = 0.15, defaultAvg = 4.5, sdMargin = 4.3, sdTotal = 4.6, totalStep = 1.0),

        League(Sport.MMA, "mma", "ufc", "UFC", ModelType.FIGHT, homeAdv = 0.0),
        League(Sport.MMA, "mma", "pfl", "PFL", ModelType.FIGHT, homeAdv = 0.0),
    )

    fun forSport(sport: Sport): List<League> = all.filter { it.sport == sport }
}
