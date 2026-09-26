package com.example.smartbartender

import com.example.smartbartender.data.hardware.BartenderMachine
import com.example.smartbartender.data.local.ActiveJobStore
import com.example.smartbartender.data.local.CustomDrinkStore
import com.example.smartbartender.data.local.FavouritesStore
import com.example.smartbartender.data.local.RackStore
import com.example.smartbartender.data.remote.CocktailApi
import com.example.smartbartender.data.remote.dto.CocktailDto
import com.example.smartbartender.data.remote.dto.CocktailResponse
import com.example.smartbartender.data.remote.dto.DrinkSummaryResponse
import com.example.smartbartender.data.repository.CocktailRepository
import com.example.smartbartender.domain.model.CalibrationRun
import com.example.smartbartender.domain.model.CleaningRun
import com.example.smartbartender.domain.model.CocktailSummary
import com.example.smartbartender.domain.model.ConnectionState
import com.example.smartbartender.domain.model.CustomDrink
import com.example.smartbartender.domain.model.JobStatus
import com.example.smartbartender.domain.model.JobStep
import com.example.smartbartender.domain.model.LedMode
import com.example.smartbartender.domain.model.LedShow
import com.example.smartbartender.domain.model.MachineBackend
import com.example.smartbartender.domain.model.MachineError
import com.example.smartbartender.domain.model.MachineException
import com.example.smartbartender.domain.model.MachineRunState
import com.example.smartbartender.domain.model.MachineSnapshot
import com.example.smartbartender.domain.model.PourJob
import com.example.smartbartender.domain.model.PourRequest
import com.example.smartbartender.domain.model.SensorReading
import com.example.smartbartender.domain.model.StepKind
import com.example.smartbartender.ui.screens.detail.DetailViewModel
import com.example.smartbartender.ui.screens.detail.PourFailure
import com.example.smartbartender.ui.screens.detail.PourPhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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

