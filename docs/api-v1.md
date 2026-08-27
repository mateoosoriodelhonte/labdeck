# LabDeck local API v1

LabDeck serves one JSON API on `127.0.0.1`. V1 has no remote mode, user account, telemetry, or
cloud service. The Vue app and a future local CLI use the same contracts.

## Base address

The default base address is `http://127.0.0.1:8787/api/v1`.

LabDeck accepts `Host` values for `127.0.0.1` or `localhost`, with an optional valid port. It rejects
forwarded identity headers. An `Origin` header, when present, must match the request scheme and
authority. LabDeck sends no cross-origin permission headers. These checks limit DNS rebinding and
cross-site requests, but they do not make the service safe for a public network.

The process fails during startup if `server.address` is not exactly `127.0.0.1`, if a separate
management port is set, or if a management address is not `127.0.0.1`. The request peer must also
be `127.0.0.1`. These rules apply to command-line and environment overrides.

## Request verification

All `POST`, `PUT`, `PATCH`, and `DELETE` requests need a session CSRF token. The token is accepted
only in the `X-LabDeck-CSRF` header. Query and form parameters do not count.

1. Send `GET /api/v1/csrf`.
2. Keep the returned session cookie.
3. Send the returned token in `X-LabDeck-CSRF` on each mutation.
4. If needed, send `POST /api/v1/csrf/rotate` with the current token. The old token stops working.

Token responses use `Cache-Control: no-store`. Problem responses also use `no-store`.

## JSON rules

Mutation bodies must use `Content-Type: application/json`. LabDeck rejects:

- unknown fields;
- duplicate fields;
- trailing JSON values;
- malformed UTF-8 or JSON;
- bodies over 64 KiB;
- excessive nesting, token counts, name lengths, string lengths, or number lengths;
- missing or invalid required fields.

IDs match `[A-Za-z0-9][A-Za-z0-9_-]{0,63}`. Revisions are non-negative integers. A manifest hash
has the form `sha256:` followed by 64 lowercase hexadecimal characters.

## Endpoints

| Method | Path | Result |
| --- | --- | --- |
| `GET` | `/system` | Local service status and API version |
| `GET` | `/csrf` | Current session CSRF token |
| `POST` | `/csrf/rotate` | New session CSRF token |
| `GET` | `/labs` | Lab summaries, newest update first |
| `POST` | `/labs` | Validate and import `labdeck.yml` from a selected workspace |
| `GET` | `/labs/{id}` | Lab state, safe manifest plan, and safe runtime failure |
| `POST` | `/labs/{id}/start` | Start the exact reviewed revision and manifest plan |
| `POST` | `/labs/{id}/stop` | Stop the exact reviewed lab revision |
| `GET` | `/labs/{id}/services` | Bounded status, metrics, topology, storage, and cleanup plan for exact-owned resources |
| `GET` | `/labs/{id}/logs?service=app&tail=200` | Bounded log history for one active exact-owned service |
| `GET` | `/labs/{id}/logs/stream?service=app&tail=100` | Bounded server-sent log stream for one active exact-owned service |
| `GET` | `/labs/{id}/tests?limit=20` | Recent bounded results and the current process-local run; limit is 1 through 100 |
| `POST` | `/labs/{id}/tests` | Start the reviewed manifest-defined assignment test |
| `GET` | `/labs/{id}/tests/{runId}` | Read active or terminal test status |
| `POST` | `/labs/{id}/tests/{runId}/cancel` | Cancel the active test and stop its exact lab |
| `GET` | `/templates` | Typed placeholder with `capability: PLANNED` until issue #10 |
| `GET` | `/settings` | Read-only local security and capability settings |

Templates remain `PLANNED` instead of returning invented data. Settings report logs and assignment
test execution as `AVAILABLE`.

## Import a lab

Request:

```json
{
  "workspace": "/Users/student/Courses/CS341/assignment-4"
}
```

LabDeck resolves the workspace as a real directory, rejects a workspace symlink or sensitive root,
and reads only the regular project file `labdeck.yml`. The file cannot be a symbolic link and is
limited to 256 KiB. LabDeck rechecks the workspace and file identity around the read.

The response includes the local workspace because the local UI must show what will be mounted. It
does not include manifest environment values. A service plan contains only environment key names.
Docker Engine IDs, ownership tokens, and volume identities are never API fields.

## Start a lab

First read `GET /labs/{id}` and show its plan to the user. Then send:

```json
{
  "expectedRevision": 0,
  "expectedManifestSha256": "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
  "confirmedImageDownloads": []
}
```

LabDeck checks the revision and reloads the manifest. If either changed, it returns `409` and does
not inspect, pull, or start Docker resources.

