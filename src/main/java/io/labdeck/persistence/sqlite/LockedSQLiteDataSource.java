package io.labdeck.persistence.sqlite;

import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.concurrent.atomic.AtomicBoolean;

public final class LockedSQLiteDataSource extends HikariDataSource {

    private final FileChannel lockChannel;
    private final FileLock lock;
    private final AtomicBoolean closed = new AtomicBoolean();

    LockedSQLiteDataSource(FileChannel lockChannel, FileLock lock) {
        this.lockChannel = lockChannel;
        this.lock = lock;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        RuntimeException closeFailure = null;
        try {
            super.close();
        } catch (RuntimeException exception) {
            closeFailure = exception;
        }
        IOException lockFailure = null;
        try {
            lock.release();
        } catch (IOException exception) {
            lockFailure = exception;
        }
        try {
            lockChannel.close();
        } catch (IOException exception) {
            if (lockFailure == null) {
                lockFailure = exception;
            } else {
                lockFailure.addSuppressed(exception);
            }
        }
        if (lockFailure != null) {
            if (closeFailure != null) {
                lockFailure.addSuppressed(closeFailure);
            }
            throw new IllegalStateException("LabDeck could not release its database lock.", lockFailure);
        }
        if (closeFailure != null) {
            throw closeFailure;
        }
    }
}
