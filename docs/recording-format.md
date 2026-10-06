# Recording file format (v2)

The drive recorder writes one CSV file per trip: `bumpmap_yyyyMMdd_HHmmss.csv`.

Lines starting with `#` are metadata (`# key=value`): format version, app version, device, Android SDK, start time (`started_at_epoch_ms`, `started_at_elapsed_ns`) and the sensor names (`null` = sensor not available).

Then a header line and one row per reading:

```
type,t_ns,v1,v2,v3,v4,v5,v6
```

| type | Meaning | v1..v6 |
|---|---|---|
| `a` | Accelerometer, raw, gravity included (m/s², phone axes) | x, y, z |
| `g` | Gyroscope (rad/s) | x, y, z |
| `v` | Gravity sensor (m/s²) | x, y, z |
| `l` | GPS fix | latitude, longitude, altitude (m), speed (m/s), bearing (°), accuracy (m) |
| `m` | Manual mark tapped by the passenger | `bump`, `pothole` or `rough` |
| `e` | Event detected automatically (v2) | `pothole` or `speed_bump`, peak (m/s²), latitude, longitude |

- `t_ns` is nanoseconds since device boot (`elapsedRealtimeNanos`) for every row type, so rows can be aligned directly. Wall-clock time = `started_at_epoch_ms + (t_ns - started_at_elapsed_ns) / 1e6`.
- Sensors are requested at 100 Hz, GPS at 1 Hz.
- Missing GPS values are written as `NaN`.
- Decimal separator is always `.`.

## Detections

While recording, `RoadAnalyzer` rates the road and detects events on the phone. Results go to `files/detections/<recording name>.ndjson`, one GeoJSON feature per line:

- `LineString` with `kind=segment`: about 25 m of road, with `quality` (`good`, `fair`, `poor`, `bad`), `score` (0-100), `roughness` and `speed_kmh`. Roughness is the vertical acceleration RMS scaled to 40 km/h: `rms * sqrt(40 km/h / speed)`. Segments driven below 12 km/h or with GPS accuracy worse than 25 m are skipped.
- `Point` with `kind=event`: `type` (`pothole`, `speed_bump`), `peak` and `speed_kmh`. A speed bump is a slow heave: the 2 Hz low-passed vertical acceleration stays above 1.2 m/s² for at least 150 ms. A pothole is a sharp jolt: the high-passed signal goes above 4.5 m/s².

All thresholds live in `DetectionConfig` and are first estimates, to be calibrated with real drives against the manual marks.
