# Issue 10 UI, Integration, and Verification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give students a complete accessible template, ZIP import/export, full-plan review, and prepare → review → start-build experience, then prove the exact final commit before a pull request is opened.

**Architecture:** The frontend API module owns typed JSON, multipart, blob, polling, and SSE boundaries. Focused Vue components own template cards, course-pack actions, the complete lab plan, and the build state machine. Final Java, Docker-isolation, frontend, package, browser, and cleanup proof runs only after all backend and UI tasks are complete.

**Tech Stack:** Vue 3.5.41, TypeScript 6.0.2, Vue Router 5.2.0, Vitest 4.1.11, Vue Test Utils 2.4.11, Vite 8.2.2, Spring Boot/MockMvc, Docker Engine, browser-testing-with-devtools or Playwright CLI

**Spec:** `docs/superpowers/specs/2026-08-27-safe-course-packs-and-local-builds-design.md`

## Global Constraints

- Keep the prepare → review → start-build gate visible and mandatory. Never call start as part of prepare.
- Use a normal text field for the explicit absolute new destination path. A browser file picker supplies only the ZIP.
- Show the full manifest resource plan and prepared build details before Docker actions.
- Show the Dockerfile as bounded read-only text and state that Dockerfile commands can use the normal build network and can print project data.
- Disable Build until status is `PREPARED`, all echoed digests are present, and the warning is visible.
- Treat `OUTCOME_UNKNOWN`, stale digests, expired snapshots, and changed manifests as blocking states.
- Never expose private image IDs, tokens, raw Docker errors, staging paths, or arbitrary file controls.
- All mutations use same-origin cookies and a fresh header-only CSRF token.
- Use text with color for state, keyboard-reachable controls, visible labels, alerts/status regions, focus return, long-text wrapping, and 320px support.
- Before any PR, run all relevant Java, security, Docker-isolation, frontend, package, and browser checks on the exact final commit.

## Locked file structure

- Keep transport types and helpers in `frontend/src/api/lab-api.ts`.
- Create `TemplatesView.vue`, `CoursePackImportPanel.vue`, `LabPlanPanel.vue`, and `BuildPanel.vue`.
- Keep polling and page-level orchestration in `LabsView.vue` and `LabDetailView.vue`.
- Keep component tests beside each component.
- Do not add a frontend state library, UI kit, browser-test dependency, or new production service.

---

### Task 1: Add typed course-pack and build client boundaries

**Files:**

- Modify: `frontend/src/api/lab-api.ts`
- Create test: `frontend/src/api/lab-api.spec.ts`

**Interfaces:**

- Consumes: the exact backend contracts from Plans A and B.
- Produces: typed list/detail/install/import/export and build prepare/status/start/cancel/stream functions.

- [ ] **Step 1: Write fetch-boundary tests first**

Mock `globalThis.fetch`. Prove JSON errors preserve stable `code`, mutations fetch CSRF first,
multipart sets no manual `Content-Type`, download creates a bounded blob, filenames come only from a
safe `Content-Disposition`, and build start echoes all four reviewed values.

```ts
it("sends only the approved multipart fields with header CSRF", async () => {
  await importCoursePack(file, "/Users/student/Courses/new-lab");
  const [, request] = fetchMock.mock.calls.at(-1)!;
  expect(request?.method).toBe("POST");
  expect(new Headers(request?.headers).has("Content-Type")).toBe(false);
  const body = request?.body as FormData;
  expect([...body.keys()]).toEqual(["pack", "destination"]);
});
```

- [ ] **Step 2: Run the client test and confirm failure**

Run: `cd frontend && npm test -- --run src/api/lab-api.spec.ts`

Expected: FAIL because the course-pack and build functions do not exist.

- [ ] **Step 3: Add exact public transport types**

```ts
export type BuildStatus =
  | "PREPARING"
  | "PREPARED"
  | "RUNNING"
  | "CANCELLING"
  | "SUCCEEDED"
  | "FAILED"
  | "CANCELLED"
  | "TIMED_OUT"
  | "EXPIRED"
  | "OUTCOME_UNKNOWN";

export interface BuildRun {
  id: string;
  labId: string;
  labRevision: number;
  manifestSha256: string;
  buildPlanSha256: string;
  preparedSnapshotSha256: string | null;
  status: BuildStatus;
  reason: string | null;
  services: BuildServiceReview[];
  output: string;
  outputTruncated: boolean;
  canStart: boolean;
  canCancel: boolean;
}
```

