package com.deeproot.app.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deeproot.app.core.model.Lecture
import com.deeproot.app.core.time.TimeSource
import com.deeproot.app.data.local.ProgressCounts
import com.deeproot.app.data.repository.LectureRepository
import com.deeproot.app.data.seed.PlanInfo
import com.deeproot.app.data.seed.PlanSeeder
import com.deeproot.app.data.seed.SeedState
import com.deeproot.app.data.sync.SyncManager
import com.deeproot.app.data.sync.SyncStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class BacklogGroup(val date: LocalDate, val lectures: List<Lecture>)

data class TodayUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val today: LocalDate,
    val selectedDate: LocalDate,
    val lectures: List<Lecture> = emptyList(),
    /** Backlog grouped by day, excluding the day being viewed (its lectures are already listed). */
    val backlogGroups: List<BacklogGroup> = emptyList(),
    val backlogCount: Int = 0,
    val todayPending: Int = 0,
    val progress: ProgressCounts = ProgressCounts(0, 0),
    val plan: PlanInfo? = null,
    val selection: Set<String> = emptySet(),
    val showDeleteConfirm: Boolean = false,
    val sync: SyncStatus = SyncStatus(),
) {
    val isToday: Boolean get() = selectedDate == today
    val selectionMode: Boolean get() = selection.isNotEmpty()
    val dayPending: Int get() = lectures.count { !it.completed }
    val isRestDay: Boolean get() = lectures.isEmpty() && plan?.noLectureDates?.contains(selectedDate) == true
    val isBeforePlan: Boolean get() = plan?.firstDate?.let { today.isBefore(it) } == true
    val visibleIds: List<String> get() = lectures.map { it.id } + backlogGroups.flatMap { g -> g.lectures.map { it.id } }
    val progressFraction: Float get() = if (progress.total == 0) 0f else progress.completed.toFloat() / progress.total
}

/** One-shot UI events. */
sealed interface TodayEvent {
    data class ShowUndo(val message: String, val undo: UndoAction) : TodayEvent
    data class ShowMessage(val message: String) : TodayEvent
}

/** Everything needed to revert a bulk action. */
data class UndoAction(val previous: List<Lecture>)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TodayViewModel @Inject constructor(
    private val repository: LectureRepository,
    private val seeder: PlanSeeder,
    private val syncManager: SyncManager,
    private val time: TimeSource,
) : ViewModel() {

    private val today = MutableStateFlow(time.today())
    private val selectedDate = MutableStateFlow(time.today())
    private val selection = MutableStateFlow<Set<String>>(emptySet())
    private val showDeleteConfirm = MutableStateFlow(false)
    private var initialDateChosen = false

    private val events = Channel<TodayEvent>(Channel.BUFFERED)
    val eventFlow: Flow<TodayEvent> = events.receiveAsFlow()

    /** Emits the date together with its lectures so a day switch never shows the previous day's list. */
    private val dayLectures = selectedDate.flatMapLatest { date -> repository.observeDay(date).map { date to it } }
    private val todayLectures = today.flatMapLatest { repository.observeDay(it) }
    private val backlog = today.flatMapLatest { repository.observeBacklog(it) }

    private data class Data(
        val today: LocalDate,
        val selected: LocalDate,
        val lectures: List<Lecture>,
        val todayLectures: List<Lecture>,
        val backlog: List<Lecture>,
        val progress: ProgressCounts,
    )

    private data class Meta(
        val seed: SeedState,
        val selection: Set<String>,
        val confirm: Boolean,
        val sync: SyncStatus,
    )

    private val data = combine(today, dayLectures, todayLectures, backlog, repository.observeProgress()) { t, day, tl, b, p ->
        Data(today = t, selected = day.first, lectures = day.second, todayLectures = tl, backlog = b, progress = p)
    }
    private val meta = combine(seeder.state, selection, showDeleteConfirm, syncManager.status) { seed, sel, confirm, sync ->
        Meta(seed, sel, confirm, sync)
    }

    val uiState: StateFlow<TodayUiState> = combine(data, meta) { d, m ->
        val plan = (m.seed as? SeedState.Done)?.info
        TodayUiState(
            loading = m.seed is SeedState.Idle || m.seed is SeedState.Seeding,
            error = (m.seed as? SeedState.Failed)?.message,
            today = d.today,
            selectedDate = d.selected,
            lectures = d.lectures,
            backlogGroups = d.backlog
                .filter { it.date != d.selected }
                .groupBy { it.date }
                .map { (date, items) -> BacklogGroup(date, items) },
            backlogCount = d.backlog.size,
            todayPending = d.todayLectures.count { !it.completed },
            progress = d.progress,
            plan = plan,
            selection = m.selection,
            showDeleteConfirm = m.confirm,
            sync = m.sync,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayUiState(today = time.today(), selectedDate = time.today()))

    init {
        viewModelScope.launch {
            // Like the prototype: before the plan starts, open on its first day.
            val state = seeder.seedIfNeeded()
            if (!initialDateChosen && state is SeedState.Done) {
                initialDateChosen = true
                val first = state.info.firstDate
                if (first != null && time.today().isBefore(first)) selectedDate.value = first
            }
        }
    }

    /** Called on every ON_RESUME so the screen rolls over at midnight. */
    fun onResume() {
        val now = time.today()
        if (now != today.value) {
            val wasOnToday = selectedDate.value == today.value
            today.value = now
            if (wasOnToday) selectedDate.value = now
        }
    }

    fun previousDay() {
        clearSelection()
        selectedDate.update { it.minusDays(1) }
    }

    fun nextDay() {
        clearSelection()
        selectedDate.update { it.plusDays(1) }
    }

    fun goToToday() {
        clearSelection()
        selectedDate.value = today.value
    }

    fun retrySeed() {
        viewModelScope.launch { seeder.seedIfNeeded() }
    }

    fun toggleCompleted(lecture: Lecture) {
        viewModelScope.launch { repository.setCompleted(listOf(lecture.id), !lecture.completed) }
    }

    // ---- multi-select ----

    fun onLongPress(lecture: Lecture) {
        selection.update { it + lecture.id }
    }

    fun toggleSelection(lecture: Lecture) {
        selection.update { if (lecture.id in it) it - lecture.id else it + lecture.id }
    }

    fun selectAll() {
        selection.value = uiState.value.visibleIds.toSet()
    }

    fun clearSelection() {
        selection.value = emptySet()
    }

    fun markSelectedCompleted() {
        val ids = selection.value
        if (ids.isEmpty()) return
        clearSelection()
        viewModelScope.launch {
            val previous = repository.setCompleted(ids, completed = true)
            if (previous.isEmpty()) {
                events.send(TodayEvent.ShowMessage("Already completed"))
            } else {
                events.send(TodayEvent.ShowUndo("Marked ${previous.size} completed", UndoAction(previous)))
            }
        }
    }

    fun requestDeleteSelected() {
        if (selection.value.isNotEmpty()) showDeleteConfirm.value = true
    }

    fun dismissDelete() {
        showDeleteConfirm.value = false
    }

    fun confirmDelete() {
        val ids = selection.value
        showDeleteConfirm.value = false
        clearSelection()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val snapshots = repository.delete(ids)
            if (snapshots.isNotEmpty()) {
                val noun = if (snapshots.size == 1) "lecture" else "lectures"
                events.send(TodayEvent.ShowUndo("Deleted ${snapshots.size} $noun", UndoAction(snapshots)))
            }
        }
    }

    fun undo(action: UndoAction) {
        viewModelScope.launch { repository.restore(action.previous) }
    }
}
