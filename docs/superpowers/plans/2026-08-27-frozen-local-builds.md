# Frozen Local Builds Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the mandatory prepare → review → start-build flow and start build-backed labs only from exact verified LabDeck-owned image IDs.

**Architecture:** A build package prepares private immutable snapshots, writes deterministic tar files, journals state in SQLite, and coordinates at most two asynchronous runs. The Docker port accepts only fixed build specifications and returns immutable image identity. The lifecycle resolves normal image sources as before and build sources only through a successful exact journal record.

**Tech Stack:** Java 25, Spring Boot 4.1.1, docker-java 3.7.1, Apache Commons Compress 1.28.0, SQLite/Flyway, Server-Sent Events, JUnit 5, Mockito, AssertJ

**Spec:** `docs/superpowers/specs/2026-08-27-safe-course-packs-and-local-builds-design.md`

## Global Constraints

- Docker must never receive a live project directory. It receives only a tar made from an owner-only frozen snapshot.
- Prepare must not call Docker. Build start is allowed only from `PREPARED` with exact echoed digests.
- Limit a context to 4,096 regular files, 256 MiB total, 64 MiB per file, 240 UTF-8 path bytes, and 32 path segments.
- Limit prepare plus build to 15 minutes. Expire a prepared snapshot after 30 minutes.
- Allow one active run per lab and two per process. Do not queue excess work.
- Keep at most 4,000 output lines, 1 MiB total, and 16,384 Unicode code points per line.
- Build services serially in manifest order. The run succeeds only if every image passes exact inspection.
- Use fixed build options, `pull=false`, an empty auth map, no build args, secrets, SSH, target, platform, custom tag, push, host network, privileged mode, or remote context.
- Store and verify immutable image IDs and every LabDeck ownership label. Tags are display only.
- On cancellation or failure, remove only proved exact final image IDs from the current run. Never remove by tag and never prune.
- If daemon completion or cleanup is not proved, persist `OUTCOME_UNKNOWN` and block reuse.
- Successful built images survive normal lab stop. V1 has no built-image deletion feature.

## Locked digest and ignore rules

- Keep the existing semantic `manifestSha256` unchanged.
- Compute `buildPlanSha256` with SHA-256 over length-prefixed UTF-8 fields: policy marker
  `labdeck-build-plan-v1`, then each build in manifest order with service ID, context, and Dockerfile.
- Compute each `contextSha256` over marker `labdeck-build-context-v1`, then sorted file path, normalized
  mode, byte size, and file SHA-256, followed by Dockerfile relative path and `buildPlanSha256`.
- Compute `preparedSnapshotSha256` over marker `labdeck-prepared-build-v1`, `buildPlanSha256`, then
  each service ID, `contextSha256`, and tar SHA-256 in manifest order.
- Accept `.dockerignore` up to 64 KiB and 256 rules. Ignore blank lines and lines whose first byte is
  `#`. Allow optional leading `!`, `/` separators, `*` and `?` inside a segment, a whole `**`
  segment, and a trailing `/`. A no-slash pattern matches a segment at any depth; a slash pattern is
  root-relative; last match wins. Reject backslashes, traversal, character classes, braces, escapes,
  control text, paths over 240 bytes, and any other syntax. Always exclude `.git` and credential
  names. Always include the selected Dockerfile and `.dockerignore` in the tar.

## Locked file structure

- Create the build domain under `src/main/java/io/labdeck/build/`.
- Add build Docker transfer records under `src/main/java/io/labdeck/docker/`.
- Add `V6__journal_image_builds.sql` and `SQLiteBuildRunRepository.java`.
- Add build routes in `BuildController.java` and bounded SSE in `BuildLogStreamService.java`.
- Extend the manifest plan, API plan, Docker adapter, lifecycle, error mapper, and tests without changing unrelated resource ownership.

---

### Task 1: Add canonical build-plan digests and portable ignore rules

**Files:**

