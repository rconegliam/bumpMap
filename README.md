# BumpMap

Crowdsourced map of road quality for Brazil. While you drive, the Android app measures how much the car shakes and builds a shared map where streets are colored from smooth (green) to very bad (red) because of potholes, speed bumps and rough pavement.

- Android app: Kotlin + Jetpack Compose, MapLibre + OpenStreetMap
- Languages: Brazilian Portuguese and English
- Backend (planned): Railway – PostGIS, API, Valhalla map matching, Martin vector tiles
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
- Map (MapLibre + OpenFreeMap / OpenStreetMap) centered on Barueri and São Paulo, labels in the app language
- Drive recorder: motion sensors + GPS saved to CSV, with buttons to mark speed bumps, potholes and rough pavement, and share the file. See [docs/recording-format.md](docs/recording-format.md)
- English and Brazilian Portuguese, with an in-app language picker
