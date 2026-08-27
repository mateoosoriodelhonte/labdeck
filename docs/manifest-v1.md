# LabDeck manifest v1

LabDeck treats every downloaded manifest as untrusted input. The v1 schema allows only the fields in this document. The parser rejects unknown fields.

The machine-readable schema is packaged at `src/main/resources/schema/labdeck-v1.schema.json`. The Java semantic validator is authoritative for rules that JSON Schema cannot express.

## Example

```yaml
version: 1
name: Database Assignment 4
workspace:
  mount: /workspace
services:
  app:
    image: python:3.12
    working_dir: /workspace
    command: ["sleep", "infinity"]
    ports:
      - container: 8000
    healthcheck:
      command: ["python", "-c", "print('ok')"]
  database:
    image: postgres:17
    volumes:
      - name: database-data
        target: /var/lib/postgresql/data
tests:
  service: app
  command: ["pytest", "-q"]
resources:
  memory: 1GB
  cpus: 2
```

## Trust boundary

The manifest contains container settings. It never contains the selected host workspace path. The desktop folder picker or CLI selects that path through a separate trusted local flow. LabDeck mounts that one project directory at `/workspace`.

Commands use YAML lists. LabDeck sends each list as direct container argv. Scalar shell commands and shell wrappers such as `sh -c` are not supported. A browser request cannot replace a manifest command.

An image must use an explicit tag other than `latest`, or a SHA-256 digest. LabDeck accepts public OCI image names. It does not accept registry credentials in a manifest.

If a service uses `build`, `context` and `dockerfile` are relative to the selected project. LabDeck rejects absolute paths, `..`, encoded traversal, non-portable path text, and symbolic links in a resolved build path.

Ports use this form:

```yaml
ports:
  - container: 8000
    host: 18000
    protocol: tcp
```

Omit `host` to request a free local port. LabDeck always binds published ports to `127.0.0.1`. V1 accepts TCP only. An explicit host port must be from 1024 through 65535.

Volumes are LabDeck-managed named volumes. V1 does not accept bind mounts. A volume cannot target `/`, `/boot`, `/dev`, `/etc`, `/proc`, `/root`, `/run`, `/sys`, or `/var/run`. A named volume also cannot equal, contain, or sit inside the approved workspace mount. This prevents a volume from hiding student files.

Resource limits apply to every service. If `resources` is absent, LabDeck uses 1 GB of memory and 2 CPUs. Memory must be from 64 MiB through 8 GiB. CPU must be from 0.25 through 8.

## Fields that always fail

LabDeck rejects privileged mode, host namespaces, host networking, Docker Engine sockets or pipes, host devices, added Linux capabilities, bind mounts, runtime or security overrides, arbitrary Docker arguments, host paths, and unknown fields.

Validation errors contain a stable code, a JSON Pointer path, and a safe message. Error responses do not repeat manifest secrets or arbitrary host paths.