- Create: `src/main/java/io/labdeck/build/LengthPrefixedDigest.java`
- Create: `src/main/java/io/labdeck/build/BuildPlanDigests.java`
- Create: `src/main/java/io/labdeck/build/DockerIgnoreRules.java`
- Create: `src/main/java/io/labdeck/build/BuildProblemCode.java`
- Create: `src/main/java/io/labdeck/build/BuildException.java`
- Modify: `src/main/java/io/labdeck/manifest/ManifestPlan.java`
- Modify: `src/main/java/io/labdeck/manifest/ManifestPlanCompiler.java`
- Modify: `src/main/java/io/labdeck/api/LabApiModels.java`
- Modify: `src/main/java/io/labdeck/api/LabPlanMapper.java`
- Test: `src/test/java/io/labdeck/build/BuildPlanDigestsTests.java`
- Test: `src/test/java/io/labdeck/build/DockerIgnoreRulesTests.java`
- Modify test: `src/test/java/io/labdeck/manifest/ManifestPlanCompilerTests.java`

**Interfaces:**

- Consumes: ordered `ManifestPlan.BuildPlan` values and portable ignore text.
- Produces: full lowercase `sha256:` digests and a deterministic `DockerIgnoreRules.includes(path, directory)` decision.

- [ ] **Step 1: Write fixed-vector digest and ignore tests**

```java
@Test
void buildPlanDigestIsStableAndOrderSensitive() {
    String first = BuildPlanDigests.plan(List.of(
            new BuildPlan("api", new BuildSource("services/api", "Dockerfile")),
            new BuildPlan("worker", new BuildSource("services/worker", "Containerfile"))));
    String second = BuildPlanDigests.plan(List.of(
            new BuildPlan("worker", new BuildSource("services/worker", "Containerfile")),
            new BuildPlan("api", new BuildSource("services/api", "Dockerfile"))));
    assertThat(first).matches("sha256:[a-f0-9]{64}").isNotEqualTo(second);
}

@Test
void lastPortableIgnoreRuleWins() {
    DockerIgnoreRules rules = DockerIgnoreRules.parse("*.log\n!important.log\nbuild/**\n");
    assertThat(rules.includes(Path.of("debug.log"), false)).isFalse();
    assertThat(rules.includes(Path.of("important.log"), false)).isTrue();
    assertThat(rules.includes(Path.of("build/out.bin"), false)).isFalse();
}
```

Add tests for every supported token, negated-child traversal, `.git`, credential hard denies,
forced Dockerfile/ignore inclusion, invalid UTF-8, rule/byte limits, and unsupported syntax.

- [ ] **Step 2: Run the focused tests and confirm failure**

Run: `./mvnw -Dtest=BuildPlanDigestsTests,DockerIgnoreRulesTests,ManifestPlanCompilerTests test`

Expected: FAIL because the digest and ignore classes do not exist.

- [ ] **Step 3: Implement unambiguous length-prefixed hashing**

```java
final class LengthPrefixedDigest {
    void put(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
        digest.update(bytes);
    }
    String finish() { return "sha256:" + HexFormat.of().formatHex(digest.digest()); }
}
```

Add `String buildPlanSha256` to `ManifestPlan` and `ManifestPlanResponse`. The compiler computes it
from `plan.builds()` and the fixed marker. Do not change how `manifestSha256` is computed.

- [ ] **Step 4: Implement the exact fail-closed ignore grammar**

Parse UTF-8 manually so malformed input fails. Compile supported patterns to anchored regular
expressions, preserve source order, and retain excluded-directory traversal when a later negation
can include a child. Apply hard excludes after user rules and forced includes last.

- [ ] **Step 5: Run digest, ignore, manifest, and API mapping tests**

Run: `./mvnw -Dtest=BuildPlanDigestsTests,DockerIgnoreRulesTests,ManifestPlanCompilerTests,LabApiServiceTests test`

Expected: PASS.

- [ ] **Step 6: Commit canonical build planning**

```bash
git add src/main/java/io/labdeck/build src/main/java/io/labdeck/manifest src/main/java/io/labdeck/api/LabApiModels.java src/main/java/io/labdeck/api/LabPlanMapper.java src/test/java/io/labdeck/build src/test/java/io/labdeck/manifest src/test/java/io/labdeck/api/LabApiServiceTests.java
git commit -m "feat: add canonical local build plans"
```

