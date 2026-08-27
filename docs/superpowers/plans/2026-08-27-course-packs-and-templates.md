# Course Packs and Templates Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add five safe local templates and strict deterministic ZIP import, install, and export without any Docker action.

**Architecture:** A `coursepack` package owns archive paths, descriptors, extraction, export, and new-destination installation. A separate `templates` catalog loads checked-in fixtures through the same validators. Controllers use small application services and the existing lab importer so every successful install returns the normal complete lab plan.

**Tech Stack:** Java 25, Spring Boot 4.1.1, Apache Commons Compress 1.28.0, Jackson 3, JUnit 5, AssertJ, MockMvc

**Spec:** `docs/superpowers/specs/2026-08-27-safe-course-packs-and-local-builds-design.md`

## Global Constraints

- Accept one ZIP only. The fixed archive root is `labdeck-course-pack/`.
- Limit the request to 17 MiB, the ZIP to 16 MiB, 1,024 entries, 64 MiB extracted, and 16 MiB per file.
- Limit a ZIP path to 240 UTF-8 bytes and 16 segments. Limit compression ratio to 100:1.
- Require `labdeck-pack.json`, `labdeck.yml`, and `README.md`.
- Reject path escape, links, special files, encrypted or split archives, duplicates, case or Unicode collisions, nested archives, hidden credential paths, extra files, and bad digests.
- Install only into an absent destination leaf under a real existing parent. Never merge or overwrite.
- The browser cannot choose arbitrary export files. Export uses the computed approved set only.
- Import and template install must return the full plan before any Docker call.
- Keep local Host, Origin, peer, session-cookie, and header-only CSRF checks.
- Do not add a general file manager, archive browser, registry client, image cleanup, telemetry, cloud service, or account.

## Locked file structure

### Storage and archive policy

- Create `src/main/java/io/labdeck/storage/LocalStoragePaths.java` for owner-only operation roots.
- Create `src/main/java/io/labdeck/config/MultipartConfiguration.java` for the exact servlet limits and upload location.
- Create `src/main/java/io/labdeck/coursepack/CoursePackLimits.java` for injected production and test limits.
- Create `src/main/java/io/labdeck/coursepack/CoursePackDescriptor.java` for the closed descriptor model.
- Create `src/main/java/io/labdeck/coursepack/CoursePackProblemCode.java` and `CoursePackException.java` for stable failures.
- Create `src/main/java/io/labdeck/coursepack/CoursePackPathPolicy.java` for portable names and the credential denylist.
- Create `src/main/java/io/labdeck/coursepack/CoursePackDescriptorCodec.java` for strict canonical JSON.
- Create `src/main/java/io/labdeck/coursepack/CoursePackArchiveReader.java` for central-directory checks and extraction.
- Create `src/main/java/io/labdeck/coursepack/CoursePackArchiveWriter.java` for deterministic ZIP output.
- Create `src/main/java/io/labdeck/coursepack/ApprovedDestinationPath.java` and `CoursePackDestinationPolicy.java` for exclusive install and rollback.
- Create `src/main/java/io/labdeck/coursepack/CoursePackService.java` for inspect, install, and export orchestration.

### Templates and HTTP

- Create `src/main/java/io/labdeck/templates/BundledTemplate.java` and `BundledTemplateCatalog.java`.
- Create five fixtures under `src/main/resources/templates/`.
- Create `src/main/java/io/labdeck/api/LabPlanMapper.java` so labs and templates use one public plan shape.
- Create `src/main/java/io/labdeck/api/CoursePackApiService.java` and `CoursePackController.java`.
- Extend `TemplateController.java`, `LabController.java`, `LabApiModels.java`, `LabApiService.java`, `ApiExceptionHandler.java`, `SettingsController.java`, `application.yml`, and `pom.xml`.

---

### Task 1: Add the archive dependency and private upload roots

**Files:**