LabDeck then inspects the exact public image references. If an image is missing, it returns `409`
with code `IMAGE_CONFIRMATION_REQUIRED` and an `images` array. Retry only after the user confirms
exactly that missing set. LabDeck supplies no registry credentials and does not pull an unconfirmed
image.

The success response contains the new lab state and service status. Service fields omit container
IDs and Engine-generated names. Published ports have `hostAddress: 127.0.0.1` and an endpoint such
as `127.0.0.1:5432`. The endpoint has no forced URL scheme because a service can use PostgreSQL,
Redis, HTTP, or another TCP protocol.

The live service list captures the lab revision and exact journal records under the per-lab lock.
It releases that lock before Docker reads, applies a five-second aggregate deadline, then checks the
lab revision again. Stop is therefore not blocked by a slow metrics request. A changed lab produces
a `409` conflict instead of a stale snapshot.

The response includes status, health, ports, start time, uptime, CPU, memory, network counters,
image size, and writable-layer size when Docker reports them. It also includes stable topology
nodes and edges, exact-owned named-volume mounts, storage availability, and a read-only cleanup
plan. Docker Engine IDs, ownership tokens, host filesystem paths, environment values, container
commands, IP addresses, and private image IDs are not response fields.

Named-volume size is `null` with `sizeAvailability: UNAVAILABLE`. Docker's per-volume inspect API
does not report a size. LabDeck does not use Docker-wide disk discovery or start a helper container
to guess it. `hasUnknownVolumeSizes` makes this limit explicit. Cleanup estimates include only
known writable-layer bytes. The cleanup plan never proposes a general prune, image deletion, or
named-volume deletion.

New LabDeck containers use Docker's `local` log driver with compressed rotation: three files of up
to 10 MiB each. Log history requires `service`; `tail` defaults to 200 and is limited to 1 through
500. A history read covers at most 15 minutes, 500 lines, 256 KiB, and five seconds. The response
uses `Cache-Control: no-store` and returns Docker timestamps when present. It strips control and
format characters and limits each rendered line to 16,384 Unicode code points.

The server-sent stream requires the same exact active service. Its initial tail defaults to 100 and
is limited to 1 through 200. A stream ends after five minutes, 2,000 lines, 1 MiB, client disconnect,
queue overflow, Stop, restart, runtime failure, or application shutdown. The queue holds at most
128 events and 256 KiB. LabDeck allows at most two streams per lab and four per process. It sends a
keepalive comment every 15 seconds while Docker is quiet, followed by an `end` event with a bounded
reason. The lab, active service, ownership, and Docker log attachment are checked before HTTP 200 is
committed. A stopped or replaced service releases its stream slot as soon as the Docker subscription
closes. A Docker transport failure ends with reason `ERROR`.

## Stop a lab

Request:

```json
{
  "expectedRevision": 2
}
```

The lifecycle checks this revision again while it holds the per-lab lock. A stale request returns
`409` before any Docker resource changes. Stop uses only exact open journal records for that lab,
then rechecks stored Engine identity and ownership labels. It never falls back to a broad Docker
list or prune operation. The response can contain `plan: null` when the manifest becomes unreadable.
The stopped state and new revision still return, so a successful cleanup is not reported as a
failed stop.

## Run an assignment test

First read `GET /labs/{id}` and show the manifest test plan. Then send:

```json
{
  "expectedRevision": 2,
  "expectedManifestSha256": "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
}
```

No other fields are accepted. The request cannot supply a command, service, environment, timeout,
or working directory. LabDeck checks that the lab is Running, the revision and manifest are current,
and the manifest matches the plan held in memory from the successful lab start. A LabDeck restart or
manifest change requires a lab restart before test execution.

A successful start returns `202 Accepted`, `Cache-Control: no-store`, a `Location` header for the
run status, and a `RUNNING` body. Poll that location until the status is terminal. Process-local
status can be `RUNNING`, `CANCELLING`, or `PERSISTING`. Terminal status is `PASSED`, `FAILED`,
`ERROR`, `CANCELLED`, or
`TIMED_OUT` and includes a stable outcome reason, duration, optional exit code, separate output,
per-stream truncation, lab revision, service, and nonsecret test-plan digest.

`GET /labs/{id}/tests` returns `runs` and a nullable `activeRun`. The client uses `activeRun` to
restore polling and cancellation after a page reload. It can also contain a terminal `ERROR` with
`RESULT_UNAVAILABLE` when the result could not be saved and the slot remains reserved. Each saved
history row includes its full nonsecret test-plan digest and recorded time.

LabDeck sends the manifest command as direct Docker argv inside the exact active owned service. It
adds no shell, stdin, TTY, privilege, environment override, or browser-provided value. One test can
run per lab and two can run per process. LabDeck does not queue a third test.

