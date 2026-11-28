# Offline geography pilot

The backend packages a frozen PostGIS lookup dataset. Flyway creates the
tables and indexes in V5 and imports the bundled gzip snapshots in V6.
The existing `migrate(pool)` call in `Application.kt` is the bootstrap: on
Railway, start the API normally (`/app/bin/bumpmap-api` in its image) with
the existing PostGIS connection variables. No additional service, startup
download, API key or observation-time geocoding is needed. Flyway applies
V6 once per database; its transaction and ID-based upserts allow safe replay.

## Coverage and attribution

| Table | Coverage | Rows | Compressed snapshot size |
| --- | --- | ---: | ---: |
| `map_admin_areas` | Brasil (`BR`), state of São Paulo (`BR-SP`), Barueri (`BR-SP-3505708`), and municipality of São Paulo (`BR-SP-3550308`) | 4 | 43,513 bytes |
| `map_street_ways` | Named motor-vehicle road candidates intersecting Barueri (2,942) or São Paulo (96,384), deduplicated by OSM way ID | 99,324 | 7,058,113 bytes |

- **Boundaries:** Instituto Brasileiro de Geografia e Estatística (IBGE),
  [API de Malhas Geográficas v3](https://servicodados.ibge.gov.br/api/docs/malhas?versao=3),
  retrieved 2026-11-28 at `qualidade=maxima`, using
  `paises/BR`, `estados/35`, `municipios/3505708` and `municipios/3550308`.
  The API returns generalized boundaries derived from Malha Municipal
  Digital. IBGE's [terms for the MMD and its derivatives](https://biblioteca.ibge.gov.br/visualizacao/livros/liv102268.pdf)
  describe a license compatible with [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).
  BumpMap converted the boundaries to MultiPolygons and repaired an
  invalid nested shell in the Brazil geometry. Credit: **Fonte: IBGE —
  Malhas Geográficas; adaptação: BumpMap.**
- **Ways:** **© [OpenStreetMap contributors](https://www.openstreetmap.org/copyright)**,
  [ODbL 1.0](https://opendatacommons.org/licenses/odbl/1-0/).
  The source was the [OpenStreetMap France São Paulo state extract](https://download.openstreetmap.fr/extracts/south-america/brazil/southeast/),
  replication sequence 7390074, timestamp `2026-11-27T01:12:53Z`
  (383,050,531 bytes; verified against the provider's MD5).
  Keep OSM attribution visible when using the road layer; distribute this
  derived street database under the ODbL, including its share-alike terms.
  The street snapshot does not include BumpMap observations or IBGE polygons.

## Spatial lookups and limits

Both tables use SRID 4326 and longitude/latitude coordinates. The
administrative geometry is a `MultiPolygon`; each road is a `LineString`
with ID `osm-way-<OSM way ID>`, not a unique ID for an entire named street.

- Build a point as `ST_SetSRID(ST_MakePoint(lon, lat), 4326)`. Search
  `map_admin_areas` with `ST_Covers(geom, point)` so a point on the
  boundary counts. Prefer city, then state, then country; return no
  administrative match where no polygon covers the point. Resolve ties
  within a level by ID. Parent IDs identify the hierarchy; independently
  generalized boundaries can disagree close to their edges.
- Search for a street with `ST_DWithin(geom::geography, point::geography, radius_m)`
  (metres). Order candidates by `ST_Distance(geom::geography, point::geography)`
  and ID; return no match if there are no candidates within the radius.
  Use the indexed expressions without further wrapping the table geometry.
- OSM ways stay whole when they intersect a pilot city, so a few extend
  past the municipal limits. The snapshot excludes unnamed roads, areas,
  explicit no/private motor-vehicle access and non-motor-road highway
  classes. The builder also skips incomplete ways (two in this state
  extract). OSM tagging and proximity cannot guarantee travel permission
  or identify the correct street at a junction or overpass. This is not a
  routing graph: it does not model connectivity, turn restrictions,
  conditional access or live changes.
- The four simplified polygons and two-city road footprint are pilot
  coverage, not a nationwide address or boundary authority. Outside the
  pilot cities, a state or country match can still be returned without a
  street; outside Brazil, neither is generally available. Do not invent
  a city or a street for unmatched observations.

## Rebuilding and publishing a refresh

Run from the repository root with Python 3.10+, GEOS supplied by the
pinned Shapely wheel, and network access on the **build workstation**.
Use a new cache for a refresh so the IBGE API is fetched again. Keep the
raw source files if you need to reproduce an exact release: the upstream
URLs are mutable and only the finished snapshot is committed.

```sh
cache="$HOME/bumpmap-geography-refresh"
mkdir -p "$cache"
python3 -m venv "$cache/venv"
"$cache/venv/bin/pip" install -r backend/geography/requirements.txt

source=https://download.openstreetmap.fr/extracts/south-america/brazil/southeast
curl -fL "$source/sao-paulo.osm.pbf.md5" -o "$cache/sao-paulo.osm.pbf.md5"
curl -fL "$source/sao-paulo.osm.pbf" -o "$cache/sao-paulo.osm.pbf"
(cd "$cache" && md5sum -c sao-paulo.osm.pbf.md5)

# Set version to the next available Flyway version, not 6.
version=7
"$cache/venv/bin/python" backend/geography/build_snapshot.py \
  --cache-dir "$cache" --osm-pbf "$cache/sao-paulo.osm.pbf" \
  --version "$version" --output-dir backend/src/main/resources/geography
```

Record the new PBF replication timestamp, source hashes, counts and sizes.
Package new versioned assets and add a Flyway migration that reads them.
Do not edit shipped V6 inputs: Flyway validates them via checksum. If a
refresh must remove retired ways, explicitly reconcile those IDs in the
new migration; upsert alone updates and inserts but never deletes. Run
the PostGIS backend verification in [backend/README.md](../backend/README.md)
against an empty database and review spatial coverage before publishing.

V6 input SHA-256 hashes:

```text
933da368ffec297004c1d934c7e4bb3653b9bd5ac8bc5c1d830458f187059aee  BR.geojson
36b0cd7eee6e61fe64a0e735383d12fc5671cc9255e03ab95ac3efd0ff3cc127  35.geojson
5612022721a83900281263a6b149710478f70596854d0d3867c1639167faf3a4  3505708.geojson
f1bdf75deb96e3f7a990387b2e74cde752ddb8e87eebe448de006e36926bb718  3550308.geojson
71f5399be1d561ec0fe7520b3c278e3168cda0f7d7716cc53b8f821d02baf114  sao-paulo.osm.pbf
```
