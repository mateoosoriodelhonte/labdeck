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

Omit `host` to request a Docker-selected free local port. LabDeck always binds published ports to `127.0.0.1` and reports the actual mapping after startup. V1 accepts TCP only. An explicit host port must be from 1024 through 65535. If another process owns a fixed port, startup fails with the service and port named. Published ports require Docker Engine 28 or newer because older releases did not fully isolate loopback-published ports from the local network.

Each running lab uses its own non-attachable bridge network. Services in that lab can reach each other by service name. They cannot join another lab network through the manifest. The bridge uses Docker's normal outbound network behavior. LabDeck does not claim that a lab is an offline or hostile-code sandbox.

Volumes are LabDeck-managed named volumes. V1 does not accept bind mounts. A volume cannot target `/`, `/boot`, `/dev`, `/etc`, `/proc`, `/root`, `/run`, `/sys`, or `/var/run`. A named volume also cannot equal, contain, or sit inside the approved workspace mount. This prevents a volume from hiding student files.

`resources` is the total lab budget. LabDeck divides it across services in stable service-name order. The service limits add up to no more than the lab budget. If `resources` is absent, LabDeck uses 1 GB of memory and 2 CPUs. Memory must be from 64 MiB through 8 GiB. CPU must be from 0.25 through 8. A multi-service lab must also leave at least 6 MiB and 0.01 CPU for each service.

A manifest health check replaces an image health check and is sent as exact Docker `CMD` argv. If the manifest has no health check, LabDeck preserves the image health check. A service with either health policy must report Docker `healthy` before the lab becomes Running. A service with no health policy must remain running for two seconds; LabDeck does not label that service healthy. Startup has a 30-second minimum readiness window and a 15-minute hard ceiling. A two-second monitor checks a Running lab for later exits or unhealthy states.

## Fields that always fail

LabDeck rejects privileged mode, host namespaces, host networking, Docker Engine sockets or pipes, host devices, added Linux capabilities, bind mounts, runtime or security overrides, arbitrary Docker arguments, host paths, and unknown fields.

Validation errors contain a stable code, a JSON Pointer path, and a safe message. Error responses do not repeat manifest secrets or arbitrary host paths.