### Task 2: Freeze contexts and write deterministic tar files

**Files:**

- Create: `src/main/java/io/labdeck/build/BuildLimits.java`
- Create: `src/main/java/io/labdeck/build/BuildContextFile.java`
- Create: `src/main/java/io/labdeck/build/FrozenBuildContext.java`
- Create: `src/main/java/io/labdeck/build/FrozenBuildSet.java`
- Create: `src/main/java/io/labdeck/build/BuildContextSnapshotter.java`
- Create: `src/main/java/io/labdeck/build/DeterministicDockerTarWriter.java`
- Modify: `src/main/java/io/labdeck/manifest/ProjectPathPolicy.java`
- Test: `src/test/java/io/labdeck/build/BuildContextSnapshotterTests.java`
- Test: `src/test/java/io/labdeck/build/DeterministicDockerTarWriterTests.java`
- Modify test: `src/test/java/io/labdeck/manifest/ProjectPathPolicyTests.java`

**Interfaces:**

- Consumes: `LocalStoragePaths.operations()`, `ApprovedWorkspacePath`, `ManifestPlan`, and `CancellationToken`.
- Produces: `FrozenBuildSet` with one closed tar per build service and no Docker side effect.

- [ ] **Step 1: Write hostile-tree snapshot tests first**

Cover recursive symlink, hard link with link count above one, FIFO/device/socket when supported,
filesystem boundary, path/depth/count/file/total limits, changing file, Dockerfile replacement,
workspace replacement, invalid `.dockerignore`, cancellation, and cleanup of only the operation root.

```java
@Test
void snapshotBytesCannotChangeAfterLiveSourceChanges(@TempDir Path workspace) throws Exception {
    writeBuildProject(workspace, "before\n");
    FrozenBuildSet frozen = snapshotter.prepare(approved(workspace), plan(), CancellationToken.NONE);
    Files.writeString(workspace.resolve("src/main.txt"), "after\n");
    assertThat(readTar(frozen.contexts().getFirst().tarFile(), "src/main.txt")).isEqualTo("before\n");
}
```

- [ ] **Step 2: Run snapshot tests and confirm failure**

Run: `./mvnw -Dtest=BuildContextSnapshotterTests,DeterministicDockerTarWriterTests test`

Expected: FAIL because snapshot types do not exist.

- [ ] **Step 3: Implement bounded no-follow file copying**

Use `Files.walkFileTree` without `FOLLOW_LINKS`. Require stable file keys and a proved link count of
one; fail closed if the platform cannot prove link safety. Open with `NOFOLLOW_LINKS`, hash while
copying, and recheck file key, size, modified time, workspace identity, and filesystem store. Copy
to a unique 0700 operation root and create output files with `CREATE_NEW`.

- [ ] **Step 4: Implement deterministic tar output and aggregate digests**

```text
FrozenBuildContext(
    service, context, dockerfile, fileCount, totalBytes,
    contextSha256, tarSha256, tarFile, dockerfileText, dockerfileTextTruncated)
FrozenBuildSet.preparedSnapshotSha256() -> String
FrozenBuildSet.contexts() -> List<FrozenBuildContext>
FrozenBuildSet.close() -> void
```

Write POSIX ustar/PAX entries with sorted portable names, mode `0644`, fixed uid/gid `0`, empty
owner/group names, and mtime `1980-01-01T00:00:00Z`. Never read source files while writing tar; read
only the closed private copy.

- [ ] **Step 5: Run snapshot, tar, and path-policy tests**

Run: `./mvnw -Dtest=BuildContextSnapshotterTests,DeterministicDockerTarWriterTests,ProjectPathPolicyTests test`

Expected: PASS.

- [ ] **Step 6: Commit frozen snapshots**

```bash
git add src/main/java/io/labdeck/build src/main/java/io/labdeck/manifest/ProjectPathPolicy.java src/test/java/io/labdeck/build src/test/java/io/labdeck/manifest/ProjectPathPolicyTests.java
git commit -m "feat: freeze project build contexts"
```