- Modify: `pom.xml`
- Modify: `src/main/resources/application.yml`
- Create: `src/main/java/io/labdeck/storage/LocalStoragePaths.java`
- Create: `src/main/java/io/labdeck/config/MultipartConfiguration.java`
- Test: `src/test/java/io/labdeck/storage/LocalStoragePathsTests.java`
- Test: `src/test/java/io/labdeck/config/MultipartConfigurationTests.java`

**Interfaces:**

- Consumes: `labdeck.data-directory`.
- Produces: `LocalStoragePaths.root()`, `uploads()`, and `operations()` as real owner-only directories; one `MultipartConfigElement` with fixed limits.

- [ ] **Step 1: Write failing storage and multipart tests**

```java
@Test
void createsPrivateNonSymlinkOperationRoots(@TempDir Path parent) throws Exception {
    LocalStoragePaths paths = LocalStoragePaths.create(parent.resolve("data"));
    assertThat(paths.uploads()).isDirectory();
    assertThat(paths.operations()).isDirectory();
    assertThat(Files.isSymbolicLink(paths.uploads())).isFalse();
    if (Files.getFileStore(paths.root()).supportsFileAttributeView("posix")) {
        assertThat(Files.getPosixFilePermissions(paths.uploads()))
                .containsExactlyInAnyOrder(OWNER_READ, OWNER_WRITE, OWNER_EXECUTE);
    }
}

@Test
void fixesMultipartLimitsAndPrivateLocation(@TempDir Path data) {
    MultipartConfigElement element = new MultipartConfiguration()
            .multipartConfig(LocalStoragePaths.create(data));
    assertThat(element.getMaxFileSize()).isEqualTo(16L * 1024 * 1024);
    assertThat(element.getMaxRequestSize()).isEqualTo(17L * 1024 * 1024);
    assertThat(element.getLocation()).isEqualTo(data.resolve("uploads").toString());
}
```

- [ ] **Step 2: Run the focused tests and confirm the missing types fail compilation**

Run: `./mvnw -Dtest=LocalStoragePathsTests,MultipartConfigurationTests test`

Expected: FAIL because the two production classes do not exist.

- [ ] **Step 3: Add the pinned dependency and exact server limits**

