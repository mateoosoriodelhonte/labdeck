ALTER TABLE lab_runtime_failure RENAME TO lab_runtime_failure_v3;

CREATE TABLE lab_runtime_failure (
    lab_id TEXT PRIMARY KEY,
    lab_revision INTEGER NOT NULL CHECK (lab_revision >= 0),
    failure_code TEXT NOT NULL CHECK (
        failure_code IN (
            'DOCKER_UNAVAILABLE',
            'DOCKER_STORAGE_FULL',
            'IMAGE_PULL_FAILED',
            'HOST_PORT_IN_USE',
            'CONTAINER_START_FAILED',
            'CONTAINER_EXITED',
            'HEALTHCHECK_UNHEALTHY',
            'STARTUP_TIMEOUT',
            'OWNERSHIP_MISMATCH',
            'CLEANUP_INCOMPLETE'
        )
    ),
    service_id TEXT CHECK (service_id IS NULL OR length(service_id) BETWEEN 1 AND 32),
    occurred_at_epoch_ms INTEGER NOT NULL CHECK (occurred_at_epoch_ms >= 0),
    cleanup_incomplete INTEGER NOT NULL CHECK (cleanup_incomplete IN (0, 1)),
    FOREIGN KEY (lab_id) REFERENCES lab(id) ON DELETE CASCADE
) STRICT;

INSERT INTO lab_runtime_failure (
    lab_id,
    lab_revision,
    failure_code,
    service_id,
    occurred_at_epoch_ms,
    cleanup_incomplete
)
SELECT
    lab_id,
    lab_revision,
    failure_code,
    service_id,
    occurred_at_epoch_ms,
    cleanup_incomplete
FROM lab_runtime_failure_v3;

DROP TABLE lab_runtime_failure_v3;

CREATE INDEX lab_runtime_failure_time_idx
    ON lab_runtime_failure(occurred_at_epoch_ms DESC, lab_id DESC);
