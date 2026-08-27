# ADR-0005: Local ports, lab budgets, and health readiness

## Status

Accepted

## Date

2026-08-26

## Context

LabDeck must show useful localhost endpoints, stop one assignment from taking the whole laptop, and
report Running only after required services are ready. The manifest is untrusted. Docker publishes
ports on all host addresses unless the client selects an address. Docker internal bridge networks
do not produce usable host port mappings. Docker releases before 28 also allowed some hosts on the
same layer-2 network to reach ports published to loopback.

Docker applies memory and CPU limits to containers, not to a LabDeck lab object. Applying the full
top-level budget to every service would multiply the requested budget. Docker image health checks
also have timing that the manifest does not control.

Sources:

- [Docker port publishing and gateway modes](https://docs.docker.com/engine/network/port-publishing/)
- [Docker bridge network driver](https://docs.docker.com/engine/network/drivers/bridge/)
- [Docker resource constraints](https://docs.docker.com/engine/containers/resource_constraints/)

## Decision

Create one dedicated, non-attachable bridge network for each running lab. It is not an internal
network. The manifest cannot select host networking or another network. Normal Docker bridge
outbound access remains available and is documented. V1 does not claim to be an offline or
hostile-code sandbox.

Publish only manifest TCP ports. Bind each one to `127.0.0.1`. An omitted host port uses Docker's
dynamic allocation. Never use publish-all or publish an image `EXPOSE` entry by itself. Require
Docker Engine 28 or newer before any port-bearing lab starts. Verify every planned binding before
start and report the full actual mapping after start. A fixed-port conflict is a typed safe failure
that names the service and port.

Treat top-level `resources` as one lab budget. Divide its bytes and nano-CPUs across services in
stable service-name order. Remainder units go to the first services, so the container ceilings add
up exactly to the requested budget. Set each container's memory and swap limit to the same value,
which disables swap beyond that memory ceiling. Set nano-CPUs and keep the kernel OOM killer
enabled. Reject an Engine without the required limit capabilities.

A manifest health check replaces the image check with exact Docker `CMD` argv and bounded timing.
Without a manifest check, preserve an image check. A health-managed service is ready only when
Docker reports `healthy`. A service with no health policy must stay running for two seconds and is
never described as healthy. Reinspect every service before storing Running. Use a monotonic
readiness deadline with a 30-second floor and a 15-minute ceiling. Unknown image-health timing uses
the ceiling.

After Running, poll exact journaled container IDs every two seconds. An exit or unhealthy state
claims the exact running revision, cleans verified ephemeral resources, and stores a safe failure.
Three Docker inspection failures store a Docker-unavailable state without cleanup. An ownership
mismatch also fails without unverified cleanup. Startup cancellation signals the readiness loop
before it waits for the per-lab lifecycle lock.

## Alternatives considered

### Keep the internal bridge

This blocks normal outbound access, but Docker does not create a usable published host mapping for
containers on that network. It cannot meet the localhost endpoint requirement.

### Apply the top-level limit to every service

This is simple, but a twelve-service manifest could use twelve times the stated budget. It does not
meet the per-lab resource requirement.

### Report Running when containers start

A running process can still be unready or unhealthy. This would give students a false success
state and is rejected.

## Consequences

- Manifest endpoints work on localhost and are not published on other host interfaces.
- Each lab has a hard aggregate container ceiling, but v1 does not support unequal service shares.
- A lab can use normal Docker outbound networking. Users must not treat it as a malware sandbox.
- Persistent volumes survive readiness failures and runtime failures.
- Safe durable messages omit raw health output and Docker error text. Detailed logs remain a
  separate user-requested feature.
