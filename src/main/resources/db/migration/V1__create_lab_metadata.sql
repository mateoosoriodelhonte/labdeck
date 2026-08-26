CREATE TABLE lab (
    id TEXT PRIMARY KEY,
    project_id TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL CHECK (length(name) BETWEEN 1 AND 100),
    manifest_version INTEGER NOT NULL CHECK (manifest_version = 1),
    workspace_path TEXT NOT NULL UNIQUE CHECK (length(workspace_path) BETWEEN 1 AND 4096),
    lifecycle_state TEXT NOT NULL CHECK (
        lifecycle_state IN ('IMPORTED', 'STARTING', 'RUNNING', 'STOPPING', 'STOPPED', 'FAILED')
    ),
    revision INTEGER NOT NULL CHECK (revision >= 0),
    created_at_epoch_ms INTEGER NOT NULL CHECK (created_at_epoch_ms >= 0),
    updated_at_epoch_ms INTEGER NOT NULL CHECK (updated_at_epoch_ms >= created_at_epoch_ms)
) STRICT;

CREATE INDEX lab_updated_at_idx ON lab(updated_at_epoch_ms DESC, id DESC);

CREATE TABLE test_run (
    id TEXT PRIMARY KEY,
    lab_id TEXT NOT NULL,
    recorded_at_epoch_ms INTEGER NOT NULL CHECK (recorded_at_epoch_ms >= 0),
    status TEXT NOT NULL CHECK (
        status IN ('PASSED', 'FAILED', 'ERROR', 'CANCELLED', 'TIMED_OUT')
    ),
    duration_ms INTEGER NOT NULL CHECK (duration_ms BETWEEN 0 AND 86400000),
    exit_code INTEGER CHECK (exit_code BETWEEN -2147483648 AND 2147483647),
    stdout TEXT NOT NULL,
    stderr TEXT NOT NULL,
    stdout_truncated INTEGER NOT NULL CHECK (stdout_truncated IN (0, 1)),
    stderr_truncated INTEGER NOT NULL CHECK (stderr_truncated IN (0, 1)),
    CHECK (length(CAST(stdout AS BLOB)) + length(CAST(stderr AS BLOB)) <= 65536),
    CHECK (
        (status = 'PASSED' AND exit_code IS NOT NULL AND exit_code = 0)
        OR (status = 'FAILED' AND exit_code IS NOT NULL AND exit_code != 0)
        OR status IN ('ERROR', 'CANCELLED', 'TIMED_OUT')
    ),
    FOREIGN KEY (lab_id) REFERENCES lab(id) ON DELETE RESTRICT
) STRICT;

CREATE INDEX test_run_lab_time_idx ON test_run(lab_id, recorded_at_epoch_ms DESC, id DESC);
