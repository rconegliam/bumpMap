# BumpMap API pilot

This Ktor service stores and reads synthetic road observations in PostgreSQL with
PostGIS. Keep the pilot token on trusted test clients. Real drives need the app
consent and privacy controls, per-device credentials, and road aggregation
described in the [plan](../docs/PLAN.md).

| Method | Path | Result |
| --- | --- | --- |
| GET | `/health` | 200 when the API can reach PostgreSQL, otherwise 503 |
| POST | `/v1/observations` | Accepts a GeoJSON FeatureCollection with 1 to 100 observations and returns the accepted count |
| GET | `/v1/observations?bbox=west,south,east,north` | Returns up to 500 intersecting features in a GeoJSON FeatureCollection |

Both observation routes require `Authorization: Bearer <BUMPMAP_API_TOKEN>`.
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
random secret of at least 32 characters in Railway. The API binds to `PORT`
(default 8080) and applies database migrations before serving requests.

From the repository root, an authorized Railway CLI can upload this directory
as the API service's build context.

```sh
railway up backend --path-as-root --service api --environment pilot --project <project-id>
```
