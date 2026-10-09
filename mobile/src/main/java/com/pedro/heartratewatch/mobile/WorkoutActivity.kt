package com.pedro.heartratewatch.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pedro.heartratewatch.mobile.theme.PipBoyTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * The gym log: an exercise database, reusable templates (e.g. "Leg day"), a notepad-style screen
 * for logging sets/intensity/notes during a workout, and a history of past ones. Phone-only.
 */
class WorkoutActivity : ComponentActivity() {

    private val repository by lazy { WorkoutRepository(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PipBoyTheme { WorkoutApp(repository) }
        }
    }
}

private sealed interface Screen {
    data object Menu : Screen
    data object ChooseTemplate : Screen
    data object Active : Screen
    data object Templates : Screen
    data class TemplateEdit(val id: String?) : Screen
    data object Exercises : Screen
    data object History : Screen
    data class HistoryDetail(val id: String) : Screen
}

private fun parentOf(screen: Screen): Screen = when (screen) {
    is Screen.TemplateEdit -> Screen.Templates
    is Screen.HistoryDetail -> Screen.History
    else -> Screen.Menu
}

private val IntensityRed = Color(0xFFE53935)
private val IntensityYellow = Color(0xFFFBC02D)
private val IntensityGreen = Color(0xFF43A047)

private fun Intensity.color(): Color = when (this) {
    Intensity.HARD -> IntensityRed
    Intensity.OK -> IntensityYellow
    Intensity.EASY -> IntensityGreen
}

