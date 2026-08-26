package io.labdeck.persistence.sqlite;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Set;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

public final class SQLiteDataSourceFactory {

    public static final String DATABASE_FILENAME = "labdeck.db";
    public static final String LOCK_FILENAME = "labdeck.lock";
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> FILE_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);

    public LockedSQLiteDataSource create(Path requestedDataDirectory) {
        if (requestedDataDirectory == null) {
            throw new IllegalArgumentException("The LabDeck data directory is required.");
        }

        Path directory = requestedDataDirectory.toAbsolutePath().normalize();
        if (directory.getParent() == null) {
            throw new IllegalArgumentException("The LabDeck data directory cannot be a filesystem root.");
        }
        try {
            if (Files.isSymbolicLink(directory)) {
                throw new IllegalArgumentException("The LabDeck data directory cannot be a symbolic link.");
            }
            Files.createDirectories(directory);
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("The LabDeck data path must be a directory.");
            }
            applyPosixPermissions(directory, DIRECTORY_PERMISSIONS);

            Path database = directory.resolve(DATABASE_FILENAME);
            if (Files.isSymbolicLink(database)) {
                throw new IllegalArgumentException("The LabDeck database cannot be a symbolic link.");
            }
            if (Files.notExists(database, LinkOption.NOFOLLOW_LINKS)) {
                Files.createFile(database);
            }
            if (!Files.isRegularFile(database, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("The LabDeck database must be a regular file.");
            }
            applyPosixPermissions(database, FILE_PERMISSIONS);
            Path lockPath = directory.resolve(LOCK_FILENAME);
            if (Files.isSymbolicLink(lockPath)) {
                throw new IllegalArgumentException("The LabDeck database lock cannot be a symbolic link.");
            }
            if (Files.notExists(lockPath, LinkOption.NOFOLLOW_LINKS)) {
                Files.createFile(lockPath);
            }
            if (!Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("The LabDeck database lock must be a regular file.");
            }
            applyPosixPermissions(lockPath, FILE_PERMISSIONS);
            FileChannel lockChannel = FileChannel.open(
                    lockPath, StandardOpenOption.WRITE);
            FileLock lock = tryLock(lockChannel);
            LockedSQLiteDataSource dataSource = sqliteDataSource(database, lockChannel, lock);
            verifyConnectionSettings(dataSource);
            return dataSource;
        } catch (IOException | SecurityException exception) {
            throw new IllegalStateException("LabDeck could not prepare its local data directory.", exception);
        }
    }

    private static LockedSQLiteDataSource sqliteDataSource(
            Path database, FileChannel lockChannel, FileLock lock) {
        SQLiteConfig config = new SQLiteConfig();
        config.enableLoadExtension(false);
        config.enforceForeignKeys(true);
        config.setBusyTimeout(5_000);
        config.setJournalMode(SQLiteConfig.JournalMode.DELETE);
        config.setSynchronous(SQLiteConfig.SynchronousMode.FULL);

        SQLiteDataSource sqliteDataSource = new SQLiteDataSource(config);
        sqliteDataSource.setUrl("jdbc:sqlite:" + database);

        LockedSQLiteDataSource pooledDataSource = new LockedSQLiteDataSource(lockChannel, lock);
        try {
            pooledDataSource.setDataSource(sqliteDataSource);
            pooledDataSource.setPoolName("labdeck-sqlite");
            pooledDataSource.setMaximumPoolSize(1);
            pooledDataSource.setMinimumIdle(1);
            pooledDataSource.setAutoCommit(true);
            pooledDataSource.setConnectionTimeout(5_000);
            pooledDataSource.setInitializationFailTimeout(5_000);
            return pooledDataSource;
        } catch (RuntimeException exception) {
            pooledDataSource.close();
            throw exception;
        }
    }

    private static FileLock tryLock(FileChannel lockChannel) throws IOException {
        try {
            FileLock lock = lockChannel.tryLock();
            if (lock == null) {
                lockChannel.close();
                throw new IllegalStateException("Another LabDeck process is using this data directory.");
            }
            return lock;
        } catch (OverlappingFileLockException exception) {
            lockChannel.close();
            throw new IllegalStateException("Another LabDeck process is using this data directory.", exception);
        } catch (IOException | RuntimeException exception) {
            try {
                lockChannel.close();
            } catch (IOException closeException) {
                exception.addSuppressed(closeException);
            }
            throw exception;
        }
    }

    private static void verifyConnectionSettings(LockedSQLiteDataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            if (pragmaInteger(connection, "foreign_keys") != 1) {
                throw new IllegalStateException("SQLite foreign-key enforcement is not active.");
            }
            if (!"delete".equalsIgnoreCase(pragmaText(connection, "journal_mode"))) {
                throw new IllegalStateException("SQLite rollback journaling is not active.");
            }
        } catch (SQLException | RuntimeException exception) {
            dataSource.close();
            throw new IllegalStateException("LabDeck could not verify its local database settings.", exception);
        }
    }

    private static int pragmaInteger(Connection connection, String pragma) throws SQLException {
        try (var statement = connection.createStatement();
                ResultSet result = statement.executeQuery("PRAGMA " + pragma)) {
            return result.next() ? result.getInt(1) : -1;
        }
    }

    private static String pragmaText(Connection connection, String pragma) throws SQLException {
        try (var statement = connection.createStatement();
                ResultSet result = statement.executeQuery("PRAGMA " + pragma)) {
            return result.next() ? result.getString(1) : null;
        }
    }

    private static void applyPosixPermissions(Path path, Set<PosixFilePermission> permissions) throws IOException {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(path, permissions);
        }
    }
}
