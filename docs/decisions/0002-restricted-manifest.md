# ADR-0002: Use a closed restricted manifest

## Status

Accepted

## Date

2026-08-26

## Context

LabDeck imports course manifests from local files and URLs. These files are untrusted. Docker offers
many options that can expose the host, including privileged mode, host namespaces, devices,
capabilities, bind mounts, and the Docker Engine socket. A general Docker Compose pass-through would
make those options hard to review and impossible to secure with one policy.

Students also need a stable preview before LabDeck downloads images or creates resources. The same
manifest must produce the same plan even when YAML maps use a different order.

## Decision

Use one closed, versioned v1 schema. Parse YAML into a bounded tree, reject duplicate keys and unknown
fields, and then apply semantic validation. Do not bind an untrusted document directly to Docker
client options.

The manifest can name images, project-local builds, container argv, environment values, local ports,
health checks, named volumes, test argv, and resource limits. It cannot contain a host workspace path.
That path comes from a separate local folder selection flow and is always mounted at `/workspace`.

Commands use direct argv lists. V1 rejects scalar commands and shell wrappers. Image references need
an explicit non-`latest` tag or a SHA-256 digest. Build paths are relative to the selected project.
Resolved build paths cannot use symbolic links or leave the project.

Compile the validated model into a canonical plan. Sort all unordered fields and use a SHA-256
fingerprint of the semantic model. Do not include timestamps, random values, Docker daemon state, or
host paths in this preflight plan.

## Alternatives considered

### Accept Docker Compose files

Compose is familiar and broad. Its host access options are larger than LabDeck needs, and future
Compose fields could expand the attack surface. This option is rejected.

### Use an image allowlist

An official-image-only list is simple, but it blocks normal course and school images. It also does
not make allowed images trustworthy. V1 instead validates reference syntax, blocks credentials and
`latest`, previews downloads, and applies the same container restrictions to every image.

### Allow shell command strings

Shell strings match some Docker examples, but they add quoting rules and shell-specific behavior.
Direct argv is deterministic and easier to review. Shell strings are rejected in v1.

## Consequences

- Unsupported Docker features fail closed with stable problem codes and JSON Pointer paths.
- The schema is smaller than Docker Compose and needs explicit version changes for new fields.
- Course authors must write command arrays such as `["pytest", "-q"]`.
- Image tags can still move upstream. LabDeck must store the resolved digest after a pull.
- A container can still change files in its approved workspace. The plan review and later threat model
  must state this limit clearly.