### Task 3: Journal build runs and exact service identity

**Files:**

- Create: `src/main/java/io/labdeck/build/BuildStatus.java`
- Create: `src/main/java/io/labdeck/build/BuildServiceStatus.java`
- Create: `src/main/java/io/labdeck/build/BuildRunRecord.java`
- Create: `src/main/java/io/labdeck/build/BuildServiceRecord.java`
- Create: `src/main/java/io/labdeck/build/BuildRunRepository.java`
- Create: `src/main/java/io/labdeck/persistence/sqlite/SQLiteBuildRunRepository.java`
- Create: `src/main/resources/db/migration/V6__journal_image_builds.sql`
- Test: `src/test/java/io/labdeck/build/BuildRunRecordTests.java`
- Test: `src/test/java/io/labdeck/persistence/sqlite/SQLiteBuildRunRepositoryTests.java`
- Modify test: `src/test/java/io/labdeck/persistence/sqlite/SQLitePersistenceIntegrationTests.java`

**Interfaces:**

- Consumes: validated run/service records and expected current states.
- Produces: constrained durable state with one blocking run per lab and CAS transitions.

- [ ] **Step 1: Write record and repository tests before the migration**

```java
@Test
void permitsOnlyDeclaredTransitions() {
    BuildRunRecord prepared = preparing().transitionTo(BuildStatus.PREPARED, instant);
    assertThat(prepared.status()).isEqualTo(BuildStatus.PREPARED);
    assertThatThrownBy(() -> prepared.transitionTo(BuildStatus.SUCCEEDED, instant))
            .isInstanceOf(IllegalStateException.class);
}

@Test
void blocksASecondOpenRunForOneLab() {
    repository.create(run("run-1", "lab-1"), services("run-1"));
    assertThatThrownBy(() -> repository.create(run("run-2", "lab-1"), services("run-2")))
            .isInstanceOf(DataIntegrityViolationException.class);
}
```

Test digest checks, IDs, output bounds, service order, immutable identity, CAS loss, reopen,
foreign keys, `OUTCOME_UNKNOWN` blocking, and interrupted-run queries.

- [ ] **Step 2: Run persistence tests and confirm failure**

Run: `./mvnw -Dtest=BuildRunRecordTests,SQLiteBuildRunRepositoryTests,SQLitePersistenceIntegrationTests test`

Expected: FAIL because the records, repository, and migration do not exist.

- [ ] **Step 3: Add strict normalized tables**

Create `image_build_run` for run identity, public status, timestamps, expiry, aggregate digests,
bounded safe reason/output, and cleanup state. Create `image_build_service` for service order,
context/build/tar digests, private snapshot path, random ownership token, deterministic tag,
internal dispatch state, exact image ID, and proved cleanup. Add foreign keys to `lab`, length/check
constraints, digest format checks, and a partial unique index over public blocking states
`PREPARING`, `PREPARED`, `RUNNING`, `CANCELLING`, and `OUTCOME_UNKNOWN`.

- [ ] **Step 4: Implement records and transactional CAS repository**

```java
public interface BuildRunRepository {
    void create(BuildRunRecord run, List<BuildServiceRecord> services);
    Optional<BuildRunRecord> findById(String runId);
    Optional<BuildRunRecord> findLatestByLab(String labId);
    List<BuildServiceRecord> findServices(String runId);
    boolean compareAndSetStatus(String runId, BuildStatus expected, BuildStatus next, Instant at);
    void saveServices(String runId, List<BuildServiceRecord> services);
    void saveTerminal(BuildRunRecord run, List<BuildServiceRecord> services);
    List<BuildRunRecord> findInterruptedRuns();
}
```

- [ ] **Step 5: Run record, migration, repository, and reopen tests**

Run: `./mvnw -Dtest=BuildRunRecordTests,SQLiteBuildRunRepositoryTests,SQLitePersistenceIntegrationTests test`

Expected: PASS.

- [ ] **Step 6: Commit the build journal**