/**
 * The detail screen's side of a pour: what it posts, what it shows, and what it remembers.
 * Runs the real [DetailViewModel] over hand-written fakes, with `Dispatchers.Main` swapped for
 * a test dispatcher so every step is advanced by hand with `runCurrent()`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DetailViewModelTest {

    @Before
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    // ------------------------------------------------------------------ fakes

    /** TheCocktailDB with one drink in it. */
    private class OneDrinkApi : CocktailApi {
        override suspend fun lookupById(id: String) = CocktailResponse(listOf(margarita).filter { it.id == id })
        override suspend fun searchByName(name: String) = CocktailResponse(emptyList())
        override suspend fun searchByFirstLetter(letter: String) = CocktailResponse(emptyList())
        override suspend fun filterByIngredient(ingredient: String) = DrinkSummaryResponse(emptyList())
        override suspend fun random() = CocktailResponse(emptyList())
    }

    /** Only the four stores the detail screen reads. */
    private class FakeStores(slots: List<String?>) : RackStore, ActiveJobStore, FavouritesStore, CustomDrinkStore {
        override val slots = MutableStateFlow(slots)
        override val loadedBottleIds = MutableStateFlow(slots.filterNotNull().toSet())
        override val activeJobId = MutableStateFlow<String?>(null)
        override val favourites = MutableStateFlow<List<CocktailSummary>>(emptyList())
        override val customDrinks = MutableStateFlow<List<CustomDrink>>(emptyList())

        override suspend fun setBottleLoaded(bottleId: String, loaded: Boolean) = false
        override suspend fun setLoadedBottles(bottleIds: Set<String>) = Unit
        override suspend fun setActiveJobId(jobId: String?) { activeJobId.value = jobId }
        override suspend fun setFavourite(cocktail: CocktailSummary, favourite: Boolean) = Unit
        override suspend fun saveCustomDrink(drink: CustomDrink) = Unit
        override suspend fun deleteCustomDrink(id: String) = Unit
    }

    /** A connected machine whose answers each test scripts. [currentJob] stands in for the socket. */
    private class ScriptedMachine : BartenderMachine {
        override val connection = MutableStateFlow<ConnectionState>(ConnectionState.Connected(snapshot()))
        override val currentJob = MutableStateFlow<PourJob?>(null)

        val requests = mutableListOf<PourRequest>()
        val aborted = mutableListOf<String>()
        var refusePour: MachineError? = null
        var refuseAbort: MachineError? = null

        override suspend fun startPour(request: PourRequest): Result<PourJob> {
            refusePour?.let { return Result.failure(MachineException(it)) }
            requests += request
            return Result.success(job(request.jobId, JobStatus.QUEUED).also { currentJob.value = it })
        }

        override suspend fun abort(jobId: String): Result<Unit> {
            refuseAbort?.let { return Result.failure(MachineException(it)) }
            aborted += jobId
            return Result.success(Unit)
        }

        override suspend fun testConnection(host: String, port: Int) = Result.success(snapshot())
        override suspend fun pushSlots(slots: List<String?>) = Result.success(Unit)
        override suspend fun fetchJob(jobId: String): Result<PourJob> = error("not used")
        override suspend fun setLed(enabled: Boolean) = Result.success(Unit)
        override suspend fun jog(pump: Int, seconds: Double) = Result.success(Unit)
        override suspend fun readSensor(): Result<SensorReading> = error("not used")
        override suspend fun measureReference(): Result<SensorReading> = error("not used")
        override suspend fun startCalibration(pumps: List<Int>?, seconds: Double?): Result<CalibrationRun> =
            error("not used")
        override suspend fun abortCalibration() = Result.success(Unit)
        override suspend fun startCleaning(pumps: List<Int>?, seconds: Double?, rounds: Int?): Result<CleaningRun> =
            error("not used")
        override suspend fun abortCleaning() = Result.success(Unit)
    }

    private val stores = FakeStores(listOf("tequila", "triple_sec", "lime_juice", null))
    private val machine = ScriptedMachine()

    /** Opens the Margarita's detail screen and lets it load. */
    private fun TestScope.openMargarita(): DetailViewModel {
        val viewModel = DetailViewModel(
            repository = CocktailRepository(OneDrinkApi()),
            cocktailId = margarita.id,
            rack = stores,
            activeJob = stores,
            favourites = stores,
            customDrinks = stores,
            machine = machine,
        )
        runCurrent()
        return viewModel
    }

    private val DetailViewModel.pour get() = uiState.value.pour

    // ------------------------------------------------------------------ loading

    @Test
    fun `the recipe loads with what the plan leaves to a person`() = runTest {
        val state = openMargarita().uiState.value

        assertEquals("Margarita", state.cocktail?.name)
        assertEquals(listOf("Salt"), state.manualSteps)
        assertTrue(state.machineOnline)
        assertEquals(PourPhase.Idle, state.pour)
    }

    // ------------------------------------------------------------------ pouring

    @Test
    fun `starting posts the plan once, under a job id the app remembers`() = runTest {
        val viewModel = openMargarita()

        viewModel.startPreparation()
        runCurrent()

        val request = machine.requests.single()
        assertEquals(listOf("tequila", "triple_sec", "lime_juice"), request.items.map { it.bottleId })
        assertEquals(request.jobId, stores.activeJobId.value)
        assertEquals(request.jobId, (viewModel.pour as PourPhase.Pouring).jobId)
    }

    @Test
    fun `the overlay mirrors what the machine pushes`() = runTest {
        val viewModel = openMargarita()
        viewModel.startPreparation()
        runCurrent()
        val jobId = machine.requests.single().jobId

        machine.currentJob.value = job(jobId, JobStatus.RUNNING, stepIndex = 1, progress = 0.5f)
        runCurrent()
        val pouring = viewModel.pour as PourPhase.Pouring
        assertEquals(1, pouring.currentStepIndex)
        assertEquals(0.5f, pouring.progress, 0.001f)

        machine.currentJob.value = job(jobId, JobStatus.FINISHED)
        runCurrent()
        assertEquals(PourPhase.Finished(jobId), viewModel.pour)
    }

    @Test
    fun `a second tap before the machine has answered does not start a second pour`() = runTest {
        val viewModel = openMargarita()

        viewModel.startPreparation()
        viewModel.startPreparation()
        runCurrent()

        val request = machine.requests.single()
        assertEquals(request.jobId, stores.activeJobId.value)
    }

    @Test
    fun `a refused pour is shown as not poured, and the job is forgotten`() = runTest {
        val refusal = MachineError.Refused("NOT_CALIBRATED", "Measure the empty tray first", 503)
        machine.refusePour = refusal
        val viewModel = openMargarita()

        viewModel.startPreparation()
        runCurrent()

        assertEquals(PourPhase.Failed(PourFailure.Refused(refusal)), viewModel.pour)
        assertNull(stores.activeJobId.value)
    }

    @Test
    fun `a recipe with nothing loaded fails without asking the machine`() = runTest {
        stores.slots.value = listOf(null, null, null, null)
        val viewModel = openMargarita()

        viewModel.startPreparation()
        runCurrent()

        assertEquals(PourPhase.Failed(PourFailure.NothingPourable), viewModel.pour)
        assertTrue(machine.requests.isEmpty())
    }

    @Test
    fun `taking the glass returns to idle and forgets the job`() = runTest {
        val viewModel = openMargarita()
        viewModel.startPreparation()
        runCurrent()
        machine.currentJob.value = job(machine.requests.single().jobId, JobStatus.FINISHED)
        runCurrent()

        viewModel.acknowledgePreparation()
        runCurrent()

        assertEquals(PourPhase.Idle, viewModel.pour)
        assertNull(stores.activeJobId.value)
    }

    // ------------------------------------------------------------------ stopping

    @Test
    fun `stopping asks the machine and shows stopping until it confirms`() = runTest {
        val viewModel = openMargarita()
        viewModel.startPreparation()
        runCurrent()
        val jobId = machine.requests.single().jobId
        machine.currentJob.value = job(jobId, JobStatus.RUNNING, stepIndex = 1)
        runCurrent()

        viewModel.cancelPreparation()
        runCurrent()
        assertEquals(listOf(jobId), machine.aborted)
        assertTrue((viewModel.pour as PourPhase.Pouring).aborting)

        machine.currentJob.value = job(jobId, JobStatus.ABORTED)
        runCurrent()
        assertEquals(PourPhase.Idle, viewModel.pour)
    }

    @Test
    fun `a stop the machine refused keeps showing the pour`() = runTest {
        val viewModel = openMargarita()
        viewModel.startPreparation()
        runCurrent()
        val jobId = machine.requests.single().jobId
        machine.currentJob.value = job(jobId, JobStatus.RUNNING, stepIndex = 1)
        runCurrent()
        machine.refuseAbort = MachineError.Unreachable

        viewModel.cancelPreparation()
        runCurrent()

        // The pumps may still be running: the overlay must stay, with its Stop button back.
        val pouring = viewModel.pour as PourPhase.Pouring
        assertEquals(jobId, pouring.jobId)
        assertEquals(false, pouring.aborting)
        assertEquals(MachineError.Unreachable, pouring.stopFailed)

        // A Stop that does get through clears the complaint.
        machine.refuseAbort = null
        viewModel.cancelPreparation()
        runCurrent()
        assertNull((viewModel.pour as PourPhase.Pouring).stopFailed)
    }

    // ------------------------------------------------------------------ re-attaching

    @Test
    fun `a pour the machine reports only after the screen opened is still picked up`() = runTest {
        // After a process restart the recipe can load before the socket's first snapshot.
        stores.activeJobId.value = "left-running"
        val viewModel = openMargarita()
        assertEquals(PourPhase.Idle, viewModel.pour)

        machine.currentJob.value = job("left-running", JobStatus.RUNNING, stepIndex = 1)
        runCurrent()

        assertEquals("left-running", (viewModel.pour as PourPhase.Pouring).jobId)

        // And from then on it is followed like any other pour.
        machine.currentJob.value = job("left-running", JobStatus.FINISHED)
        runCurrent()
        assertEquals(PourPhase.Finished("left-running"), viewModel.pour)
    }

    @Test
    fun `a pour left running is picked back up when the screen opens`() = runTest {
        stores.activeJobId.value = "left-running"
        machine.currentJob.value = job("left-running", JobStatus.RUNNING, stepIndex = 1, progress = 0.4f)

        val viewModel = openMargarita()

        val pouring = viewModel.pour as PourPhase.Pouring
        assertEquals("left-running", pouring.jobId)
        assertEquals(0.4f, pouring.progress, 0.001f)
    }

    @Test
    fun `another phone's pour is not shown here`() = runTest {
        stores.activeJobId.value = "mine"
        machine.currentJob.value = job("someone-elses", JobStatus.RUNNING)

        assertEquals(PourPhase.Idle, openMargarita().pour)
    }

    @Test
    fun `this phone's pour of another drink is not shown here`() = runTest {
        stores.activeJobId.value = "mine"
        machine.currentJob.value = job("mine", JobStatus.RUNNING).copy(drinkId = "11000")

        assertEquals(PourPhase.Idle, openMargarita().pour)
    }
}

// ---------------------------------------------------------------------- fixtures

private val margarita = CocktailDto(
    id = "11007", name = "Margarita", glass = "Cocktail glass",
    ingredient1 = "Tequila", measure1 = "1 1/2 oz",
    ingredient2 = "Triple sec", measure2 = "1/2 oz",
    ingredient3 = "Lime juice", measure3 = "1 oz",
    ingredient4 = "Salt",
)

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
)

private fun job(jobId: String, status: JobStatus, stepIndex: Int = 0, progress: Float = 0f) = PourJob(
    jobId = jobId,
    drinkId = "11007",
    drinkName = "Margarita",
    status = status,
    steps = listOf(
        JobStep(0, StepKind.GLASS, "Waiting for a glass", null, null, null, null),
        JobStep(1, StepKind.POUR, "Pouring Tequila", null, 1, 44.0, 22.0),
        JobStep(2, StepKind.FINISH, "Finishing touch", null, null, null, null),
    ),
    currentStepIndex = stepIndex,
    progress = progress,
    totalMl = 44.0,
    dispensedMl = 22.0,
    error = null,
)
