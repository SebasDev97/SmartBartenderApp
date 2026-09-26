# CLAUDE.md

This file provides guidance to Claude Code when working with code in this repository.

## What this is

Android companion app for the "Smart Bartender" cocktail machine (single `:app` module,
Kotlin + Compose), plus the machine's own service in `pi/` (Python + FastAPI). Recipes come
live from TheCocktailDB v1 using the public test key `1`, baked into the base URL in
`data/remote/NetworkModule.kt`. Pours run on a Raspberry Pi over HTTP + WebSocket — the Pi
drives no GPIO itself; it commands an Arduino over USB serial (pump relays, an ultrasonic sensor
above the glass, a 16x2 LCD; no LED strip). The Arduino runs the group's own sketch, which is
**not in this repo**: `pi/app/hardware/arduino.py` treats it as a black box speaking `RELAY n
ON|OFF`, `ALL OFF`, `GET DIST`, `LCD BOTH;a;b` at 115200 baud, and the sketch has no watchdog.
`pi/API.md` is the contract between the two halves and the first thing to read
before changing either side, and `pi/DEPLOY.md` is the walk-through for getting the service
onto real hardware. `README.md` holds the product-level tour; this file covers what
you need to change code safely.

## Commands

No JDK is on `PATH` on this machine — prefix Gradle invocations with Android Studio's JBR:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

./gradlew assembleDebug        # build the APK (app/build/outputs/apk/debug/)
./gradlew installDebug         # build + install on the connected device/emulator
./gradlew testDebugUnitTest    # JVM unit tests
./gradlew connectedDebugAndroidTest   # instrumented tests (needs a device)

