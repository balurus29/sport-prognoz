@file:OptIn(ExperimentalMaterial3Api::class)

package com.sportprognoz.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sportprognoz.app.data.Leagues
import com.sportprognoz.app.data.Match
import com.sportprognoz.app.data.Sport
import com.sportprognoz.app.predict.Outcome
import com.sportprognoz.app.predict.Prediction
import com.sportprognoz.app.predict.Predictor
import com.sportprognoz.app.predict.Section
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Номер версии на главном экране — сразу видно, какая сборка установлена */
private const val APP_VERSION = "1.2"

private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
private val dayFmt = DateTimeFormatter.ofPattern("d MMM", Locale("ru"))
private val fullFmt = DateTimeFormatter.ofPattern("d MMMM, HH:mm", Locale("ru"))

@Composable
fun AppRoot(vm: MainViewModel = viewModel()) {
    val sel = vm.selected
    if (sel != null) {
        BackHandler { vm.close() }
        DetailScreen(vm)
    } else {
        MainScreen(vm)
    }
}

// ======================= Список матчей =======================

@Composable
private fun MainScreen(vm: MainViewModel) {
    Scaffold(containerColor = Palette.bg) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp),
            ) {
                Text("СпортПрогноз", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Palette.text)
                Text("  v$APP_VERSION", fontSize = 13.sp, color = Palette.muted, modifier = Modifier.padding(bottom = 3.dp))
            }

            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(Sport.entries.toList()) { s ->
                    FilterChip(
                        selected = s == vm.sport,
                        onClick = { vm.selectSport(s) },
                        enabled = s.available,
                        label = { Text(if (s.available) "${s.emoji} ${s.title}" else "${s.emoji} ${s.title} · скоро") },
                    )
                }
            }

            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(Leagues.forSport(vm.sport)) { l ->
                    FilterChip(
                        selected = l == vm.league,
                        onClick = { vm.selectLeague(l) },
                        label = { Text(l.title) },
                    )
                }
            }

            val today = LocalDate.now()
            val days = listOf(-1 to "Вчера", 0 to "Сегодня", 1 to "Завтра", 2 to today.plusDays(2).format(dayFmt))
            Row(
                Modifier.padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                days.forEach { (off, title) ->
                    FilterChip(selected = vm.dayOffset == off, onClick = { vm.selectDay(off) }, label = { Text(title) })
                }
            }

            when {
                vm.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Palette.accent)
                }
                vm.error != null -> Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(vm.error ?: "", color = Palette.bad)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { vm.load() }) { Text("Повторить") }
                }
                vm.items.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "На эту дату матчей нет. Возможно, у лиги перерыв или межсезонье — выберите другой день или турнир.",
                        color = Palette.muted,
                    )
                }
                else -> LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(vm.items, key = { it.match.id }) { item ->
                        MatchCard(item) { vm.open(item) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MatchCard(item: MatchItem, onClick: () -> Unit) {
    val m = item.match
    val p = item.prediction
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = Palette.card),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(localTime(m) ?: "время ?", color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                stateTag(m)?.let { (tag, color) ->
                    Text(
                        tag,
                        color = color,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(color.copy(alpha = 0.14f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                m.tournament?.let {
                    Text(" · $it", color = Palette.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false))
                }
                Spacer(Modifier.weight(1f))
                ConfidenceBadge(p.confidence)
            }
            Spacer(Modifier.height(8.dp))
            TeamRow(m.home.name, if (m.state != "pre") m.home.score else null)
            TeamRow(m.away.name, if (m.state != "pre") m.away.score else null)
            Spacer(Modifier.height(10.dp))
            ProbBar(p.p1, p.draw, p.p2)
            Spacer(Modifier.height(6.dp))
            Row {
                Text("П1 ${Predictor.pct(p.p1)}", color = Palette.home, fontSize = 13.sp)
                Spacer(Modifier.weight(1f))
                p.draw?.let { Text("Х ${Predictor.pct(it)}", color = Palette.draw, fontSize = 13.sp) }
                Spacer(Modifier.weight(1f))
                Text("П2 ${Predictor.pct(p.p2)}", color = Palette.away, fontSize = 13.sp)
            }
            Spacer(Modifier.height(8.dp))
            Text(p.pick, color = Palette.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun TeamRow(name: String, score: String?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(name, color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (score != null) Text(score, color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ProbBar(p1: Double, draw: Double?, p2: Double) {
    Row(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))) {
        Box(Modifier.weight(p1.toFloat().coerceAtLeast(0.01f)).fillMaxHeight().background(Palette.home))
        if (draw != null) {
            Spacer(Modifier.width(2.dp))
            Box(Modifier.weight(draw.toFloat().coerceAtLeast(0.01f)).fillMaxHeight().background(Palette.draw))
        }
        Spacer(Modifier.width(2.dp))
        Box(Modifier.weight(p2.toFloat().coerceAtLeast(0.01f)).fillMaxHeight().background(Palette.away))
    }
}

@Composable
private fun ConfidenceBadge(c: String) {
    val color = when (c) { "Высокая" -> Palette.accent; "Средняя" -> Palette.warn; else -> Palette.muted }
    Text(
        "Уверенность: $c",
        color = color,
        fontSize = 12.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** Время начала по часовому поясу телефона */
private fun localTime(m: Match): String? =
    m.start?.atZoneSameInstant(ZoneId.systemDefault())?.format(timeFmt)

/** Метка состояния: идёт / завершён / перенесён */
private fun stateTag(m: Match): Pair<String, Color>? = when (m.state) {
    "in" -> "● LIVE ${m.statusText}".trim() to Palette.bad
    "post" -> {
        val t = m.statusText.lowercase()
        when {
            "postp" in t -> "Перенесён" to Palette.warn
            "canc" in t -> "Отменён" to Palette.warn
            else -> "Завершён" to Palette.muted
        }
    }
    else -> null
}

private fun statusLabel(m: Match): String {
    val time = m.start?.atZoneSameInstant(ZoneId.systemDefault())?.format(fullFmt) ?: m.statusText
    val tag = stateTag(m)?.first
    return if (tag != null) "$time · $tag" else time
}

// ======================= Экран матча =======================

@Composable
private fun DetailScreen(vm: MainViewModel) {
    val item = vm.selected ?: return
    val p = vm.detail ?: item.prediction
    val m = item.match

    Scaffold(containerColor = Palette.bg) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize().imePadding(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                TextButton(onClick = { vm.close() }) { Text("← Назад к матчам", color = Palette.accent) }
            }
            item { HeaderCard(m, p) }
            item { PickCard(p) }
            items(p.sections, key = { it.title }) { SectionCard(it) }
            item { OddsInputCard(m.id, p.draw != null) { a, b, c -> vm.setUserOdds(a, b, c) } }
            if (p.valueBets.isNotEmpty()) item { ValueCard(p.valueBets) }
            item { AnalysisCard(p.analysis, vm.detailLoading) }
            item {
                Text(
                    "Прогноз — это вероятностная оценка по статистике, а не гарантия результата. " +
                        "Ставьте только те деньги, потерю которых готовы принять.",
                    color = Palette.muted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
    }
}

@Composable
private fun HeaderCard(m: Match, p: Prediction) {
    PanelCard {
        Text(m.league.title + (m.tournament?.let { " · $it" } ?: ""), color = Palette.muted, fontSize = 13.sp)
        Text(
            "Начало: ${statusLabel(m)}",
            color = if (m.state == "in") Palette.bad else Palette.text,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (m.start == null) {
            Text("Источник прислал дату: ${m.rawDate ?: "нет"}", color = Palette.warn, fontSize = 12.sp)
        }
        Spacer(Modifier.height(10.dp))
        TeamRow(m.home.name, if (m.state != "pre") m.home.score else null)
        TeamRow(m.away.name, if (m.state != "pre") m.away.score else null)
        Spacer(Modifier.height(10.dp))
        ProbBar(p.p1, p.draw, p.p2)
        Spacer(Modifier.height(8.dp))
        Text("Ожидаемый результат по модели: ${p.expectedScore}", color = Palette.text, fontSize = 14.sp)
    }
}

@Composable
private fun PickCard(p: Prediction) {
    PanelCard(Palette.accent.copy(alpha = 0.10f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Главный вывод", color = Palette.muted, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            ConfidenceBadge(p.confidence)
        }
        Spacer(Modifier.height(6.dp))
        Text(p.pick, color = Palette.accent, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SectionCard(s: Section) {
    PanelCard {
        Text(s.title, color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Row {
            Text("Ставка", color = Palette.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text("Шанс", color = Palette.muted, fontSize = 12.sp, modifier = Modifier.width(52.dp))
            Text("Честный кф", color = Palette.muted, fontSize = 12.sp, modifier = Modifier.width(80.dp))
        }
        s.items.forEach { OutcomeRow(it) }
        s.note?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, color = Palette.muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun OutcomeRow(o: Outcome) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(o.label, color = Palette.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text(Predictor.pct(o.prob), color = Palette.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.width(52.dp))
            Text(if (o.fairOdds >= 100) "—" else Predictor.f2(o.fairOdds), color = Palette.muted, fontSize = 15.sp,
                modifier = Modifier.width(80.dp))
        }
        val book = o.bookOdds
        val edge = o.edge
        if (book != null && edge != null) {
            val good = edge >= 0.03
            Text(
                "У букмекера ${Predictor.f2(book)} · " +
                    (if (edge >= 0) "перевес +${Predictor.pct(edge)}" else "невыгодно (${Predictor.pct(edge)})"),
                color = if (good) Palette.accent else Palette.muted,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun OddsInputCard(matchId: String, hasDraw: Boolean, onApply: (String, String, String) -> Unit) {
    var o1 by remember(matchId) { mutableStateOf("") }
    var ox by remember(matchId) { mutableStateOf("") }
    var o2 by remember(matchId) { mutableStateOf("") }
    PanelCard {
        Text("Проверить ценность ставки", color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text("Введите коэффициенты вашей букмекерской конторы — приложение сравнит их с моделью.",
            color = Palette.muted, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OddsField("П1", o1, Modifier.weight(1f)) { o1 = it }
            if (hasDraw) OddsField("Х", ox, Modifier.weight(1f)) { ox = it }
            OddsField("П2", o2, Modifier.weight(1f)) { o2 = it }
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = { onApply(o1, ox, o2) }, modifier = Modifier.fillMaxWidth()) { Text("Рассчитать") }
    }
}

@Composable
private fun OddsField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() || it == '.' || it == ',' }.take(6)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

@Composable
private fun ValueCard(list: List<Outcome>) {
    PanelCard(Palette.accent.copy(alpha = 0.10f)) {
        Text("Ценные ставки", color = Palette.accent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text("Коэффициент выше, чем «честный» по модели.", color = Palette.muted, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        list.take(5).forEach { o ->
            Text(
                "${o.label}: кф ${Predictor.f2(o.bookOdds ?: 0.0)}, модель ${Predictor.pct(o.prob)}, перевес +${Predictor.pct(o.edge ?: 0.0)}",
                color = Palette.text, fontSize = 14.sp, modifier = Modifier.padding(vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun AnalysisCard(lines: List<String>, loading: Boolean) {
    PanelCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Разбор матча", color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (loading) {
                Spacer(Modifier.width(10.dp))
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Palette.accent)
                Text("  форма, травмы…", color = Palette.muted, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(6.dp))
        lines.forEach {
            Row(Modifier.padding(vertical = 3.dp)) {
                Text("•  ", color = Palette.accent)
                Text(it, color = Palette.text, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun PanelCard(color: Color = Palette.card, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = color),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) { content() }
    }
}
