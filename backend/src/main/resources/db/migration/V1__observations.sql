CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE road_observations (
    id uuid PRIMARY KEY,
    feature jsonb NOT NULL,
    geometry geometry(Geometry, 4326) NOT NULL
);

CREATE INDEX road_observations_geometry_idx ON road_observations USING gist (geometry);
