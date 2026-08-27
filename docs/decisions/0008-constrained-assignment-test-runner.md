# ADR-0008: Run bounded assignment tests inside the exact owned service

## Status

Accepted

## Date

2026-08-27

## Context

Students need to run an assignment test and see a durable result. The test manifest, Docker exec
stream, and test output are untrusted. Docker Engine can create and inspect an exec process, but it
does not provide an operation that kills only one exec process. Closing the attached stream does
not prove that the process stopped.

The API must not become a remote command endpoint. A changed manifest, restarted LabDeck process,
or replaced container must not silently change which command runs.

## Decision

Run only the test service, argv, working directory, and timeout from the validated `labdeck.yml`.
The mutation accepts only the reviewed lab revision and full manifest hash. It accepts no command,
service, environment, timeout, or working-directory override. Reject known shell wrappers in the
manifest and send the command to Docker as direct argv with no stdin, TTY, privilege, or environment
override.

Bind test execution to the exact active container in the resource journal. Verify its immutable
Engine ID, ownership labels, running state, exec container ID, argv, TTY state, and privilege state.
Keep the full manifest hash only in memory after a successful lab start. A manifest change or
LabDeck restart requires the student to restart the lab before running a test.

Allow one active test per lab and two per LabDeck process. Do not queue tests. Give every LabDeck
container an init process and a 256-process PID limit. Capture at most 128 KiB while draining the
Docker stream. Sanitize control characters, the selected workspace, known manifest environment
values, credential assignments, and bearer tokens. Persist at most 64 KiB across separate standard
output and error fields.

Persist the lab revision, service, a nonsecret test-plan digest, timestamp, status, outcome reason,
duration, optional exit code, bounded output, and per-stream truncation. Keep at most 100 results per
lab and 1,000 results in total.

A proved natural exit preserves the running lab. A user cancellation, timeout, or ambiguous Docker
exec error stops and cleans the exact lab because Docker cannot prove that only the exec process
stopped. Report `CANCELLED` or `TIMED_OUT` only after scoped cleanup succeeds. If cleanup cannot be
proved, report `ERROR` with `RESULT_UNAVAILABLE`. Never store an exec ID.

Return the current process-local run with bounded history. This lets a reloaded page restore status
polling and cancellation. Report `PERSISTING` while a terminal result is being written. If SQLite
cannot store that result, retain an in-memory
`RESULT_UNAVAILABLE` result and keep its lab and process slots reserved until restart. This prevents
a later run from hiding the lost result. After an application crash, LabDeck does not invent a
terminal result for the interrupted attempt.

## Alternatives considered

### Accept a command in the API request

This would make the local API an arbitrary command endpoint. Rejected. The request can select only
the reviewed lab and manifest revision.

### Close the exec attachment on cancel

This stops output delivery but does not prove that the process stopped. Rejected as a completion
rule. LabDeck closes the attachment and then stops the exact lab.

### Run tests in a new helper container

This would need a new image, mount, network, ownership, and cleanup contract. It could also differ
from the student's running service. Rejected for V1.

### Store the full manifest hash

The full hash includes environment values and could support offline guessing of a weak secret.
Rejected. The hash remains in process memory. SQLite stores only a digest of the nonsecret test
service, timeout, and argv.

## Consequences

- A browser cannot replace the assignment command.
- Test output and history are intentionally bounded and can be incomplete.
- Redaction is defense in depth. It cannot prove that arbitrary program output contains no secret.
- Cancel and timeout are disruptive. The student must restart the lab.
- An ambiguous Docker exec error is also disruptive because an untracked process might exist.
- A LabDeck restart requires a lab restart before the next test.
- A persistence failure blocks another test in that slot until LabDeck restarts.
- A process crash can leave no terminal history row for an active attempt.
