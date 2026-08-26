CREATE UNIQUE INDEX lab_id_project_idx ON lab(id, project_id);

CREATE TABLE docker_resource (
    ownership_token TEXT PRIMARY KEY CHECK (
        length(ownership_token) = 32
        AND ownership_token NOT GLOB '*[^a-f0-9]*'
    ),
    lab_id TEXT NOT NULL,
    project_id TEXT NOT NULL,
    resource_type TEXT NOT NULL CHECK (resource_type IN ('CONTAINER', 'NETWORK', 'VOLUME')),
    logical_name TEXT NOT NULL CHECK (
        length(logical_name) BETWEEN 1 AND 32
        AND logical_name GLOB '[a-z]*'
        AND logical_name NOT GLOB '*[^a-z0-9-]*'
    ),
    engine_id TEXT CHECK (engine_id IS NULL OR length(engine_id) BETWEEN 1 AND 255),
    lifecycle_state TEXT NOT NULL CHECK (lifecycle_state IN ('RESERVED', 'ACTIVE', 'REMOVED')),
    created_at_epoch_ms INTEGER NOT NULL CHECK (created_at_epoch_ms >= 0),
    updated_at_epoch_ms INTEGER NOT NULL CHECK (updated_at_epoch_ms >= created_at_epoch_ms),
    CHECK (
        (lifecycle_state = 'RESERVED' AND engine_id IS NULL)
        OR (lifecycle_state = 'ACTIVE' AND engine_id IS NOT NULL)
        OR lifecycle_state = 'REMOVED'
    ),
    FOREIGN KEY (lab_id, project_id) REFERENCES lab(id, project_id) ON DELETE RESTRICT
) STRICT;

CREATE UNIQUE INDEX docker_resource_open_logical_idx
    ON docker_resource(lab_id, resource_type, logical_name)
    WHERE lifecycle_state IN ('RESERVED', 'ACTIVE');

CREATE UNIQUE INDEX docker_resource_open_engine_idx
    ON docker_resource(resource_type, engine_id)
    WHERE lifecycle_state = 'ACTIVE';

CREATE INDEX docker_resource_lab_state_idx
    ON docker_resource(lab_id, lifecycle_state, resource_type, logical_name);
