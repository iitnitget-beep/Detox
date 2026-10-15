package com.deeproot.app.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deeproot.app.core.stats.StatsCalculator
import com.deeproot.app.core.stats.StatsSnapshot
import com.deeproot.app.core.time.TimeSource
import com.deeproot.app.data.repository.LectureRepository
import com.deeproot.app.data.repository.SessionRepository
import com.deeproot.app.data.auth.AuthRepository
import com.deeproot.app.data.remote.CloudSummary
import com.deeproot.app.data.remote.FirestoreDataSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import javax.inject.Inject

data class StatsUiState(
    val loading: Boolean = true,
    val stats: StatsSnapshot? = null,
    val today: LocalDate = LocalDate.now(),
    /** Server-side aggregate from the onLectureCompleted function (null offline / not deployed). */
    val cloud: CloudSummary? = null,
)

/** Stats are computed locally from Room (lectures + sessions), so they work offline and include every device. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StatsViewModel @Inject constructor(
    lectures: LectureRepository,
    sessions: SessionRepository,
    auth: AuthRepository,
    remote: FirestoreDataSource,
    private val time: TimeSource,
) : ViewModel() {

    private val today = MutableStateFlow(time.today())

    private val cloud = auth.user.map { it?.uid }.distinctUntilChanged().flatMapLatest { uid ->
        if (uid == null) flowOf(null) else remote.statsSummaryFlow(uid).catch { emit(null) }
    }

    val uiState: StateFlow<StatsUiState> = combine(lectures.observeAll(), sessions.sessions, today, cloud) { all, sessionList, day, summary ->
        StatsUiState(
            loading = false,
            stats = StatsCalculator.compute(all, sessionList, day, time.zone()),
            today = day,
            cloud = summary,
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState(today = time.today()))

    fun onResume() {
        today.value = time.today()
    }
}
