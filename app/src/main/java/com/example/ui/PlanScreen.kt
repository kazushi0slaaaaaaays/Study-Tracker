package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.SubdirectoryArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.Chapter
import com.example.data.Exam
import com.example.data.StudyGoal
import com.example.data.Subject
import com.example.ui.theme.LocalStudyColors
import com.example.ui.theme.parseHexColor
import com.example.ui.theme.subjectTint
import com.example.viewmodel.StudyAnalytics
import com.example.viewmodel.StudyAppState
import com.example.viewmodel.StudyViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@Composable
fun PlanScreen(
    state: StudyAppState,
    viewModel: StudyViewModel,
) {
    val colors = LocalStudyColors.current
    var selectedSection by remember { mutableIntStateOf(0) } // 0 Checklist, 1 Goals, 2 Exams
    val expandedSubjects = remember { mutableStateMapOf<String, Boolean>() }

    var showAddSubjectDialog by remember { mutableStateOf(false) }
    var showAddChapterDialog by remember { mutableStateOf(false) }
    var addChapterParentId by remember { mutableStateOf("") }
    var addChapterDefaultSubjectId by remember { mutableStateOf("") }
    var showAddGoalDialog by remember { mutableStateOf(false) }
    var showAddExamDialog by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Sticky segmented header: Checklist, Goals, Exams
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.bg)
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                StudySegmentedControl(
                    options = listOf("Checklist", "Goals", "Exams"),
                    selectedIndex = selectedSection,
                    onSelect = { selectedSection = it },
                    testTagPrefix = "plan_segment",
                )
            }

            when (selectedSection) {
                0 -> ChecklistSection(
                    state = state,
                    expandedSubjects = expandedSubjects,
                    onToggleChapter = { viewModel.toggleChapterDone(it) },
                    onDeleteChapter = { viewModel.deleteChapter(it.id) },
                    onReorderChapters = { viewModel.reorderChapters(it) },
                    onAddSubItem = { subId, parentId ->
                        addChapterDefaultSubjectId = subId
                        addChapterParentId = parentId
                        showAddChapterDialog = true
                    },
                    onAddSubject = { showAddSubjectDialog = true },
                )
                1 -> GoalsSection(
                    state = state,
                    onAddGoal = { showAddGoalDialog = true },
                    onDeleteGoal = { viewModel.deleteGoal(it) },
                )
                2 -> ExamsSection(
                    state = state,
                    onAddExam = { showAddExamDialog = true },
                    onDeleteExam = { viewModel.deleteExam(it) },
                )
            }
        }

        // Contextual Floating Add button
        FloatingActionButton(
            onClick = {
                when (selectedSection) {
                    0 -> {
                        if (state.activeSubjects.isEmpty()) {
                            showAddSubjectDialog = true
                        } else {
                            addChapterDefaultSubjectId = state.effectiveSelectedSubjectId
                            addChapterParentId = ""
                            showAddChapterDialog = true
                        }
                    }
                    1 -> showAddGoalDialog = true
                    2 -> showAddExamDialog = true
                }
            },
            containerColor = colors.accent,
            contentColor = Color.White,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
                .testTag("plan_fab_add"),
        ) {
            Icon(Icons.Outlined.Add, contentDescription = "Add item")
        }
    }

    if (showAddSubjectDialog) {
        AddSubjectDialog(
            onDismiss = { showAddSubjectDialog = false },
            onSave = { name, colorHex, emoji, courseCode ->
                viewModel.addSubject(name, colorHex, emoji, courseCode)
                showAddSubjectDialog = false
            },
        )
    }

    if (showAddChapterDialog) {
        AddChapterDialog(
            subjects = state.activeSubjects,
            defaultSubjectId = addChapterDefaultSubjectId.ifBlank { state.effectiveSelectedSubjectId },
            isSubItem = addChapterParentId.isNotBlank(),
            onDismiss = { showAddChapterDialog = false },
            onSave = { subjectId, title, dueDate ->
                viewModel.addChapter(subjectId, addChapterParentId, title, dueDate)
                expandedSubjects[subjectId] = true
                showAddChapterDialog = false
            },
        )
    }

    if (showAddGoalDialog) {
        AddGoalDialog(
            subjects = state.activeSubjects,
            onDismiss = { showAddGoalDialog = false },
            onSave = { goal ->
                viewModel.saveGoal(goal)
                showAddGoalDialog = false
            },
        )
    }

    if (showAddExamDialog) {
        AddExamDialog(
            subjects = state.activeSubjects,
            onDismiss = { showAddExamDialog = false },
            onSave = { title, subId, date ->
                viewModel.addExam(title, subId, date)
                showAddExamDialog = false
            },
        )
    }
}

