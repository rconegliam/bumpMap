CREATE TABLE app_installations (
    id uuid PRIMARY KEY,
    token_digest bytea UNIQUE NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    usage_day date NOT NULL DEFAULT CURRENT_DATE,
    requests integer NOT NULL DEFAULT 0,
    bytes bigint NOT NULL DEFAULT 0
);

CREATE INDEX app_installations_created_at_idx ON app_installations (created_at);
