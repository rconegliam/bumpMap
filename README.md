# BumpMap

Crowdsourced map of road quality for Brazil. While you drive, the Android app measures how much the car shakes and builds a shared map where streets are colored from smooth (green) to very bad (red) because of potholes, speed bumps and rough pavement.

- Android app: Kotlin + Jetpack Compose, MapLibre + OpenStreetMap
- Languages: Brazilian Portuguese and English
- Backend and web map: Kotlin/Ktor, PostgreSQL with PostGIS, and MapLibre GL JS on Railway
- Beta cities: Barueri and São Paulo

See [docs/PLAN.md](docs/PLAN.md) for the full plan.

## Android app

The app lives in [`android/`](android). Requirements: JDK 17 and the Android SDK (platform 35).

```bash
cd android
./gradlew lintDebug testDebugUnitTest assembleDebug
# APK: android/app/build/outputs/apk/debug/app-debug.apk
```

Current features:
- Shared road map centered on Barueri and São Paulo, with obstacle filters and average speed
- Drive recording with GPS, motion sensors, automatic detections and manual marks. See [docs/recording-format.md](docs/recording-format.md)
- Central storage for original CSV recordings, NDJSON detections and shared observations. The phone queues pending uploads and removes files after confirmation.
- English and Brazilian Portuguese, with an in-app language picker

## Web map and Railway pilot

The web map is served at the root of the Railway API URL and uses a dedicated read-only pilot code. Android and the website consult the same PostgreSQL database.

The website provides map filters, car routes in the pilot area, city/state/country statistics, and rankings by street, city, state and country.

See [backend/README.md](backend/README.md) for pilot setup, API configuration, and deployment.
