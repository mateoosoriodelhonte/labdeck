# ADR-0007: Bound observability to exact-owned Docker resources

## Status

Accepted

## Date

2026-08-27

## Context

Students need service status, logs, resource use, endpoints, topology, and disk guidance. Docker can
also expose unrelated local resources, unbounded log output, and slow streaming calls. LabDeck must
not turn a selected-lab view into general Docker discovery or let observation block Stop.

Docker's per-volume inspect response does not include a byte size. Docker-wide disk discovery can
reveal unrelated resources. A helper container would add mutation and image trust to a read path.

## Decision

Use only active journal records for the selected lab. Recheck exact ownership labels and immutable
Engine IDs before each Docker read. Build public responses from explicit data-transfer records that
omit Engine IDs, ownership tokens, private paths, environment values, commands, IP addresses, and
unverified image identifiers.

Capture the lab revision and exact journal records while holding the per-lab lock. Release the lock
before Docker observation. Run the observation through a four-worker bounded executor with a
five-second aggregate deadline. Recheck the revision before returning. Stop and runtime failure can
therefore proceed, and an old request cannot return a stale snapshot.

Configure new containers with Docker's `local` log driver, three compressed files, and a 10 MiB
limit per file. Bound history by time, lines, bytes, and wait time. Bound streams by initial tail,
lifetime, total output, queue size, queue bytes, lab count, and process count. Close streams on
disconnect, timeout, overflow, Stop, runtime failure, replacement, and application shutdown. Treat
close as a barrier for an active frame handler. Render Docker timestamps and replace control and
format characters.

Report exact image and writable-layer sizes when available. Report named-volume size as unavailable.
Do not call Docker-wide disk discovery, run a measurement container, prune resources, or delete
images or named volumes. Return a read-only cleanup plan that describes only normal Stop cleanup.

## Alternatives considered

### Docker-wide system disk report

This can report volume sizes, but it inspects unrelated local resources and can return a large body.
Rejected because selected-lab visibility is more important than an incomplete global estimate.

### Helper container for `du`

This can measure one volume, but a read request would create and run another container. It also
needs a trusted image and access to the student's retained data. Rejected because observation must
stay read-only.

### Hold the lab lock during Docker reads

This gives a simple snapshot, but one slow Docker call can delay Stop. Rejected. Revision capture
and recheck gives a safe result without blocking lifecycle control.

### Unbounded log follow

This is simple, but a noisy service or disconnected client can consume memory and worker slots.
Rejected. Every history and stream path has fixed time, count, byte, and concurrency bounds.

## Consequences

- Observation is safe to poll and cannot block Stop longer than lock acquisition around local data.
- A concurrent lab change returns a conflict. The client must refresh.
- Volume size remains unknown and is shown as such.
- Log history is intentionally incomplete. Students see a clear truncation or stream-end reason.
- Images and retained named volumes are never presented as automatically reclaimable.