@Composable
private fun WorkoutApp(repository: WorkoutRepository) {
    val scope = rememberCoroutineScope()
    val exercises by repository.exercisesFlow.collectAsStateWithLifecycle(initialValue = DEFAULT_EXERCISES)
    val templates by repository.templatesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val history by repository.historyFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    // The in-progress workout is edited in memory (so typing is instant) and saved to storage a
    // moment after each change, so closing the app mid-workout never loses it.
    var active by remember { mutableStateOf<WorkoutLog?>(null) }
    var activeLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        active = repository.activeFlow.first()
        activeLoaded = true
    }
    LaunchedEffect(active, activeLoaded) {
        if (!activeLoaded) return@LaunchedEffect
        delay(400)
        repository.saveActive(active)
    }

    var screen by remember { mutableStateOf<Screen>(Screen.Menu) }
    BackHandler(enabled = screen != Screen.Menu) { screen = parentOf(screen) }

    Scaffold { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp)) {
            when (val current = screen) {
                Screen.Menu -> MenuScreen(
                    active = active,
                    onResume = { screen = Screen.Active },
                    onDiscard = { active = null },
                    onStart = { screen = Screen.ChooseTemplate },
                    onTemplates = { screen = Screen.Templates },
                    onExercises = { screen = Screen.Exercises },
                    onHistory = { screen = Screen.History }
                )

                Screen.ChooseTemplate -> ChooseTemplateScreen(
                    templates = templates,
                    onPick = { template ->
                        val chosen = template?.exerciseIds.orEmpty().mapNotNull { id -> exercises.firstOrNull { it.id == id } }
                        active = WorkoutLog(
                            id = WorkoutRepository.newId(),
                            startedAtMillis = System.currentTimeMillis(),
                            templateName = template?.name ?: "Workout",
                            exercises = chosen.map { ExerciseLog(WorkoutRepository.newId(), it.id, it.name) }
                        )
                        screen = Screen.Active
                    }
                )

                Screen.Active -> {
                    val workout = active
                    if (workout == null) {
                        screen = Screen.Menu
                    } else {
                        ActiveWorkoutScreen(
                            workout = workout,
                            history = history,
                            exercises = exercises,
                            onChange = { active = it },
                            onCreateExercise = { name -> repository.addExercise(name) },
                            onFinish = {
                                scope.launch {
                                    repository.finish(workout)
                                    active = null
                                    screen = Screen.Menu
                                }
                            },
                            onDiscard = {
                                active = null
                                screen = Screen.Menu
                            }
                        )
                    }
                }

                Screen.Templates -> TemplatesScreen(
                    templates = templates,
                    onNew = { screen = Screen.TemplateEdit(null) },
                    onEdit = { screen = Screen.TemplateEdit(it.id) },
                    onDelete = { scope.launch { repository.deleteTemplate(it.id) } }
                )

                is Screen.TemplateEdit -> TemplateEditScreen(
                    existing = templates.firstOrNull { it.id == current.id },
                    exercises = exercises,
                    onCreateExercise = { name -> repository.addExercise(name) },
                    onSave = { template ->
                        scope.launch {
                            repository.saveTemplate(template)
                            screen = Screen.Templates
                        }
                    }
                )

                Screen.Exercises -> ExercisesScreen(
                    exercises = exercises,
                    onAdd = { name -> scope.launch { repository.addExercise(name) } },
                    onDelete = { scope.launch { repository.deleteExercise(it.id) } }
                )

                Screen.History -> HistoryScreen(history = history, onOpen = { screen = Screen.HistoryDetail(it.id) })

                is Screen.HistoryDetail -> {
                    val workout = history.firstOrNull { it.id == current.id }
                    if (workout == null) {
                        screen = Screen.History
                    } else {
                        HistoryDetailScreen(
                            workout = workout,
                            onDelete = {
                                scope.launch {
                                    repository.deleteWorkout(workout.id)
                                    screen = Screen.History
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- menu

@Composable
private fun ScreenTitle(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(vertical = 16.dp))
}

@Composable
private fun MenuScreen(
    active: WorkoutLog?,
    onResume: () -> Unit,
    onDiscard: () -> Unit,
    onStart: () -> Unit,
    onTemplates: () -> Unit,
    onExercises: () -> Unit,
    onHistory: () -> Unit
) {
    var confirmDiscard by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenTitle("Workout")
        if (active != null) {
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("In progress: ${active.templateName}", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Started ${formatDateTime(active.startedAtMillis)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onResume) { Text("Resume") }
                        OutlinedButton(onClick = { confirmDiscard = true }) { Text("Discard") }
                    }
                }
            }
        }
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("Start workout") }
        OutlinedButton(onClick = onTemplates, modifier = Modifier.fillMaxWidth()) { Text("Templates") }
        OutlinedButton(onClick = onExercises, modifier = Modifier.fillMaxWidth()) { Text("Exercises") }
        OutlinedButton(onClick = onHistory, modifier = Modifier.fillMaxWidth()) { Text("History") }
    }
    if (confirmDiscard) {
        ConfirmDialog(
            text = "Discard the workout in progress? Nothing from it will be saved.",
            confirmLabel = "Discard",
            onConfirm = { confirmDiscard = false; onDiscard() },
            onDismiss = { confirmDiscard = false }
        )
    }
}

@Composable
private fun ChooseTemplateScreen(templates: List<WorkoutTemplate>, onPick: (WorkoutTemplate?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScreenTitle("Start workout")
        Text("Pick a template, or start empty and add exercises as you go.", style = MaterialTheme.typography.bodySmall)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(templates, key = { it.id }) { template ->
                Button(onClick = { onPick(template) }, modifier = Modifier.fillMaxWidth()) {
                    Text("${template.name} (${template.exerciseIds.size})")
                }
            }
            item {
                OutlinedButton(onClick = { onPick(null) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Empty workout")
                }
            }
        }
    }
}

// ---------------------------------------------------------------- active workout

@Composable
private fun ActiveWorkoutScreen(
    workout: WorkoutLog,
    history: List<WorkoutLog>,
    exercises: List<Exercise>,
    onChange: (WorkoutLog) -> Unit,
    onCreateExercise: suspend (String) -> Exercise,
    onFinish: () -> Unit,
    onDiscard: () -> Unit
) {
    var showPicker by remember { mutableStateOf(false) }
    var confirmFinish by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }

    fun update(id: String, transform: (ExerciseLog) -> ExerciseLog) {
        onChange(workout.copy(exercises = workout.exercises.map { if (it.id == id) transform(it) else it }))
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column {
                ScreenTitle(workout.templateName)
                Text("Started ${formatDateTime(workout.startedAtMillis)}", style = MaterialTheme.typography.bodySmall)
            }
        }
        items(workout.exercises, key = { it.id }) { log ->
            ExerciseCard(
                log = log,
                lastTime = lastTimeFor(history, log.exerciseId),
                onChange = { changed -> update(log.id) { changed } },
                onRemove = { onChange(workout.copy(exercises = workout.exercises.filterNot { it.id == log.id })) }
            )
        }
        item {
            OutlinedButton(onClick = { showPicker = true }, modifier = Modifier.fillMaxWidth()) {
                Text("+ Add exercise")
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 24.dp)) {
                Button(onClick = { confirmFinish = true }, modifier = Modifier.weight(1f)) { Text("Finish workout") }
                OutlinedButton(onClick = { confirmDiscard = true }) { Text("Discard") }
            }
        }
    }

    if (showPicker) {
        ExercisePickerDialog(
            exercises = exercises,
            onCreate = onCreateExercise,
            onPick = { picked ->
                onChange(
                    workout.copy(
                        exercises = workout.exercises + ExerciseLog(WorkoutRepository.newId(), picked.id, picked.name)
                    )
                )
                showPicker = false
            },
            onDismiss = { showPicker = false }
        )
    }
    if (confirmFinish) {
        ConfirmDialog(
            text = "Finish and save this workout?",
            confirmLabel = "Finish",
            onConfirm = { confirmFinish = false; onFinish() },
            onDismiss = { confirmFinish = false }
        )
    }
    if (confirmDiscard) {
        ConfirmDialog(
            text = "Discard this workout? Nothing from it will be saved.",
            confirmLabel = "Discard",
            onConfirm = { confirmDiscard = false; onDiscard() },
            onDismiss = { confirmDiscard = false }
        )
    }
}

