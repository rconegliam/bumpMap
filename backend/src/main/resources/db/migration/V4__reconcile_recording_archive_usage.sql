-- V3 could miss parts committed between its backfill and trigger installation.
-- Acquire the same row lock as the writer before taking a new statement snapshot.
SELECT singleton FROM recording_archive_usage WHERE singleton FOR UPDATE;

UPDATE recording_archive_usage
SET charged_bytes = (
    SELECT COALESCE(sum(octet_length(contents)::bigint + 4096), 0)
    FROM recording_file_parts
)
WHERE singleton;
