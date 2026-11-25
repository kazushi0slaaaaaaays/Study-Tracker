package com.example.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.LocalStudyColors
import com.example.ui.theme.parseHexColor
import com.example.viewmodel.DailyBarPoint
import com.example.viewmodel.HeatmapDayCell
import com.example.viewmodel.StatsRange
import com.example.viewmodel.StudyAnalytics
import com.example.viewmodel.StudyAppState
import com.example.viewmodel.SubjectBreakdownItem

@Composable
fun StatsScreen(state: StudyAppState) {
    val colors = LocalStudyColors.current
    var rangeIndex by remember { mutableIntStateOf(1) } // Default: Week
    val selectedRange = StatsRange.entries[rangeIndex]

    val stats = remember(
        state.sessions,
        state.subjects,
        selectedRange,
        state.settings.weekStartsOn,
        state.settings.streakMinMinutes,
        state.settings.dailyGoalMin,
        state.nowServerMs / 60_000L,
    ) {
        StudyAnalytics.computeStats(
            sessions = state.sessions,
            subjects = state.subjects,
            range = selectedRange,
            weekStartsOn = state.settings.weekStartsOn,
            streakMinMinutes = state.settings.streakMinMinutes,
            dailyGoalMin = state.settings.dailyGoalMin,
            nowMs = state.nowServerMs,
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Sticky segmented control: Today, Week, Month, All
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.bg)
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            StudySegmentedControl(
                options = StatsRange.entries.map { it.label },
                selectedIndex = rangeIndex,
                onSelect = { rangeIndex = it },
                testTagPrefix = "stats_range",
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // (1) Summary tiles in 2x2 grid
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        SummaryTile(
                            label = "Total Time",
                            value = StudyAnalytics.formatDurationHuman(stats.totalMs),
                            modifier = Modifier.weight(1f),
                        )
                        SummaryTile(
                            label = "Daily Average",
                            value = StudyAnalytics.formatDurationHuman(stats.dailyAverageMs),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        SummaryTile(
                            label = "Sessions",
                            value = stats.sessionCount.toString(),
                            modifier = Modifier.weight(1f),
                        )
                        SummaryTile(
                            label = "Best Day",
                            value = StudyAnalytics.formatDurationHuman(stats.bestDayMs),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // (2) Hand-built Bar Chart with dashed goal line & tap tooltip bubble
            item {
                DailyBarChartCard(
                    bars = stats.bars,
                    goalMin = state.settings.dailyGoalMin.toFloat(),
                )
            }

            // (3) Hand-built Subject Donut with legend list
            item {
                SubjectDonutCard(items = stats.subjectBreakdown)
            }

            // (4) 20-week x 7-day GitHub-style Heatmap with 4 intensity steps & month labels
            item {
                StudyHeatmapCard(weeks = stats.heatmapWeeks)
            }

            // (5) Streak card with 14-day mini calendar
            item {
                StudyCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.LocalFireDepartment,
                            contentDescription = "Streak",
                            tint = colors.warning,
                            modifier = Modifier.size(28.dp),
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${stats.streak.currentStreak} day current streak",
                                style = MaterialTheme.typography.titleMedium,
                                color = colors.text,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "Best: ${stats.streak.bestStreak} days · Last 14 days",
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.textDim,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        stats.streak.last14Days.forEach { (dayNum, reached) ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(
                                    modifier = Modifier
                                        .size(18.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (reached) colors.accent else colors.surface2)
                                        .border(
                                            1.dp,
                                            if (reached) colors.accent else colors.border,
                                            RoundedCornerShape(6.dp),
                                        )
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = dayNum,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 10.sp,
                                    color = if (reached) colors.text else colors.textDim,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalStudyColors.current
    StudyCard(modifier = modifier, contentPadding = 14.dp) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = colors.textDim)
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            color = colors.text,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun DailyBarChartCard(
    bars: List<DailyBarPoint>,
    goalMin: Float,
) {
    val colors = LocalStudyColors.current
    var selectedBar by remember(bars) { mutableStateOf<DailyBarPoint?>(null) }
    val maxVal = maxOf(goalMin * 1.15f, bars.maxOfOrNull { it.minutes } ?: 60f, 30f)

    StudyCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Study Time per Day",
                style = MaterialTheme.typography.titleMedium,
                color = colors.text,
                fontWeight = FontWeight.SemiBold,
            )
            selectedBar?.let { pt ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.accent.copy(alpha = 0.16f))
                        .border(1.dp, colors.accent.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "${pt.dayKey}: ${StudyAnalytics.formatDurationHuman((pt.minutes * 60_000L).toLong())}",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.text,
                        fontWeight = FontWeight.Medium,
                    )
                }
            } ?: Text(
                text = "Goal ${goalMin.toInt()}m",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textDim,
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
        ) {
            // Dashed goal line behind bars
            Canvas(modifier = Modifier.fillMaxSize()) {
                val chartH = size.height - 24.dp.toPx()
                val goalY = chartH * (1f - (goalMin / maxVal).coerceIn(0f, 1f))
                drawLine(
                    color = colors.warning.copy(alpha = 0.65f),
                    start = Offset(0f, goalY),
                    end = Offset(size.width, goalY),
                    strokeWidth = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
                )
            }

            // Interactive bars row
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                bars.forEach { pt ->
                    val ratio = (pt.minutes / maxVal).coerceIn(0f, 1f)
                    val isSelected = selectedBar?.dayKey == pt.dayKey
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { selectedBar = if (isSelected) null else pt },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(134.dp),
                            contentAlignment = Alignment.BottomCenter,
                        ) {
                            val barHeight = (134f * ratio).coerceAtLeast(if (pt.minutes > 0f) 6f else 2f)
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.72f)
                                    .height(barHeight.dp)
                                    .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                                    .background(
                                        when {
                                            isSelected -> colors.success
                                            pt.minutes >= goalMin -> colors.accent
                                            pt.minutes > 0f -> colors.accent.copy(alpha = 0.65f)
                                            else -> colors.surface2
                                        }
                                    )
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = pt.label,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp,
                            color = if (isSelected) colors.text else colors.textDim,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SubjectDonutCard(items: List<SubjectBreakdownItem>) {
    val colors = LocalStudyColors.current
    StudyCard {
        Text(
            text = "Time by Subject",
            style = MaterialTheme.typography.titleMedium,
            color = colors.text,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(14.dp))

        if (items.isEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Outlined.BarChart, contentDescription = null, tint = colors.textDim)
                Text(
                    text = "Log a study session to see your subject breakdown.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textDim,
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                // Hand-built Donut chart
                Canvas(modifier = Modifier.size(124.dp)) {
                    val stroke = 18.dp.toPx()
                    val inset = stroke / 2f
                    val sz = Size(size.width - stroke, size.height - stroke)
                    var startAngle = -90f
                    items.forEach { item ->
                        val sweep = (360f * item.percent).coerceAtLeast(2f)
                        drawArc(
                            color = parseHexColor(item.colorHex),
                            startAngle = startAngle,
                            sweepAngle = sweep,
                            useCenter = false,
                            topLeft = Offset(inset, inset),
                            size = sz,
                            style = Stroke(width = stroke, cap = StrokeCap.Butt),
                        )
                        startAngle += sweep
                    }
                }

                // Legend list
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items.take(6).forEach { item ->
                        val c = parseHexColor(item.colorHex)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(9.dp)
                                    .background(c, CircleShape)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${item.emoji} ${item.name}".trim(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "${StudyAnalytics.formatDurationHuman(item.durationMs)} · ${(item.percent * 100).toInt()}%",
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.textDim,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StudyHeatmapCard(weeks: List<List<HeatmapDayCell>>) {
    val colors = LocalStudyColors.current
    val scrollState = rememberScrollState()

    StudyCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "20-Week Activity",
                style = MaterialTheme.typography.titleMedium,
                color = colors.text,
                fontWeight = FontWeight.SemiBold,
            )
            // 4-step intensity legend
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Less", style = MaterialTheme.typography.labelSmall, color = colors.textDim, fontSize = 10.sp)
                listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { alpha ->
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(if (alpha == 0f) colors.surface2 else colors.accent.copy(alpha = alpha))
                    )
                }
                Text("More", style = MaterialTheme.typography.labelSmall, color = colors.textDim, fontSize = 10.sp)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Column(modifier = Modifier.horizontalScroll(scrollState)) {
            // Month label row
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                weeks.forEach { week ->
                    val label = week.firstOrNull()?.monthLabel.orEmpty()
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        color = colors.textDim,
                        maxLines = 1,
                        modifier = Modifier.width(14.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            // 20 columns x 7 rows
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                weeks.forEach { week ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        week.forEach { cell ->
                            val cellColor = when (cell.level) {
                                0 -> colors.surface2
                                1 -> colors.accent.copy(alpha = 0.28f)
                                2 -> colors.accent.copy(alpha = 0.52f)
                                3 -> colors.accent.copy(alpha = 0.76f)
                                else -> colors.accent
                            }
                            Canvas(modifier = Modifier.size(14.dp)) {
                                drawRoundRect(
                                    color = cellColor,
                                    cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