Extend `LabDetail.plan` with workspace mount, resources, services, images, volumes, tests,
`buildPlanSha256`, and build plans. Add nullable `latestBuild`. Add template summary/detail types.

- [ ] **Step 4: Implement JSON, multipart, blob, and event helpers**

Export:

```ts
loadLabs();
loadTemplates();
loadTemplate(id);
installTemplate(id, destination);
downloadTemplate(id);
importCoursePack(file, destination);
exportLab(lab);
prepareBuild(lab);
loadBuildStatus(labId, runId);
startPreparedBuild(lab, run);
cancelBuild(labId, runId);
openBuildStream(labId, runId, handlers);
```

Validate IDs through `encodeURIComponent`, keep `credentials: 'same-origin'`, parse problem JSON
into `LabApiError(code, detail, status)`, accept ZIP only, and reject response blobs above 16 MiB.

- [ ] **Step 5: Run the API client tests, type check, and lint**

Run: `cd frontend && npm test -- --run src/api/lab-api.spec.ts && npm run type-check && npm run lint`

Expected: PASS.

- [ ] **Step 6: Commit the typed frontend boundary**

```bash
git add frontend/src/api/lab-api.ts frontend/src/api/lab-api.spec.ts
git commit -m "feat: add course pack and build client APIs"
```

### Task 2: Replace the template placeholder with the local gallery

**Files:**

- Create: `frontend/src/views/TemplatesView.vue`
- Create test: `frontend/src/views/TemplatesView.spec.ts`
- Modify: `frontend/src/router.ts`
- Modify: `frontend/src/styles.css`

**Interfaces:**

- Consumes: `loadTemplates`, `loadTemplate`, `installTemplate`, and `downloadTemplate`.
- Produces: an accessible five-card local gallery with detail disclosure, export, and install into one new destination.

- [ ] **Step 1: Write gallery behavior tests first**

Test loading, five stable cards, demo labels, empty/error/retry, detail plan, export busy state,
destination validation, install success navigation, install conflict, keyboard controls, and focus
return after closing detail/install disclosure.

```ts
it("installs only after an explicit new absolute destination is entered", async () => {
  const wrapper = mount(TemplatesView, { global: { plugins: [router] } });
  await flushPromises();
  await wrapper
    .get('[data-template="python-pytest"] [data-action="install"]')
    .trigger("click");
  expect(
    wrapper.get('[data-action="confirm-install"]').attributes("disabled"),
  ).toBeDefined();
  await wrapper
    .get('input[name="destination"]')
    .setValue("/Users/student/Courses/python-lab");
  expect(
    wrapper.get('[data-action="confirm-install"]').attributes("disabled"),
  ).toBeUndefined();
});
```

- [ ] **Step 2: Run the gallery test and confirm failure**

Run: `cd frontend && npm test -- --run src/views/TemplatesView.spec.ts`

Expected: FAIL because the view does not exist.

- [ ] **Step 3: Implement the gallery and replace the route**

Use `aria-busy` while loading, `role="alert"` for failures, `role="status" aria-live="polite"`
for install/export progress, visible labels for destination, and real buttons. Show stack, included
services, limits, test command, build-required state, file list, and `Demo template`. An install
success routes to `/labs/{id}` and never starts or prepares Docker.

- [ ] **Step 4: Add responsive flat card styles**

Use the current color tokens and typography. Keep a one-column layout at 320px, wrap paths/digests,
show text state beside color, and preserve focus outlines. Do not add images or decorative motion.

- [ ] **Step 5: Run gallery, router, app, format, lint, and type checks**

Run: `cd frontend && npm test -- --run src/views/TemplatesView.spec.ts src/App.spec.ts && npm run format:check && npm run lint && npm run type-check`

Expected: PASS.

- [ ] **Step 6: Commit the local template gallery**

```bash
git add frontend/src/views/TemplatesView.vue frontend/src/views/TemplatesView.spec.ts frontend/src/router.ts frontend/src/styles.css
git commit -m "feat: add the local template gallery"
```

### Task 3: Make ZIP import and approved export real

**Files:**

- Create: `frontend/src/components/CoursePackImportPanel.vue`
- Create test: `frontend/src/components/CoursePackImportPanel.spec.ts`
- Modify: `frontend/src/views/LabsView.vue`
- Modify test: `frontend/src/views/LabsView.spec.ts`
- Modify: `frontend/src/styles.css`

**Interfaces:**

- Consumes: `loadLabs`, `importCoursePack`, and `exportLab`.
- Produces: actual labs, strict ZIP selection, explicit destination, import result navigation, and computed export download.

