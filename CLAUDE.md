# CLAUDE.md

This file provides guidance to Claude Code when working with code in this repository.

## What this is

Android companion app for the "Smart Bartender" cocktail machine (single `:app` module,
Kotlin + Compose), plus the machine's own service in `pi/` (Python + FastAPI). Recipes come
live from TheCocktailDB v1 using the public test key `1`, baked into the base URL in
`data/remote/NetworkModule.kt`. Pours and the LED strip run on a Raspberry Pi over HTTP +
WebSocket — the Pi drives no GPIO itself; it commands an Arduino over USB serial, running the
sketch in `pi/firmware/bartender/`, which owns every pin; `pi/API.md` is the contract between the two halves and the first thing to read
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

**Dependency injection is manual.** `AppContainer` (created once in `SmartBartenderApplication`)
holds the single `CocktailRepository` and `BartenderPreferences`. ViewModels get them through
`containerViewModelFactory { container -> … }` in `di/ViewModelExt.kt`, declared as a
`companion object { val Factory = … }` on each ViewModel and passed to `viewModel(factory = …)`
in `ui/navigation/SmartBartenderApp.kt`. `DetailViewModel` is the exception: it needs the nav
argument, so it builds its factory by hand with `createSavedStateHandle()`. Do not introduce
Hilt/Koin without a reason — the container pattern is deliberate.

### The availability engine (`data/repository/CocktailRepository.kt`)

The most load-bearing code in the project. TheCocktailDB has no "what can I pour with these
bottles" endpoint, so `findMakeable()` computes it client-side from two candidate sources:

1. `filter.php?i={bottle}` per loaded bottle (the spec'd path), with ids not already held
   fetched via `lookup.php?i=` — capped at `MAX_EXTRA_LOOKUPS`.
2. The full recipe book, fetched once per process through the 26 first-letter pages
   (`search.php?f=a…z`). This exists **because the public test key caps `filter.php?i=` at one
   drink per ingredient**; without it the Available tab would show a handful of drinks. Swap in
   a real API key and source 1 widens on its own — nothing else changes.

Recipes are then scored: 0 missing ingredients → *can make now*, exactly 1 → *one bottle away*,
2+ → dropped. Everything is cached in memory for the process lifetime (`cocktailsById`,
`candidatesByIngredient`, `recipeCatalog`, `lastAvailability`) behind `cacheLock`/`catalogLock`,
with a `Semaphore(6)` bounding parallel requests to the public API. Fan-outs use
`supervisorScope` + `runCatching` so a partial failure degrades instead of blanking the screen;
only an all-pages failure throws.

`lastAvailability` is keyed on the loaded-bottle id set, but `BottlesViewModel` also calls
`repository.invalidateAvailability()` after every rack change — keep that call when adding new
ways to mutate the rack.

### Ingredient matching (`domain/model/BottleCatalog.kt`)

The single place to edit when the machine's rack changes. It owns the bottle catalog (each
`Bottle` carries `apiName` + `aliases`, so "Light rum"/"White rum"/"Rum" are one bottle), the
`pantryStaples` set (ice, sugar, salt, mint, garnishes always count as available), and
`String.normalizedIngredient()` — the case/accent/punctuation-insensitive form used for *all*
ingredient comparisons ("Curaçao" == "Curacao"). Any new comparison must go through it.

`MAX_SLOTS = 4` is the machine's physical slot count. The limit is enforced in the data layer,
not just the UI: `clampToCapacity()` is applied on both read and write in
`BartenderPreferences`, and `inSlotOrder()` keeps slot 1 the same bottle across restarts.

### API quirks (`data/remote/dto/CocktailDto.kt`)

TheCocktailDB answers a miss with `"drinks": null` *or* the bare string `"drinks": "no data
found"`. `LenientDrinkListSerializer` turns anything that is not a JSON array into an empty
list, so a miss is never a parse error. Recipes arrive as 15 flat `strIngredientN`/`strMeasureN`
pairs that `ingredientPairs()` zips and trims.

