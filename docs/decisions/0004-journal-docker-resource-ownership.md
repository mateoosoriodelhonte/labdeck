# ADR-0004: Journal Docker resource ownership

## Status

Accepted

## Date

2026-08-26

## Context

LabDeck must create Docker containers, networks, and persistent volumes. Normal stop and failed-start
cleanup must leave unrelated Docker workloads untouched. Docker labels are useful discovery data,
but another local Docker client can copy them. A name can also be reused after a resource is removed.
Labels and names alone are therefore not enough to authorize a mutation.

A bind-mount path is evaluated by the Docker daemon. A remote daemon could interpret a selected
local path as a different directory. Docker images can also declare `VOLUME` targets that create
anonymous, unlabeled volumes unless LabDeck covers each target explicitly.

## Decision

Use docker-java 3.7.1 with its shaded zero-dependency transport. V1 accepts only a local Unix socket
or Windows named pipe and uses Docker Engine API 1.44. Use a public-only client configuration. It
does not read Docker registry credentials. A confirmed public pull always supplies an empty auth
object.

Before each Docker create call, write a resource reservation to SQLite. The reservation contains a
random 128-bit ownership token, lab ID, project ID, resource type, logical name, and timestamps.
Every created resource receives those values as static labels. After creation, inspect the resource
and store the full Engine ID. A later start, stop, inspect, or removal must use that stored ID and
must recheck every ownership label. A stale ID or mismatched label fails closed. It never falls back
to a name or broad label search.

The one narrow recovery case is a create call with an ambiguous response. While the journal row is
still reserved and has no Engine ID, LabDeck may search for the exact random token. It accepts one
exact match, rejects more than one, and stores the returned ID. It does not retry the create blindly.

Use one internal, non-attachable bridge network per running lab. Pre-create every manifest volume
with the local driver and ownership labels. Preserve named volumes during normal stop, cancellation,
and failed-start cleanup. Never call a Docker prune endpoint. Never remove pulled images.

Resolve the selected workspace to a real directory and record its filesystem identity. Recheck that
identity immediately before every container create call. Use a structured bind mount with `rprivate`
propagation. Reject a named-volume target that overlaps the workspace. Inspect each immutable image
ID and reject a container create when an image-declared volume target lacks an explicit LabDeck
mount.

Container removal uses the stored ID with `force=false` and `removeVolumes=false`. Network removal
fails when any endpoint remains attached. Normal stop removes journaled containers and the empty
lab network, then verifies that persistent volumes still match the journal.

## Alternatives considered

### Use labels as the full ownership check

This is simple, but labels are user-controlled metadata. A foreign resource can copy them. It is
rejected as an authorization rule.

### Use Docker Compose as the ownership database

Compose applies labels and groups resources, but LabDeck still needs exact recovery records and
policy checks before each Engine mutation. Compose metadata is not a replacement for the local
journal.

### Allow remote Docker endpoints

A remote daemon cannot safely use a local selected workspace path. Remote TCP, HTTP, HTTPS, and SSH
endpoints are rejected in v1.

## Consequences

- A SQLite row is an ownership hint, not authority by itself. Engine inspection must also match.
- A process that controls the Docker socket remains host-powerful. LabDeck prevents its own broad
  cleanup mistakes; it cannot defend the host from another Docker administrator.
- Internal networks reduce unintended outbound access. A later outbound-network feature needs an
  explicit trust and user-consent design.
- Named volumes survive stop and failed startup. A later disk-management feature must add a separate
  reference check and confirmation flow before deletion.
- Resource limits, port publishing, collision handling, and health readiness remain in issue #6.
- Project-local image builds and their cancellation flow remain in issue #10.
