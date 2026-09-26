package com.example.smartbartender

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.smartbartender.data.hardware.BartenderMachine
import com.example.smartbartender.data.local.CustomDrinkStore
import com.example.smartbartender.data.local.FavouritesStore
import com.example.smartbartender.data.local.PourHistoryStore
import com.example.smartbartender.data.remote.CocktailApi
import com.example.smartbartender.data.remote.dto.CocktailResponse
import com.example.smartbartender.data.remote.dto.DrinkSummaryResponse
import com.example.smartbartender.data.repository.CocktailRepository
import com.example.smartbartender.domain.model.CalibrationRun
import com.example.smartbartender.domain.model.CleaningRun
import com.example.smartbartender.domain.model.CocktailSummary
import com.example.smartbartender.domain.model.ConnectionState
import com.example.smartbartender.domain.model.CustomDrink
import com.example.smartbartender.domain.model.LedMode
import com.example.smartbartender.domain.model.LedShow
import com.example.smartbartender.domain.model.MachineBackend
import com.example.smartbartender.domain.model.MachineRunState
import com.example.smartbartender.domain.model.MachineSnapshot
import com.example.smartbartender.domain.model.PourJob
import com.example.smartbartender.domain.model.PourOutcome
import com.example.smartbartender.domain.model.PourRecord
import com.example.smartbartender.domain.model.PourRequest
import com.example.smartbartender.domain.model.SensorReading
import com.example.smartbartender.ui.screens.calibration.CalibrationViewModel
import com.example.smartbartender.ui.screens.library.LibraryEvent
import com.example.smartbartender.ui.screens.library.LibraryViewModel
import com.example.smartbartender.ui.screens.stats.StatsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Screens that should behave differently while nobody is looking at them, and the Library's
 * Surprise me, whose failure belongs to the button rather than to the list.
 *
 * "Looking" means collecting the ViewModel's `uiState`, which `collectAsStateWithLifecycle`
 * does only while the screen is at least started.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScreenVisibilityTest {

    @Before
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    /**
     * Ends the ViewModel's scope once [block] is done. `Dispatchers.Main` shares the test's
     * virtual clock, and `runTest` only returns once that clock has nothing left to run — a
     * polling loop left behind in `viewModelScope` would keep it running forever.
     */
    private inline fun <VM : ViewModel> VM.closedAfter(block: (VM) -> Unit) {
        try {
            block(this)
        } finally {
            viewModelScope.cancel()
        }
    }

    // ------------------------------------------------------------------ calibration

    /** A connected, idle machine that counts sensor reads. */
    private class SensorMachine : BartenderMachine {
        override val connection = MutableStateFlow<ConnectionState>(ConnectionState.Connected(snapshot()))
        override val currentJob = MutableStateFlow<PourJob?>(null)
        var sensorReads = 0

        override suspend fun readSensor(): Result<SensorReading> {
            sensorReads++
            return Result.success(SensorReading(distanceCm = 12.0, glassPresent = false, referenceCm = 12.0))
        }

        override suspend fun testConnection(host: String, port: Int) = Result.success(snapshot())
        override suspend fun pushSlots(slots: List<String?>) = Result.success(Unit)
        override suspend fun startPour(request: PourRequest): Result<PourJob> = error("not used")
        override suspend fun abort(jobId: String) = Result.success(Unit)
        override suspend fun fetchJob(jobId: String): Result<PourJob> = error("not used")
        override suspend fun setLed(enabled: Boolean) = Result.success(Unit)
        override suspend fun jog(pump: Int, seconds: Double) = Result.success(Unit)
        override suspend fun measureReference(): Result<SensorReading> = error("not used")
        override suspend fun startCalibration(pumps: List<Int>?, seconds: Double?): Result<CalibrationRun> =
            error("not used")
        override suspend fun abortCalibration() = Result.success(Unit)
        override suspend fun startCleaning(pumps: List<Int>?, seconds: Double?, rounds: Int?): Result<CleaningRun> =
            error("not used")
        override suspend fun abortCleaning() = Result.success(Unit)
    }

    @Test
    fun `the sensor is polled only while the calibration screen is on show`() = runTest {
        val machine = SensorMachine()
        CalibrationViewModel(machine).closedAfter { viewModel ->
            advanceTimeBy(5_000)
            assertEquals("nobody is looking, so the Pi is left alone", 0, machine.sensorReads)

            val screen = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceTimeBy(2_500)
            assertTrue(machine.sensorReads >= 2)

            screen.cancel()
            runCurrent()
            val readsWhenHidden = machine.sensorReads
            advanceTimeBy(5_000)
            assertEquals(readsWhenHidden, machine.sensorReads)
        }
    }

    // ------------------------------------------------------------------ stats

    private class FakeHistory(records: List<PourRecord>) : PourHistoryStore {
        override val pourHistory = MutableStateFlow(records)
        override suspend fun recordPour(record: PourRecord) = Unit
        override suspend fun clearPourHistory() = Unit
    }

    @Test
    fun `this week is worked out again when the stats tab comes back`() = runTest {
        val poured = 1_700_000_000_000L
        var now = poured
        val history = FakeHistory(listOf(PourRecord("job", "11007", "Margarita", PourOutcome.FINISHED, poured, emptyMap())))
        StatsViewModel(history, clock = { now }).closedAfter { viewModel ->
            val firstVisit = backgroundScope.launch { viewModel.uiState.collect {} }
            runCurrent()
            assertEquals(1, viewModel.uiState.value.stats?.thisWeek)
            firstVisit.cancel()
            runCurrent()

            now = poured + 8 * DAY_MS
            backgroundScope.launch { viewModel.uiState.collect {} }
            runCurrent()

            assertEquals(0, viewModel.uiState.value.stats?.thisWeek)
            assertEquals(1, viewModel.uiState.value.stats?.cocktailsMade)
        }
    }

    // ------------------------------------------------------------------ surprise me

    /** A book with nothing in it, whose random endpoint is down. */
    private class NoRandomApi : CocktailApi {
        override suspend fun random(): CocktailResponse = throw IOException("random.php timed out")
        override suspend fun searchByName(name: String) = CocktailResponse(emptyList())
        override suspend fun searchByFirstLetter(letter: String) = CocktailResponse(emptyList())
        override suspend fun filterByIngredient(ingredient: String) = DrinkSummaryResponse(emptyList())
        override suspend fun lookupById(id: String) = CocktailResponse(emptyList())
    }

    private object NoFavourites : FavouritesStore, CustomDrinkStore {
        override val favourites = MutableStateFlow<List<CocktailSummary>>(emptyList())
        override val customDrinks = MutableStateFlow<List<CustomDrink>>(emptyList())
        override suspend fun setFavourite(cocktail: CocktailSummary, favourite: Boolean) = Unit
        override suspend fun saveCustomDrink(drink: CustomDrink) = Unit
        override suspend fun deleteCustomDrink(id: String) = Unit
    }

    @Test
    fun `a failed surprise leaves the list alone`() = runTest {
        LibraryViewModel(CocktailRepository(NoRandomApi()), NoFavourites, NoFavourites).closedAfter { viewModel ->
            runCurrent()
            assertNull(viewModel.uiState.value.loadError)

            val events = mutableListOf<LibraryEvent>()
            backgroundScope.launch { viewModel.events.collect { events += it } }

            viewModel.surpriseMe()
            runCurrent()

            assertNull(viewModel.uiState.value.loadError)
            assertEquals(listOf(LibraryEvent.SurpriseFailed), events)
        }
    }
}

private const val DAY_MS = 24 * 60 * 60 * 1000L

private fun snapshot() = MachineSnapshot(
    machineId = "bartender-01",
    name = "Smart Bartender",
    firmware = "0.1.0",
    backend = MachineBackend.SIMULATED,
    state = MachineRunState.IDLE,
    pumpCount = 4,
    maxPourMl = 250.0,
    slots = emptyList(),
    led = LedShow(enabled = true, mode = LedMode.SPECTRUM, cycleMillis = LedShow.CYCLE_MILLIS),
    currentJob = null,
    fault = null,
    sensorReferenceCm = 12.0,
)
