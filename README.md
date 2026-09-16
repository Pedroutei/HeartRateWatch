# PulseGuard (HeartRateWatch)

A Wear OS training app for the Galaxy Watch 4: real-time heart-rate threshold alerts with a
break timer, distance tracking during a run, and a phone companion app for settings and
audio alerts. Scaffolded from the requirements in this project's `requirements.md` doc.

## Modules

- **`:shared`** -- plain Kotlin models both other modules depend on: `TrainingSettings`,
  `RunSummary`, and the Data Layer path constants (alerts, settings sync, calibration results,
  run summaries). No Android UI code here.
- **`:wear`** -- the actual watch app. `ExerciseSessionService` is the core: a foreground service
  that runs a Health Services `ExerciseClient` session and independently checks heart rate
  (`TrainingSettings.heartRateAlertsEnabled`) and/or pace (`paceAlertsEnabled`) against your
  thresholds every update -- either, both, or neither can be on, each with its own break/
  push-harder state (`ExerciseSessionService.AlertChannel`) so one metric's break countdown never
  blocks the other's. Heart-rate thresholds resolve as literal bpm or a percentage of your max HR
  (calibrated or manually entered), depending on `thresholdMode`; pace thresholds are plain
  seconds-per-km bounds, with pace itself computed locally from a rolling window of distance
  samples (not from Health Services' own pace field, whose unit isn't reliable in this -rc build).
  Crossing a threshold triggers the break timer or a repeating push-harder alert and a
  corresponding phone message; the alert audio is cut off the moment the break timer ends or the
  metric recovers. `ExerciseSessionService` also tracks avg/max/min bpm and avg pace for the run
  and sends a `RunSummary` to the phone when it stops. `MainActivity` is the on-watch start/stop
  screen, `CalibrationActivity` is the guided max-HR test (see below), `tile/HeartRateTileService`
  is the glanceable Tile.
- **`:mobile`** -- the companion phone app. This is where everything gets entered
  (`MainActivity`, organized into collapsible sections: General, Heart rate, Pace, Target, Custom
  sounds) -- settings sync to the watch automatically over the Data Layer. Distance and pace
  fields have their own unit dropdown (`Units.kt` -- km/miles/meters/feet/yards, plus football
  fields and bananas for distance) via phone-local-only `UnitPreferencesRepository`; the
  underlying stored values always stay in canonical meters / seconds-per-km, so the display unit
  can never drift from what actually triggers an alert. `WearMessageListenerService` receives the
  watch's alert messages (plays them via `AlertPlayer`, which is what lets the sound reach
  headphones/AirPods connected to the phone rather than the watch), calibration results, and run
  summaries (browsable in `RunHistoryActivity`).

## What's implemented vs. stubbed

Implemented: the full settings model and sync loop, an exercise session that alerts on heart rate
and/or pace independently (`TrainingSettings.heartRateAlertsEnabled` / `paceAlertsEnabled`) with a
repeating break countdown and push-harder alerts that stop the instant the metric recovers, local
vibration alerts, phone-side audio playback (default system sound, with per-alert custom sound
uploads for all five alert types -- break/push-harder for both heart rate and pace, plus
target-reached -- and audio formats beyond `.wav`: mp3, m4a/aac, ogg, flac, etc.), a guided max-HR
calibration test (`CalibrationActivity` on `:wear` -- warm up / build / max effort / cool down,
tracks peak bpm, suppresses the normal threshold alerts during the test, keeps a short history on
the watch via `CalibrationStore`, and pushes the latest result to the phone via
`CalibrationRepository`), a manual max-HR override that takes precedence over the calibration when
set, an optional percent-of-max-HR threshold mode that resolves against whichever max HR is active
(`TrainingSettings.resolvedLowerBpm`/`resolvedUpperBpm`), pace thresholds entered as m:ss in your
choice of km or miles, a distance-unit picker for the target field (km, miles, meters, feet,
yards, football fields, bananas), per-run history (duration, avg/max/min bpm, avg pace, distance)
pushed from the watch and browsable on the phone (`RunHistoryActivity`), the Tile, and permission
requests.

Left as a follow-up, deliberately, to keep this a starting point rather than a finished app: a
proper "distance covered" spoken/visual announcement during the run (distance is tracked and
shown, just not periodically called out).

