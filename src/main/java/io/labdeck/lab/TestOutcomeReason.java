package io.labdeck.lab;

public enum TestOutcomeReason {
    EXIT_ZERO,
    NON_ZERO_EXIT,
    SERVICE_NOT_ACTIVE,
    DOCKER_ERROR,
    RESULT_UNAVAILABLE,
    LAB_CHANGED,
    USER_CANCELLED,
    LAB_STOPPED,
    APPLICATION_SHUTDOWN,
    TIMEOUT,
    LEGACY
}