@Composable
private fun ExerciseCard(
    log: ExerciseLog,
    lastTime: Pair<WorkoutLog, ExerciseLog>?,
    onChange: (ExerciseLog) -> Unit,
    onRemove: () -> Unit
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(log.exerciseName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onRemove) { Text("Remove") }
            }

            if (lastTime != null) {
                val (workout, previous) = lastTime
                Column {
                    Text(
                        "Last time (${formatDate(workout.startedAtMillis)}): ${summarizeSets(previous.sets)}" +
                            (previous.intensity?.let { " - ${it.label}" } ?: ""),
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (previous.note.isNotBlank()) {
                        Text("Note: ${previous.note}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            log.sets.forEachIndexed { index, set ->
                SetRow(
                    index = index,
                    set = set,
                    onChange = { changed ->
                        onChange(log.copy(sets = log.sets.map { if (it.id == set.id) changed else it }))
                    },
                    onRemove = { onChange(log.copy(sets = log.sets.filterNot { it.id == set.id })) }
                )
            }

            OutlinedButton(
                onClick = {
                    // Pre-fills the next set from the one just above it (or, for the first set,
                    // the same set number last time) -- most sets repeat the previous weight/reps,
                    // so logging is usually one tap.
                    val basis = log.sets.lastOrNull() ?: lastTime?.second?.sets?.getOrNull(log.sets.size)
                    onChange(
                        log.copy(
                            sets = log.sets + LoggedSet(WorkoutRepository.newId(), basis?.weight, basis?.reps)
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("+ Add set") }

            IntensityPicker(selected = log.intensity, onSelect = { onChange(log.copy(intensity = it)) })

            OutlinedTextField(
                value = log.note,
                onValueChange = { onChange(log.copy(note = it)) },
                label = { Text("Notes") },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun SetRow(index: Int, set: LoggedSet, onChange: (LoggedSet) -> Unit, onRemove: () -> Unit) {
    // Local text (rather than deriving from the numbers) so half-typed values like "132." survive.
    var weightText by remember(set.id) { mutableStateOf(set.weight?.let(::formatWeight) ?: "") }
    var repsText by remember(set.id) { mutableStateOf(set.reps?.toString() ?: "") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${index + 1}", modifier = Modifier.width(20.dp))
        OutlinedTextField(
            value = weightText,
            onValueChange = { typed ->
                val filtered = filterDecimal(typed)
                weightText = filtered
                onChange(set.copy(weight = filtered.toDoubleOrNull()))
            },
            label = { Text("Weight") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f)
        )
        Text("x")
        OutlinedTextField(
            value = repsText,
            onValueChange = { typed ->
                val filtered = typed.filter { it.isDigit() }
                repsText = filtered
                onChange(set.copy(reps = filtered.toIntOrNull()))
            },
            label = { Text("Reps") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onRemove) { Text("X") }
    }
}

@Composable
private fun IntensityPicker(selected: Intensity?, onSelect: (Intensity?) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("How hard?", style = MaterialTheme.typography.bodySmall)
        Intensity.entries.forEach { intensity ->
            val isSelected = selected == intensity
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { onSelect(if (isSelected) null else intensity) }
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .alpha(if (selected == null || isSelected) 1f else 0.3f)
                        .background(intensity.color(), CircleShape)
                        .border(
                            if (isSelected) 3.dp else 1.dp,
                            if (isSelected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                            CircleShape
                        )
                )
                Text(intensity.label, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

// ---------------------------------------------------------------- templates / exercises

@Composable
private fun TemplatesScreen(
    templates: List<WorkoutTemplate>,
    onNew: () -> Unit,
    onEdit: (WorkoutTemplate) -> Unit,
    onDelete: (WorkoutTemplate) -> Unit
) {
    var pendingDelete by remember { mutableStateOf<WorkoutTemplate?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScreenTitle("Templates")
        Button(onClick = onNew, modifier = Modifier.fillMaxWidth()) { Text("New template") }
        if (templates.isEmpty()) {
            Text("No templates yet. Make one per workout day (leg day, shoulders...) to start workouts in one tap.")
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(templates, key = { it.id }) { template ->
                OutlinedCard(modifier = Modifier.fillMaxWidth().clickable { onEdit(template) }) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(template.name, style = MaterialTheme.typography.titleMedium)
                            Text("${template.exerciseIds.size} exercises", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { pendingDelete = template }) { Text("Delete") }
                    }
                }
            }
        }
    }
    pendingDelete?.let { template ->
        ConfirmDialog(
            text = "Delete the template \"${template.name}\"? Past workouts aren't affected.",
            confirmLabel = "Delete",
            onConfirm = { onDelete(template); pendingDelete = null },
            onDismiss = { pendingDelete = null }
        )
    }
}

@Composable
private fun TemplateEditScreen(
    existing: WorkoutTemplate?,
    exercises: List<Exercise>,
    onCreateExercise: suspend (String) -> Exercise,
    onSave: (WorkoutTemplate) -> Unit
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var ids by remember { mutableStateOf(existing?.exerciseIds.orEmpty()) }
    var showPicker by remember { mutableStateOf(false) }
    val byId = exercises.associateBy { it.id }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
        item { ScreenTitle(if (existing == null) "New template" else "Edit template") }
        item {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Template name (e.g. Leg day)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        itemsIndexed(ids, key = { _, id -> id }) { index, id ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(byId[id]?.name ?: "(deleted exercise)", modifier = Modifier.weight(1f))
                TextButton(
                    enabled = index > 0,
                    onClick = { ids = ids.toMutableList().also { it.add(index - 1, it.removeAt(index)) } }
                ) { Text("Up") }
                TextButton(
                    enabled = index < ids.lastIndex,
                    onClick = { ids = ids.toMutableList().also { it.add(index + 1, it.removeAt(index)) } }
                ) { Text("Down") }
                TextButton(onClick = { ids = ids.filterNot { it == id } }) { Text("X") }
            }
        }
        item {
            OutlinedButton(onClick = { showPicker = true }, modifier = Modifier.fillMaxWidth()) {
                Text("+ Add exercise")
            }
        }
        item {
            Button(
                enabled = name.isNotBlank() && ids.isNotEmpty(),
                onClick = {
                    onSave(WorkoutTemplate(existing?.id ?: WorkoutRepository.newId(), name.trim(), ids))
                },
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
            ) { Text("Save template") }
        }
    }

    if (showPicker) {
        ExercisePickerDialog(
            exercises = exercises,
            onCreate = onCreateExercise,
            onPick = { picked ->
                if (picked.id !in ids) ids = ids + picked.id
                showPicker = false
            },
            onDismiss = { showPicker = false }
        )
    }
}

@Composable
private fun ExercisesScreen(
    exercises: List<Exercise>,
    onAdd: (String) -> Unit,
    onDelete: (Exercise) -> Unit
) {
    var newName by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<Exercise?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScreenTitle("Exercises")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text("New exercise") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Button(
                enabled = newName.isNotBlank(),
                onClick = { onAdd(newName); newName = "" }
            ) { Text("Add") }
        }
        LazyColumn {
            items(exercises.sortedBy { it.name.lowercase() }, key = { it.id }) { exercise ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(exercise.name, modifier = Modifier.weight(1f))
                    if (!exercise.builtIn) {
                        TextButton(onClick = { pendingDelete = exercise }) { Text("Delete") }
                    }
                }
                HorizontalDivider()
            }
        }
    }
    pendingDelete?.let { exercise ->
        ConfirmDialog(
            text = "Delete \"${exercise.name}\"? Past workouts keep their record of it.",
            confirmLabel = "Delete",
            onConfirm = { onDelete(exercise); pendingDelete = null },
            onDismiss = { pendingDelete = null }
        )
    }
}

/** Search the database; if what's typed doesn't exist yet, it can be created right there. */
@Composable
private fun ExercisePickerDialog(
    exercises: List<Exercise>,
    onCreate: suspend (String) -> Exercise,
    onPick: (Exercise) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    val matches = exercises.filter { it.name.contains(query.trim(), ignoreCase = true) }
        .sortedBy { it.name.lowercase() }
    val canCreate = query.isNotBlank() && exercises.none { it.name.equals(query.trim(), ignoreCase = true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add exercise") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search or type a new one") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    if (canCreate) {
                        item {
                            TextButton(
                                onClick = { scope.launch { onPick(onCreate(query)) } },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Create \"${query.trim()}\"") }
                        }
                    }
                    items(matches, key = { it.id }) { exercise ->
                        Text(
                            exercise.name,
                            modifier = Modifier.fillMaxWidth().clickable { onPick(exercise) }.padding(vertical = 12.dp)
                        )
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ---------------------------------------------------------------- history

@Composable
private fun HistoryScreen(history: List<WorkoutLog>, onOpen: (WorkoutLog) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScreenTitle("History")
        if (history.isEmpty()) Text("No finished workouts yet.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(history, key = { it.id }) { workout ->
                OutlinedCard(modifier = Modifier.fillMaxWidth().clickable { onOpen(workout) }) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(workout.templateName, style = MaterialTheme.typography.titleMedium)
                        Text(formatDateTime(workout.startedAtMillis), style = MaterialTheme.typography.bodySmall)
                        Text(
                            "${workout.exercises.size} exercises, ${workout.exercises.sumOf { it.sets.size }} sets",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryDetailScreen(workout: WorkoutLog, onDelete: () -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
        item {
            Column {
                ScreenTitle(workout.templateName)
                Text(formatDateTime(workout.startedAtMillis), style = MaterialTheme.typography.bodySmall)
            }
        }
        items(workout.exercises, key = { it.id }) { log ->
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(log.exerciseName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        log.intensity?.let { intensity ->
                            Box(modifier = Modifier.size(14.dp).background(intensity.color(), CircleShape))
                            Text(intensity.label, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    log.sets.forEachIndexed { index, set ->
                        Text("${index + 1}.  ${formatSet(set)}")
                    }
                    if (log.note.isNotBlank()) Text("Note: ${log.note}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            OutlinedButton(
                onClick = { confirmDelete = true },
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
            ) { Text("Delete workout", color = MaterialTheme.colorScheme.error) }
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            text = "Delete this workout from your history?",
            confirmLabel = "Delete",
            onConfirm = { confirmDelete = false; onDelete() },
            onDismiss = { confirmDelete = false }
        )
    }
}

// ---------------------------------------------------------------- helpers

@Composable
private fun ConfirmDialog(text: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Most recent finished workout that has real data (sets or a note) for this exercise. */
private fun lastTimeFor(history: List<WorkoutLog>, exerciseId: String): Pair<WorkoutLog, ExerciseLog>? =
    history.firstNotNullOfOrNull { workout ->
        workout.exercises
            .firstOrNull { log ->
                log.exerciseId == exerciseId &&
                    (log.sets.any { it.weight != null || it.reps != null } || log.note.isNotBlank())
            }
            ?.let { workout to it }
    }

private fun summarizeSets(sets: List<LoggedSet>): String =
    sets.filter { it.weight != null || it.reps != null }.joinToString(", ") { formatSet(it) }
        .ifEmpty { "no sets logged" }

private fun formatSet(set: LoggedSet): String =
    "${set.weight?.let(::formatWeight) ?: "?"} x ${set.reps ?: "?"}"

private fun formatWeight(weight: Double): String =
    if (weight % 1.0 == 0.0) weight.toLong().toString() else weight.toString()

/** Digits with at most one decimal point. */
private fun filterDecimal(text: String): String {
    var seenDot = false
    return buildString {
        for (c in text) {
            if (c.isDigit()) append(c)
            else if (c == '.' && !seenDot) {
                append(c)
                seenDot = true
            }
        }
    }
}

private fun formatDate(millis: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))

private fun formatDateTime(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))