```bash
git add src/main/java/io/labdeck/build src/main/java/io/labdeck/persistence/sqlite/SQLiteBuildRunRepository.java src/main/resources/db/migration/V6__journal_image_builds.sql src/test/java/io/labdeck/build src/test/java/io/labdeck/persistence/sqlite
git commit -m "feat: journal local image builds"
```

### Task 4: Add the fixed docker-java tar build boundary

**Files:**

- Create: `src/main/java/io/labdeck/docker/DockerBuildRequest.java`
- Create: `src/main/java/io/labdeck/docker/DockerBuildResult.java`
- Create: `src/main/java/io/labdeck/docker/DockerBuildLogLine.java`
- Create: `src/main/java/io/labdeck/docker/DockerImageInspection.java`
- Create: `src/main/java/io/labdeck/docker/BoundedDockerBuildCallback.java`
- Create: `src/main/java/io/labdeck/docker/DockerBuildException.java`
- Modify: `src/main/java/io/labdeck/docker/DockerEnginePort.java`
- Modify: `src/main/java/io/labdeck/docker/DockerJavaLabEngine.java`
- Modify: `src/main/java/io/labdeck/docker/LabOwnership.java`
- Test: `src/test/java/io/labdeck/docker/BoundedDockerBuildCallbackTests.java`
- Modify test: `src/test/java/io/labdeck/docker/DockerJavaLabEngineTests.java`

**Interfaces:**

- Consumes: a private tar path, relative Dockerfile, one server tag, exact labels, timeout, output consumer, and cancellation token.
- Produces: the returned immutable image ID plus inspected labels, or a typed proved/unknown failure.

- [ ] **Step 1: Write mocked adapter tests before adding the port**

Assert `buildImageCmd(InputStream)` receives tar bytes and only `withDockerfilePath`, one tag,
`withPull(false)`, `withRemove(true)`, `withForcerm(true)`, `withBuildAuthConfigs(Map.of())`, and
exact labels. Verify no push command, auth config, build arg, target, platform, network override,
remote context, or browser value is used.

```java
@Test
void removesOnlyAnExactImageIdAfterLabelsMatch() {
    when(docker.inspectImageCmd(IMAGE_ID).exec()).thenReturn(inspectionWith(EXACT_LABELS));
    engine.removeOwnedImage(IMAGE_ID, EXACT_LABELS);
    verify(docker).removeImageCmd(IMAGE_ID);
    verify(remove).withForce(false);
    verify(remove).withNoPrune(true);
}
```

Also test label mismatch, missing image, callback error, timeout, cancellation before dispatch,
cancellation after dispatch, output truncation, storage-full classification, and unknown outcome.

- [ ] **Step 2: Run Docker adapter tests and confirm failure**

Run: `./mvnw -Dtest=BoundedDockerBuildCallbackTests,DockerJavaLabEngineTests test`

Expected: FAIL because build transfer types and methods do not exist.

- [ ] **Step 3: Add the narrow port**

```java
DockerBuildResult buildImage(
        DockerBuildRequest request,
        Consumer<DockerBuildLogLine> output,
        CancellationToken cancellation);
DockerImageInspection inspectImageById(String imageId);
Optional<DockerImageInspection> reconcileBuiltImage(Map<String, String> exactLabels, String exactTag);
void removeOwnedImage(String imageId, Map<String, String> exactLabels);
```

`reconcileBuiltImage` is allowed only for a journaled dispatched token with no stored image ID. It
must return zero or one exact match and reject multiple matches. Zero does not prove cancellation.

- [ ] **Step 4: Implement bounded callback, timeout, and fixed command options**

Poll callback completion in 100 ms slices so cancellation and the 15-minute deadline are checked.
Close the tar stream and callback on cancellation. Return success only after the callback image ID
is nonblank and exact inspection matches all labels. Do not convert an ambiguous transport close to
`CANCELLED`.

- [ ] **Step 5: Run Docker unit tests**

Run: `./mvnw -Dtest=BoundedDockerBuildCallbackTests,DockerJavaLabEngineTests,DockerClientConfigurationTests test`

Expected: PASS.

