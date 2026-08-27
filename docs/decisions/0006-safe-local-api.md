# ADR-0006: Safe local API boundary

## Status

Accepted

## Date

2026-08-26

## Context

The Vue app and future CLI need one stable API for lab plans and lifecycle actions. The service has
no V1 account or login because it listens only on the local machine. Loopback binding alone is not
enough. Browser requests can still come from another site, and DNS rebinding can supply a hostile
`Host` value. Unsafe methods also need request verification. Error responses must not repeat a
hostile path, manifest value, environment value, local path, or raw Docker message.

The API also crosses several trust boundaries. A workspace path selects a host directory. A
manifest controls an execution plan. Start and stop change Docker state. The UI can become stale
while a manifest, lab record, or Docker resource changes.

Sources:

- [Spring Security CSRF](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html)
- [Spring Framework problem details](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-rest-exceptions.html)
- [Spring Framework CORS](https://docs.spring.io/spring-framework/reference/web/webmvc-cors.html)
- [Spring Boot server and Jackson properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html)

## Decision

Bind the process to the exact IPv4 loopback literal `127.0.0.1`. Register an early environment
processor that rejects every other value, including wildcard, IPv6, LAN, and `localhost` values.
Reject forwarded identity headers. Accept only `localhost` or `127.0.0.1` in `Host`, with an
optional valid port. If `Origin` exists, require its scheme and authority to match the request.
Configure no CORS permission.

Keep Spring Security CSRF enabled. Store the raw token in the HTTP session and accept it only from
`X-LabDeck-CSRF`. Provide one no-store bootstrap endpoint and one protected rotation endpoint.
Reject missing, wrong, duplicated, parameter-only, and stale tokens. No account or remote access is
advertised.

Require JSON for API mutations. Reject unknown and duplicate properties, trailing values, malformed
input, and bounded-parser limit violations. Use typed request and response records. Validate path
IDs, revisions, hashes, query limits, strings, and collection sizes before an application service
can call the Docker lifecycle.

Treat a start as an optimistic concurrency operation. The client must supply the lab revision and
manifest hash that it reviewed. Reload the bounded regular `labdeck.yml` from the approved
workspace. If the revision or hash changed, fail before Docker inspection. Inspect required public
images and require confirmation of exactly the missing set before a pull. The existing lifecycle
then rechecks the lab revision and workspace identity under its own lock and ownership rules.

Treat stop the same way. Require the reviewed revision and check it again while holding the
per-lab lifecycle lock. A stale stop changes no resources.

Service reads use only active container records returned by the selected lab's ownership journal.
Inspect each exact Engine ID and recheck all ownership labels. Do not expose Engine IDs, generated
names, ownership tokens, volume identities, or manifest environment values in API models.

Use RFC 9457 problem details with a stable LabDeck code. Set `instance` to the fixed `/api/v1`
value so hostile request text is not reflected. Use safe manifest problems and typed Docker
messages. Do not copy exception, registry, daemon, credential, environment, or stack text into a
response.

Logs, test execution, and templates keep typed placeholder or read-only contracts until their
separate issues implement the bounded engine behavior. They must say `PLANNED`; they must not
invent results.

## Alternatives considered

### Disable CSRF because the server is local

A browser can send local requests from another site. Local binding does not remove the cross-site
request risk. This option is rejected.

### Use a CSRF cookie that JavaScript reads

This avoids a bootstrap request, but it makes the verification token available in a script-readable
cookie. The session-backed header flow is direct and keeps the cookie HTTP-only.

### Allow any loopback spelling or forwarded host

This adds proxy and address-normalization cases with no V1 product need. One fixed bind literal and
no proxy trust are easier to test and explain.

### Pass persistence and Docker domain objects through controllers

Those objects contain local paths, Engine IDs, ownership tokens, and other internal details. Typed
API models keep the public contract smaller and safer.

## Consequences

- The API works from the bundled same-origin Vue app and a session-aware local client.
- A client must bootstrap CSRF before its first mutation and refresh after session loss.
- Stale UI actions fail with a clear `409` instead of applying to a newer lab state or plan.
- The API shows the selected workspace path because the local user must verify the mount. Problems
  and list summaries do not repeat it.
- Remote access needs a separate authentication, authorization, origin, proxy, TLS, and threat
  model. The startup guard also rejects a separate management port and any non-local management
  address. Changing server properties cannot enable remote access.
- Logs, test execution, and templates remain explicitly incomplete until issues #8, #9, and #10.