- [ ] **Step 1: Write import/export component tests first**

Cover `.zip` acceptance, other-type rejection, 16-MiB client early rejection, no destination, relative
destination, upload progress, server traversal/limit/destination errors, retry, successful plan
navigation, export revision/hash body, download, busy disable, and no arbitrary file list controls.

```ts
it("does not call import for a relative destination", async () => {
  const wrapper = mount(CoursePackImportPanel);
  await chooseZip(wrapper, validZip());
  await wrapper.get('input[name="destination"]').setValue("relative/lab");
  await wrapper.get("form").trigger("submit");
  expect(importCoursePack).not.toHaveBeenCalled();
  expect(wrapper.get('[role="alert"]').text()).toContain("absolute");
});
```

- [ ] **Step 2: Run the import tests and confirm failure**

Run: `cd frontend && npm test -- --run src/components/CoursePackImportPanel.spec.ts src/views/LabsView.spec.ts`

Expected: FAIL because the real panel and API flow do not exist.

- [ ] **Step 3: Implement import and replace hard-coded demo rows**

Load `/api/v1/labs`, render actual state/revision/update values, and keep a clear empty state. The
panel accepts exactly one ZIP, uses a visible absolute-destination text field, explains that the
destination must not exist, and returns focus to the import trigger when closed. On success, route
to the returned lab detail so the full plan is shown before Docker.

- [ ] **Step 4: Add approved export actions**

Show Export for imported labs with a current plan. The click sends only revision and manifest hash.
Display the server's exact approved-file warning. Do not add file browsing, checkboxes, globs, or
custom paths.

- [ ] **Step 5: Run Labs, import, app, accessibility-state, format, lint, and type checks**

Run: `cd frontend && npm test -- --run src/components/CoursePackImportPanel.spec.ts src/views/LabsView.spec.ts src/App.spec.ts && npm run format:check && npm run lint && npm run type-check`

Expected: PASS.

- [ ] **Step 6: Commit real course-pack actions**

```bash
git add frontend/src/components/CoursePackImportPanel.vue frontend/src/components/CoursePackImportPanel.spec.ts frontend/src/views/LabsView.vue frontend/src/views/LabsView.spec.ts frontend/src/styles.css
git commit -m "feat: import and export course packs"
```

### Task 4: Show the full plan and enforce prepare → review → start-build

**Files:**

- Create: `frontend/src/components/LabPlanPanel.vue`
- Create test: `frontend/src/components/LabPlanPanel.spec.ts`
- Create: `frontend/src/components/BuildPanel.vue`
- Create test: `frontend/src/components/BuildPanel.spec.ts`
- Modify: `frontend/src/views/LabDetailView.vue`
- Modify test: `frontend/src/views/LabDetailView.spec.ts`
- Modify: `frontend/src/styles.css`

**Interfaces:**

- Consumes: full `LabDetail`, prepare/status/start/cancel functions, and build SSE.
- Produces: complete read-only resource review plus a blocking build state machine.

- [ ] **Step 1: Write complete-plan tests first**

Assert the panel displays workspace, memory, CPUs, each service source/command/environment key,
ports, health, volumes, image references, network, test command, build context, Dockerfile path, and
digests without showing environment values, image IDs, or tokens.

- [ ] **Step 2: Write build-gate tests first**

Cover no-build labs, prepare disabled in wrong lab state, `PREPARING`, `PREPARED`, Dockerfile text,
warning, explicit review acknowledgement, exact start echo, progress, bounded/truncated output,
cancel, timeout, expiry, stale plan, changed source, failed build, `OUTCOME_UNKNOWN`, polling retry,
SSE close, reload recovery, and no automatic build start.

```ts
it("never starts Docker until the prepared snapshot is reviewed", async () => {
  const wrapper = mount(BuildPanel, {
    props: { lab: buildLab(), latestBuild: preparedRun() },
  });
  expect(
    wrapper.get('[data-action="start-build"]').attributes("disabled"),
  ).toBeDefined();
  await wrapper.get('input[name="reviewed-build-warning"]').setValue(true);
  await wrapper.get('[data-action="start-build"]').trigger("click");
  expect(startPreparedBuild).toHaveBeenCalledWith(
    expect.objectContaining({ revision: 3 }),
    expect.objectContaining({ status: "PREPARED" }),
  );
});
```

- [ ] **Step 3: Run plan/build/detail tests and confirm failure**