- [ ] **Step 6: Commit the exact build adapter**

```bash
git add src/main/java/io/labdeck/docker src/test/java/io/labdeck/docker
git commit -m "feat: build exact images from frozen tar"
```

### Task 5: Coordinate asynchronous prepare, build, expiry, and cancellation

**Files:**

- Create: `src/main/java/io/labdeck/build/BuildOutput.java`
- Create: `src/main/java/io/labdeck/build/BuildRunSnapshot.java`
- Create: `src/main/java/io/labdeck/build/BuildRunCoordinatorException.java`
- Create: `src/main/java/io/labdeck/build/BuildRunService.java`
- Test: `src/test/java/io/labdeck/build/BuildOutputTests.java`
- Test: `src/test/java/io/labdeck/build/BuildRunServiceTests.java`

**Interfaces:**

- Consumes: exact lab/plan review values, snapshotter, repository, Docker port, clock, and generated IDs/tokens.
- Produces: asynchronous `PREPARING`/`PREPARED`/terminal snapshots and cancellation-safe state.

- [ ] **Step 1: Write coordinator state-machine tests first**

Cover no Docker during prepare, `202`-ready `PREPARING`, prepared expiry, exact start echoes,
manifest/build/prepared mismatch, stable service order, two-process limit, one-lab limit, no queue,
success only after exact inspection, partial-run cleanup, timeout, cancel while preparing, cancel
while prepared, cancel while running, ambiguous close, persistence failure, shutdown, and restart.

```java
@Test
void prepareCompletesWithoutAnyDockerCall() {
    BuildRunSnapshot accepted = runs.prepare(lab, plan);
    BuildRunSnapshot prepared = awaitStatus(accepted.id(), BuildStatus.PREPARED);
    assertThat(prepared.canStart()).isTrue();
    verifyNoInteractions(docker);
}
```

- [ ] **Step 2: Run coordinator tests and confirm failure**

Run: `./mvnw -Dtest=BuildOutputTests,BuildRunServiceTests test`

Expected: FAIL because the coordinator does not exist.

- [ ] **Step 3: Implement bounded output and process slots**

Use a zero-queue `ThreadPoolExecutor` with virtual threads and maximum two active runs. Reserve
under one lock before executor submission. Track one active run per lab. `BuildOutput` sanitizes
control text and workspace paths and enforces 4,000 lines, 1 MiB, and 16,384 code points per line.

- [ ] **Step 4: Implement prepare and exact start**

```text
BuildRunService.prepare(LabRecord lab, ManifestPlan plan) -> BuildRunSnapshot
BuildRunService.start(
    labId, runId, expectedRevision,
    expectedManifestSha256, expectedBuildPlanSha256,
    expectedPreparedSnapshotSha256) -> BuildRunSnapshot
BuildRunService.find(labId, runId) -> BuildRunSnapshot
BuildRunService.latest(labId) -> Optional<BuildRunSnapshot>
BuildRunService.cancel(labId, runId) -> BuildRunSnapshot
```

Prepare writes all frozen contexts and becomes `PREPARED` with a 30-minute expiry. Start CASes to
`RUNNING`, builds serially, records `DISPATCHED` before each Docker call, and commits `SUCCEEDED`
only after all exact image inspections. Lab state must be `IMPORTED`, `STOPPED`, or `FAILED`.

- [ ] **Step 5: Implement cancellation and recovery without false proof**

Prepared cancellation deletes only recorded snapshots and becomes `CANCELLED`. Running
cancellation closes the active transfer, waits a bounded interval, removes only exact verified
final IDs, and becomes `CANCELLED` only with proof. Otherwise store `OUTCOME_UNKNOWN`. On startup,
expire `PREPARING`/`PREPARED`; mark `RUNNING`/`CANCELLING` unknown; delete only recorded snapshot
roots; never retry or delete an image blindly.

- [ ] **Step 6: Run all coordinator tests**

Run: `./mvnw -Dtest=BuildOutputTests,BuildRunServiceTests,SQLiteBuildRunRepositoryTests,DockerJavaLabEngineTests test`

Expected: PASS.

