package io.labdeck.docker;

@FunctionalInterface
public interface CancellationToken {

    CancellationToken NONE = () -> false;

    boolean isCancellationRequested();

    default void throwIfCancellationRequested() {
        if (isCancellationRequested()) {
            throw new DockerOperationCancelledException();
        }
    }
}
