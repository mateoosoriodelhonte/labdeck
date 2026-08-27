package io.labdeck.lab;

public final class TestRunCoordinatorException extends RuntimeException {

    public enum Reason {
        TEST_NOT_CONFIGURED,
        TEST_ALREADY_RUNNING,
        PROCESS_LIMIT_REACHED,
        RUNNER_UNAVAILABLE,
        TEST_RUN_NOT_FOUND
    }

    private final Reason reason;

    public TestRunCoordinatorException(Reason reason) {
        super(message(reason));
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    private static String message(Reason reason) {
        return switch (reason) {
            case TEST_NOT_CONFIGURED -> "This manifest does not define an assignment test.";
            case TEST_ALREADY_RUNNING -> "This lab already has an active assignment test.";
            case PROCESS_LIMIT_REACHED -> "Two assignment tests are already active in this LabDeck process.";
            case RUNNER_UNAVAILABLE -> "The bounded assignment test worker is not available.";
            case TEST_RUN_NOT_FOUND -> "No assignment test run matches this lab and run ID.";
        };
    }
}