# a single test class or method
./gradlew testDebugUnitTest --tests "com.example.smartbartender.AvailabilityTest"
./gradlew testDebugUnitTest --tests "*.AvailabilityTest.canMakeNow*"
```

The machine service has its own toolchain, in `pi/`:

```bash
cd pi
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
.venv/bin/python -m app.main --simulate --speed 4 --port 8080   # no hardware needed
.venv/bin/python -m pytest tests/ -q
```

There is no lint/format plugin configured; `./gradlew lint` runs the stock AGP Android Lint.
`minSdk 24`, `compileSdk`/`targetSdk 37`, AGP 9.4, Gradle 9.7.1, daemon JVM 25. Dependencies
are declared only through the version catalog (`gradle/libs.versions.toml`) — add a library
there first, then reference it as `libs.…` in `app/build.gradle.kts`.

## Architecture

MVVM with unidirectional data flow. Every screen owns a ViewModel that exposes one immutable
`…UiState` over a `StateFlow`; composables are stateless and take `state` plus callbacks.
Something a ViewModel needs its host to do *once* — navigate after a save, open the drink
"Surprise me" picked — goes out as an event on a buffered `Channel`, collected with
`ObserveAsEvents` in `ui/common/`. Don't pass navigation lambdas into a ViewModel.

**Dependency injection is manual.** `AppContainer` (created once in `SmartBartenderApplication`,
which also calls its `start()`) holds the single `CocktailRepository`, the machine, and the
persisted stores. ViewModels get them through `containerViewModelFactory { container -> … }` in
`di/ViewModelExt.kt`, declared as a `companion object { val Factory = … }` on each ViewModel and
passed to `viewModel(factory = …)` in `ui/navigation/SmartBartenderApp.kt`. `DetailViewModel` and
`CustomDrinkEditorViewModel` are the exceptions: they need their nav argument, so they build their
factory by hand and read it with `createSavedStateHandle().toRoute<…>()`. Routes are type-safe
`@Serializable` objects in `ui/navigation/Destinations.kt`. Do not introduce Hilt/Koin without a
reason — the container pattern is deliberate.

**Persisted state is split by concern** (`data/local/Stores.kt`): `RackStore`,
`MachineSettingsStore`, `ActiveJobStore`, `FavouritesStore`, `CustomDrinkStore`,
`PourHistoryStore`. Depend on the narrowest one you need. `DataStoreBartenderPreferences` is the
only implementation, and the JSON-list codecs it uses live beside it in `StoredLists.kt` — the
domain layer knows nothing about storage formats.

**User-facing text lives in `res/values/strings.xml`.** The domain returns typed reasons
(`PlanWarning`, `DrinkProblem`, `MilestoneKind`, `MachineError`) and the UI words them; the only
prose that passes through untouched is what the Pi writes itself (step labels, run messages,
refusal messages).

### The availability engine (`data/repository/CocktailRepository.kt`)

The most load-bearing code in the project. TheCocktailDB has no "what can I pour with these
bottles" endpoint, so `findMakeable()` computes it client-side from two candidate sources:

1. `filter.php?i={bottle}` per loaded bottle (the spec'd path), with ids not already held
   fetched via `lookup.php?i=` — capped at `MAX_EXTRA_LOOKUPS`.
2. The full recipe book, fetched once per process through the 26 first-letter pages
   (`search.php?f=a…z`). This exists **because the public test key caps `filter.php?i=` at one
   drink per ingredient**; without it the Available tab would show a handful of drinks. Swap in
   a real API key and source 1 widens on its own — nothing else changes.

Recipes are then scored by `domain/model/Availability.kt`: 0 missing ingredients → *can make
now*, exactly 1 → *one bottle away*, 2+ → dropped. Custom drinks go through the same scorer. Everything is cached in memory for the process lifetime (`cocktailsById`,
`candidatesByIngredient`, `recipeCatalog`, `lastAvailability`) behind `cacheLock`/`catalogLock`,
with a `Semaphore(6)` bounding parallel requests to the public API. Fan-outs use
`supervisorScope` + `runCatching` so a partial failure degrades instead of blanking the screen;
only an all-pages failure throws.

`lastAvailability` is keyed on the loaded-bottle id set, so a rack change recomputes it on its
own. Telling the *machine* about a rack change is `data/hardware/RackSync.kt`'s job: it watches
`RackStore.slots` app-wide against the map the machine reports, and pushes whenever the two
differ and the machine is idle — so a new way to change the rack needs no extra call, and a
rack changed mid-pour or offline still arrives.

### Ingredient matching (`domain/model/BottleCatalog.kt`)

The single place to edit when the machine's rack changes. It owns the bottle catalog (each
`Bottle` carries `apiName` + `aliases`, so "Light rum"/"White rum"/"Rum" are one bottle), the
`pantryStaples` set (ice, sugar, salt, mint, garnishes always count as available), and
`String.folded()` (in `TextFolding.kt`) — the case/accent/punctuation-insensitive form used for
*all* ingredient comparisons ("Curaçao" == "Curacao") and every name search
(`filterByName`). Any new comparison must go through it.

`MAX_SLOTS = 4` is the machine's physical slot count. The limit is enforced in the data layer,
not just the UI: `clampToCapacity()` is applied on both read and write in
`DataStoreBartenderPreferences`, and `inSlotOrder()` keeps slot 1 the same bottle across restarts.

### API quirks (`data/remote/dto/CocktailDto.kt`)

TheCocktailDB answers a miss with `"drinks": null` *or* a bare string such as `"no data found"`
or `"None Found"`. `LenientDrinkListSerializer` turns anything that is not a JSON array into an empty
list, so a miss is never a parse error. Recipes arrive as 15 flat `strIngredientN`/`strMeasureN`
pairs that `ingredientPairs()` zips and trims.

### The machine link (`data/hardware/`)

The app never simulates a pour. It builds a plan, posts it once, and then renders only what
the machine pushes back over the WebSocket — so a pour survives the app being backgrounded or
killed, and **no foreground service is needed**. `HttpBartenderMachine` lives in an
application-scoped `CoroutineScope` created in `AppContainer` for exactly that reason; putting
it in a `viewModelScope` would blind the app the moment someone leaves the detail screen.

Five rules that are easy to break:

1. **The app mints the `jobId`** (a UUID) and sends it as `Idempotency-Key`. That is what makes
   a retry safe — the machine returns the running job instead of pouring a second drink.
2. **Every WebSocket event carries a whole object, never a delta**, so `reduce()` is always a
   replace. Keep it that way; it is what makes a reconnect self-healing.
3. **`slot_assignment` is positional.** Index 0 is pump 1. `DataStoreBartenderPreferences.writeRack()`
   is the only place the rack is written, and it writes membership and slot order in one
   `dataStore.edit {}` so they cannot drift. Getting this wrong pours the wrong liquid.
4. **The glass sensor gates every pour.** The Pi waits (no timeout) for an empty glass before
   the first pump, reported as `PourJob.waitingForGlass`, and refuses to pour at all
   (`503 NOT_CALIBRATED`) until the empty tray has been measured. After each pour step it swaps
   the time-based `dispensedMl` for the sensor's measurement (`measured: true`), which is what
   the Stats tab then counts. Pump rates come from the Pi's calibration file
   (`~/pump_calibration.json`), driven from `ui/screens/calibration/`; a finished run is kept in
   the snapshot by `HttpBartenderMachine.keepingEndedRuns` because the Pi's own snapshot
   only carries a running one.
5. **Cleartext HTTP is enabled app-wide** in `res/xml/network_security_config.xml`, because the
   machine is plain `http://` on a LAN and its address is typed in at runtime. Without it every
   request fails with `CLEARTEXT communication ... not permitted`, which looks exactly like the
   machine being offline.

Every machine command returns a `Result` whose failure is a `MachineException` carrying a typed
`MachineError` (`domain/model/MachineError.kt`); read it with `toMachineError()` rather than
parsing a message. A refusal keeps the Pi's `code`, so callers can branch on `NOT_CALIBRATED`.