@Composable
private fun ChecklistSection(
    state: StudyAppState,
    expandedSubjects: MutableMap<String, Boolean>,
    onToggleChapter: (Chapter) -> Unit,
    onDeleteChapter: (Chapter) -> Unit,
    onReorderChapters: (List<String>) -> Unit,
    onAddSubItem: (subjectId: String, parentId: String) -> Unit,
    onAddSubject: () -> Unit,
) {
    val colors = LocalStudyColors.current
    val subjects = state.activeSubjects

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 92.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Subjects & Chapters",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.text,
                    fontWeight = FontWeight.SemiBold,
                )
                TextButton(
                    onClick = onAddSubject,
                    modifier = Modifier.testTag("plan_add_subject_btn"),
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Subject")
                }
            }
        }

        if (subjects.isEmpty()) {
            item {
                FriendlyEmptyState(
                    icon = Icons.Outlined.CheckCircle,
                    title = "Add your university subjects",
                    subtitle = "Organize chapters, problem sets, and sub-items under each mechanical engineering course.",
                    actionLabel = "Add first subject",
                    onAction = onAddSubject,
                    testTag = "empty_checklist_add_subject",
                )
            }
        } else {
            items(subjects, key = { it.id }) { subject ->
                val allSubjectChapters = state.chapters.filter { it.subjectId == subject.id }
                val topLevel = allSubjectChapters.filter { it.parentId.isBlank() }.sortedBy { it.order }
                val doneCount = allSubjectChapters.count { it.done }
                val pct = if (allSubjectChapters.isNotEmpty()) doneCount.toFloat() / allSubjectChapters.size else 0f
                val expanded = expandedSubjects[subject.id] ?: true
                val subColor = parseHexColor(subject.colorHex)

                StudyCard(contentPadding = 0.dp) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        // Colored left edge bar
                        Box(
                            modifier = Modifier
                                .width(5.dp)
                                .height(68.dp)
                                .background(subColor)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { expandedSubjects[subject.id] = !expanded }
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(text = subject.emoji.ifBlank { "📘" }, fontSize = 20.sp)
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = subject.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = colors.text,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    val subMeta = buildString {
                                        if (subject.courseCode.isNotBlank()) {
                                            append(subject.courseCode)
                                            append(" · ")
                                        }
                                        append("$doneCount/${allSubjectChapters.size} done")
                                    }
                                    Text(
                                        text = subMeta,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = colors.textDim,
                                    )
                                }
                                MiniProgressRing(progress = pct, color = subColor)
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(
                                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                    contentDescription = if (expanded) "Collapse" else "Expand",
                                    tint = colors.textDim,
                                )
                            }

                            AnimatedVisibility(visible = expanded) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 10.dp, end = 10.dp, bottom = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    if (topLevel.isEmpty()) {
                                        Text(
                                            text = "No chapters yet — tap + to add one.",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = colors.textDim,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                        )
                                    } else {
                                        topLevel.forEachIndexed { idx, chapter ->
                                            val subItems = allSubjectChapters
                                                .filter { it.parentId == chapter.id }
                                                .sortedBy { it.order }

                                            ChapterRow(
                                                chapter = chapter,
                                                subjectColor = subColor,
                                                indent = false,
                                                onToggle = { onToggleChapter(chapter) },
                                                onAddSubItem = { onAddSubItem(subject.id, chapter.id) },
                                                onDelete = { onDeleteChapter(chapter) },
                                                onMoveStep = { delta ->
                                                    val targetIdx = (idx + delta).coerceIn(0, topLevel.lastIndex)
                                                    if (targetIdx != idx) {
                                                        val mutable = topLevel.map { it.id }.toMutableList()
                                                        val moved = mutable.removeAt(idx)
                                                        mutable.add(targetIdx, moved)
                                                        onReorderChapters(mutable)
                                                    }
                                                },
                                            )

                                            subItems.forEachIndexed { subIdx, child ->
                                                ChapterRow(
                                                    chapter = child,
                                                    subjectColor = subColor,
                                                    indent = true,
                                                    onToggle = { onToggleChapter(child) },
                                                    onAddSubItem = null,
                                                    onDelete = { onDeleteChapter(child) },
                                                    onMoveStep = { delta ->
                                                        val targetIdx = (subIdx + delta).coerceIn(0, subItems.lastIndex)
                                                        if (targetIdx != subIdx) {
                                                            val mutable = subItems.map { it.id }.toMutableList()
                                                            val moved = mutable.removeAt(subIdx)
                                                            mutable.add(targetIdx, moved)
                                                            onReorderChapters(mutable)
                                                        }
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChapterRow(
    chapter: Chapter,
    subjectColor: Color,
    indent: Boolean,
    onToggle: () -> Unit,
    onAddSubItem: (() -> Unit)?,
    onDelete: () -> Unit,
    onMoveStep: (Int) -> Unit,
) {
    val colors = LocalStudyColors.current
    var dragAccumY by remember { mutableFloatStateOf(0f) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (indent) 24.dp else 0.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (chapter.done) colors.surface2.copy(alpha = 0.5f) else Color.Transparent)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onToggle,
            modifier = Modifier.size(44.dp).testTag("chapter_check_${chapter.id}"),
        ) {
            Icon(
                imageVector = if (chapter.done) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = if (chapter.done) "Mark incomplete" else "Mark done",
                tint = if (chapter.done) subjectColor else colors.textDim,
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = chapter.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (chapter.done) colors.textDim else colors.text,
                textDecoration = if (chapter.done) TextDecoration.LineThrough else TextDecoration.None,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (chapter.dueDate.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.surface2)
                        .border(1.dp, colors.border, RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "Due ${chapter.dueDate}",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textDim,
                    )
                }
            }
        }

        if (onAddSubItem != null) {
            IconButton(onClick = onAddSubItem, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.Outlined.SubdirectoryArrowRight,
                    contentDescription = "Add sub-item",
                    tint = colors.textDim,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = Icons.Outlined.Delete,
                contentDescription = "Delete chapter",
                tint = colors.textDim.copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp),
            )
        }

        // Pointer-event drag handle (custom Compose pointerInput, no drag library)
        Box(
            modifier = Modifier
                .size(40.dp)
                .pointerInput(chapter.id) {
                    detectVerticalDragGestures(
                        onDragStart = { dragAccumY = 0f },
                        onVerticalDrag = { _, dragAmount ->
                            dragAccumY += dragAmount
                            if (dragAccumY > 48f) {
                                onMoveStep(1)
                                dragAccumY = 0f
                            } else if (dragAccumY < -48f) {
                                onMoveStep(-1)
                                dragAccumY = 0f
                            }
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.DragHandle,
                contentDescription = "Drag to reorder",
                tint = colors.textDim,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun MiniProgressRing(progress: Float, color: Color) {
    val colors = LocalStudyColors.current
    Box(modifier = Modifier.size(42.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 4.dp.toPx()
            val inset = stroke / 2f
            val sz = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = colors.surface2,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = sz,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 360f * progress.coerceIn(0f, 1f),
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = sz,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        Text(
            text = "${(progress * 100).toInt()}%",
            style = MaterialTheme.typography.labelSmall,
            color = colors.text,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun GoalsSection(
    state: StudyAppState,
    onAddGoal: () -> Unit,
    onDeleteGoal: (String) -> Unit,
) {
    val colors = LocalStudyColors.current
    val todayStart = StudyAnalytics.startOfTodayMs(state.nowServerMs)
    val weekStart = StudyAnalytics.startOfWeekMs(state.nowServerMs, state.settings.weekStartsOn)
    val todayMs = StudyAnalytics.totalStudyMsInRange(state.sessions, todayStart)
    val weekMs = StudyAnalytics.totalStudyMsInRange(state.sessions, weekStart)
    val dailyGoalMs = (state.settings.dailyGoalMin * 60_000L).coerceAtLeast(60_000L)
    val weeklyGoalMs = (state.settings.weeklyGoalMin * 60_000L).coerceAtLeast(60_000L)
    val streak = StudyAnalytics.computeStreak(state.sessions, state.settings.streakMinMinutes, state.nowServerMs)
    val subjectMap = state.subjects.associateBy { it.id }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 92.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Two rings side by side: Today & This Week
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                GoalRingCard(
                    title = "Today",
                    currentMs = todayMs,
                    targetMs = dailyGoalMs,
                    ringColor = colors.accent,
                    modifier = Modifier.weight(1f),
                )
                GoalRingCard(
                    title = "This Week",
                    currentMs = weekMs,
                    targetMs = weeklyGoalMs,
                    ringColor = colors.success,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // Streak card with flame icon, current streak large, best streak dim
        item {
            StudyCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .background(colors.warning.copy(alpha = 0.16f), CircleShape)
                            .border(1.dp, colors.warning.copy(alpha = 0.35f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.LocalFireDepartment,
                            contentDescription = "Study streak",
                            tint = colors.warning,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${streak.currentStreak} day${if (streak.currentStreak == 1) "" else "s"}",
                            style = MaterialTheme.typography.headlineMedium,
                            color = colors.text,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Best streak: ${streak.bestStreak} days · Threshold ${state.settings.streakMinMinutes}m/day",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textDim,
                        )
                    }
                }
            }
        }

        // Per-subject goals
        item {
            Text(
                text = "Subject Goals",
                style = MaterialTheme.typography.titleMedium,
                color = colors.text,
                fontWeight = FontWeight.SemiBold,
            )
        }

        val subjectGoals = state.goals.filter { it.scope != "overall" }
        if (subjectGoals.isEmpty()) {
            item {
                FriendlyEmptyState(
                    icon = Icons.Outlined.Flag,
                    title = "No per-subject goals yet",
                    subtitle = "Set daily or weekly targets for key courses like Thermodynamics or Fluid Mechanics.",
                    actionLabel = "Add subject goal",
                    onAction = onAddGoal,
                    testTag = "empty_goals_add_btn",
                )
            }
        } else {
            items(subjectGoals, key = { it.id }) { goal ->
                val sub = subjectMap[goal.scope]
                val subColor = parseHexColor(sub?.colorHex ?: "#6C7BFF")
                val rangeStart = if (goal.period == "weekly") weekStart else todayStart
                val doneMs = StudyAnalytics.totalStudyMsInRange(state.sessions, rangeStart, subjectId = goal.scope)
                val targetMs = (goal.targetMin * 60_000L).coerceAtLeast(60_000L)
                val pct = (doneMs.toFloat() / targetMs.toFloat()).coerceIn(0f, 1f)

                StudyCard(contentPadding = 14.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${sub?.emoji.orEmpty()} ${sub?.name ?: "Subject"} (${goal.period})".trim(),
                                style = MaterialTheme.typography.titleMedium,
                                color = colors.text,
                            )
                            Text(
                                text = "${StudyAnalytics.formatDurationHuman(doneMs)} / ${StudyAnalytics.formatDurationHuman(targetMs)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textDim,
                            )
                        }
                        Text(
                            text = "${(pct * 100).toInt()}%",
                            style = MaterialTheme.typography.titleMedium,
                            color = subColor,
                            fontWeight = FontWeight.SemiBold,
                        )
                        IconButton(onClick = { onDeleteGoal(goal.id) }, modifier = Modifier.size(44.dp)) {
                            Icon(Icons.Outlined.Delete, contentDescription = "Delete goal", tint = colors.textDim, modifier = Modifier.size(18.dp))
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { pct },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = subColor,
                        trackColor = colors.surface2,
                    )
                }
            }
        }
    }
}

@Composable
private fun GoalRingCard(
    title: String,
    currentMs: Long,
    targetMs: Long,
    ringColor: Color,
    modifier: Modifier = Modifier,
) {
    val colors = LocalStudyColors.current
    val pct = (currentMs.toFloat() / targetMs.toFloat()).coerceIn(0f, 1f)

    StudyCard(modifier = modifier, contentPadding = 16.dp) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = title, style = MaterialTheme.typography.labelMedium, color = colors.textDim)
            Spacer(modifier = Modifier.height(12.dp))
            Box(modifier = Modifier.size(92.dp), contentAlignment = Alignment.Center) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val stroke = 8.dp.toPx()
                    val inset = stroke / 2f
                    val sz = Size(size.width - stroke, size.height - stroke)
                    drawArc(
                        color = colors.surface2,
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = sz,
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                    drawArc(
                        color = ringColor,
                        startAngle = -90f,
                        sweepAngle = 360f * pct,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = sz,
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
                Text(
                    text = "${(pct * 100).toInt()}%",
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.text,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "${StudyAnalytics.formatDurationHuman(currentMs)} / ${StudyAnalytics.formatDurationHuman(targetMs)}",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textDim,
            )
        }
    }
}

@Composable
private fun ExamsSection(
    state: StudyAppState,
    onAddExam: () -> Unit,
    onDeleteExam: (String) -> Unit,
) {
    val colors = LocalStudyColors.current
    val subjectMap = state.subjects.associateBy { it.id }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 92.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.exams.isEmpty()) {
            item {
                FriendlyEmptyState(
                    icon = Icons.Outlined.Event,
                    title = "No exam countdowns yet",
                    subtitle = "Add upcoming midterms and finals to see live countdowns and get reminders at 7, 3, and 1 days before.",
                    actionLabel = "Add exam",
                    onAction = onAddExam,
                    testTag = "empty_exams_add_btn",
                )
            }
        } else {
            items(state.exams, key = { it.id }) { exam ->
                val daysLeft = StudyAnalytics.daysUntilExam(exam.date, state.nowServerMs)
                val sub = subjectMap[exam.subjectId]
                val subColor = parseHexColor(sub?.colorHex ?: "#6C7BFF")

                val (cardBg, cardBorder, badgeColor) = when {
                    daysLeft <= 2 -> Triple(colors.dangerSurface, colors.danger.copy(alpha = 0.6f), colors.danger)
                    daysLeft <= 7 -> Triple(colors.warning.copy(alpha = 0.10f), colors.warning.copy(alpha = 0.55f), colors.warning)
                    else -> Triple(colors.surface, colors.border, colors.text)
                }

                StudyCard(
                    containerColor = cardBg,
                    borderColor = cardBorder,
                    contentPadding = 16.dp,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = exam.title,
                                style = MaterialTheme.typography.titleMedium,
                                color = colors.text,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (sub != null) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(subColor.subjectTint())
                                            .border(1.dp, subColor.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                                            .padding(horizontal = 8.dp, vertical = 3.dp)
                                    ) {
                                        Text(
                                            text = "${sub.emoji} ${sub.name}".trim(),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = colors.text,
                                        )
                                    }
                                }
                                Text(
                                    text = exam.date,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colors.textDim,
                                )
                            }
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = when {
                                    daysLeft < 0 -> "Passed"
                                    daysLeft == 0 -> "Today"
                                    daysLeft == 1 -> "1 day"
                                    else -> "$daysLeft days"
                                },
                                style = MaterialTheme.typography.headlineMedium,
                                color = badgeColor,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "Reminders: 7, 3, 1d",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textDim,
                            )
                        }

                        IconButton(onClick = { onDeleteExam(exam.id) }, modifier = Modifier.size(44.dp)) {
                            Icon(Icons.Outlined.Delete, contentDescription = "Delete exam", tint = colors.textDim, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddChapterDialog(
    subjects: List<Subject>,
    defaultSubjectId: String,
    isSubItem: Boolean,
    onDismiss: () -> Unit,
    onSave: (subjectId: String, title: String, dueDate: String) -> Unit,
) {
    val colors = LocalStudyColors.current
    var subjectId by remember { mutableStateOf(defaultSubjectId.ifBlank { subjects.firstOrNull()?.id.orEmpty() }) }
    var title by remember { mutableStateOf("") }
    var dueDate by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = { Text(if (isSubItem) "Add Sub-item" else "Add Chapter", color = colors.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!isSubItem) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(subjects, key = { it.id }) { sub ->
                            val selected = sub.id == subjectId
                            val c = parseHexColor(sub.colorHex)
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (selected) c.subjectTint() else colors.surface2)
                                    .border(1.dp, if (selected) c else colors.border, RoundedCornerShape(14.dp))
                                    .clickable { subjectId = sub.id }
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Text("${sub.emoji} ${sub.name}", color = colors.text, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(200) },
                    label = { Text(if (isSubItem) "Sub-item title" else "Chapter title") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = dueDate,
                    onValueChange = { dueDate = it.take(16) },
                    label = { Text("Due date (optional, YYYY-MM-DD)") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(subjectId, title.trim(), dueDate.trim()) },
                enabled = title.trim().isNotEmpty() && subjectId.isNotBlank(),
                shape = RoundedCornerShape(14.dp),
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AddGoalDialog(
    subjects: List<Subject>,
    onDismiss: () -> Unit,
    onSave: (StudyGoal) -> Unit,
) {
    val colors = LocalStudyColors.current
    var selectedSubId by remember { mutableStateOf(subjects.firstOrNull()?.id.orEmpty()) }
    var periodIdx by remember { mutableIntStateOf(0) }
    var targetText by remember { mutableStateOf("60") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = { Text("Add Subject Goal", color = colors.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (subjects.isEmpty()) {
                    Text("Add a subject first.", color = colors.danger)
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(subjects, key = { it.id }) { sub ->
                            val selected = sub.id == selectedSubId
                            val c = parseHexColor(sub.colorHex)
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (selected) c.subjectTint() else colors.surface2)
                                    .border(1.dp, if (selected) c else colors.border, RoundedCornerShape(14.dp))
                                    .clickable { selectedSubId = sub.id }
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Text("${sub.emoji} ${sub.name}", color = colors.text, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                StudySegmentedControl(
                    options = listOf("Daily", "Weekly"),
                    selectedIndex = periodIdx,
                    onSelect = { periodIdx = it },
                )
                OutlinedTextField(
                    value = targetText,
                    onValueChange = { targetText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Target minutes") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val mins = targetText.toLongOrNull()?.coerceIn(5L, 10080L) ?: 60L
                    onSave(
                        StudyGoal(
                            scope = selectedSubId,
                            period = if (periodIdx == 1) "weekly" else "daily",
                            targetMin = mins,
                        )
                    )
                },
                enabled = selectedSubId.isNotBlank(),
                shape = RoundedCornerShape(14.dp),
            ) { Text("Save Goal") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AddExamDialog(
    subjects: List<Subject>,
    onDismiss: () -> Unit,
    onSave: (title: String, subjectId: String, date: String) -> Unit,
) {
    val colors = LocalStudyColors.current
    val defaultDate = remember {
        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 14) }
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
    }
    var title by remember { mutableStateOf("") }
    var selectedSubId by remember { mutableStateOf(subjects.firstOrNull()?.id.orEmpty()) }
    var date by remember { mutableStateOf(defaultDate) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = { Text("Add Exam Countdown", color = colors.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(120) },
                    label = { Text("Exam title (e.g. Thermo Final)") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = date,
                    onValueChange = { date = it.take(16) },
                    label = { Text("Date (YYYY-MM-DD)") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (subjects.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(subjects, key = { it.id }) { sub ->
                            val selected = sub.id == selectedSubId
                            val c = parseHexColor(sub.colorHex)
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (selected) c.subjectTint() else colors.surface2)
                                    .border(1.dp, if (selected) c else colors.border, RoundedCornerShape(14.dp))
                                    .clickable { selectedSubId = sub.id }
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Text("${sub.emoji} ${sub.name}", color = colors.text, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(title.trim(), selectedSubId, date.trim()) },
                enabled = title.trim().isNotEmpty() && date.trim().length >= 8,
                shape = RoundedCornerShape(14.dp),
            ) { Text("Add Exam") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
