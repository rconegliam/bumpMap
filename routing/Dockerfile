FROM ghcr.io/project-osrm/osrm-backend:v5.27.1@sha256:855614a38f464b0558a2ad6eaa7cb8c139f39887da9b38b485ce453c6e6e6124 AS graph
WORKDIR /data
COPY pilot.osm.pbf ./
RUN osrm-extract --threads 2 -p /opt/car.lua pilot.osm.pbf \
    && osrm-partition --threads 2 pilot.osrm \
    && osrm-customize --threads 2 pilot.osrm

FROM ghcr.io/project-osrm/osrm-backend:v5.27.1@sha256:855614a38f464b0558a2ad6eaa7cb8c139f39887da9b38b485ce453c6e6e6124
WORKDIR /data
COPY --chown=10001:10001 --from=graph /data/pilot.osrm* ./
USER 10001:10001
EXPOSE 5000
ENTRYPOINT ["osrm-routed", "--algorithm", "mld", "--ip", "0.0.0.0", "--port", "5000", "--threads", "2", "--mmap", "--verbosity", "NONE", "/data/pilot.osrm"]