The detail screen's pour is one sealed `PourPhase` (`Idle`, `Pouring`, `Finished`, `Failed`),
produced by the pure `reducePour()` — not a set of booleans.

`domain/model/Measure.kt` and `domain/model/PourPlan.kt` are the only places a recipe becomes
millilitres. TheCocktailDB measures are free text (`"1 1/2 oz"`, `"2-3 oz"`, `"Fill"`, `null`),
so every guess lives there, in one place, with a test. Ranges take their **lower** bound on
purpose. `MAX_ITEM_ML` caps a single pour whatever the parser says.

### Pour statistics (`domain/model/PourHistory.kt`, `PourStats.kt`, `data/hardware/PourRecorder.kt`)

The Stats tab is recorded **on the phone**, not on the Pi, so it counts only pours this phone
started: `PourRecorder` records a terminal job only when its id equals the persisted
`activeJobId`. Like `HttpBartenderMachine` it lives in the app-scoped `CoroutineScope`, so a
drink is counted even if nobody is on the detail screen. If the app was killed mid-pour, it
catches up on the next connect through `fetchJob` (`GET /api/v1/pours/{jobId}`; the Pi keeps its
last 20 jobs).

Volumes come from each pour step's `dispensedMl`, not the planned `ml`, so a stopped pour
counts only what came out. Pumps are mapped to bottles positionally, the same way as the slot
rack. Nothing can measure what's left in a bottle, so keep stats to what the pumps poured.
History is stored as one JSON string in its own DataStore file (`pour_history`), capped at
`PourHistory.MAX_RECORDS`, and `append` ignores a jobId it already holds. All the arithmetic
is in the pure `PourStats.compute()`. It uses `java.util.Calendar`, because minSdk 24 has no
`java.time` and desugaring isn't enabled.

### LED show

`rememberLedState()` in `ui/components/Led.kt` is created **once**, at the root in
`SmartBartenderApp`, and the resulting `LedState` is threaded down to every screen as a
parameter — one shared animation clock keeps the strip preview, panel edges, bottom bar,
ambient glow and pour animation in step. The infinite transition runs regardless of whether the show
is enabled (so toggling never restructures the composition); when off, colours collapse to
neutral cyan, so `led.primary` is always the accent to draw with — no need to check
`led.enabled` first. Don't call `rememberLedState` inside a screen; previews use `LedState.Off`.
`LedState` is one stable object whose values are read from the animation when accessed, so only
code that reads them follows the clock: prefer reading them inside draw lambdas (`drawBehind`),
where a new frame only redraws, over reading them during composition, which recomposes.

### Theme

`SmartBartenderTheme` is always dark and never dynamic-colored — a single-purpose appliance
look. The `darkTheme` parameter exists only for previews. Accent colours flow from `LedState`,
not from `MaterialTheme`, wherever the LED show should bleed through (`GlassPanel(accent = …)`).

## Tests

JVM unit tests only, under `app/src/test/`, JUnit 4 + `kotlinx-coroutines-test`, with
hand-written fakes — there is no mocking library, and `AvailabilityTest`'s `FakeApi` is the
pattern to copy. They cover ingredient normalisation and alias resolution, the 15-slot
ingredient/measure pairing, both miss-response shapes, the four-slot clamp,
can-make/almost/not-shown classification, measure parsing, pour planning, slot ordering,
favourites storage and search, pour history and statistics, the pour recorder, the pour reducer,
the detail screen's pour (`DetailViewModelTest`), the rack sync, load-error wording, the
screens that pause while out of view, and the machine contract through a `FakeMachine` and fakes of the stores. The stores are
interfaces for that last reason, and small ones, so a fake implements only what its subject
reads.

ViewModel tests swap `Dispatchers.Main` for a `StandardTestDispatcher`, which shares `runTest`'s
virtual clock — and `runTest` returns only once that clock has nothing left to run. A ViewModel
that loops (the calibration sensor poll) must have its `viewModelScope` cancelled before the test
body ends, or the whole Gradle run hangs; see `closedAfter` in `ScreenVisibilityTest`.

When touching the availability engine or the catalog, extend `AvailabilityTest` with a fake
`CocktailApi` rather than hitting the network. When touching the pour, extend `PourReducerTest`
— it is a pure function precisely so the riskiest code in the project is cheap to test.

The machine service has its own suite under `pi/tests/` (`pytest`, `TestClient` +
`SimulatedBackend`), including a test that a crash mid-pour still stops every pump. The
simulator models the tray, a glass and the rising liquid, so glass detection, measured volumes
and calibration are tested end to end; it must run at the same `speed` as the `Machine` (see
`SPEED` in `conftest.py`) or the glass fills slower than the pumps "pour".
`test_arduino.py` drives `ArduinoBackend` against a `FakeBoard` that answers like the group's
sketch — change the serial protocol in `arduino.py` and that fake together, and only after
checking what the real sketch answers. `MachineContractTest`'s JSON strings are captured from
`--simulate`, not hand-written.