### The machine link (`data/hardware/`)

The app never simulates a pour. It builds a plan, posts it once, and then renders only what
the machine pushes back over the WebSocket — so a pour survives the app being backgrounded or
killed, and **no foreground service is needed**. `HttpBartenderMachine` lives in an
application-scoped `CoroutineScope` created in `AppContainer` for exactly that reason; putting
it in a `viewModelScope` would blind the app the moment someone leaves the detail screen.

Four rules that are easy to break:

1. **The app mints the `jobId`** (a UUID) and sends it as `Idempotency-Key`. That is what makes
   a retry safe — the machine returns the running job instead of pouring a second drink.
2. **Every WebSocket event carries a whole object, never a delta**, so `reduce()` is always a
   replace. Keep it that way; it is what makes a reconnect self-healing.
3. **`slot_assignment` is positional.** Index 0 is pump 1. `BartenderPreferences.writeRack()`
   is the only place the rack is written, and it writes membership and slot order in one
   `dataStore.edit {}` so they cannot drift. Getting this wrong pours the wrong liquid.
4. **Cleartext HTTP is enabled app-wide** in `res/xml/network_security_config.xml`, because the
   machine is plain `http://` on a LAN and its address is typed in at runtime. Without it every
   request fails with `CLEARTEXT communication ... not permitted`, which looks exactly like the
   machine being offline.

`domain/model/Measure.kt` and `domain/model/PourPlan.kt` are the only places a recipe becomes
millilitres. TheCocktailDB measures are free text (`"1 1/2 oz"`, `"2-3 oz"`, `"Fill"`, `null`),
so every guess lives there, in one place, with a test. Ranges take their **lower** bound on
purpose. `MAX_ITEM_ML` caps a single pour whatever the parser says.

### LED show

`rememberLedState()` in `ui/components/Led.kt` is created **once**, at the root in
`SmartBartenderApp`, and the resulting `LedState` is threaded down to every screen as a
parameter — one shared animation clock keeps the strip preview, panel edges, bottom bar,
ambient glow and pour animation in step. The infinite transition runs regardless of whether the show
is enabled (so toggling never restructures the composition); when off, colours collapse to
neutral cyan. Don't call `rememberLedState` inside a screen.

### Theme

`SmartBartenderTheme` is always dark and never dynamic-colored — a single-purpose appliance
look. The `darkTheme` parameter exists only for previews. Accent colours flow from `LedState`,
not from `MaterialTheme`, wherever the LED show should bleed through (`GlassPanel(accent = …)`).

## Tests

JVM unit tests only, under `app/src/test/`, JUnit 4 + `kotlinx-coroutines-test`, with
hand-written fakes — there is no mocking library, and `AvailabilityTest`'s `FakeApi` is the
pattern to copy. They cover ingredient normalisation and alias resolution, the 15-slot
ingredient/measure pairing, both miss-response shapes, the four-slot clamp,
can-make/almost/not-shown classification, measure parsing, pour planning, slot ordering, the
pour reducer, and the machine contract through a `FakeMachine`/`FakePreferences` pair.
`BartenderPreferences` is an interface for that last reason; `DataStoreBartenderPreferences` is
the only implementation that ships.

When touching the availability engine or the catalog, extend `AvailabilityTest` with a fake
`CocktailApi` rather than hitting the network. When touching the pour, extend `PourReducerTest`
— it is a pure function precisely so the riskiest code in the project is cheap to test.

The machine service has its own suite under `pi/tests/` (`pytest`, `TestClient` +
`SimulatedBackend`), including a test that a crash mid-pour still stops every pump.
`test_arduino.py` drives `ArduinoBackend` against a `FakeBoard` that answers like the sketch —
change the serial protocol in `arduino.py`, `bartender.ino` and that fake together.
