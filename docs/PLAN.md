# BumpMap – Plan

**Decisions:** name BumpMap · shared crowdsourced map from day 1 · cars only · Brazil first (beta: Barueri + São Paulo) · backend on Railway · languages pt-BR + English.

Goal: while driving, the phone measures how much the car shakes, links it to the street, and everyone's data builds one shared map where streets are colored from smooth (green) to terrible (red) — potholes (buracos), speed bumps (lombadas / quebra-molas), dips (valetas) and rough surfaces like cobblestone (paralelepípedo).

---

## 2. Map

- **MapLibre Native Android** (open source) + **OpenStreetMap** data.
- Base map: **OpenFreeMap** tiles (free, no API key) to start; later a self-hosted **Protomaps** Brazil extract (single PMTiles file) for full control.
- Do not use `tile.openstreetmap.org` in the app (usage policy forbids it).
- Roughness layer: vector tiles from our backend (see §5) drawn on top of the base map, colored by score.
- Street labels follow the app language (`name:pt` → `name:en` → `name`).

## 3. Measuring shaking (cars only)

**Sensors:** accelerometer + gravity at ~50–100 Hz, gyroscope, fused GPS at 1 Hz.

**Processing on the phone**
1. Only record when **in a vehicle** (Activity Recognition `IN_VEHICLE`) and speed > ~10 km/h; pause when GPS accuracy is poor.
2. Rotate acceleration into the vertical axis using gravity (phone may be in a holder, cup holder, etc.; a holder is recommended in the onboarding).
3. Discard windows where the phone is being handled (big rotations, screen interaction).
4. High-pass filter, then for every ~25 m compute:
   - **Roughness**: RMS vertical acceleration normalized by speed.
   - **Events**: pothole (sharp, short drop + spike), speed bump (longer symmetric bump, usually after slowing down), cross-checked with OSM `traffic_calming=*` tags.
   - Surface type: continuous high roughness with no single event → likely cobblestone/dirt; compared with OSM `surface=*` tag.
5. **Per-device calibration**: every car/phone combo shakes differently → normalize scores against that device's own baseline so data from a Gol and a Hilux is comparable.

## 4. Crowdsourced scoring & colors

- Unit = OSM way split into ~25 m segments.
- Segment score = robust median across passes, newer passes weigh more (roads get fixed or get worse).
- **Show a color only after ≥ 3 different devices** have driven it (avoids one bad phone or abuse painting a street red). Otherwise gray.
- Classes: Green (smooth) · Yellow (some irregularities) · Orange (bad) · Red (very bad) · Gray (not enough data). Color-blind palette option.
- Pins for confirmed potholes/bumps (detected by several devices at the same spot).
- Optional "Report pothole" button for manual confirmation.

## 5. Backend on Railway

| Service (Railway) | Tech | Role |
|---|---|---|
| **API** | Kotlin **Ktor** (same language as the app) – or Python FastAPI | Auth, receive trip uploads, serve stats |
| **Database** | PostgreSQL + **PostGIS** (Railway PostGIS template / `postgis/postgis` image) + volume | Road segments, scores, events |
| **Map matching** | **Valhalla** (Docker) with Brazil OSM extract (Geofabrik) | Snaps GPS traces to OSM ways |
| **Tile server** | **Martin** (Docker) | Serves the roughness layer from PostGIS as vector tiles |
| **Worker / cron** | Same codebase as API | Recomputes segment scores, imports OSM road updates monthly |

Railway notes:
- Valhalla tile build for all of Brazil needs several GB of RAM. Build the tiles once (locally or in a temporary job) and store them on a Railway volume, so the running service stays small. May need the Railway Pro plan for memory.
- Put Cloudflare (free) in front of Martin to cache tiles and keep egress costs low.
- Staging + production environments in Railway; deploy from GitHub.

**Upload flow:** phone → batch of (timestamp, lat/lon, speed, per-25 m roughness, events) gzipped → API → Valhalla match → write per-segment observations → worker aggregates → Martin serves updated tiles.

**Abuse / trust:** anonymous device account (random key on install) + **Google Play Integrity API**, rate limits, outlier rejection per device.

## 6. Privacy – LGPD

- Explicit opt-in consent screen (pt-BR + EN), privacy policy, data controller contact (encarregado/DPO).
- Cut the first/last ~300 m of every trip on the phone before upload (hides home/work); user-defined privacy zones.
- Store per-segment observations, not continuous raw tracks, after map matching; delete raw uploads after processing.
- "Delete my data" in the app.

## 7. Android app

- Kotlin, Jetpack Compose, MVVM, Hilt, Navigation, Room, DataStore, WorkManager, Retrofit/Ktor client.
- Foreground Service with `foregroundServiceType="location"` (Android 14+), notification "Recording road quality".
- Optional auto-start when driving (Activity Recognition transitions).
- Uploads via WorkManager (Wi-Fi only by default, toggle for mobile data — data cost matters in Brazil).
- minSdk 26; must run well on low-end phones common in Brazil.
- Screens: Map · Record/Trip status · My contributions (km driven, segments mapped) · Settings (language, units, palette, privacy zones, upload rules) · Onboarding/consent.

## 8. Multi-language

- `res/values/strings.xml` = English (Android fallback), `res/values-pt-rBR/strings.xml` = Brazilian Portuguese (primary market). Spanish later (Latin America).
- No hard-coded text; lint `MissingTranslation` / `HardcodedText` fail the build in CI.
- `plurals` for counts ("3 viagens"), locale-aware numbers/dates.
- In-app language picker via per-app language preferences (`AppCompatDelegate.setApplicationLocales` + `locales_config.xml`).
- Backend returns message keys, not text; app translates.
- Store listing, screenshots, privacy policy in pt-BR and EN. Weblate/Crowdin when more languages come.

## 9. Phases

| Phase | Scope | Est. |
|---|---|---|
| 0. Logger prototype | Raw sensor+GPS recorder; test drives over known lombadas/buracos in 1–2 cars; tune algorithm offline | 1–2 wks |
| 1. Backend foundation | Railway project: PostGIS, Ktor API, Valhalla with Brazil tiles, Martin; import Brazil road network | 2–3 wks |
| 2. MVP app | Recording service, on-device scoring, upload, shared colored map, pt-BR/EN, consent/LGPD | 3–4 wks |
| 3. Closed beta | Google Play internal/closed testing in one city; tune thresholds and calibration with real users | 2–4 wks |
| 4. Public launch + extras | Pothole pins, manual reports, city rankings, data export for city halls | ongoing |
| 5. Later | Routes that avoid bad roads, Spanish, iOS | – |

## 10. Testing

- Unit tests for signal processing using recorded real drives (replayed CSV) – the emulator can't fake real vibrations.
- Backend integration tests with sample traces through Valhalla + PostGIS.
- UI tests for map, recording flow, language switching.
- Field validation: same street, different cars/phones → scores should agree after calibration.
