# LabDeck

**One command. The exact environment your assignment needs.**

LabDeck launches isolated Docker-based development labs for coursework, so students can spend
less time fighting dependencies and more time working on the assignment.

> [!NOTE]
> LabDeck is under active v1 development. The current bootstrap serves a tested Vue application
> from one local Spring Boot process. The guarded Docker lifecycle is implemented behind the domain
> port. The user-facing start flow, ports, health readiness, and resource limits are still tracked
> in the v1 milestone and are not claimed as complete yet.

## What works now

- Java 25 and Spring Boot 4.1.1 local service bound to `127.0.0.1`.
- Vue 3.5.41, Vite, and TypeScript interface with desktop and mobile layouts.
- Versioned `GET /api/v1/system` status contract.
- Closed v1 manifest schema, bounded YAML parser, semantic validator, and deterministic plan.
- Locked local SQLite metadata store with migrations, lifecycle revisions, and bounded test history.
- Public-image inspection and confirmed pulls without Docker registry credentials.
- Journaled Docker containers, private networks, persistent volumes, and exact-ID cleanup.
- Workspace identity checks before structured bind mounts; no general Docker prune operations.
- One executable JAR that contains the production frontend.
- Deterministic synthetic lab examples. No real coursework or personal data.
- Unit, contract, build, and browser smoke checks for the bootstrap.

## Build and run

Requirements:

- Java 25 LTS
- A Docker-compatible engine for lab features as they land

```bash
./mvnw clean package
java -jar target/labdeck-0.1.0-SNAPSHOT.jar
```

Open [http://127.0.0.1:8787](http://127.0.0.1:8787).

LabDeck stores its local database under `~/.labdeck` by default. Set `LABDECK_DATA_DIR` to use a
different directory. Student project files stay in the workspace that the user selects.

The Maven build downloads a project-local Node 24 runtime and uses `npm ci`. A normal LabDeck user
does not need to start the frontend and backend separately.

Common checks:

| Command | Purpose |
| --- | --- |
| `./mvnw clean package` | Build the UI and service, run Java tests, and create the executable JAR |
| `cd frontend && npm test` | Run Vue component tests |
| `cd frontend && npm run lint` | Check Vue and TypeScript source |
| `cd frontend && npm run format:check` | Check formatting without changing files |

For frontend development:

```bash
cd frontend
npm ci
npm run dev
```

## Architecture

The v1 target architecture is:

```text
Vue UI / CLI
      │
      ▼
Spring Boot API
      │
      ▼
Lab domain engine
      │
      ├── Manifest validator
      ├── Port and resource policy
      ├── Test runner
      └── Resource monitor
      │
      ▼
Docker Engine adapter ──► Docker Engine

SQLite stores LabDeck metadata.
Student files stay in the selected host workspace.
```

Docker is not a deployment detail here. Reproducible images, isolated containers, private networks,
resource controls, and persistent workspaces are the product.

Architecture decisions are recorded in
[ADR-0001](docs/decisions/0001-local-modular-monolith.md) and
[ADR-0002](docs/decisions/0002-restricted-manifest.md), and
[ADR-0003](docs/decisions/0003-use-locked-sqlite-metadata-store.md), and
[ADR-0004](docs/decisions/0004-journal-docker-resource-ownership.md).

## Safety and privacy

LabDeck is local-first. It has no account, analytics, telemetry, paid API, or cloud database.
The v1 manifest is intentionally restricted. LabDeck will manage only resources with exact LabDeck
ownership labels and will never perform a general Docker prune.

See the [manifest v1 guide](docs/manifest-v1.md) for the accepted fields and safety rules.

Docker isolation is not a perfect security sandbox. The full threat model and adversarial manifest
tests are tracked in the [LabDeck v1.0 milestone](https://github.com/mateoosoriodelhonte/labdeck/milestone/1).

## Verified foundations

- [Oracle Java support roadmap](https://www.oracle.com/java/technologies/java-se-support-roadmap.html)
- [Apache Maven 3.9.16 release notes](https://maven.apache.org/docs/3.9.16/release-notes.html)
- [Spring Boot 4.1.1 system requirements](https://docs.spring.io/spring-boot/system-requirements.html)
- [Vue quick start](https://vuejs.org/guide/quick-start.html)
- [Spring Boot static content](https://docs.spring.io/spring-boot/reference/web/servlet.html#web.servlet.spring-mvc.static-content)
- [Spring Security CSRF guidance](https://docs.spring.io/spring-security/reference/7.0/servlet/exploits/csrf.html)

## Project tracking

The public v1 plan is in [issue #1](https://github.com/mateoosoriodelhonte/labdeck/issues/1).
The bootstrap work is tracked in [issue #2](https://github.com/mateoosoriodelhonte/labdeck/issues/2).
Restricted manifest work is tracked in
[issue #3](https://github.com/mateoosoriodelhonte/labdeck/issues/3).
SQLite persistence is tracked in
[issue #4](https://github.com/mateoosoriodelhonte/labdeck/issues/4).
Docker lifecycle and ownership are tracked in
[issue #5](https://github.com/mateoosoriodelhonte/labdeck/issues/5).