- [ ] **Step 7: Commit bounded build coordination**

```bash
git add src/main/java/io/labdeck/build src/test/java/io/labdeck/build
git commit -m "feat: coordinate reviewed local builds"
```

### Task 6: Gate lab start on exact successful build identity

**Files:**

- Create: `src/main/java/io/labdeck/docker/ResolvedServiceImage.java`
- Modify: `src/main/java/io/labdeck/docker/DockerLabLifecycle.java`
- Modify: `src/main/java/io/labdeck/api/LabApiService.java`
- Modify: `src/main/java/io/labdeck/api/LabApiModels.java`
- Modify: `src/main/java/io/labdeck/api/LabPlanMapper.java`
- Modify test: `src/test/java/io/labdeck/docker/DockerLabLifecycleTests.java`
- Modify test: `src/test/java/io/labdeck/api/LabApiServiceTests.java`

**Interfaces:**

- Consumes: normal image sources plus `BuildRunService.requireUsableImages(lab, plan)`.
- Produces: exact per-service image metadata for container creation or a pre-Docker build conflict.

- [ ] **Step 1: Write lifecycle rejection tests first**

Test no build record, stale revision, manifest mismatch, build-plan mismatch, context mismatch after a
fresh safe rescan, label mismatch, replaced image ID, missing image, partial run, expired run, and
`OUTCOME_UNKNOWN`. In every case verify network, volume, and container creation were never called.

```java
@Test
void ambiguousBuildCannotCreateAnyLabResource() {
    when(builds.latest(lab.id())).thenReturn(Optional.of(outcomeUnknownRun()));
    assertThatThrownBy(() -> lifecycle.start(lab, workspace, plan, CancellationToken.NONE))
            .isInstanceOf(BuildException.class)
            .extracting("code").isEqualTo(BuildProblemCode.OUTCOME_UNKNOWN);
    verify(engine, never()).createNetwork(any());
}
```

- [ ] **Step 2: Run lifecycle and API tests and confirm failure**

Run: `./mvnw -Dtest=DockerLabLifecycleTests,LabApiServiceTests test`

Expected: FAIL because lifecycle has only unconditional build rejection.

- [ ] **Step 3: Replace casts with resolved service images**

```java
public record ResolvedServiceImage(
        String service, String displayReference, DockerImageMetadata metadata) {}
```

Resolve `ImageSource` through current inspect/pull confirmation. Resolve `BuildSource` only through
the latest exact `SUCCEEDED` run, a fresh source digest, immutable image ID inspection, and exact
labels. Pass the immutable ID to `DockerContainerSpec` and the server tag only as display text.

- [ ] **Step 4: Keep normal stop and cleanup unchanged for images**

Remove `rejectBuildServices()` only after all rejection tests pass. Do not add `IMAGE` to
`DockerResourceType`, do not journal images in `docker_resource`, and do not remove successful
images on stop. Existing container/network/volume exact cleanup remains unchanged.

- [ ] **Step 5: Run lifecycle, API, and unrelated-resource regression tests**

Run: `./mvnw -Dtest=DockerLabLifecycleTests,LabApiServiceTests,SQLiteDockerResourceJournalTests test`

Expected: PASS.

- [ ] **Step 6: Commit exact start gating**

```bash
git add src/main/java/io/labdeck/docker src/main/java/io/labdeck/api src/test/java/io/labdeck/docker src/test/java/io/labdeck/api
git commit -m "feat: start labs from verified built images"
```

### Task 7: Expose build prepare, review, start, stream, and cancel APIs

**Files:**

- Create: `src/main/java/io/labdeck/api/BuildController.java`
- Create: `src/main/java/io/labdeck/api/BuildLogStreamService.java`
- Modify: `src/main/java/io/labdeck/api/LabApiModels.java`
- Modify: `src/main/java/io/labdeck/api/LabApiService.java`
- Modify: `src/main/java/io/labdeck/api/ApiExceptionHandler.java`
- Test: `src/test/java/io/labdeck/api/BuildControllerContractTests.java`
- Test: `src/test/java/io/labdeck/api/BuildLogStreamServiceTests.java`
- Modify test: `src/test/java/io/labdeck/api/LabControllerContractTests.java`
- Modify test: `src/test/java/io/labdeck/api/LocalApiSecurityTests.java`

