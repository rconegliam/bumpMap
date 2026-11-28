# BumpMap API pilot

This Ktor service stores road observations and Android recording archives
in PostgreSQL with PostGIS. The website and current Android client read the public map without credentials.

| Method | Path | Result |
| --- | --- | --- |
| GET | `/`, `/assets/…` | Serves the public website shell and assets bundled under `src/main/resources/web` |
| GET | `/health` | 200 when the API can reach PostgreSQL, otherwise 503 |
| POST | `/v1/installations` | Registers an Android installation and returns 204 |
| POST | `/v1/observations` | Accepts a GeoJSON FeatureCollection with 1 to 100 observations and returns the accepted count |
| GET | `/v1/observations?bbox=west,south,east,north` | Returns up to 500 intersecting features in a GeoJSON FeatureCollection |
| GET | `/v1/public/map/observations?bbox=west,south,east,north` | Returns up to 500 aggregated road and obstacle features |
| GET | `/v1/public/map/statistics?level=city&sort=coverage&limit=20&offset=0` | Returns statistics or ranked results for measured cities, states, countries or streets |
| GET | `/v1/public/map/route?from=lon,lat&to=lon,lat` | Calculates a car route between two points in the pilot area |
| GET | `/v1/public/map/route/config` | Reports whether routing is enabled and whether the provider is external |
| PUT | `/v1/recordings/{id}/{kind}/{number}?parts=…&name=…` | Saves one part of an original recording file and returns 204 |

Read access to `/v1/observations` requires the legacy operator credential `BUMPMAP_API_TOKEN`.
Writes accept the registered installation credential scoped to the recording, or the legacy credential.
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

The site and current Android app use `/v1/public/map` without a read token.
The map uses the geometry of the nearest road in the pilot OpenStreetMap snapshot
when the segment midpoint is within 35 metres. It averages speed and penalty
and totals obstacles across passages on each road way. Obstacle markers represent
the approximate location on the way. Segments outside the road snapshot or
without a match can still count in administrative statistics.

The public map endpoint returns an `application/geo+json` FeatureCollection with the
same bounding box and `after` pagination rules as the legacy endpoint.
`next_cursor` is present only when there is another page.
Features have `type`, `id`, `geometry`, and `properties` without
`recording_id`. Each feature's properties include `kind` and the way's mean `speed_kmh`.
Scored segments have `potholes`,
`speed_bumps`, `map_quality` (`good`, `fair`, `poor`, or `bad`), and
`penalty_points` (integer). Obstacle markers have `type`.
Map penalties use the current Android pavement calibration from `roughness`
(0.6 to 2.5), falling back to the stored `score` when roughness is absent.
The legacy `/v1/map` endpoints remain available with `BUMPMAP_MAP_READ_TOKEN`
for authenticated pilot clients.

### Statistics and rankings

Use `level=city|state|country|street` and `sort=coverage|best|worst`.
Coverage sorts by measured segment count. Best and worst sort by average
map penalty, which combines pavement, measured speed, potholes and speed
bumps. The response has `items` and `total`. Each item includes the segment
count, measured kilometres, mean speed and penalty, and obstacle totals.
Use `limit` from 1 to 100 (default 50) and a zero-based `offset` (default 0).

Statistics include qualifying recorded passages. The area is assigned by
the midpoint of each measured segment. A street must have an OpenStreetMap
name and match within 25 metres of that midpoint within the segment's city.
Ways with the same name in a city are grouped together. Repeated passages
add to the distance and obstacle totals, and small samples can appear in
rankings. Only places with qualifying data appear.
The [geography snapshot](../docs/geography.md) documents the boundary and
street sources and coverage.

### Car routes

Set the origin and destination on the map to draw a route and get an
estimated distance and duration. The [private OSRM service](../routing/)
calculates the route with OpenStreetMap's car profile. The estimate uses
the road network and typical speeds from the profile. BumpMap penalties
and live traffic are outside this estimate.

## Recording archives

Android queues finished recordings already on the phone and future recordings.
On the first upload it registers its installation UUID and a locally generated
random credential. The API stores the credential digest and accepts retrying
the same registration. Enrollment is anonymous and the API limits new
registrations and the daily upload volume per installation.
WorkManager uses an unmetered connection by default,
resumes interrupted uploads and removes files after the archive and map uploads
are confirmed. The archive includes the original GPS routes and sensor data.

The archive endpoint requires the installation bearer credential or legacy
`BUMPMAP_API_TOKEN` and accepts
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
separate pothole and speed bump counts. Installation credentials can publish
observations only for recordings with an uploaded NDJSON file.

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

The `pilot` environment uses `api`, `postgis` and `routing` services.
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

Set `BUMPMAP_ROUTING_BASE_URL=http://routing.railway.internal:5000` on the
API to use the [private pilot router](../routing/README.md). For local
development with the router running, use `http://127.0.0.1:5000`.

From the repository root, an authorized Railway CLI can upload this directory
as the API service's build context.

```sh
railway up backend --path-as-root --service api --environment pilot --project <project-id>
```