Run: `cd frontend && npm test -- --run src/components/LabPlanPanel.spec.ts src/components/BuildPanel.spec.ts src/views/LabDetailView.spec.ts`

Expected: FAIL because the components and full types do not exist.

- [ ] **Step 4: Implement the complete read-only plan**

Use semantic headings and definition lists. Render direct argv as separate code tokens. Show only
environment key names. Mark the network as a local LabDeck bridge with normal outbound behavior.
State that the workspace remains editable and named volumes persist after stop.

- [ ] **Step 5: Implement the guarded build state machine**

Prepare is one explicit button. When status reaches `PREPARED`, show file count, bytes, relative
paths, context/build/prepared digests, bounded Dockerfile text, and the trust warning. Require a
checkbox labelled `I reviewed this frozen build plan` before enabling Build. Start uses only values
from the displayed run. Any lab refresh or digest mismatch clears acknowledgement and blocks Build.

Use `role="status" aria-live="polite"` for progress and `role="alert"` for blocked/error states.
Provide Refresh for stale/expired state and Cancel only when `canCancel`. Never offer override or
retry for `OUTCOME_UNKNOWN`; tell the user the build is blocked because Docker completion is not
proved.

- [ ] **Step 6: Run all frontend tests and quality checks**

Run: `cd frontend && npm test && npm run format:check && npm run lint && npm run type-check && npm run build`

Expected: PASS.

- [ ] **Step 7: Commit the mandatory build review UI**

```bash
git add frontend/src/components/LabPlanPanel.vue frontend/src/components/LabPlanPanel.spec.ts frontend/src/components/BuildPanel.vue frontend/src/components/BuildPanel.spec.ts frontend/src/views/LabDetailView.vue frontend/src/views/LabDetailView.spec.ts frontend/src/styles.css
git commit -m "feat: require review before local builds"
```

### Task 5: Document the security contract and add exact Docker integration proof

**Files:**

- Create: `docs/decisions/0009-safe-course-packs-and-built-image-ownership.md`
- Modify: `docs/api-v1.md`
- Modify: `docs/manifest-v1.md`
- Modify: `README.md`
- Create test: `src/test/java/io/labdeck/docker/DockerBuildIntegrationTests.java`
- Modify test: `src/test/java/io/labdeck/docker/DockerJavaLabEngineIntegrationTests.java`

**Interfaces:**

- Consumes: the complete backend and frontend behavior.
- Produces: executable Docker proof and accurate public documentation.

- [ ] **Step 1: Write the Docker integration test before final documentation**

Use one unique run token. Create a tiny build workspace and an unrelated running sentinel. Prepare
and assert no Docker resources changed. Change live source and prove the build uses frozen bytes.
Start the prepared run, inspect the returned image ID and exact labels, start/stop the lab, and
prove the sentinel is unchanged. Exercise successful cancellation where the daemon proves it and an
injected ambiguous-cancellation unit path. Clean only exact IDs and label-scoped test resources.

```java
@EnabledIfEnvironmentVariable(named = "LABDECK_DOCKER_TESTS", matches = "true")
class DockerBuildIntegrationTests {
    @Test
    void buildsFrozenContextAndNeverChangesTheUnrelatedSentinel() throws Exception {
        BuildRunSnapshot prepared = awaitPrepared(runs.prepare(lab, plan));
        assertThat(prepared.status()).isEqualTo(BuildStatus.PREPARED);
        Files.writeString(workspace.resolve("source.txt"), "changed after review\n");
        BuildRunSnapshot built = awaitSucceeded(runs.start(
                lab.id(), prepared.id(), lab.revision(), plan.manifestSha256(),
                plan.buildPlanSha256(), prepared.preparedSnapshotSha256()));
        assertThat(built.status()).isEqualTo(BuildStatus.SUCCEEDED);
        assertThat(docker.inspectContainerCmd(sentinelId).exec().getState().getRunning()).isTrue();
    }
}
```

- [ ] **Step 2: Run the exact Docker integration test**

Run: `LABDECK_DOCKER_TESTS=true ./mvnw -Dtest=DockerBuildIntegrationTests test`

Expected: PASS. Record the run token, built image ID digest, sentinel ID, and exact cleanup proof.

- [ ] **Step 3: Write the ADR and update user/API documentation**

Record ZIP-only rationale, limits, descriptor rules, exclusive install, computed export, private
multipart staging, frozen contexts, ignore grammar, digest algorithms, two-step build confirmation,
fixed Docker options, build network risk, base/cache persistence, image journal, exact-ID authority,
cancellation ambiguity, and why image cleanup is absent in V1. List all five template IDs and all
new API routes/problem codes.

