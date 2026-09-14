# PulseGuard (HeartRateWatch)

A Wear OS training app for the Galaxy Watch 4: real-time heart-rate threshold alerts with a
break timer, distance tracking during a run, and a phone companion app for settings and
audio alerts. Scaffolded from the requirements in this project's `requirements.md` doc.

## Modules

- **`:shared`** -- plain Kotlin models both other modules depend on: `TrainingSettings`,
  `RunSummary`, and the Data Layer path constants (alerts, settings sync, calibration results,
  run summaries). No Android UI code here.
- **`:wear`** -- the actual watch app. `ExerciseSessionService` is the core: a foreground
  service that runs a Health Services `ExerciseClient` session, checks heart rate against your
  thresholds every update (literal bpm or a percentage of your calibrated max HR, depending on
  `TrainingSettings.thresholdMode`), triggers the break timer / push-harder alerts /
  target-reached message, tracks avg/max/min bpm for the run, and sends a `RunSummary` to the
  phone when it stops. `MainActivity` is the on-watch start/stop screen, `CalibrationActivity` is
  the guided max-HR test (see below), `tile/HeartRateTileService` is the glanceable Tile.
- **`:mobile`** -- the companion phone app. This is where thresholds (bpm or % of max HR), break
  timer length, GPS toggle, and custom alert sounds actually get entered (`MainActivity`) --
  settings sync to the watch automatically over the Data Layer. `WearMessageListenerService`
  receives the watch's alert messages (plays them via `AlertPlayer`, which is what lets the sound
  reach headphones/AirPods connected to the phone rather than the watch), calibration results,
  and run summaries (browsable in `RunHistoryActivity`).

## What's implemented vs. stubbed

Implemented: the full settings model and sync loop, the exercise session with real threshold
checking and a repeating break countdown, local vibration alerts, phone-side audio playback
(default system sound, with per-alert custom sound uploads for all three alert types, and audio
formats beyond `.wav` -- mp3, m4a/aac, ogg, flac, etc.), a guided max-HR calibration test
(`CalibrationActivity` on `:wear` -- warm up / build / max effort / cool down, tracks peak bpm,
suppresses the normal threshold alerts during the test, keeps a short history on the watch via
`CalibrationStore`, and pushes the latest result to the phone via `CalibrationRepository`), an
optional percent-of-max-HR threshold mode that resolves against that calibration
(`TrainingSettings.resolvedLowerBpm`/`resolvedUpperBpm`), per-run history (duration, avg/max/min
bpm, distance) pushed from the watch and browsable on the phone (`RunHistoryActivity`), the Tile,
and permission requests.

Left as a follow-up, deliberately, to keep this a starting point rather than a finished app: a
proper "distance covered" spoken/visual announcement during the run (distance is tracked and
shown, just not periodically called out).

