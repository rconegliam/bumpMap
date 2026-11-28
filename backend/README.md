# BumpMap API pilot

This Ktor service stores road observations and opt-in Android recording archives
in PostgreSQL with PostGIS. Keep the pilot token on trusted test clients.

| Method | Path | Result |
| --- | --- | --- |
| GET | `/`, `/assets/…` | Serves the public website shell and assets bundled under `src/main/resources/web` |
| GET | `/health` | 200 when the API can reach PostgreSQL, otherwise 503 |
| POST | `/v1/observations` | Accepts a GeoJSON FeatureCollection with 1 to 100 observations and returns the accepted count |
| GET | `/v1/observations?bbox=west,south,east,north` | Returns up to 500 intersecting features in a GeoJSON FeatureCollection |
| GET | `/v1/map/observations?bbox=west,south,east,north` | Returns up to 500 intersecting recording-derived features for the pilot website |
| PUT | `/v1/recordings/{id}/{kind}/{number}?parts=…&name=…` | Saves one part of an original recording file and returns 204 |

The legacy `/v1/observations` routes require `Authorization: Bearer <BUMPMAP_API_TOKEN>`.
Use `Content-Type: application/json` or `application/geo+json` to submit observations.
Send `Accept: application/geo+json` to request that media type when reading them.
Upload acknowledgments and errors are returned as `application/json`.
Pass the returned `next_cursor` as `after` with the same bounding box to read
the next page. Bounding boxes use longitude and latitude in degrees and can
span up to one degree on each axis.

Add a client-generated lowercase UUID as each feature's `id`. Reuse it on
retries. Repeating identical features succeeds, while using the same UUID
with changed content returns 409 and rolls back the entire submission. The
allowed geometry and properties are the segment and automatic event fields
from the [recording format](../docs/recording-format.md#detections), with 2D
coordinates and no trip metadata.

## Pilot map

The website shell is public; exact observation locations are not public data.
The map endpoint requires `Authorization: Bearer <BUMPMAP_MAP_READ_TOKEN>`.
The map token does not work on the archive or legacy observation routes, and
the API token does not work on the map endpoint. Give the map token only to
authorized pilot users; never embed it in bundled website resources or put it
in a URL. If the map token is unset or blank, the endpoint rejects access.

The map endpoint returns an `application/geo+json` FeatureCollection with the
same bounding box and `after` pagination rules as the legacy endpoint.
`next_cursor` is present only when there is another page.
Features have `type`, `id`, `geometry`, and `properties` without
`recording_id`. Each feature's properties include `kind` and `speed_kmh`.
Scored segments have `quality`, `score`, `roughness` (when available), `potholes`,
`speed_bumps`, `map_quality` (`good`, `fair`, `poor`, or `bad`), and
`penalty_points` (integer). Events have `type` and `peak` instead.
Map penalties use the current Android pavement calibration from `roughness`
(0.6 to 2.5), falling back to the stored `score` when roughness is absent.
The returned `score` and `quality` are unchanged from the stored observation.

## Recording archives

In the Android app, go to **Settings → Recording sync** and enter the value of
`BUMPMAP_API_TOKEN` from the Railway API service. Activating sync queues finished
recordings already on the phone and future recordings. WorkManager uploads on an
unmetered network, resumes after interruption, and leaves the local files available
for the map and export. This opt-in pilot archive includes the original GPS routes
and sensor data.

The archive endpoint requires the same bearer token and accepts
`application/octet-stream`. The recording ID is a stable UUID shared by its
`csv` and `ndjson` files. Each request includes an original file name, total
part count, and zero-based part number. Parts are 512 KiB except for the last.
An empty file is one empty part. A repeated identical part returns 204, while a
changed part or file description returns 409. Upload parts in order. If earlier
parts are missing, HTTP 412 tells the client to restart from part zero.
The archive has its own storage quota of 512 MiB by default, configurable with
`BUMPMAP_ARCHIVE_MAX_BYTES`. The quota includes a 4 KiB allowance per part.
HTTP 507 means new parts no longer fit. Identical retries still return 204,
and deleting archive files in PostgreSQL releases their charges.

The `completed_recording_files` view lists files with every part in PostgreSQL.
To reconstruct a file, read the matching `recording_file_parts.contents` bytes
in `part_number` order and concatenate them. The archive has no HTTP download
route. Its NDJSON files are separate from the shared road-observation endpoint.

After archiving a recording, Android publishes its measured sections and events
to the observation API. Sections include the mean measured speed in km/h and
separate pothole and speed bump counts. The Android map requests these
observations with `from_recordings=true`.

## Local verification

With JDK 17 and Docker installed, run from the repository root.

```sh
docker run --rm -d --name bumpmap-test-postgis -p 127.0.0.1:5432:5432 \
  -e POSTGRES_DB=bumpmap_test -e POSTGRES_USER=bumpmap_test \
  -e POSTGRES_PASSWORD=bumpmap_test postgis/postgis:17-3.5
docker exec bumpmap-test-postgis pg_isready -U bumpmap_test -d bumpmap_test
PGHOST=127.0.0.1 PGDATABASE=bumpmap_test PGUSER=bumpmap_test PGPASSWORD=bumpmap_test \
  ./android/gradlew -p backend --no-daemon test installDist
docker stop bumpmap-test-postgis
```

## Railway

The `pilot` environment uses an `api` service and a `postgis` service.
Provision PostgreSQL with `postgis/postgis:17-3.5`, mount a persistent volume
at `/var/lib/postgresql/data`, and set `PGDATA` to a subdirectory of that path.
Keep the database on the private network and expose HTTPS only for the API.

Set `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`, and `PGPASSWORD` on the
API using private service variable references. Set `BUMPMAP_API_TOKEN` to a
random secret of at least 32 characters in Railway. Set `BUMPMAP_MAP_READ_TOKEN`
to a separate random secret of at least 32 characters to enable pilot map
queries. The service rejects a configuration that reuses the API token as the
map token. The API binds to `PORT` (default 8080) and applies database
migrations before serving requests.

From the repository root, an authorized Railway CLI can upload this directory
as the API service's build context.

```sh
railway up backend --path-as-root --service api --environment pilot --project <project-id>
```
