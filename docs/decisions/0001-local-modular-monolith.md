# ADR-0001: Use a local modular monolith

## Status

Accepted

## Date

2026-08-26

## Context

LabDeck must give students one local application. It must manage Docker labs without requiring a
hosted account, cloud database, or paid service. The browser UI and CLI must use the same domain
rules. The first release must also stay simple enough for one person to build, test, and explain.

## Decision

Use one Spring Boot process as a modular monolith. It owns the local API and serves the production
Vue application from its executable JAR. Domain code will depend on ports. Docker, SQLite, the CLI,
and the HTTP API will be adapters around those ports.

The API will use versioned paths under `/api/v1`. The local service will bind to `127.0.0.1` by
default. The Maven build will use `npm ci` and bundle the compiled Vue files into the JAR.

## Alternatives considered

### Separate frontend and backend processes

This is easy during development, but it adds setup, ports, and version coordination for students.
It is rejected for the production package. The frontend can still use a separate Vite process while
developers work on the UI.

### Electron desktop application

Electron can make desktop packaging direct. It would add another privileged runtime and duplicate
service boundaries around Docker. It is rejected for v1.

### Microservices

Separate services can isolate scaling and deployment. LabDeck is local-first and has one user per
installation, so that cost has no matching benefit. It is rejected.

## Consequences

- Students start one process and open one local URL.
- UI and CLI actions share the same policy and validation code.
- Docker and SQLite stay behind replaceable ports.
- Long Docker work must run outside short SQLite transactions.
- Deep browser routes need an explicit fallback to the bundled `index.html`.
- A later native wrapper can start the same local service without changing the domain design.
