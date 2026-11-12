package com.sportprognoz.app.predict

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sqrt

object PMath {

    fun poisson(k: Int, lambda: Double): Double {
        var r = exp(-lambda)
        for (i in 1..k) r *= lambda / i
        return r
    }

    /** Функция ошибок (Абрамовиц–Стиган 7.1.26, точность ~1e-7) */
    fun erf(x: Double): Double {
        val t = 1.0 / (1.0 + 0.3275911 * abs(x))
        val y = 1 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t +
            0.254829592) * t * exp(-x * x)
        return if (x >= 0) y else -y
    }

    fun normCdf(z: Double): Double = 0.5 * (1 + erf(z / sqrt(2.0)))

    fun binom(n: Int, k: Int): Double {
        var r = 1.0
        for (i in 1..k) r = r * (n - k + i) / i
        return r
    }

    /** P(выиграть матч до k сетов со счётом k:j), s — вероятность взять один сет */
    fun setScoreProb(s: Double, k: Int, j: Int): Double =
        binom(k - 1 + j, j) * s.pow(k) * (1 - s).pow(j)

    fun matchFromSet(s: Double, k: Int): Double = (0 until k).sumOf { setScoreProb(s, k, it) }

    /** Обратная задача: по вероятности выиграть матч найти вероятность выиграть сет */
    fun setFromMatch(p: Double, k: Int): Double {
        var lo = 0.0005
        var hi = 0.9995
        repeat(60) {
            val mid = (lo + hi) / 2
            if (matchFromSet(mid, k) < p) lo = mid else hi = mid
        }
        return (lo + hi) / 2
    }

    /** Ближайшая «половинчатая» линия: 2.3 → 2.5, 2.7 → 2.5, 219.2 → 219.5 */
    fun halfLine(x: Double): Double = floor(x) + 0.5

    /** Формула log5: шанс A против B по их общим долям побед */
    fun log5(a: Double, b: Double): Double {
        val den = a + b - 2 * a * b
        return if (abs(den) < 1e-9) 0.5 else (a - a * b) / den
    }
}
