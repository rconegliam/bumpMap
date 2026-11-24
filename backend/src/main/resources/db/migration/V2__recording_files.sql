CREATE TABLE recording_files (
    recording_id uuid NOT NULL,
    kind text NOT NULL CHECK (kind IN ('csv', 'ndjson')),
    file_name varchar(120) NOT NULL,
    part_count integer NOT NULL CHECK (part_count > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (recording_id, kind)
);

CREATE TABLE recording_file_parts (
    recording_id uuid NOT NULL,
    kind text NOT NULL,
    part_number integer NOT NULL CHECK (part_number >= 0),
    contents bytea NOT NULL,
    PRIMARY KEY (recording_id, kind, part_number),
    FOREIGN KEY (recording_id, kind) REFERENCES recording_files ON DELETE CASCADE
);

CREATE VIEW completed_recording_files AS
SELECT files.*
FROM recording_files AS files
WHERE files.part_count = (
    SELECT count(*) FROM recording_file_parts AS parts
    WHERE parts.recording_id = files.recording_id AND parts.kind = files.kind
);
