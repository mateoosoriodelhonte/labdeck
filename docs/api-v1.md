# LabDeck local API v1

LabDeck serves one JSON API on `127.0.0.1`. V1 has no remote mode, user account, telemetry, or
cloud service. The Vue app and a future local CLI use the same contracts.

## Base address

The default base address is `http://127.0.0.1:8787/api/v1`.

LabDeck accepts `Host` values for `127.0.0.1` or `localhost`, with an optional valid port. It rejects
forwarded identity headers. An `Origin` header, when present, must match the request scheme and
authority. LabDeck sends no cross-origin permission headers. These checks limit DNS rebinding and
cross-site requests, but they do not make the service safe for a public network.

The process fails during startup if `server.address` is not exactly `127.0.0.1`. This rule also
applies to command-line and environment overrides.

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
| `GET` | `/labs/{id}/services` | Inspect active, journaled, label-verified service containers |
| `GET` | `/labs/{id}/logs` | Typed placeholder with `capability: PLANNED` until issue #8 |
| `GET` | `/labs/{id}/tests?limit=20` | Recent bounded test results; limit is 1 through 100 |
| `GET` | `/templates` | Typed placeholder with `capability: PLANNED` until issue #10 |
| `GET` | `/settings` | Read-only local security and capability settings |

Test execution is not part of this issue. Issue #9 will add the constrained mutation that runs
only the manifest-defined argv inside an owned service. The current `/tests` contract is read-only
history. Logs and templates are also marked `PLANNED` instead of returning invented data.

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
list or prune operation.

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
| `415` | `JSON_REQUIRED` | A mutation did not use JSON |
| `422` | `MANIFEST_INVALID` | The restricted manifest failed validation |
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