**Interfaces:**

- Consumes: closed review/start/cancel requests and bounded status/stream reads.
- Produces: `202 + Location` for prepare/start, no-store status JSON, and bounded SSE.

- [ ] **Step 1: Write all controller and SSE contracts first**

Cover exact routes, `202`, `Location`, no-store, closed JSON, malformed digests, CSRF, Host, Origin,
wrong lab/run IDs, prepare no-Docker proof, start-before-prepared, stale echoes, process limits,
cancellation, stream keepalive/end, queue overflow, disconnect, shutdown, and response redaction.

- [ ] **Step 2: Run API tests and confirm failure**

Run: `./mvnw -Dtest=BuildControllerContractTests,BuildLogStreamServiceTests,LocalApiSecurityTests test`

Expected: FAIL because build HTTP types do not exist.

- [ ] **Step 3: Add exact closed request and safe response records**

```java
public record PrepareBuildRequest(
        @NotNull @PositiveOrZero Long expectedRevision,
        @NotBlank @Pattern(regexp = "sha256:[a-f0-9]{64}") String expectedManifestSha256) {}
public record StartBuildRequest(
        @NotNull @PositiveOrZero Long expectedRevision,
        @NotBlank @Pattern(regexp = "sha256:[a-f0-9]{64}") String expectedManifestSha256,
        @NotBlank @Pattern(regexp = "sha256:[a-f0-9]{64}") String expectedBuildPlanSha256,
        @NotBlank @Pattern(regexp = "sha256:[a-f0-9]{64}") String expectedPreparedSnapshotSha256) {}
public record CancelBuildRequest() {}
```

`BuildRunResponse` includes public IDs, revision, digests, status/reason, timestamps, expiry,
per-service relative context/Dockerfile, counts, bytes, context digest, bounded Dockerfile text,
bounded output/truncation, `canStart`, and `canCancel`. It omits snapshot paths, ownership tokens,
raw Docker errors, and image IDs. Add nullable `latestBuild` to `LabDetailResponse`.

- [ ] **Step 4: Implement routes and stable failures**

Expose prepare, status, start, stream, and cancel exactly as the spec. Map public codes
`BUILD_NOT_REQUIRED`, `BUILD_ALREADY_ACTIVE`, `BUILD_PROCESS_LIMIT_REACHED`, `BUILD_RUN_NOT_FOUND`,
`BUILD_NOT_PREPARED`, `BUILD_EXPIRED`, `BUILD_PLAN_CHANGED`, `BUILD_SNAPSHOT_CHANGED`,
`BUILD_REQUIRED`, `BUILD_OUTDATED`, `BUILD_OUTCOME_UNKNOWN`, and `BUILD_FAILED` to stable safe
statuses and messages.

- [ ] **Step 5: Implement bounded SSE from coordinator events**

Use a 128-event/256-KiB queue, 15-second keepalive, five-minute stream limit, two streams per lab,
four per process, and explicit terminal end reason. A stream cannot expose more output than the
1-MiB run cap and must close on cancellation, terminal state, disconnect, overflow, or shutdown.

- [ ] **Step 6: Run build API, local security, and full Java unit tests**

Run: `./mvnw -Dtest=BuildControllerContractTests,BuildLogStreamServiceTests,LocalApiSecurityTests,LabControllerContractTests test`

Expected: PASS.

Run: `./mvnw test`

Expected: PASS with Docker-gated tests skipped unless `LABDECK_DOCKER_TESTS=true`.

- [ ] **Step 7: Commit the reviewed build API**

```bash
git add src/main/java/io/labdeck/api src/test/java/io/labdeck/api
git commit -m "feat: expose reviewed local build flow"
```

## Plan B completion proof

Run:

```bash
./mvnw test
git status --short
```

Expected: all non-Docker Java tests PASS and the worktree contains only intentional issue #10 work.
