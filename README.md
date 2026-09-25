# Smart Bartender

Companion / control app for the **Smart Bartender** cocktail machine, built as an
Android prototype.

Recipes come live from [TheCocktailDB](https://www.thecocktaildb.com/api.php) using the
public test key `1`.

---

## Running it

**Android Studio (recommended)**

1. Open the project folder in Android Studio (Ladybug or newer — it targets AGP 9.4 / Gradle 9.7.1).
2. Let the Gradle sync finish. All dependencies come from Google Maven and Maven Central.
3. Pick an emulator (or a device) running **Android 7.0 / API 24 or newer** and press **Run**.
   If you have no emulator yet: *Device Manager → Add a new device* and take any Pixel image.
4. The device needs an internet connection — recipes and photos are fetched at runtime.

**Command line**

```bash
./gradlew assembleDebug          # build the APK
./gradlew installDebug           # build + install on the connected device/emulator
./gradlew testDebugUnitTest      # run the unit tests
```

The debug APK lands in `app/build/outputs/apk/debug/`.

---

## Screens

| Tab           | What it does                                                                                                                                            |
|---------------|---------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Available** | Everything the machine can pour with the bottles currently loaded, split into *Can make now* and *One bottle away* (with the missing ingredient named). |
| **Library**   | The full recipe book as a photo grid, with name search (`search.php?s=`) and a *surprise me* random pick (`random.php`).                                |
| **Bottles**   | The bottle rack. Toggle the bottles that are physically loaded; the selection is persisted.                                                             |
| **Stats**     | What this phone has poured: cocktails made, litres through each pump, most-made drinks, happy hour, alcohol-free share, and milestones.                 |
| **Settings**  | The **LED show** switch, with a live preview of the strip, plus machine status.                                                                         |

Tapping any cocktail opens the **recipe detail**: photo, category, glass, alcoholic/non-alcoholic,
ingredients with their measures, instructions, and a **Make this cocktail** button that sends
the pour to the machine and follows it live — a step-by-step animation of what the machine
reports, washed in the LED colours when the show is switched on.

---

## How "what can I make" works

TheCocktailDB has no endpoint for "given these bottles, what can I pour", so the app works
it out client-side:

1. **Candidates from the spec'd path** — `filter.php?i={bottle}` for every loaded bottle,
   unioned. Any id it returns that we do not already have in full is fetched via
   `lookup.php?i={id}`.
2. **Candidates from the bulk catalog** — the recipe book is also loaded in full through the
   26 first-letter pages (`search.php?f=a` … `?f=z`), which return complete recipes.
   *Why:* on the public test key `1`, `filter.php?i=` is capped at **one drink per
   ingredient**, so on its own it would surface a handful of cocktails instead of the whole
   book. The first-letter pages return ~650 complete recipes in 26 requests — far cheaper
   than looking candidates up one at a time. Swap in a real API key and step 1 widens on its
   own; nothing else needs to change.
3. **Scoring** — every recipe's ingredients are matched against the loaded bottles:
   * no ingredient missing → **Can make now**
   * exactly one missing → **One bottle away**, naming what is absent
   * two or more missing → not shown

**The four-slot rack.** The machine holds four bottles at a time, so the *One bottle away*
section carries most of the discovery: it is where you see what ejecting one bottle would buy
you. The limit is enforced in the data layer, not just the UI — `BartenderPreferences` clamps
on both read and write, so a rack persisted by an earlier build (or a race between two
writes) can never present more bottles than the machine has. Slot order follows the catalogue,
so slot 1 holds the same bottle across restarts. The factory rack is vodka, white rum, lime
juice and cola — good for about 5 pourable drinks and 33 one-bottle-away.

**Matching rules.** Ingredient names are compared case-, accent- and punctuation-insensitively
("Curaçao" = "Curacao"), and each bottle carries the alternative spellings the API uses
("Light rum" / "White rum" / "Rum" are one bottle). A small set of **bar-top staples** — ice,
water, sugar, salt, mint, garnishes — always counts as available, so a Margarita is not
"unmakeable" for want of a pinch of salt. Both lists live in
`domain/model/BottleCatalog.kt` and are the one place to edit when the machine's rack changes.

**Caching.** The recipe catalog, every looked-up recipe and the last availability result are
held in memory for the process lifetime, so returning to the Available tab is instant and
toggling a bottle re-scores locally without re-fetching.

---

## Architecture

MVVM, unidirectional data flow. Each screen has a ViewModel exposing one immutable
`UiState` over a `StateFlow`; composables are stateless and receive state plus callbacks.

```
app/src/main/java/com/example/smartbartender/
├── data/
│   ├── local/       BartenderPreferences   – DataStore: loaded bottles + LED switch
│   ├── remote/      CocktailApi, NetworkModule, dto/ – Retrofit + kotlinx.serialization
│   └── repository/  CocktailRepository     – fetching, caching, availability engine
├── domain/model/    Cocktail, Bottle, BottleCatalog, MakeableCocktail
├── di/              AppContainer           – manual DI, one instance per process
├── ui/
│   ├── components/  GlassPanel, LED simulation, skeletons, error/empty states, cards
│   ├── navigation/  Routes, bottom bar, NavHost
│   ├── screens/     available/ library/ bottles/ stats/ settings/ detail/  (screen + ViewModel)
│   └── theme/       dark neon palette, type scale
└── MainActivity.kt, SmartBartenderApplication.kt
```

**Stack:** Kotlin, Jetpack Compose (Material 3), Navigation Compose, Retrofit +
kotlinx.serialization, OkHttp, Coil 3, DataStore Preferences, Coroutines/Flow. No DI
framework, `AppContainer` is created once in the `Application` and read by the ViewModel
factories.

---

## Look & feel

Always-dark "machined instrument" theme: near-black graphite surfaces, glossy panels with a
hairline lit edge, neon cyan/magenta accents. The **LED show** is a real animation, not a
static colour: a single infinite transition at the root of the app drives one shared colour
clock, so the strip preview, panel edges, bottom bar, ambient glow and the pour animation all
cycle in step. Switch it off and everything falls back to a neutral finish.

---

## Quality notes

* **Loading** — shimmer skeletons shaped like the content they replace (grid, list, detail).
* **Errors** — every network failure surfaces a message with a **Retry** button; timeouts and
  offline states get their own wording. A partial failure (some letters/ingredients fail)
  degrades gracefully instead of blanking the screen.
* **Empty states** — no bottles loaded, no matches, nothing found, each with a way forward.
* **API quirks handled** — TheCocktailDB answers a miss with `"drinks": null` *or* the bare
  string `"drinks": "no data found"`. A lenient deserializer treats both as "no results"
  rather than crashing.
* **Tests** — `./gradlew testDebugUnitTest` covers ingredient normalisation and alias
  resolution, the ingredient/measure pairing of the 15 API slot pairs, the two miss-response
  shapes, the four-slot rack limit and its clamping, and the can-make/almost/not-shown
  classification against a stubbed API.

## Prototype boundaries

* Pours are timed animations. There is no hardware link, no BLE/Wi-Fi transport, no dosing.
* The bottle rack is a fixed catalog of common base ingredients, not arbitrary free text.
* Four slots is a constant (`BottleCatalog.MAX_SLOTS`); a machine with more slots needs only
  that one value changed.
* Caching is in-memory only; a cold start re-fetches the catalog.