The Docker stream capture is limited to 128 KiB. Stored standard output and error are sanitized and
limited to 64 KiB combined. Redaction covers the workspace, all manifest environment values, common
credential assignments, bearer tokens, and unsafe control characters. This is defense in depth; no
generic filter can prove that arbitrary test output contains no secret. History keeps at most 100
results per lab and 1,000 in total.

Cancel with an exact empty JSON body:

```json
{}
```

Docker Engine cannot kill only one exec process. A user cancel, timeout, or ambiguous exec error
therefore stops and cleans the exact selected lab. The UI tells the student to restart it. LabDeck
reports `CANCELLED` or `TIMED_OUT` only after scoped cleanup succeeds. If cleanup cannot be proved,
the result is `ERROR` with reason `RESULT_UNAVAILABLE`. If SQLite cannot save a terminal result,
LabDeck keeps an in-memory `RESULT_UNAVAILABLE` result and keeps the test slot reserved until the
process restarts. A proved natural test exit leaves the lab running.

## Problem details

Errors use `application/problem+json` and RFC 9457 fields plus a stable `code`:

```json
{
  "type": "urn:labdeck:problem:lab_not_found",
  "title": "Lab not found",
  "status": 404,
  "detail": "No LabDeck lab has that ID.",
  "instance": "/api/v1",
  "code": "LAB_NOT_FOUND"
}
```

The fixed `instance` value prevents a hostile path or query from being reflected in an error body.
Validation problems can add safe `fields` or `problems` arrays. Docker failures use fixed or
sanitized messages. Raw daemon, registry, stack, local path, environment value, and credential text
is not copied into a problem response.

Common codes include:

| Status | Code | Meaning |
| --- | --- | --- |
| `400` | `LOCAL_REQUEST_REQUIRED` | Host or forwarded identity is not allowed |
| `400` | `MALFORMED_JSON` | JSON is malformed, duplicated, trailing, or has unknown fields |
| `400` | `REQUEST_VALIDATION_FAILED` | A body, path, or query constraint failed |
| `403` | `CROSS_ORIGIN_REJECTED` | Origin does not match the local request |
| `403` | `CSRF_REJECTED` | Mutation token is missing, duplicated, stale, or wrong |
| `404` | `LAB_NOT_FOUND` | No local lab has the requested ID |
| `409` | `LAB_REVISION_CHANGED` | The lab changed after the client read it |
| `409` | `MANIFEST_CHANGED` | The manifest plan hash changed |
| `409` | `IMAGE_CONFIRMATION_REQUIRED` | Public images need explicit confirmation |
| `409` | `DOCKER_OWNERSHIP_MISMATCH` | Stored and actual Docker ownership do not match |
| `409` | `TEST_ALREADY_RUNNING` | The selected lab already has an active test |
| `409` | `TEST_RESTART_REQUIRED` | Restart the lab after a manifest or application change |
| `404` | `LAB_SERVICE_NOT_ACTIVE` | The selected service is not active in the selected lab |
| `415` | `JSON_REQUIRED` | A mutation did not use JSON |
| `422` | `MANIFEST_INVALID` | The restricted manifest failed validation |
| `429` | `LOG_STREAM_LIMIT_REACHED` | The local log stream bound is already in use |
| `429` | `TEST_PROCESS_LIMIT_REACHED` | Two assignment tests are already active |
| `502` | `DOCKER_LOGS_UNAVAILABLE` | Docker could not provide the selected service logs safely |
| `503` | `DOCKER_UNAVAILABLE` | Install or start the local Docker engine |
| `503` | `LOG_STREAM_UNAVAILABLE` | The bounded local stream worker is unavailable |
| `503` | `TEST_RUNNER_UNAVAILABLE` | The bounded test worker is unavailable |
| `503` | `DOCKER_VERSION_UNSUPPORTED` | Update Docker Engine for safe local port publishing |
| `503` | `DOCKER_RESOURCE_LIMITS_UNSUPPORTED` | Docker cannot enforce the required resource limits |
| `504` | `DOCKER_OBSERVATION_TIMEOUT` | Docker observation exceeded the five-second deadline |
| `507` | `DOCKER_STORAGE_FULL` | Docker storage is full; LabDeck did not prune it |

## Browser policy

API responses receive the same local security headers as the app. The content security policy uses
same-origin scripts, styles, connections, and forms; blocks objects and framing; and does not allow
an external base URL. Frames, camera, location, microphone, payment, and USB access are disabled.

Sources:

- [Spring Security CSRF](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html)
- [Spring Framework problem details](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-rest-exceptions.html)
- [Spring Framework CORS](https://docs.spring.io/spring-framework/reference/web/webmvc-cors.html)
- [Spring Boot server and Jackson properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html)
