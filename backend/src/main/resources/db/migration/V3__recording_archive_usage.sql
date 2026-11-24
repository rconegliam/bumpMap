CREATE TABLE recording_archive_usage (
    singleton boolean PRIMARY KEY CHECK (singleton),
    charged_bytes bigint NOT NULL CHECK (charged_bytes >= 0)
);

-- Charge each part for its payload bytes and a fixed allowance for table and index storage.
INSERT INTO recording_archive_usage
SELECT true, COALESCE(sum(octet_length(contents)::bigint + 4096), 0)
FROM recording_file_parts;

CREATE FUNCTION account_for_recording_file_part() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        UPDATE recording_archive_usage
        SET charged_bytes = charged_bytes + octet_length(NEW.contents) + 4096
        WHERE singleton;
    ELSIF TG_OP = 'DELETE' THEN
        UPDATE recording_archive_usage
        SET charged_bytes = charged_bytes - octet_length(OLD.contents) - 4096
        WHERE singleton;
    END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER account_for_recording_file_part_changes
AFTER INSERT OR DELETE ON recording_file_parts
FOR EACH ROW EXECUTE FUNCTION account_for_recording_file_part();
