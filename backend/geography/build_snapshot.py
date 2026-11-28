import argparse
import csv
import gzip
import hashlib
import io
import json
import shutil
from pathlib import Path
from urllib.request import urlopen

import osmium
import shapely
from shapely.geometry import LineString, MultiPolygon, shape


IBGE = "https://servicodados.ibge.gov.br/api/v3/malhas"
AREAS = (
    ("BR", "country", "Brasil", None, "paises", "BR"),
    ("BR-SP", "state", "São Paulo", "BR", "estados", "35"),
    ("BR-SP-3505708", "city", "Barueri", "BR-SP", "municipios", "3505708"),
    ("BR-SP-3550308", "city", "São Paulo", "BR-SP", "municipios", "3550308"),
)
HIGHWAYS = {
    "motorway", "trunk", "primary", "secondary", "tertiary",
    "motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link",
    "unclassified", "residential", "living_street", "service", "track",
}


def fetch(url, path):
    if not path.exists():
        temporary = path.with_suffix(path.suffix + ".part")
        with urlopen(url, timeout=180) as source, temporary.open("wb") as target:
            shutil.copyfileobj(source, target)
        temporary.replace(path)
    return path


def digest(path):
    checksum = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            checksum.update(block)
    return checksum.hexdigest()


def load_areas(cache):
    rows = []
    for area_id, level, name, parent, endpoint, code in AREAS:
        url = f"{IBGE}/{endpoint}/{code}?formato=application%2Fvnd.geo%2Bjson&qualidade=maxima"
        path = fetch(url, cache / f"{code}.geojson")
        print(f"{path.name} sha256:{digest(path)}")
        features = json.loads(path.read_bytes())["features"]
        if len(features) != 1 or features[0]["properties"]["codarea"] != code:
            raise ValueError(f"Unexpected IBGE feature for {code}")
        geom = shape(features[0]["geometry"])
        if not geom.is_valid:
            geom = shapely.make_valid(geom, method="structure", keep_collapsed=False)
        if geom.geom_type == "Polygon":
            geom = MultiPolygon([geom])
        if geom.geom_type != "MultiPolygon" or geom.is_empty or not geom.is_valid:
            raise ValueError(f"Invalid administrative polygon for {code}")
        rows.append((area_id, level, name, parent, geom))
    return rows


class NamedWays(osmium.SimpleHandler):
    def __init__(self, cities):
        super().__init__()
        self.cities = cities
        self.bounds = {key: geom.bounds for key, geom in cities.items()}
        for geom in cities.values():
            shapely.prepare(geom)
        self.counts = dict.fromkeys(cities, 0)
        self.rows = []
        self.incomplete = 0

    def way(self, way):
        name = way.tags.get("name", "").strip()
        if not name or way.tags.get("highway") not in HIGHWAYS or way.tags.get("area") == "yes":
            return
        access = next(
            (way.tags[key] for key in ("motorcar", "motor_vehicle", "vehicle", "access") if key in way.tags),
            None,
        )
        if access in {"no", "private"} or len(way.nodes) < 2:
            return
        if not all(node.location.valid() for node in way.nodes):
            self.incomplete += 1
            return
        points = [(node.lon, node.lat) for node in way.nodes]
        xs, ys = zip(*points)
        west, east, south, north = min(xs), max(xs), min(ys), max(ys)
        candidates = [
            key for key, (xmin, ymin, xmax, ymax) in self.bounds.items()
            if east >= xmin and west <= xmax and north >= ymin and south <= ymax
        ]
        if not candidates:
            return
        geom = LineString(points)
        if not geom.is_valid:
            return
        matches = [key for key in candidates if self.cities[key].intersects(geom)]
        if matches:
            for key in matches:
                self.counts[key] += 1
            self.rows.append((way.id, name, geom))


def ewkt(geom):
    return "SRID=4326;" + shapely.to_wkt(geom, rounding_precision=7)


def write_snapshot(path, rows):
    with path.open("wb") as file:
        with gzip.GzipFile(filename="", fileobj=file, mode="wb", mtime=0) as compressed:
            with io.TextIOWrapper(compressed, encoding="utf-8", newline="") as output:
                csv.writer(output).writerows(rows)
    print(f"{path.name}: {path.stat().st_size:,} bytes, sha256:{digest(path)}")


def main():
    parser = argparse.ArgumentParser(description="Build an offline IBGE/OSM pilot snapshot")
    parser.add_argument("--cache-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--version", type=int, required=True)
    parser.add_argument("--osm-pbf", type=Path, required=True, help="OSM extract covering both pilot cities")
    args = parser.parse_args()
    args.cache_dir.mkdir(parents=True, exist_ok=True)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    areas = load_areas(args.cache_dir)

    pbf = args.osm_pbf
    print(f"{pbf.name} sha256:{digest(pbf)}")

    ways = NamedWays({key: geom for key, level, _, _, geom in areas if level == "city"})
    ways.apply_file(str(pbf), locations=True)
    if not all(ways.counts.values()):
        raise ValueError(f"OSM extract does not cover both cities: {ways.counts}")
    print(f"{len(ways.rows):,} distinct OSM ways; ways intersecting each city: {ways.counts}")
    print(f"{ways.incomplete:,} incomplete named routable ways skipped across the source extract")

    write_snapshot(
        args.output_dir / f"v{args.version}-admin-areas.csv.gz",
        ((key, level, name, parent, ewkt(geom)) for key, level, name, parent, geom in areas),
    )
    write_snapshot(
        args.output_dir / f"v{args.version}-street-ways.csv.gz",
        ((f"osm-way-{key}", name, ewkt(geom)) for key, name, geom in sorted(ways.rows)),
    )


if __name__ == "__main__":
    main()
