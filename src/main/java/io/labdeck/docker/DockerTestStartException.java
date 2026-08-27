package io.labdeck.docker;

public final class DockerTestStartException extends RuntimeException {

    public enum Reason {
        LAB_NOT_RUNNING,
        LAB_CHANGED,
        RESTART_REQUIRED,
        ALREADY_RUNNING
    }

    private final Reason reason;

    public DockerTestStartException(Reason reason) {
        super(message(reason));
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    private static String message(Reason reason) {
        return switch (reason) {
            case LAB_NOT_RUNNING -> "Start the lab before running its assignment tests.";
            case LAB_CHANGED -> "The lab changed before the assignment test could start.";
            case RESTART_REQUIRED ->
                "Restart the lab after a manifest change or application restart before running tests.";
            case ALREADY_RUNNING -> "This lab already has an active assignment test.";
        };
    }
}