Add `commons-compress.version` value `1.28.0` and dependency coordinates
`org.apache.commons:commons-compress`. This is the current release named by the
[official Apache project page](https://commons.apache.org/proper/commons-compress/). Set:

```yaml
spring:
  servlet:
    multipart:
      enabled: true
      file-size-threshold: 0
      max-file-size: 16MB
      max-request-size: 17MB
server:
  tomcat:
    max-http-form-post-size: 17MB
    max-part-count: 2
    max-swallow-size: 17MB
```

- [ ] **Step 4: Implement safe storage creation and the multipart bean**

```text
LocalStoragePaths.create(Path requestedRoot) -> LocalStoragePaths
LocalStoragePaths.root() -> Path
LocalStoragePaths.uploads() -> Path
LocalStoragePaths.operations() -> Path
```

```java
@Bean
MultipartConfigElement multipartConfig(LocalStoragePaths paths) {
    MultipartConfigFactory factory = new MultipartConfigFactory();
    factory.setLocation(paths.uploads().toString());
    factory.setFileSizeThreshold(DataSize.ofBytes(0));
    factory.setMaxFileSize(DataSize.ofMebibytes(16));
    factory.setMaxRequestSize(DataSize.ofMebibytes(17));
    return factory.createMultipartConfig();
}
```

- [ ] **Step 5: Run the focused tests and the existing local API security tests**

Run: `./mvnw -Dtest=LocalStoragePathsTests,MultipartConfigurationTests,LocalApiSecurityTests test`

Expected: PASS.

- [ ] **Step 6: Commit the archive foundation**

```bash
git add pom.xml src/main/resources/application.yml src/main/java/io/labdeck/storage src/main/java/io/labdeck/config/MultipartConfiguration.java src/test/java/io/labdeck/storage src/test/java/io/labdeck/config/MultipartConfigurationTests.java
git commit -m "feat: add private course pack staging"
```

### Task 2: Define portable paths and the strict descriptor

**Files:**

- Create: `src/main/java/io/labdeck/coursepack/CoursePackLimits.java`
- Create: `src/main/java/io/labdeck/coursepack/CoursePackDescriptor.java`
- Create: `src/main/java/io/labdeck/coursepack/CoursePackProblemCode.java`
- Create: `src/main/java/io/labdeck/coursepack/CoursePackException.java`
- Create: `src/main/java/io/labdeck/coursepack/CoursePackPathPolicy.java`
- Create: `src/main/java/io/labdeck/coursepack/CoursePackDescriptorCodec.java`
- Test: `src/test/java/io/labdeck/coursepack/CoursePackPathPolicyTests.java`
- Test: `src/test/java/io/labdeck/coursepack/CoursePackDescriptorCodecTests.java`

**Interfaces:**

- Consumes: raw ZIP names and at most 256 KiB of descriptor bytes.
- Produces: normalized `CoursePackDescriptor(format, files)` with sorted `CoursePackFile(path, sha256)` entries.

- [ ] **Step 1: Write failing path-policy parameter tests**

```java
@ParameterizedTest
@ValueSource(strings = {
    "../secret", "/etc/passwd", "C:/Windows/file", "src\\main.py", "a//b", "a/./b",
    "a/../b", ".env", "src/.npmrc", "keys/id_rsa", "cert/server.key", "a. "
})
void rejectsUnsafeOrSensitiveNames(String value) {
    assertThatThrownBy(() -> CoursePackPathPolicy.normalizeFile(value))
            .isInstanceOf(CoursePackException.class);
}

@Test
void rejectsNul() {
    assertThatThrownBy(() -> CoursePackPathPolicy.normalizeFile("a" + '\0' + "b"))
            .isInstanceOf(CoursePackException.class);
}

@Test
void rejectsUnicodeAndCaseCollisions() {
    assertThatThrownBy(() -> CoursePackPathPolicy.requireUnique(List.of("src/Caf\u00e9.py", "src/Cafe\u0301.py")))
            .isInstanceOf(CoursePackException.class);
    assertThatThrownBy(() -> CoursePackPathPolicy.requireUnique(List.of("README.md", "readme.md")))
            .isInstanceOf(CoursePackException.class);
}
```

- [ ] **Step 2: Write failing descriptor tests**

```java
@Test
void acceptsOnlyCanonicalClosedDescriptor() {
    byte[] json = """
            {"format":"labdeck-course-pack-v1","files":[
              {"path":"README.md","sha256":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},
              {"path":"labdeck.yml","sha256":"sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}
            ]}
            """.getBytes(UTF_8);
    CoursePackDescriptor value = codec.decode(json);
    assertThat(value.files()).extracting(CoursePackDescriptor.CoursePackFile::path)
            .containsExactly("README.md", "labdeck.yml");
}
```

Also test unknown fields, duplicate JSON keys, trailing JSON, unsorted paths, duplicate paths,
descriptor self-listing, invalid digests, missing `labdeck.yml`, and missing `README.md`.

- [ ] **Step 3: Run the focused tests and confirm failure**

Run: `./mvnw -Dtest=CoursePackPathPolicyTests,CoursePackDescriptorCodecTests test`

Expected: FAIL because the course-pack policy classes do not exist.

- [ ] **Step 4: Implement the exact records and stable codes**

```java
public record CoursePackLimits(
        long zipBytes, int entries, long extractedBytes, long fileBytes,
        int ratio, int pathBytes, int pathSegments, int descriptorBytes) {
    public static CoursePackLimits production() {
        return new CoursePackLimits(16L << 20, 1_024, 64L << 20, 16L << 20, 100, 240, 16, 256 << 10);
    }
}

public record CoursePackDescriptor(String format, List<CoursePackFile> files) {
    public static final String FORMAT = "labdeck-course-pack-v1";
    public record CoursePackFile(String path, String sha256) {}
}

public enum CoursePackProblemCode {
    INVALID_ARCHIVE, LIMIT_EXCEEDED, PATH_REJECTED, ENTRY_TYPE_REJECTED,
    DESCRIPTOR_INVALID, DIGEST_MISMATCH, DESTINATION_EXISTS, DESTINATION_UNSAFE,
    SOURCE_CHANGED, TEMPLATE_NOT_FOUND
}
```

- [ ] **Step 5: Implement normalization and strict canonical JSON**

Use Unicode NFC and `Locale.ROOT` case folding. Permit `.gitignore` as the only hidden segment.
Treat `labdeck-pack.json` as the one file not listed in its own descriptor. Encode fields in the
exact order `format`, then `files`; encode each file as `path`, then `sha256`; end with one LF.

- [ ] **Step 6: Run the focused tests**

Run: `./mvnw -Dtest=CoursePackPathPolicyTests,CoursePackDescriptorCodecTests test`

Expected: PASS.

- [ ] **Step 7: Commit the portable format**

```bash
git add src/main/java/io/labdeck/coursepack src/test/java/io/labdeck/coursepack
git commit -m "feat: define strict course pack format"
```

### Task 3: Read and write deterministic ZIP files

**Files:**

- Create: `src/main/java/io/labdeck/coursepack/ApprovedPackFile.java`
- Create: `src/main/java/io/labdeck/coursepack/ValidatedCoursePack.java`
- Create: `src/main/java/io/labdeck/coursepack/CoursePackArchiveReader.java`
- Create: `src/main/java/io/labdeck/coursepack/CoursePackArchiveWriter.java`
- Test: `src/test/java/io/labdeck/coursepack/CoursePackArchiveReaderTests.java`
- Test: `src/test/java/io/labdeck/coursepack/CoursePackArchiveWriterTests.java`

**Interfaces:**

- Consumes: a staged ZIP path or a sorted set of already-approved regular files.
- Produces: an extracted validated root with verified digests, or deterministic bounded ZIP bytes.

- [ ] **Step 1: Write malicious archive tests before the reader**

Create ZIP fixtures in the test with `ZipArchiveOutputStream`. Cover `../`, absolute, drive-letter,
backslash, NUL/raw-name failure, symlink Unix mode, device/FIFO mode, duplicate raw names, Unicode
and case collisions, encryption flag, nested ZIP magic, undeclared entry, missing declared entry,
bad digest, too many entries, one-file limit, total limit, and 100:1 ratio breach.

```java
@Test
void traversalNeverWritesOutsideStaging(@TempDir Path root) throws Exception {
    Path zip = archive(root.resolve("pack.zip"), entry("labdeck-course-pack/../escape", "owned"));
    assertThatThrownBy(() -> reader.validateAndExtract(zip, root.resolve("stage")))
            .isInstanceOf(CoursePackException.class)
            .extracting("code").isEqualTo(CoursePackProblemCode.PATH_REJECTED);
    assertThat(root.resolve("escape")).doesNotExist();
}
```

- [ ] **Step 2: Run the reader tests and confirm failure**

Run: `./mvnw -Dtest=CoursePackArchiveReaderTests test`

Expected: FAIL because the reader does not exist.

- [ ] **Step 3: Implement central-directory validation and exclusive extraction**

Use `org.apache.commons.compress.archivers.zip.ZipFile` over the staged regular file. Reject an
entry when `canReadEntryData` is false, encryption is set, the Unix type is not regular/directory,
the entry is a Unix symlink, its disk start is not zero, or its normalized name violates policy.
Directory entries are optional on input. Count declared and actual bytes. Open output files with
`CREATE_NEW`, `WRITE`, and `NOFOLLOW_LINKS`. Hash while copying, then recheck the descriptor.

- [ ] **Step 4: Write deterministic writer tests before the writer**

```java
@Test
void sameApprovedFilesProduceIdenticalZipBytes(@TempDir Path root) throws Exception {
    ApprovedPackFile manifest = approved(root, "labdeck.yml", "version: 1\n");
    ApprovedPackFile readme = approved(root, "README.md", "# Demo\n");
    byte[] first = writer.write(List.of(manifest, readme));
    byte[] second = writer.write(List.of(readme, manifest));
    assertThat(second).isEqualTo(first);
}
```

Assert root and parent directories precede sorted files, all times use
`1980-01-01T00:00:00Z`, files use mode `0644`, directories use `0755`, UTF-8 is fixed, Zip64 is
disabled, and the generated descriptor matches the bytes.

- [ ] **Step 5: Implement the bounded deterministic writer**

Write the fixed root, sorted parent directories, descriptor, and sorted approved files through
`ZipArchiveOutputStream`. Use level 9 DEFLATE and reject output once it exceeds 16 MiB. Read each
source with `NOFOLLOW_LINKS`; compare file key, size, and modified time before and after; fail with
`SOURCE_CHANGED` if any value changes.

- [ ] **Step 6: Run both archive suites**

Run: `./mvnw -Dtest=CoursePackArchiveReaderTests,CoursePackArchiveWriterTests test`

Expected: PASS.

- [ ] **Step 7: Commit archive validation and deterministic output**

```bash
git add src/main/java/io/labdeck/coursepack src/test/java/io/labdeck/coursepack
git commit -m "feat: validate and write course pack archives"
```

### Task 4: Install only into a new destination and export only approved files

**Files:**

- Create: `src/main/java/io/labdeck/coursepack/ApprovedDestinationPath.java`
- Create: `src/main/java/io/labdeck/coursepack/CoursePackDestinationPolicy.java`
- Create: `src/main/java/io/labdeck/coursepack/PendingCoursePackInstall.java`
- Create: `src/main/java/io/labdeck/coursepack/CoursePackDownload.java`
- Create: `src/main/java/io/labdeck/coursepack/CoursePackService.java`
- Test: `src/test/java/io/labdeck/coursepack/CoursePackDestinationPolicyTests.java`
- Test: `src/test/java/io/labdeck/coursepack/CoursePackServiceTests.java`

**Interfaces:**

- Consumes: a staged validated archive, an absent absolute destination, and existing lab workspace/plan data for export.
- Produces: a rollback-safe installed workspace or a bounded in-memory ZIP download.

- [ ] **Step 1: Write failing destination and rollback tests**

```java
@Test
void rejectsEveryExistingDestination(@TempDir Path parent) throws Exception {
    Path existing = Files.createDirectory(parent.resolve("existing"));
    assertThatThrownBy(() -> policy.reserve(existing))
            .isInstanceOf(CoursePackException.class)
            .extracting("code").isEqualTo(CoursePackProblemCode.DESTINATION_EXISTS);
}

@Test
void failedInstallRemovesOnlyItsNewLeaf(@TempDir Path parent) {
    Path sibling = write(parent.resolve("keep.txt"), "keep");
    assertThatThrownBy(() -> service.install(badPack, parent.resolve("new-lab")))
            .isInstanceOf(CoursePackException.class);
    assertThat(sibling).hasContent("keep");
    assertThat(parent.resolve("new-lab")).doesNotExist();
}
```

Also cover parent symlink, root destination, home/system destination, relative input, parent
replacement, destination replacement, exclusive-copy collision, and cleanup failure reporting.

- [ ] **Step 2: Run the focused tests and confirm failure**

Run: `./mvnw -Dtest=CoursePackDestinationPolicyTests,CoursePackServiceTests test`

Expected: FAIL because destination and service types do not exist.

- [ ] **Step 3: Implement exclusive reservation and scoped rollback**

```text
PendingCoursePackInstall.workspace() -> Path
PendingCoursePackInstall.commit() -> void
PendingCoursePackInstall.close() -> void, reverse-delete only recorded files and the request-created leaf
CoursePackService.install(Path stagedZip, Path requestedDestination) -> PendingCoursePackInstall
CoursePackService.export(Path workspace, ManifestPlan plan, long expectedRevision) -> CoursePackDownload
```

Validate the full archive and manifest in private staging before reserving the destination. Copy
only descriptor-listed files with exclusive creation. Load the installed manifest again before
returning the pending install.

- [ ] **Step 4: Implement the computed export set**

Use the union of `labdeck.yml`, regular `README.md` or a generated README, paths already listed in
the descriptor, and regular files in validated build contexts. Apply the hard path, type,
credential-name, count, and byte limits before reading. `.dockerignore` controls only bytes sent to
Docker; it does not let the browser expand or narrow the export set. The browser never sends paths.

- [ ] **Step 5: Add round-trip and source-change tests**

Prove export → import preserves every approved byte and manifest plan. Prove `.git`, secret-name
files, links, special files, unlisted non-build files, changed files, and workspace replacement are
rejected or excluded as specified.

- [ ] **Step 6: Run the focused service tests**

Run: `./mvnw -Dtest=CoursePackDestinationPolicyTests,CoursePackServiceTests test`

Expected: PASS.

- [ ] **Step 7: Commit destination and export policy**

```bash
git add src/main/java/io/labdeck/coursepack src/test/java/io/labdeck/coursepack
git commit -m "feat: install and export approved course packs"
```

### Task 5: Add the five validated bundled templates

**Files:**

- Create: `src/main/java/io/labdeck/templates/BundledTemplate.java`
- Create: `src/main/java/io/labdeck/templates/BundledTemplateCatalog.java`
- Create: `src/main/resources/templates/python-pytest/labdeck-course-pack/`
- Create: `src/main/resources/templates/node-npm/labdeck-course-pack/`
- Create: `src/main/resources/templates/java-maven/labdeck-course-pack/`
- Create: `src/main/resources/templates/cpp-cmake/labdeck-course-pack/`
- Create: `src/main/resources/templates/python-data-science/labdeck-course-pack/`
- Test: `src/test/java/io/labdeck/templates/BundledTemplateCatalogTests.java`

**Interfaces:**

- Consumes: checked-in synthetic fixture bytes.
- Produces: stable ordered template metadata, validated plans, deterministic ZIP bytes, and install sources.

- [ ] **Step 1: Write the catalog contract first**

```java
@Test
void exposesExactlyFiveStableValidatedTemplates() {
    assertThat(catalog.list()).extracting(BundledTemplate::id).containsExactly(
            "python-pytest", "node-npm", "java-maven", "cpp-cmake", "python-data-science");
    catalog.list().forEach(template -> {
        assertThat(template.plan().schemaVersion()).isEqualTo(1);
        assertThat(template.synthetic()).isTrue();
        assertThat(template.archive()).isNotEmpty();
        assertThat(catalog.get(template.id()).archive()).isEqualTo(template.archive());
    });
}
```

- [ ] **Step 2: Run the catalog test and confirm failure**

Run: `./mvnw -Dtest=BundledTemplateCatalogTests test`

Expected: FAIL because no catalog or fixtures exist.

- [ ] **Step 3: Verify and use the pinned public base image digests**

Verify the immutable references without pulling images or changing local Docker state:

```bash
docker manifest inspect python@sha256:0f5b26b9518d002b6173fd61daad821fa340635ebfec5bba471013f9ca114579 >/dev/null
docker manifest inspect node@sha256:ba849c60be29959425b8734d57b8b4b7d56f98edd9504c9af091d5281095a71e >/dev/null
docker manifest inspect maven@sha256:d67198007bb4441b07d45587320f83154de80ece3608f80408ef14c6ea847753 >/dev/null
docker manifest inspect gcc@sha256:9ca91b05c7b07d2979f16413e8b2cd6ec8a7c80ffca4121ccab0aeba33f90460 >/dev/null
```

Use these exact tag-and-manifest-list-digest pairs in the Dockerfile `FROM` lines:

```text
python:3.12-slim-bookworm@sha256:0f5b26b9518d002b6173fd61daad821fa340635ebfec5bba471013f9ca114579
node:24-bookworm-slim@sha256:ba849c60be29959425b8734d57b8b4b7d56f98edd9504c9af091d5281095a71e
maven:3.9-eclipse-temurin-25@sha256:d67198007bb4441b07d45587320f83154de80ece3608f80408ef14c6ea847753
gcc:15-bookworm@sha256:9ca91b05c7b07d2979f16413e8b2cd6ec8a7c80ffca4121ccab0aeba33f90460
```

These public manifest-list digests were verified on 2026-08-27. The Python data-science template
uses the same pinned Python base and installs pinned JupyterLab/test dependencies. Do not use
`latest`, private registries, credentials, build arguments, or remote `ADD` URLs. Save the exact
verification commands and digests in the issue resume comment.

- [ ] **Step 4: Create original minimal fixture content**

Use these stable IDs and direct assignment-test commands:

```text
python-pytest:          ["python", "-m", "pytest", "-q"]
node-npm:               ["npm", "test"]
java-maven:             ["mvn", "-q", "test"]
cpp-cmake:              ["make", "test"]
python-data-science:    ["python", "-m", "pytest", "-q"]
```

Each pack contains a small README, original source, one passing deterministic test, one local
Dockerfile, a constrained `labdeck.yml`, and a descriptor with real SHA-256 values. The notebook is
small fixed JSON and no notebook server starts by default. Keep all dependency locks explicit.

- [ ] **Step 5: Implement catalog startup validation**

```java
public record BundledTemplate(
        String id, String name, String stack, String description, boolean synthetic,
        ManifestPlan plan, byte[] archive) {}

public interface BundledTemplateCatalog {
    List<BundledTemplate> list();
    BundledTemplate get(String id);
}
```

Load each resource through `CoursePackArchiveReader`, `WorkspaceManifestLoader`, and
`CoursePackArchiveWriter`. Fail application startup if any fixture, digest, plan, or deterministic
round trip differs.

- [ ] **Step 6: Run catalog, manifest, and compiler tests**

Run: `./mvnw -Dtest=BundledTemplateCatalogTests,ManifestSchemaTests,ManifestPlanCompilerTests test`

Expected: PASS.

- [ ] **Step 7: Commit the original templates**

```bash
git add src/main/java/io/labdeck/templates src/main/resources/templates src/test/java/io/labdeck/templates
git commit -m "feat: add five safe local templates"
```

### Task 6: Expose closed template and course-pack APIs

**Files:**

- Create: `src/main/java/io/labdeck/api/LabPlanMapper.java`
- Create: `src/main/java/io/labdeck/api/CoursePackApiService.java`
- Create: `src/main/java/io/labdeck/api/CoursePackController.java`
- Modify: `src/main/java/io/labdeck/api/TemplateController.java`
- Modify: `src/main/java/io/labdeck/api/LabController.java`
- Modify: `src/main/java/io/labdeck/api/LabApiModels.java`
- Modify: `src/main/java/io/labdeck/api/LabApiService.java`
- Modify: `src/main/java/io/labdeck/api/ApiExceptionHandler.java`
- Modify: `src/main/java/io/labdeck/api/SettingsController.java`
- Test: `src/test/java/io/labdeck/api/TemplateControllerContractTests.java`
- Test: `src/test/java/io/labdeck/api/CoursePackControllerContractTests.java`
- Test: `src/test/java/io/labdeck/api/LabCoursePackIntegrationTests.java`
- Modify test: `src/test/java/io/labdeck/api/LocalApiSecurityTests.java`

**Interfaces:**

- Consumes: exact template IDs, one multipart `pack`, one multipart `destination`, and closed JSON mutations.
- Produces: safe template/detail responses, full imported lab details, and bounded ZIP downloads.

- [ ] **Step 1: Write failing controller contracts for every route**

Cover list, detail, template export, template install, ZIP import, lab export, unknown template,
unknown/repeated/missing multipart parts, wrong media types, oversized requests, missing CSRF,
hostile Host/Origin/peer, no-store headers, safe attachment names, and no Docker collaborator calls.

```java
@Test
void importReturnsFullPlanWithoutCallingDocker() throws Exception {
    mvc.perform(multipart("/api/v1/course-packs/import")
                    .file(validPackPart())
                    .param("destination", destination.toString())
                    .session(session).header("X-LabDeck-CSRF", token))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.plan.services").isArray())
            .andExpect(jsonPath("$.plan.resources.memoryBytes").isNumber());
    verifyNoInteractions(dockerEngine);
}
```

- [ ] **Step 2: Run the contracts and confirm failure**

Run: `./mvnw -Dtest=TemplateControllerContractTests,CoursePackControllerContractTests,LabCoursePackIntegrationTests test`

Expected: FAIL because the routes and models do not exist.

- [ ] **Step 3: Add exact API records and one plan mapper**

```java
public record InstallTemplateRequest(@NotBlank @Size(max = 4096) String destination) {}
public record ExportLabRequest(
        @NotNull @PositiveOrZero Long expectedRevision,
        @NotBlank @Pattern(regexp = "sha256:[a-f0-9]{64}") String expectedManifestSha256) {}
public record TemplateSummaryResponse(
        String id, String name, String stack, String description, boolean synthetic, boolean buildRequired) {}
public record TemplateDetailResponse(
        String apiVersion, String id, String name, String stack, String description,
        boolean synthetic, List<String> files, ManifestPlanResponse plan) {}
```

Keep private image IDs, ownership tokens, staging paths, and raw archive names out of all responses.

- [ ] **Step 4: Implement the routes and rollback-safe application service**

Expose the six spec routes. `CoursePackApiService` must keep `PendingCoursePackInstall` uncommitted
until `LabApiService.importLab()` succeeds; any failure closes it and removes only the new leaf.
Return ZIP bytes with `application/zip`, `Cache-Control: no-store`, `nosniff`, and a server-made
ASCII filename. Validate the exact multipart part map before opening the uploaded archive.

- [ ] **Step 5: Map stable problems and mark templates available**

Map the course-pack codes to `400`, `404`, `409`, `413`, or `422` as appropriate. Use public codes
`COURSE_PACK_INVALID`, `COURSE_PACK_LIMIT_EXCEEDED`, `COURSE_PACK_PATH_REJECTED`,
`COURSE_PACK_DESTINATION_EXISTS`, `COURSE_PACK_DESTINATION_UNSAFE`, `COURSE_PACK_CHANGED`, and
`TEMPLATE_NOT_FOUND`. Change settings and template list capability from `PLANNED` to `AVAILABLE`.

- [ ] **Step 6: Run course-pack, API, and security suites**

Run: `./mvnw -Dtest=CoursePackPathPolicyTests,CoursePackDescriptorCodecTests,CoursePackArchiveReaderTests,CoursePackArchiveWriterTests,CoursePackDestinationPolicyTests,CoursePackServiceTests,BundledTemplateCatalogTests,TemplateControllerContractTests,CoursePackControllerContractTests,LabCoursePackIntegrationTests,LocalApiSecurityTests test`

Expected: PASS.

- [ ] **Step 7: Commit the safe local APIs**

```bash
git add src/main/java/io/labdeck/api src/test/java/io/labdeck/api
git commit -m "feat: expose safe course pack and template APIs"
```

## Plan A completion proof

Run:

```bash
./mvnw -Dtest=CoursePackPathPolicyTests,CoursePackDescriptorCodecTests,CoursePackArchiveReaderTests,CoursePackArchiveWriterTests,CoursePackDestinationPolicyTests,CoursePackServiceTests,BundledTemplateCatalogTests,TemplateControllerContractTests,CoursePackControllerContractTests,LabCoursePackIntegrationTests,LocalApiSecurityTests test
git status --short
```

Expected: all selected tests PASS and the worktree contains only intentional issue #10 work.
