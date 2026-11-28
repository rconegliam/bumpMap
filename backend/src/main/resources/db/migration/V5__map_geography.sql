CREATE TABLE map_admin_areas (
    id text PRIMARY KEY,
    level text NOT NULL CHECK (level IN ('country', 'state', 'city')),
    name text NOT NULL,
    parent_id text REFERENCES map_admin_areas (id),
    geom geometry(MultiPolygon, 4326) NOT NULL
);

CREATE INDEX map_admin_areas_geom_idx ON map_admin_areas USING gist (geom);
CREATE INDEX map_admin_areas_geography_idx ON map_admin_areas USING gist ((geom::geography));

CREATE TABLE map_street_ways (
    id text PRIMARY KEY,
    name text NOT NULL,
    geom geometry(LineString, 4326) NOT NULL
);

CREATE INDEX map_street_ways_geom_idx ON map_street_ways USING gist (geom);
CREATE INDEX map_street_ways_geography_idx ON map_street_ways USING gist ((geom::geography));
CREATE INDEX map_street_ways_name_idx ON map_street_ways (name);