- [ ] **Step 4: Run documentation formatting and all focused tests**

Run: `frontend/node_modules/.bin/prettier --check README.md docs/**/*.md`

Expected: PASS.

Run: `./mvnw -Dtest=CoursePackArchiveReaderTests,CoursePackArchiveWriterTests,CoursePackServiceTests,BundledTemplateCatalogTests,BuildContextSnapshotterTests,DeterministicDockerTarWriterTests,SQLiteBuildRunRepositoryTests,DockerJavaLabEngineTests,BuildRunServiceTests,DockerLabLifecycleTests,TemplateControllerContractTests,CoursePackControllerContractTests,BuildControllerContractTests,LocalApiSecurityTests test`

Expected: PASS.

- [ ] **Step 5: Commit integration proof and documentation**

```bash
git add docs README.md src/test/java/io/labdeck/docker
git commit -m "docs: record safe packs and build ownership"
```

### Task 6: Verify the exact final commit before opening a pull request

**Files:**

- No source changes unless a verification failure requires a tested fix.
- Evidence target: issue #10 comment and future PR body after every required check passes.

**Interfaces:**

- Consumes: exact final commit SHA.
- Produces: PASS/FAIL/NOT_RUN/UNKNOWN evidence, scoped Docker cleanup proof, browser screenshots, and only then a PR.

- [ ] **Step 1: Start from a clean exact commit**

Run:

```bash
git status --short
git rev-parse HEAD
git diff --check origin/main...HEAD
```

Expected: clean status, one recorded SHA, and no whitespace errors.

- [ ] **Step 2: Reinstall locked frontend dependencies and run every frontend check**

Run:

```bash
cd frontend
npm ci
npm run format:check
npm run lint
npm run type-check
npm test
npm audit --audit-level=high
```

Expected: every command PASS and zero high-or-higher audit findings.

- [ ] **Step 3: Run the full packaged application and Docker suite**

Run:

```bash
LABDECK_DOCKER_TESTS=true ./mvnw --batch-mode --no-transfer-progress clean package
```

Expected: Java tests, security tests, Docker integration tests, frontend package build, and executable JAR packaging all PASS.

- [ ] **Step 4: Run live browser checks from the built JAR**

Start the exact JAR on a free loopback port and a temporary LabDeck data directory. Use the
browser-testing-with-devtools skill or Playwright CLI with an isolated profile. Test 1440×900,
390×844, and the 320px minimum. Complete:

1. Template gallery loads exactly five demo templates.
2. One template exports a ZIP.
3. ZIP import installs only into a new destination and opens the full plan.
4. Prepare shows the frozen digest and Dockerfile without Docker activity.
5. Review acknowledgement enables Build; Build streams bounded logs.
6. Successful build reaches `SUCCEEDED`; the API integration check starts and stops that exact lab.
7. Cancel shows a proved terminal or honest unknown state.
8. Export uses the approved set.
9. Keyboard order, visible focus, alerts/status, wrapping, and no horizontal overflow pass.
10. Browser console has zero application errors or warnings.

Save screenshots under the normal ignored evidence directory. Record timing as measured or
`NOT_MEASURED`; never infer it.

- [ ] **Step 5: Prove exact Docker isolation and cleanup**

List only resources with the test run's exact LabDeck labels. Verify unrelated sentinel resources
are unchanged. Stop the exact test lab. Remove only exact test images whose stored image IDs and all
labels match. Verify no test containers, networks, volumes, or images remain. Do not use prune,
broad cleanup, or an unresolved shell variable.

- [ ] **Step 6: Push the final commit and wait for branch CI**

Run:

```bash
git push origin codex/safe-course-packs-local-builds
gh run list --branch codex/safe-course-packs-local-builds --limit 3
```

Wait for source detection, frontend quality, and package application to finish. Required result:
all checks PASS on the exact final SHA.

- [ ] **Step 7: Open the PR only after all prior proof passes**

Create the PR with issue #10 scope, exact SHA, test counts, commands, CI links, screenshots, Docker
sentinel/cleanup proof, security boundaries, known limits, and `Closes #10`. Do not merge until the
PR head is reviewed and all required checks are green.

## Plan C completion proof

Issue #10 is implementation-complete only when Task 6 has no required `FAIL`, `NOT_RUN`, or
`UNKNOWN` state. A platform limit can remain documented only if it is outside a required issue #10
acceptance criterion. The PR must not be opened early.
