package io.labdeck.docker;

import java.time.Duration;

public interface DockerLogSubscription extends AutoCloseable {

    boolean await(Duration timeout) throws InterruptedException;

    boolean truncated();

    boolean failed();

    boolean closed();

    void onClose(Runnable listener);

    @Override
    void close();
}
