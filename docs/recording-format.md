# Recording file format (v1)

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

- `t_ns` is nanoseconds since device boot (`elapsedRealtimeNanos`) for every row type, so rows can be aligned directly. Wall-clock time = `started_at_epoch_ms + (t_ns - started_at_elapsed_ns) / 1e6`.
- Sensors are requested at 100 Hz, GPS at 1 Hz.
- Missing GPS values are written as `NaN`.
- Decimal separator is always `.`.
