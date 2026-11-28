# BumpMap pilot routing

OSRM computes car routes over the pilot road graph. The graph covers Barueri,
São Paulo and nearby roads that can connect points in the two cities. It uses
OpenStreetMap speeds and access rules. It has no live traffic or BumpMap
pavement scores.

The **routing** service in Railway should have only a private network
address. Deploy it with `PORT=5000`, without a public domain or TCP proxy.
Set `BUMPMAP_ROUTING_BASE_URL=http://routing.railway.internal:5000` on the API.
From the repository root:

```sh
railway up routing --path-as-root --service routing --environment pilot --project <project-id>
```

The image builds the graph from the bundled extract. To verify it locally:

```sh
docker build -t bumpmap-routing routing
docker run --rm -p 127.0.0.1:5000:5000 bumpmap-routing
curl 'http://127.0.0.1:5000/route/v1/driving/-46.87568,-23.51014;-46.84721,-23.49797?geometries=geojson'
```

## OpenStreetMap data

`pilot.osm.pbf` contains data © [OpenStreetMap contributors](https://www.openstreetmap.org/copyright)
under the [Open Database License 1.0](https://opendatacommons.org/licenses/odbl/1-0/).
It draws on the [2026-11-27 Sudeste snapshot distributed by Geofabrik](https://download.geofabrik.de/south-america/brazil/sudeste/sudeste-261127.osm.pbf).
Download SHA-256:
`a470646fcb61fc8db32d915c14d53e8bdfb7252b2738d09b8e99941e78ea7ebe`.

To regenerate the extract with Osmium, download the snapshot as
`sudeste.osm.pbf` and run:

```sh
osmium extract --strategy smart --option types=restriction --set-bounds \
  --bbox=-47.08,-24.07,-46.24,-23.23 sudeste.osm.pbf -o pilot-area.osm.pbf
osmium tags-filter pilot-area.osm.pbf w/highway wr/route=ferry r/type=restriction \
  -o routing/pilot.osm.pbf
osmium check-refs --check-relations routing/pilot.osm.pbf
```
