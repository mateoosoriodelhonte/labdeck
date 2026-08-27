ALTER TABLE test_run ADD COLUMN lab_revision INTEGER NOT NULL DEFAULT 0
    CHECK (lab_revision >= 0);

ALTER TABLE test_run ADD COLUMN service_id TEXT NOT NULL DEFAULT 'legacy'
    CHECK (
        length(service_id) BETWEEN 1 AND 32
        AND substr(service_id, 1, 1) GLOB '[a-z]'
        AND service_id NOT GLOB '*[^a-z0-9-]*'
    );

ALTER TABLE test_run ADD COLUMN test_plan_sha256 TEXT NOT NULL
    DEFAULT 'sha256:0000000000000000000000000000000000000000000000000000000000000000'
    CHECK (
        length(test_plan_sha256) = 71
        AND substr(test_plan_sha256, 1, 7) = 'sha256:'
        AND substr(test_plan_sha256, 8) NOT GLOB '*[^0-9a-f]*'
    );

ALTER TABLE test_run ADD COLUMN outcome_reason TEXT NOT NULL DEFAULT 'LEGACY'
    CHECK (
        outcome_reason IN (
            'EXIT_ZERO',
            'NON_ZERO_EXIT',
            'SERVICE_NOT_ACTIVE',
            'DOCKER_ERROR',
            'RESULT_UNAVAILABLE',
            'LAB_CHANGED',
            'USER_CANCELLED',
            'LAB_STOPPED',
            'APPLICATION_SHUTDOWN',
            'TIMEOUT',
            'LEGACY'
        )
        AND (
            (outcome_reason = 'LEGACY'
                AND lab_revision = 0
                AND service_id = 'legacy'
                AND test_plan_sha256 =
                    'sha256:0000000000000000000000000000000000000000000000000000000000000000')
            OR (status = 'PASSED' AND outcome_reason = 'EXIT_ZERO')
            OR (status = 'FAILED' AND outcome_reason = 'NON_ZERO_EXIT')
            OR (status = 'TIMED_OUT' AND outcome_reason = 'TIMEOUT')
            OR (status = 'CANCELLED' AND outcome_reason IN (
                'USER_CANCELLED',
                'LAB_STOPPED',
                'APPLICATION_SHUTDOWN'
            ))
            OR (status = 'ERROR' AND outcome_reason IN (
                'SERVICE_NOT_ACTIVE',
                'DOCKER_ERROR',
                'RESULT_UNAVAILABLE',
                'LAB_CHANGED'
            ))
        )
    );
