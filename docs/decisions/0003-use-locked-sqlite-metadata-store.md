# ADR-0003: Use a locked SQLite metadata store

## Status

Accepted

## Date

2026-08-26

## Context

LabDeck must preserve imported lab records, lifecycle state, and bounded test history without a
server, account, or cloud service. Student project files must stay in the selected workspace.
SQLite allows one local file, but it permits only one writer at a time and disables foreign-key
checks by default.

The validated manifest plan can contain environment values. Its current fingerprint depends on
those values. Storing that fingerprint could expose a low-entropy credential to an offline guessing
attack.

## Decision

Use one SQLite database under `LABDECK_DATA_DIR`, which defaults to `~/.labdeck`. Use Flyway as the
only schema migration tool. Disable Flyway clean and automatic baseline operations.

Hold an adjacent process lock for the life of the application. Use a connection pool with a maximum
of one connection, a five-second busy timeout, the rollback journal, full synchronous writes, and
foreign-key checks on every connection. Run SQLite quick and foreign-key checks after migration.
Use owner-only permissions for the data directory, database, and lock file when POSIX permissions
are available.

Store only allowlisted lab metadata: lab ID, project ID, name, manifest version, selected workspace
path, lifecycle state, revision, and timestamps. Do not store the manifest, its fingerprint,
environment values, student files, analytics, or telemetry. Use compare-and-set revisions for state
changes so a stale operation cannot overwrite a newer result.

Store test timestamp, status, duration, optional exit code, and separate standard output and error.
The combined output limit is 65,536 UTF-8 bytes. Truncation cannot split a character. LabDeck does
not scan student source files. The persistence adapter accepts only output made by the test-output
sanitizer. The sanitizer removes the selected workspace path, known sensitive manifest values,
common credential assignments, and bearer tokens before it applies the byte limit. A later test
runner must give the sanitizer all known sensitive environment values.

## Alternatives considered

### Use the write-ahead log

The write-ahead log can improve concurrent access. V1 has one local user and one connection, so that
benefit does not justify sidecar and checkpoint recovery work. The rollback journal is simpler.

### Persist the deterministic manifest fingerprint

The fingerprint can detect a changed manifest. It also depends on environment values and could make
a guessed credential testable. It is not stored. A later structural fingerprint must exclude
sensitive values before it can enter SQLite.

### Use an in-memory database

An in-memory database is easy to test but cannot preserve records after restart. Integration tests
therefore use temporary file-backed databases and reopen the same file.

## Consequences

- Imported labs and test history survive application restarts.
- A second LabDeck process fails closed instead of sharing one database.
- Lifecycle writes detect stale revisions.
- SQLite constraints reject orphan history, invalid states, and oversized output.
- The selected workspace path is local metadata. Diagnostic text must redact it.
- Arbitrary test output can contain text chosen by a test process. The test runner must scrub known
  sensitive values. Pattern scrubbing adds defense in depth, but no generic filter can prove that
  arbitrary output contains no secret.
