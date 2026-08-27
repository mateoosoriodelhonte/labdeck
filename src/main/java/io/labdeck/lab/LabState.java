package io.labdeck.lab;

import java.util.EnumSet;
import java.util.Set;

public enum LabState {
    IMPORTED,
    STARTING,
    RUNNING,
    STOPPING,
    STOPPED,
    FAILED;

    public boolean canTransitionTo(LabState next) {
        if (next == null || next == this) {
            return false;
        }
        return allowedNextStates().contains(next);
    }

    private Set<LabState> allowedNextStates() {
        return switch (this) {
            case IMPORTED -> EnumSet.of(STARTING, STOPPED);
            case STARTING -> EnumSet.of(RUNNING, STOPPING, FAILED);
            case RUNNING -> EnumSet.of(STOPPING, FAILED);
            case STOPPING -> EnumSet.of(STOPPED, FAILED);
            case STOPPED -> EnumSet.of(STARTING);
            case FAILED -> EnumSet.of(STARTING, STOPPING);
        };
    }
}
