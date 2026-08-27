package io.labdeck.lab;

import java.time.Instant;

public record TestRunSnapshot(
        String id,
        String labId,
        long labRevision,
        String service,
        String testPlanSha256,
        Instant startedAt,
        Instant completedAt,
        String status,
        String outcomeReason,
        long durationMillis,
        Integer exitCode,
        String stdout,
        String stderr,
        boolean stdoutTruncated,
        boolean stderrTruncated,
        boolean canCancel) {}
