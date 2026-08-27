# Safe course packs and local builds

## Status

The direction and this exact design were approved on 2026-08-27.

Issue: [#10](https://github.com/mateoosoriodelhonte/labdeck/issues/10)

## Goal

LabDeck will provide five original local templates, portable ZIP course packs, and project-local
Docker image builds. A student must see the complete lab and build plan before any Docker build,
pull, or start action.

This work is limited to issue #10. It does not add a general archive browser, file manager,
registry client, or image cleanup tool.

## Non-goals

V1 will not:

- accept TAR, 7z, RAR, or nested archives;
- extract into an existing or non-empty destination;
- browse, rename, delete, or download arbitrary workspace files;
- accept browser-provided Docker build arguments, secrets, targets, platforms, tags, or network
  modes;
- read or send registry credentials;
- push images;
- use remote Docker endpoints;
- prune images or other Docker resources;
- promise that an untrusted Dockerfile is safe to run.

## Course-pack format

A course pack is one ZIP file. It has one fixed root directory named `labdeck-course-pack/`. The
root contains:

- `labdeck-pack.json`;
- `labdeck.yml`;
- `README.md`;
- only the project files listed in `labdeck-pack.json`.

`labdeck-pack.json` is a closed JSON object. It contains the format value
`labdeck-course-pack-v1` and a sorted list of relative file paths. It does not contain host paths,
credentials, LabDeck database data, Docker Engine IDs, or ownership tokens. Each listed file has a
SHA-256 digest. Every non-directory ZIP entry other than the descriptor itself must be listed.
Every listed entry must exist exactly once. The descriptor cannot list itself because its digest
would be self-referential.

Paths use `/` as the separator. They must be Unicode NFC, relative, and unique after normalization.
LabDeck rejects empty segments, `.`, `..`, backslashes, drive letters, URI-style paths, NUL bytes,
control characters, trailing spaces or dots, case-fold collisions, and paths that are too deep or
long. It rejects entries with a hidden path segment except the exact file `.gitignore`. It also
rejects common credential and key names, including `.env`, `.npmrc`, `.pypirc`, credential files,
private keys, and certificate-key files.

The archive has deterministic entry order, timestamps, compression settings, and normalized file
permissions. Exporting the same accepted inputs produces the same bytes.

## Import flow

The local Vue app sends one ZIP and one explicit destination path to the course-pack import API.
The endpoint is the only multipart mutation in V1. It still requires the session cookie, the
`X-LabDeck-CSRF` header, the loopback peer, an allowed `Host`, and an allowed `Origin`.

The server performs these steps:

1. Save the upload in a new owner-only staging directory.
2. Read the ZIP directory and validate all names, metadata, counts, and declared sizes before
   extraction.
3. Extract with exclusive file creation and without following links.
4. Count the actual bytes while extracting. Stop on any limit breach or metadata mismatch.
5. Reject symbolic links, hard links, devices, FIFOs, sockets, reparse-style entries, encrypted
   entries, split archives, data descriptors that defeat limits, duplicate entries, and nested
   archives.
6. Verify `labdeck-pack.json`, every declared SHA-256 digest, the manifest schema, manifest policy,
   and the compiled resource plan inside staging.
7. Resolve the requested destination with a new destination policy that reuses the existing
   sensitive-root rules. Require an existing real parent and a destination leaf that does not
   exist. Create that leaf exclusively and recheck its identity before each copy step.
8. Copy accepted files with exclusive creation. If any copy fails, remove only files and the new
   destination created by this import.
9. Import the installed workspace through the existing lab service and return the full lab detail
   and plan. Do not call Docker.
10. Remove the private staging directory.

The import fails before destination changes if validation fails. It never merges with or overwrites
an existing project. A failed copy removes only the destination leaf that this request created.

### Course-pack limits

- Maximum request size: 17 MiB.
- Maximum ZIP file size: 16 MiB.
- Maximum entries: 1,024, including directories.
- Maximum extracted total: 64 MiB.
- Maximum one file: 16 MiB.
- Maximum compression ratio for one file or the total: 100:1.
- Maximum normalized relative path: 240 UTF-8 bytes.
- Maximum path depth: 16 segments.
- Maximum `labdeck-pack.json`: 256 KiB.
- Maximum `labdeck.yml`: the existing 256 KiB limit.

All checks use actual streamed bytes. ZIP header values are only early rejection hints.

## Export flow

Export is a `POST` action because it reads selected project content into a downloadable file. The
request contains only the expected lab revision and manifest SHA-256. It requires CSRF and the
normal local API checks.

LabDeck computes the export set. The browser cannot submit arbitrary file paths. The set is:

- `labdeck.yml`;
- `README.md`, or a small generated README when no regular README exists;
- files already approved by an imported or bundled `labdeck-pack.json`;
- regular files inside each validated project-local build context.

LabDeck applies the same path, secret-name, file-type, file-count, and byte limits used by import.
It rejects links, special files, unstable files, and changes to the workspace or manifest during
the read. It writes a new descriptor with current digests. It never exports `.git`, LabDeck state,
absolute paths, unlisted workspace files, or files that match the credential denylist.

The UI shows the complete export file list and warns the user to inspect project content. Name and
path checks cannot prove that a regular source file contains no secret.

The response uses `application/zip`, `Cache-Control: no-store`, and a server-made safe filename. It
does not place a workspace path in the filename or response metadata.

## Bundled templates

The backend owns a checked-in catalog. Each template is a complete course-pack fixture that passes
the same pack and manifest validators used for user imports. V1 includes exactly:

- Python with pytest;
- Node with npm tests;
- Java with Maven tests;
- C++ with CMake and CTest;
- Python data science with a small deterministic notebook and test.

All source, exercises, tests, and README text are original synthetic fixtures. Template IDs and
bytes are stable. Manifests use explicit supported image tags or project-local builds, direct argv
test commands, bounded resources, and no secrets. The data-science template does not start a public
notebook server by default.

The Templates route replaces its placeholder with a local gallery. It shows the stack, included
services, resource limits, test command, build files, and a clear `Demo template` label. A student
can download a pack or install it into a new empty destination. Installation uses the same staging
and validation flow as uploaded packs. The result opens the full lab plan. It does not start or
build the lab.

## Template and course-pack API

The API adds these typed operations:

- `GET /api/v1/templates` lists the five local templates with `capability: AVAILABLE`.
- `GET /api/v1/templates/{id}` returns template metadata and its compiled safe plan.
- `GET /api/v1/templates/{id}/export` returns the fixed deterministic ZIP.
- `POST /api/v1/templates/{id}/install` installs the template into an explicit new empty
  destination and returns the imported lab detail.
- `POST /api/v1/course-packs/import` validates and installs one uploaded ZIP into an explicit new
  destination and returns the imported lab detail.
- `POST /api/v1/labs/{id}/export` returns a freshly validated portable ZIP.

Mutation JSON objects remain closed. The multipart endpoint accepts only the exact `pack` and
`destination` fields. Unknown, repeated, missing, or oversized parts are rejected.

## Frozen build context

LabDeck never passes a live project directory to Docker. For each manifest `build` service, it:

1. Rechecks the selected workspace identity and resolves the context and Dockerfile with the
   existing `ProjectPathPolicy`.
2. Walks the context without following links.
3. Rejects symbolic links, hard links, devices, sockets, FIFOs, filesystem boundaries, unsupported
   file types, unsafe paths, and files that change while read. The platform must provide enough
   file identity and link metadata to prove this boundary. Otherwise the snapshot fails closed.
4. Applies `.dockerignore` with one documented portable subset. Unsupported syntax fails closed;
   LabDeck does not silently send extra files.
5. Copies accepted bytes into an owner-only temporary snapshot while recording path, size,
   filesystem identity, and SHA-256.
6. Rechecks source metadata after each read and the workspace identity after the walk.
7. Writes a deterministic tar stream from the closed snapshot. The Dockerfile path stays relative
   to the context.
8. Sends only that tar stream to docker-java.
9. Deletes the snapshot when the build reaches a proved terminal state.

The context digest covers each normalized path, file mode, file size, and file digest in stable
order. It also covers the Dockerfile path and manifest build plan. A live source change cannot
change bytes already sent to Docker.

### Build-context limits

- Maximum regular files: 4,096.
- Maximum total file bytes: 256 MiB.
- Maximum one file: 64 MiB.
- Maximum normalized relative path: 240 UTF-8 bytes.
- Maximum path depth: 32 segments.
- Maximum snapshot and build time: 15 minutes.
- One active build per lab and two active builds per process.
- No wait queue. A request above a limit returns a stable conflict response.

## Docker build policy

The Docker adapter uses docker-java's tar-stream build API. It does not start a shell command. The
allowed options are fixed in code: one deterministic local tag, exact LabDeck labels, `pull=false`,
removal of intermediate containers when Docker supports it, and no forced cache bypass. It passes
an explicit empty registry-auth map. The API does not expose build args, secrets, SSH forwarding,
alternate Dockerfiles outside the context, targets, platforms, registry auth, custom tags, push,
privileged mode, host networking, or remote contexts.

A Dockerfile can run commands and can use the normal build network. A missing base image can cause
Docker to fetch public image data even with `pull=false`; pressing Build confirms this reviewed
Dockerfile action. Base images and Docker build-cache layers can remain after a build. LabDeck sends
no credentials, never removes those shared layers, and does not claim to sandbox a malicious
Dockerfile from the Docker daemon or the network. The review screen states these limits clearly.

Every build gets a random 128-bit ownership token and these identity values:

- managed by LabDeck;
- lab ID and project ID;
- resource type `image`;
- service logical name;
- ownership token;
- manifest SHA-256;
- build-plan SHA-256;
- context SHA-256.

The successful result stores the immutable Docker image ID returned by the daemon. LabDeck inspects
that exact ID and verifies every identity label before it marks the build usable. A deterministic
tag is for display and lookup only. It is never mutation authority.

Successful built images persist across normal lab stop. V1 does not provide image deletion. Start
uses a build only when the stored image ID, all labels, lab revision, manifest digest, build plan,
and a fresh context digest match. Otherwise it returns `BUILD_REQUIRED` or `BUILD_OUTDATED` before
creating Docker resources.

## Build state, logs, and cancellation

A dedicated SQLite build journal records request identity, service, digests, ownership token,
status, timestamps, bounded failure data, image ID, and proved cleanup. It is separate from the
container/network/volume journal because built images persist after stop.

The statuses are `PREPARING`, `PREPARED`, `RUNNING`, `CANCELLING`, `SUCCEEDED`, `FAILED`,
`CANCELLED`, `TIMED_OUT`, `EXPIRED`, and `OUTCOME_UNKNOWN`. Only `SUCCEEDED` is usable by lab
start.

The API adds:

- `POST /api/v1/labs/{id}/builds/prepare` creates frozen snapshots for the exact reviewed lab
  revision and manifest digest. It returns `202` and a run location. It does not call Docker.
- `GET /api/v1/labs/{id}/builds/{runId}` returns status, per-service results, digests, timestamps,
  and bounded output.
- `POST /api/v1/labs/{id}/builds/{runId}/start` accepts the exact reviewed revision, manifest
  SHA-256, build-plan SHA-256, and prepared-snapshot SHA-256. It starts Docker only while the run is
  `PREPARED` and every value matches.
- `GET /api/v1/labs/{id}/builds/{runId}/stream` returns a bounded server-sent log stream.
- `POST /api/v1/labs/{id}/builds/{runId}/cancel` requests cancellation with an exact empty JSON
  body.

Build output is untrusted. LabDeck keeps at most 4,000 lines and 1 MiB for one run, limits one line
to 16,384 Unicode code points, removes unsafe controls, replaces known workspace paths, and adds an
explicit truncation marker. Responses use `Cache-Control: no-store`. Redaction is defense in depth;
the UI warns that Dockerfile commands can print project secrets.

A prepared snapshot is owner-only and single-use. It expires after 30 minutes. Expiry, application
restart, cancellation, or any failed identity recheck removes the exact snapshot and requires a new
prepare step. A Docker build reads only the accepted prepared snapshot, never the current live
directory. A later source change makes the successful build `BUILD_OUTDATED` at lab start and
requires a new prepare and build.

One run prepares all build services and builds them serially in stable manifest order. The run is
successful only when every service image passes identity inspection. If one service fails, is
cancelled, or times out, LabDeck verifies and removes each exact final image made by that run. If it
cannot prove complete cleanup, the run becomes `OUTCOME_UNKNOWN`.

Cancellation closes the context stream and callback, then waits for a bounded terminal signal. It
does not claim that closing the client stopped daemon work. If the daemon proves a new exact image
exists, LabDeck verifies its ID and labels and removes only that image when the cancelled or failed
build must be discarded. It never removes by tag and never prunes.

If daemon completion or cleanup cannot be proved, the result is `OUTCOME_UNKNOWN`. The record stays
open, blocks reuse for that lab and service, and is available for exact recovery. An empty image
lookup does not prove that an in-flight build stopped. Startup changes interrupted nonterminal runs
to `OUTCOME_UNKNOWN`; it does not retry or delete blindly.

## Full review before Docker

The lab detail response will include build plans and the latest build state. When prepare reaches
`PREPARED`, and before the Docker build button is enabled, the UI shows:

- the exact workspace and build-context relative paths;
- Dockerfile relative path;
- files, bytes, and context digest after snapshot preparation;
- the Dockerfile as read-only text plus only conservative line explanations;
- the images, containers, network, volumes, ports, resource limits, health checks, and test command;
- a warning that Dockerfile commands run through the local Docker daemon and can use network access.

Preparing and reviewing a snapshot does not call Docker. The build request names the expected lab
revision, manifest SHA-256, build-plan SHA-256, and prepared context SHA-256. A mismatch returns a
conflict before Docker. Start repeats the exact identity checks.

## Error handling

All archive, template, snapshot, build, and cancellation errors use stable problem codes and safe
messages. Responses do not expose staging paths, ownership tokens, private image IDs, raw Docker
errors, archive entry bytes, or host paths other than the already approved workspace or destination
shown to the local user.

Temporary files are owner-only. Cleanup targets only the exact staging or snapshot directory made
for the current operation. Docker cleanup targets only an exact stored image ID after all ownership
labels match. No failure path calls a broad list-and-delete or prune operation.

## Verification

Implementation is not complete until these checks pass on the exact final commit:

- all five template packs pass archive, manifest, compiler, digest, deterministic-byte, and
  import/export round-trip tests;
- imports reject traversal, absolute and Windows paths, links, special files, duplicates,
  normalization and case collisions, encrypted and nested archives, malformed metadata, bombs,
  limit breaches, hidden credential paths, bad digests, extra files, and any destination that
  already exists;
- failed imports prove that existing destination content is unchanged;
- exports reject links, special files, source changes, sensitive names, and files outside the
  computed export set;
- snapshots reject traversal, recursive links, special files, filesystem changes, workspace
  replacement, Dockerfile replacement, unsupported ignore rules, and every size/count limit;
- build tests cover success, fixed options, label and image-ID verification, mismatches, callback
  errors, timeout, cancellation, ambiguous cancellation, restart recovery, concurrency, exact image
  cleanup, and proof that push and registry-auth APIs are never called;
- lifecycle tests prove a missing, stale, mismatched, or ambiguous build cannot start a container;
- API tests cover CSRF, loopback, Host, Origin, body/part limits, no-store headers, closed objects,
  stable errors, and no Docker call before review;
- Vue tests cover gallery, template install, ZIP import, complete plan review, build progress,
  truncation, cancellation, stale plans, and accessible keyboard/error states;
- Docker integration tests build from a frozen tar, verify exact labels and image ID, exercise
  cancellation behavior, start the built lab, stop it, and prove unrelated Docker resources remain
  unchanged;
- desktop and mobile browser checks show the templates and build flow with no console errors or
  horizontal overflow;
- final label-scoped inspection proves no temporary LabDeck containers, networks, or volumes remain.

Tests that depend on unavailable host features must be marked `NOT_RUN`. Missing proof stays
`UNKNOWN`; it is never reported as passed.

## Documentation changes

The implementation will update the API guide, manifest/build documentation, security model,
template list, README, and an ADR for course-pack and built-image ownership. It will state the
archive limits, Dockerfile trust warning, network behavior, cancellation ambiguity, image
persistence, and lack of image cleanup in V1.
