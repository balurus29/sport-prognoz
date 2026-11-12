package com.sportprognoz.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sportprognoz.app.data.EspnApi
import com.sportprognoz.app.data.League
import com.sportprognoz.app.data.Leagues
import com.sportprognoz.app.data.Match
import com.sportprognoz.app.data.MatchExtras
import com.sportprognoz.app.data.Sport
import com.sportprognoz.app.data.TeamStats
import com.sportprognoz.app.predict.Prediction
import com.sportprognoz.app.predict.Predictor
import com.sportprognoz.app.predict.UserOdds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate

data class MatchItem(val match: Match, val prediction: Prediction)

class MainViewModel : ViewModel() {

    var sport by mutableStateOf(Sport.FOOTBALL); private set
    var league by mutableStateOf(Leagues.forSport(Sport.FOOTBALL).first()); private set
    var dayOffset by mutableIntStateOf(0); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var items by mutableStateOf<List<MatchItem>>(emptyList()); private set

    var selected by mutableStateOf<MatchItem?>(null); private set
    var detail by mutableStateOf<Prediction?>(null); private set
    var detailLoading by mutableStateOf(false); private set

    private var stats: Map<String, TeamStats> = emptyMap()
    private var extras: MatchExtras? = null
    private var userOdds: UserOdds? = null
    private var loadJob: Job? = null
    private var detailJob: Job? = null

    init {
        load()
    }

    fun selectSport(s: Sport) {
        if (!s.available || s == sport) return
        sport = s
        league = Leagues.forSport(s).first()
        load()
    }

    fun selectLeague(l: League) {
        if (l == league) return
        league = l
        load()
    }

    fun selectDay(offset: Int) {
        if (offset == dayOffset) return
        dayOffset = offset
        load()
    }

    fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            loading = true
            error = null
            items = emptyList()
            try {
                val lg = league
                val day = LocalDate.now().plusDays(dayOffset.toLong())
                val st = EspnApi.standings(lg)
                val matches = EspnApi.matches(lg, day)
                stats = st
                items = matches.map { MatchItem(it, Predictor.predict(it, st, null, null)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "Не удалось загрузить данные: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                if (isActive) loading = false
            }
        }
    }

    fun open(item: MatchItem) {
        selected = item
        detail = item.prediction
        extras = null
        userOdds = null
        detailLoading = true
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            extras = try {
                EspnApi.extras(item.match)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            recompute()
            detailLoading = false
        }
    }

    fun close() {
        detailJob?.cancel()
        selected = null
        detail = null
    }

    fun setUserOdds(o1: String, ox: String, o2: String) {
        fun parse(s: String) = s.replace(',', '.').trim().toDoubleOrNull()?.takeIf { it > 1.0 }
        userOdds = UserOdds(parse(o1), parse(ox), parse(o2))
        recompute()
    }

    private fun recompute() {
        val sel = selected ?: return
        detail = Predictor.predict(sel.match, stats, extras, userOdds)
    }
}
