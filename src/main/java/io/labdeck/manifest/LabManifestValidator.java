package io.labdeck.manifest;

import static io.labdeck.manifest.ManifestProblemCode.MANIFEST_CAPABILITY_FORBIDDEN;
import static io.labdeck.manifest.ManifestProblemCode.MANIFEST_DEVICE_FORBIDDEN;
import static io.labdeck.manifest.ManifestProblemCode.MANIFEST_DOCKER_SOCKET_FORBIDDEN;
import static io.labdeck.manifest.ManifestProblemCode.MANIFEST_ESCAPE_OPTION_FORBIDDEN;
import static io.labdeck.manifest.ManifestProblemCode.MANIFEST_HOST_NAMESPACE_FORBIDDEN;
import static io.labdeck.manifest.ManifestProblemCode.MANIFEST_HOST_PATH_FORBIDDEN;
import static io.labdeck.manifest.ManifestProblemCode.MANIFEST_IMAGE_INVALID;
import static io.labdeck.manifest.ManifestProblemCode.MANIFEST_MUTABLE_IMAGE_TAG_FORBIDDEN;
import static io.labdeck.manifest.ManifestProblemCode.MANIFEST_PORT_POLICY_VIOLATION;
import static io.labdeck.manifest.ManifestProblemCode.MANIFEST_PRIVILEGED_FORBIDDEN;
import static io.labdeck.manifest.ManifestProblemCode.MANIFEST_REQUIRED_FIELD_MISSING;
import static io.labdeck.manifest.ManifestProblemCode.MANIFEST_RESOURCE_LIMIT_INVALID;
import static io.labdeck.manifest.ManifestProblemCode.MANIFEST_SCHEMA_VERSION_UNSUPPORTED;
import static io.labdeck.manifest.ManifestProblemCode.MANIFEST_SENSITIVE_MOUNT_FORBIDDEN;
import static io.labdeck.manifest.ManifestProblemCode.MANIFEST_SHELL_COMMAND_FORBIDDEN;
import static io.labdeck.manifest.ManifestProblemCode.MANIFEST_TRAVERSAL_FORBIDDEN;
import static io.labdeck.manifest.ManifestProblemCode.MANIFEST_UNKNOWN_FIELD;
import static io.labdeck.manifest.ManifestProblemCode.MANIFEST_VALUE_INVALID;
import static io.labdeck.manifest.ManifestProblemCode.MANIFEST_VALUE_TYPE_INVALID;

import io.labdeck.manifest.LabManifest.BuildSource;
import io.labdeck.manifest.LabManifest.HealthCheck;
import io.labdeck.manifest.LabManifest.ImageSource;
import io.labdeck.manifest.LabManifest.Port;
import io.labdeck.manifest.LabManifest.ResourceLimits;
import io.labdeck.manifest.LabManifest.Service;
import io.labdeck.manifest.LabManifest.ServiceSource;
import io.labdeck.manifest.LabManifest.TestDefinition;
import io.labdeck.manifest.LabManifest.VolumeMount;
import io.labdeck.manifest.LabManifest.Workspace;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

final class LabManifestValidator {

    private static final int EXPECTED_VERSION = 1;
    private static final int MAX_SERVICES = 12;
    private static final long DEFAULT_MEMORY_BYTES = 1_000_000_000L;
    private static final long MIN_MEMORY_BYTES = 64L * 1024 * 1024;
    private static final long MAX_MEMORY_BYTES = 8L * 1024 * 1024 * 1024;
    private static final BigDecimal DEFAULT_CPUS = new BigDecimal("2");
    private static final BigDecimal MIN_CPUS = new BigDecimal("0.25");
    private static final BigDecimal MAX_CPUS = new BigDecimal("8");
    private static final Pattern SERVICE_ID = Pattern.compile("[a-z][a-z0-9-]{0,31}");
    private static final Pattern VOLUME_ID = Pattern.compile("[a-z][a-z0-9-]{0,31}");
    private static final Pattern ENVIRONMENT_KEY = Pattern.compile("[A-Z_][A-Z0-9_]{0,63}");
    private static final Pattern IMAGE_REFERENCE = Pattern.compile(
            "(?=.{1,255}$)[a-z0-9]+(?:[._-][a-z0-9]+)*(?:/[a-z0-9]+(?:[._-][a-z0-9]+)*)*"
                    + "(?::[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}|@sha256:[a-f0-9]{64})");
    private static final Pattern MEMORY = Pattern.compile("([1-9][0-9]{0,5})(MB|GB|MiB|GiB)");
    private static final Pattern DURATION = Pattern.compile("([0-9]{1,9})(ms|s|m)");
    private static final Set<String> TOP_LEVEL_FIELDS = Set.of(
            "version", "name", "workspace", "services", "resources", "tests");
    private static final Set<String> WORKSPACE_FIELDS = Set.of("mount");
    private static final Set<String> SERVICE_FIELDS = Set.of(
            "image", "build", "working_dir", "command", "environment", "ports", "healthcheck", "volumes");
    private static final Set<String> BUILD_FIELDS = Set.of("context", "dockerfile");
    private static final Set<String> PORT_FIELDS = Set.of("container", "host", "protocol");
    private static final Set<String> HEALTHCHECK_FIELDS = Set.of(
            "command", "interval", "timeout", "retries", "start_period");
    private static final Set<String> VOLUME_FIELDS = Set.of("name", "target", "read_only");
    private static final Set<String> RESOURCE_FIELDS = Set.of("memory", "cpus");
    private static final Set<String> TEST_FIELDS = Set.of("service", "command", "timeout");
    private static final Map<String, ManifestProblemCode> FORBIDDEN_SERVICE_FIELDS = forbiddenServiceFields();
    private static final Set<String> SENSITIVE_CONTAINER_PATHS = Set.of(
            "/", "/boot", "/dev", "/etc", "/proc", "/root", "/run", "/sys", "/var/run");
    private static final Set<String> SHELLS = Set.of(
            "sh", "bash", "zsh", "/bin/sh", "/bin/bash", "/bin/zsh", "cmd", "cmd.exe", "powershell", "pwsh");
    private static final Set<String> SHELL_EXECUTE_FLAGS = Set.of("-c", "/c", "-command");

    LabManifest validate(JsonNode root) {
        Problems problems = new Problems();
        if (root == null || root.isNull()) {
            problems.add(MANIFEST_REQUIRED_FIELD_MISSING, "/", "The manifest document is required.");
            throw problems.exception();
        }
        if (!root.isObject()) {
            problems.add(MANIFEST_VALUE_TYPE_INVALID, "/", "The manifest document must be an object.");
            throw problems.exception();
        }

        ObjectNode document = root.asObject();
        checkDockerSocketValues(document, "", problems);
        checkAllowedFields(document, TOP_LEVEL_FIELDS, Set.of(), "", problems);

        int version = parseVersion(document.get("version"), problems);
        String name = parseName(document.get("name"), problems);
        Workspace workspace = parseWorkspace(document.get("workspace"), problems);
        NavigableMap<String, Service> services = parseServices(document.get("services"), workspace.mount(), problems);
        validateUniqueHostPorts(services, problems);
        ResourceLimits resources = parseResources(document.get("resources"), problems);
        validateResourceBudget(resources, services.size(), problems);
        Optional<TestDefinition> tests = parseTests(document.get("tests"), services.keySet(), problems);

        if (problems.hasProblems()) {
            throw problems.exception();
        }
        return new LabManifest(version, name, workspace, services, resources, tests);
    }

    private static int parseVersion(JsonNode node, Problems problems) {
        if (node == null || node.isNull()) {
            problems.add(MANIFEST_REQUIRED_FIELD_MISSING, "/version", "The schema version is required.");
            return EXPECTED_VERSION;
        }
        if (!node.isIntegralNumber() || !node.canConvertToInt()) {
            problems.add(MANIFEST_VALUE_TYPE_INVALID, "/version", "The schema version must be the integer 1.");
            return EXPECTED_VERSION;
        }
        int version = node.intValue();
        if (version != EXPECTED_VERSION) {
            problems.add(MANIFEST_SCHEMA_VERSION_UNSUPPORTED, "/version", "Only schema version 1 is supported.");
        }
        return version;
    }

    private static String parseName(JsonNode node, Problems problems) {
        String name = requiredText(node, "/name", 100, problems);
        if (name.isEmpty()) {
            return "Invalid lab";
        }
        if (!name.equals(name.strip()) || name.codePoints().anyMatch(Character::isISOControl)) {
            problems.add(MANIFEST_VALUE_INVALID, "/name", "The lab name must be trimmed and printable.");
        }
        return name;
    }

    private static Workspace parseWorkspace(JsonNode node, Problems problems) {
        ObjectNode workspace = requiredObject(node, "/workspace", problems);
        if (workspace == null) {
            return new Workspace("/workspace");
        }
        Set<String> hostFields = Set.of("host", "host_path", "path", "source");
        for (String field : workspace.propertyNames()) {
            if (hostFields.contains(field)) {
                problems.add(MANIFEST_HOST_PATH_FORBIDDEN, pointer("/workspace", field),
                        "Host workspace paths cannot be set in a manifest.");
            }
        }
        checkAllowedFields(workspace, WORKSPACE_FIELDS, hostFields, "/workspace", problems);
        String mount = requiredText(workspace.get("mount"), "/workspace/mount", 64, problems);
        if (containsTraversal(mount)) {
            problems.add(MANIFEST_TRAVERSAL_FORBIDDEN, "/workspace/mount", "Path traversal is not allowed.");
        } else if (!mount.equals("/workspace")) {
            problems.add(MANIFEST_VALUE_INVALID, "/workspace/mount", "The v1 workspace mount must be /workspace.");
        }
        return new Workspace("/workspace");
    }

    private static NavigableMap<String, Service> parseServices(
            JsonNode node, String workspaceMount, Problems problems) {
        ObjectNode servicesNode = requiredObject(node, "/services", problems);
        NavigableMap<String, Service> services = new TreeMap<>();
        if (servicesNode == null) {
            return services;
        }
        if (servicesNode.isEmpty() || servicesNode.size() > MAX_SERVICES) {
            problems.add(MANIFEST_VALUE_INVALID, "/services", "A manifest must define 1 to 12 services.");
        }

        servicesNode.properties().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    String serviceId = entry.getKey();
                    String path = pointer("/services", serviceId);
                    if (!SERVICE_ID.matcher(serviceId).matches()) {
                        problems.add(MANIFEST_VALUE_INVALID, path,
                                "Service IDs must use lowercase letters, digits, and hyphens.");
                    }
                    ObjectNode serviceNode = object(entry.getValue(), path, problems);
                    if (serviceNode != null) {
                        services.put(serviceId, parseService(serviceNode, path, workspaceMount, problems));
                    }
                });
        return services;
    }

    private static Service parseService(
            ObjectNode node, String path, String workspaceMount, Problems problems) {
        for (Map.Entry<String, ManifestProblemCode> entry : FORBIDDEN_SERVICE_FIELDS.entrySet()) {
            if (node.has(entry.getKey())) {
                problems.add(entry.getValue(), pointer(path, entry.getKey()), forbiddenMessage(entry.getValue()));
            }
        }
        checkAllowedFields(node, SERVICE_FIELDS, FORBIDDEN_SERVICE_FIELDS.keySet(), path, problems);

        JsonNode imageNode = node.get("image");
        JsonNode buildNode = node.get("build");
        boolean hasImage = imageNode != null && !imageNode.isNull();
        boolean hasBuild = buildNode != null && !buildNode.isNull();
        if (hasImage == hasBuild) {
            problems.add(MANIFEST_VALUE_INVALID, path, "Each service must define exactly one image or build.");
        }
        ServiceSource source = hasBuild
                ? parseBuild(buildNode, pointer(path, "build"), problems)
                : parseImage(imageNode, pointer(path, "image"), problems);

        String workingDirectory = optionalText(node.get("working_dir"), pointer(path, "working_dir"), 256,
                workspaceMount, problems);
        validateWorkspacePath(workingDirectory, pointer(path, "working_dir"), workspaceMount, problems);
        List<String> command = parseArgv(node.get("command"), pointer(path, "command"), false, problems);
        NavigableMap<String, String> environment = parseEnvironment(
                node.get("environment"), pointer(path, "environment"), problems);
        List<Port> ports = parsePorts(node.get("ports"), pointer(path, "ports"), problems);
        Optional<HealthCheck> healthcheck = parseHealthCheck(
                node.get("healthcheck"), pointer(path, "healthcheck"), problems);
        List<VolumeMount> volumes = parseVolumes(
                node.get("volumes"), pointer(path, "volumes"), workspaceMount, problems);
        return new Service(source, workingDirectory, command, environment, ports, healthcheck, volumes);
    }

    private static ServiceSource parseImage(JsonNode node, String path, Problems problems) {
        String reference = requiredText(node, path, 255, problems);
        if (reference.isEmpty()) {
            return new ImageSource("invalid:0");
        }
        if (!IMAGE_REFERENCE.matcher(reference).matches()) {
            problems.add(MANIFEST_IMAGE_INVALID, path,
                    "Use a public image reference with an explicit tag or SHA-256 digest.");
        } else if (reference.toLowerCase(Locale.ROOT).endsWith(":latest")) {
            problems.add(MANIFEST_MUTABLE_IMAGE_TAG_FORBIDDEN, path, "The latest image tag is not reproducible.");
        }
        return new ImageSource(reference);
    }

    private static ServiceSource parseBuild(JsonNode node, String path, Problems problems) {
        ObjectNode build = requiredObject(node, path, problems);
        if (build == null) {
            return new BuildSource(".", "Dockerfile");
        }
        checkAllowedFields(build, BUILD_FIELDS, Set.of(), path, problems);
        String context = optionalText(build.get("context"), pointer(path, "context"), 256, ".", problems);
        String dockerfile = optionalText(
                build.get("dockerfile"), pointer(path, "dockerfile"), 256, "Dockerfile", problems);
        context = validateRelativeProjectPath(context, pointer(path, "context"), true, problems);
        dockerfile = validateRelativeProjectPath(dockerfile, pointer(path, "dockerfile"), false, problems);
        return new BuildSource(context, dockerfile);
    }

    private static NavigableMap<String, String> parseEnvironment(JsonNode node, String path, Problems problems) {
        NavigableMap<String, String> environment = new TreeMap<>();
        if (node == null || node.isNull()) {
            return environment;
        }
        ObjectNode object = object(node, path, problems);
        if (object == null) {
            return environment;
        }
        if (object.size() > 128) {
            problems.add(MANIFEST_VALUE_INVALID, path, "A service can define at most 128 environment values.");
        }
        object.properties().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            String valuePath = pointer(path, entry.getKey());
            if (!ENVIRONMENT_KEY.matcher(entry.getKey()).matches()) {
                problems.add(MANIFEST_VALUE_INVALID, valuePath, "The environment variable name is not valid.");
            }
            String value = text(entry.getValue(), valuePath, 4_096, problems);
            if (value != null) {
                if (value.contains("${") || value.contains("$HOME") || value.contains("%USERPROFILE%")) {
                    problems.add(MANIFEST_VALUE_INVALID, valuePath,
                            "Host environment interpolation is not supported.");
                }
                environment.put(entry.getKey(), value);
            }
        });
        return environment;
    }

    private static List<Port> parsePorts(JsonNode node, String path, Problems problems) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            problems.add(MANIFEST_VALUE_TYPE_INVALID, path, "Ports must be a list of objects.");
            return List.of();
        }
        if (node.size() > 32) {
            problems.add(MANIFEST_PORT_POLICY_VIOLATION, path, "A service can publish at most 32 ports.");
        }
        List<Port> ports = new ArrayList<>();
        Set<String> containerKeys = new HashSet<>();
        Set<Integer> hostPorts = new HashSet<>();
        for (int index = 0; index < node.size(); index++) {
            String itemPath = path + "/" + index;
            JsonNode item = node.get(index);
            if (!item.isObject()) {
                problems.add(MANIFEST_PORT_POLICY_VIOLATION, itemPath,
                        "Ports must use objects; host address strings are not supported.");
                continue;
            }
            ObjectNode portNode = item.asObject();
            Set<String> hostAddressFields = Set.of("host_ip", "hostIp", "bind", "address");
            for (String field : portNode.propertyNames()) {
                if (hostAddressFields.contains(field)) {
                    problems.add(MANIFEST_PORT_POLICY_VIOLATION, pointer(itemPath, field),
                            "LabDeck fixes published port addresses to 127.0.0.1.");
                }
            }
            checkAllowedFields(portNode, PORT_FIELDS, hostAddressFields, itemPath, problems);
            int container = requiredPort(portNode.get("container"), pointer(itemPath, "container"), problems);
            Optional<Integer> host = optionalHostPort(portNode.get("host"), pointer(itemPath, "host"), problems);
            String protocol = optionalText(
                    portNode.get("protocol"), pointer(itemPath, "protocol"), 8, "tcp", problems)
                    .toLowerCase(Locale.ROOT);
            if (!protocol.equals("tcp")) {
                problems.add(MANIFEST_PORT_POLICY_VIOLATION, pointer(itemPath, "protocol"),
                        "Only TCP ports are supported in v1.");
            }
            if (!containerKeys.add(container + "/" + protocol)) {
                problems.add(MANIFEST_PORT_POLICY_VIOLATION, itemPath, "The container port is duplicated.");
            }
            if (host.isPresent() && !hostPorts.add(host.get())) {
                problems.add(MANIFEST_PORT_POLICY_VIOLATION, pointer(itemPath, "host"),
                        "The requested host port is duplicated.");
            }
            ports.add(new Port(container, host, protocol));
        }
        return ports.stream()
                .sorted(Comparator.comparingInt(Port::container)
                        .thenComparing(port -> port.host().orElse(0))
                        .thenComparing(Port::protocol))
                .toList();
    }

    private static Optional<HealthCheck> parseHealthCheck(JsonNode node, String path, Problems problems) {
        if (node == null || node.isNull()) {
            return Optional.empty();
        }
        ObjectNode health = object(node, path, problems);
        if (health == null) {
            return Optional.empty();
        }
        checkAllowedFields(health, HEALTHCHECK_FIELDS, Set.of(), path, problems);
        List<String> command = parseArgv(health.get("command"), pointer(path, "command"), true, problems);
        Duration interval = parseDuration(health.get("interval"), pointer(path, "interval"),
                Duration.ofSeconds(5), Duration.ofSeconds(1), Duration.ofMinutes(5), problems);
        Duration timeout = parseDuration(health.get("timeout"), pointer(path, "timeout"),
                Duration.ofSeconds(3), Duration.ofSeconds(1), Duration.ofMinutes(1), problems);
        Duration startPeriod = parseDuration(health.get("start_period"), pointer(path, "start_period"),
                Duration.ZERO, Duration.ZERO, Duration.ofMinutes(10), problems);
        int retries = optionalInteger(health.get("retries"), pointer(path, "retries"), 5, 1, 20, problems);
        return Optional.of(new HealthCheck(command, interval, timeout, retries, startPeriod));
    }

    private static void validateUniqueHostPorts(
            NavigableMap<String, Service> services, Problems problems) {
        Map<Integer, String> firstServiceByPort = new HashMap<>();
        services.forEach((serviceId, service) -> {
            for (int index = 0; index < service.ports().size(); index++) {
                Port port = service.ports().get(index);
                if (port.host().isEmpty()) {
                    continue;
                }
                int hostPort = port.host().orElseThrow();
                String firstService = firstServiceByPort.putIfAbsent(hostPort, serviceId);
                if (firstService != null && !firstService.equals(serviceId)) {
                    problems.add(
                            MANIFEST_PORT_POLICY_VIOLATION,
                            pointer("/services", serviceId) + "/ports/" + index + "/host",
                            "Host port " + hostPort + " is already requested by service '"
                                    + firstService + "'.");
                }
            }
        });
    }

    private static List<VolumeMount> parseVolumes(
            JsonNode node, String path, String workspaceMount, Problems problems) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            classifyUnsupportedVolume(node, path, problems);
            return List.of();
        }
        if (node.size() > 16) {
            problems.add(MANIFEST_VALUE_INVALID, path, "A service can mount at most 16 named volumes.");
        }
        List<VolumeMount> volumes = new ArrayList<>();
        Set<String> names = new HashSet<>();
        Set<String> targets = new HashSet<>();
        for (int index = 0; index < node.size(); index++) {
            String itemPath = path + "/" + index;
            JsonNode item = node.get(index);
            if (!item.isObject()) {
                classifyUnsupportedVolume(item, itemPath, problems);
                continue;
            }
            ObjectNode volume = item.asObject();
            Set<String> hostFields = Set.of("source", "host", "host_path", "bind", "type");
            for (String field : volume.propertyNames()) {
                if (hostFields.contains(field)) {
                    problems.add(MANIFEST_HOST_PATH_FORBIDDEN, pointer(itemPath, field),
                            "Host bind mounts are not supported.");
                }
            }
            checkAllowedFields(volume, VOLUME_FIELDS, hostFields, itemPath, problems);
            String name = requiredText(volume.get("name"), pointer(itemPath, "name"), 32, problems);
            if (!name.isEmpty() && !VOLUME_ID.matcher(name).matches()) {
                problems.add(MANIFEST_VALUE_INVALID, pointer(itemPath, "name"),
                        "Volume names must use lowercase letters, digits, and hyphens.");
            }
            String target = requiredText(volume.get("target"), pointer(itemPath, "target"), 256, problems);
            validateVolumeTarget(target, pointer(itemPath, "target"), problems);
            if (pathsOverlap(target, workspaceMount)) {
                problems.add(MANIFEST_SENSITIVE_MOUNT_FORBIDDEN, pointer(itemPath, "target"),
                        "Named volumes cannot hide or overlap the approved workspace mount.");
            }
            boolean readOnly = optionalBoolean(
                    volume.get("read_only"), pointer(itemPath, "read_only"), false, problems);
            if (!names.add(name)) {
                problems.add(MANIFEST_VALUE_INVALID, pointer(itemPath, "name"),
                        "A service cannot mount the same volume twice.");
            }
            if (!targets.add(target)) {
                problems.add(MANIFEST_VALUE_INVALID, pointer(itemPath, "target"),
                        "A service cannot reuse a volume target.");
            }
            volumes.add(new VolumeMount(name, target, readOnly));
        }
        return volumes.stream()
                .sorted(Comparator.comparing(VolumeMount::name).thenComparing(VolumeMount::target))
                .toList();
    }

    private static ResourceLimits parseResources(JsonNode node, Problems problems) {
        if (node == null || node.isNull()) {
            return new ResourceLimits(DEFAULT_MEMORY_BYTES, DEFAULT_CPUS);
        }
        ObjectNode resources = object(node, "/resources", problems);
        if (resources == null) {
            return new ResourceLimits(DEFAULT_MEMORY_BYTES, DEFAULT_CPUS);
        }
        checkAllowedFields(resources, RESOURCE_FIELDS, Set.of(), "/resources", problems);
        long memory = parseMemory(resources.get("memory"), "/resources/memory", problems);
        BigDecimal cpus = parseCpus(resources.get("cpus"), "/resources/cpus", problems);
        return new ResourceLimits(memory, cpus);
    }

    private static void validateResourceBudget(
            ResourceLimits resources, int serviceCount, Problems problems) {
        if (serviceCount < 1) {
            return;
        }
        long minimumMemory = 6L * 1024 * 1024 * serviceCount;
        if (resources.memoryBytes() < minimumMemory) {
            problems.add(
                    MANIFEST_RESOURCE_LIMIT_INVALID,
                    "/resources/memory",
                    "The lab memory must allow at least 6MiB for each service.");
        }
        BigDecimal minimumCpus = new BigDecimal("0.01").multiply(BigDecimal.valueOf(serviceCount));
        if (resources.cpus().compareTo(minimumCpus) < 0) {
            problems.add(
                    MANIFEST_RESOURCE_LIMIT_INVALID,
                    "/resources/cpus",
                    "The lab CPU limit must allow at least 0.01 CPU for each service.");
        }
    }

    private static Optional<TestDefinition> parseTests(
            JsonNode node, Set<String> serviceIds, Problems problems) {
        if (node == null || node.isNull()) {
            return Optional.empty();
        }
        ObjectNode tests = object(node, "/tests", problems);
        if (tests == null) {
            return Optional.empty();
        }
        checkAllowedFields(tests, TEST_FIELDS, Set.of(), "/tests", problems);
        String service = requiredText(tests.get("service"), "/tests/service", 32, problems);
        if (!service.isEmpty() && !serviceIds.contains(service)) {
            problems.add(MANIFEST_VALUE_INVALID, "/tests/service", "The test service is not defined.");
        }
        List<String> command = parseArgv(tests.get("command"), "/tests/command", true, problems);
        Duration timeout = parseDuration(tests.get("timeout"), "/tests/timeout",
                Duration.ofMinutes(5), Duration.ofSeconds(1), Duration.ofMinutes(30), problems);
        return Optional.of(new TestDefinition(service, command, timeout));
    }

    private static List<String> parseArgv(
            JsonNode node, String path, boolean required, Problems problems) {
        if (node == null || node.isNull()) {
            if (required) {
                problems.add(MANIFEST_REQUIRED_FIELD_MISSING, path, "An argv command list is required.");
            }
            return List.of();
        }
        if (!node.isArray()) {
            problems.add(MANIFEST_VALUE_TYPE_INVALID, path, "Commands must use an argv list, not a shell string.");
            return List.of();
        }
        if (node.isEmpty() || node.size() > 64) {
            problems.add(MANIFEST_VALUE_INVALID, path, "A command must contain 1 to 64 arguments.");
        }
        List<String> command = new ArrayList<>();
        for (int index = 0; index < node.size(); index++) {
            String argument = text(node.get(index), path + "/" + index, 4_096, problems);
            if (argument != null) {
                if (argument.isEmpty() || argument.codePoints().anyMatch(Character::isISOControl)) {
                    problems.add(MANIFEST_VALUE_INVALID, path + "/" + index,
                            "Command arguments must be non-empty printable strings.");
                }
                command.add(argument);
            }
        }
        if (usesShell(command)) {
            problems.add(MANIFEST_SHELL_COMMAND_FORBIDDEN, path,
                    "Shell-wrapper commands are not supported; use direct argv execution.");
        }
        return List.copyOf(command);
    }

    private static boolean usesShell(List<String> command) {
        if (command.size() < 2) {
            return false;
        }
        String executable = command.getFirst().toLowerCase(Locale.ROOT);
        String flag = command.get(1).toLowerCase(Locale.ROOT);
        return SHELLS.contains(executable) && SHELL_EXECUTE_FLAGS.contains(flag);
    }

    private static long parseMemory(JsonNode node, String path, Problems problems) {
        if (node == null || node.isNull()) {
            return DEFAULT_MEMORY_BYTES;
        }
        if (!node.isString()) {
            problems.add(MANIFEST_RESOURCE_LIMIT_INVALID, path,
                    "Memory must use a value such as 512MiB or 1GB.");
            return DEFAULT_MEMORY_BYTES;
        }
        Matcher matcher = MEMORY.matcher(node.stringValue());
        if (!matcher.matches()) {
            problems.add(MANIFEST_RESOURCE_LIMIT_INVALID, path,
                    "Memory must use MB, GB, MiB, or GiB units.");
            return DEFAULT_MEMORY_BYTES;
        }
        long amount = Long.parseLong(matcher.group(1));
        long multiplier = switch (matcher.group(2)) {
            case "MB" -> 1_000_000L;
            case "GB" -> 1_000_000_000L;
            case "MiB" -> 1024L * 1024;
            case "GiB" -> 1024L * 1024 * 1024;
            default -> throw new IllegalStateException("Unexpected memory unit");
        };
        long bytes;
        try {
            bytes = Math.multiplyExact(amount, multiplier);
        } catch (ArithmeticException exception) {
            problems.add(MANIFEST_RESOURCE_LIMIT_INVALID, path, "The memory limit is too large.");
            return DEFAULT_MEMORY_BYTES;
        }
        if (bytes < MIN_MEMORY_BYTES || bytes > MAX_MEMORY_BYTES) {
            problems.add(MANIFEST_RESOURCE_LIMIT_INVALID, path,
                    "Memory must be between 64MiB and 8GiB.");
        }
        return bytes;
    }

    private static BigDecimal parseCpus(JsonNode node, String path, Problems problems) {
        if (node == null || node.isNull()) {
            return DEFAULT_CPUS;
        }
        if (!node.isNumber()) {
            problems.add(MANIFEST_RESOURCE_LIMIT_INVALID, path, "CPUs must be a number from 0.25 to 8.");
            return DEFAULT_CPUS;
        }
        BigDecimal cpus = node.decimalValue();
        if (cpus.scale() > 2 || cpus.compareTo(MIN_CPUS) < 0 || cpus.compareTo(MAX_CPUS) > 0) {
            problems.add(MANIFEST_RESOURCE_LIMIT_INVALID, path, "CPUs must be a number from 0.25 to 8.");
        }
        return cpus;
    }

    private static Duration parseDuration(
            JsonNode node,
            String path,
            Duration defaultValue,
            Duration minimum,
            Duration maximum,
            Problems problems) {
        if (node == null || node.isNull()) {
            return defaultValue;
        }
        if (!node.isString()) {
            problems.add(MANIFEST_VALUE_TYPE_INVALID, path, "A duration must use ms, s, or m units.");
            return defaultValue;
        }
        Matcher matcher = DURATION.matcher(node.stringValue());
        if (!matcher.matches()) {
            problems.add(MANIFEST_VALUE_INVALID, path, "A duration must use ms, s, or m units.");
            return defaultValue;
        }
        long amount = Long.parseLong(matcher.group(1));
        Duration value = switch (matcher.group(2)) {
            case "ms" -> Duration.ofMillis(amount);
            case "s" -> Duration.ofSeconds(amount);
            case "m" -> Duration.ofMinutes(amount);
            default -> throw new IllegalStateException("Unexpected duration unit");
        };
        if (value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            problems.add(MANIFEST_VALUE_INVALID, path, "The duration is outside the allowed range.");
        }
        return value;
    }

    private static int requiredPort(JsonNode node, String path, Problems problems) {
        if (node == null || node.isNull() || !node.isIntegralNumber() || !node.canConvertToInt()) {
            problems.add(MANIFEST_PORT_POLICY_VIOLATION, path,
                    "A container port must be an integer from 1 to 65535.");
            return 1;
        }
        int port = node.intValue();
        if (port < 1 || port > 65_535) {
            problems.add(MANIFEST_PORT_POLICY_VIOLATION, path,
                    "A container port must be an integer from 1 to 65535.");
        }
        return port;
    }

    private static Optional<Integer> optionalHostPort(JsonNode node, String path, Problems problems) {
        if (node == null || node.isNull()) {
            return Optional.empty();
        }
        if (!node.isIntegralNumber() || !node.canConvertToInt()) {
            problems.add(MANIFEST_PORT_POLICY_VIOLATION, path,
                    "A host port must be an integer from 1024 to 65535.");
            return Optional.empty();
        }
        int port = node.intValue();
        if (port < 1_024 || port > 65_535) {
            problems.add(MANIFEST_PORT_POLICY_VIOLATION, path,
                    "A host port must be an integer from 1024 to 65535.");
        }
        return Optional.of(port);
    }

    private static int optionalInteger(
            JsonNode node, String path, int defaultValue, int minimum, int maximum, Problems problems) {
        if (node == null || node.isNull()) {
            return defaultValue;
        }
        if (!node.isIntegralNumber() || !node.canConvertToInt()) {
            problems.add(MANIFEST_VALUE_TYPE_INVALID, path, "The value must be an integer.");
            return defaultValue;
        }
        int value = node.intValue();
        if (value < minimum || value > maximum) {
            problems.add(MANIFEST_VALUE_INVALID, path, "The integer is outside the allowed range.");
        }
        return value;
    }

    private static boolean optionalBoolean(
            JsonNode node, String path, boolean defaultValue, Problems problems) {
        if (node == null || node.isNull()) {
            return defaultValue;
        }
        if (!node.isBoolean()) {
            problems.add(MANIFEST_VALUE_TYPE_INVALID, path, "The value must be true or false.");
            return defaultValue;
        }
        return node.booleanValue();
    }

    private static String requiredText(JsonNode node, String path, int maxLength, Problems problems) {
        if (node == null || node.isNull()) {
            problems.add(MANIFEST_REQUIRED_FIELD_MISSING, path, "The value is required.");
            return "";
        }
        String value = text(node, path, maxLength, problems);
        if (value == null) {
            return "";
        }
        if (value.isEmpty()) {
            problems.add(MANIFEST_VALUE_INVALID, path, "The value must not be empty.");
        }
        return value;
    }

    private static String optionalText(
            JsonNode node, String path, int maxLength, String defaultValue, Problems problems) {
        if (node == null || node.isNull()) {
            return defaultValue;
        }
        String value = text(node, path, maxLength, problems);
        return value == null ? defaultValue : value;
    }

    private static String text(JsonNode node, String path, int maxLength, Problems problems) {
        if (!node.isString()) {
            problems.add(MANIFEST_VALUE_TYPE_INVALID, path, "The value must be a string.");
            return null;
        }
        String value = node.stringValue();
        if (value.length() > maxLength) {
            problems.add(MANIFEST_VALUE_INVALID, path, "The string is too long.");
        }
        if (value.codePoints().anyMatch(Character::isISOControl)) {
            problems.add(MANIFEST_VALUE_INVALID, path, "The string contains a control character.");
        }
        return value;
    }

    private static ObjectNode requiredObject(JsonNode node, String path, Problems problems) {
        if (node == null || node.isNull()) {
            problems.add(MANIFEST_REQUIRED_FIELD_MISSING, path, "The object is required.");
            return null;
        }
        return object(node, path, problems);
    }

    private static ObjectNode object(JsonNode node, String path, Problems problems) {
        if (!node.isObject()) {
            problems.add(MANIFEST_VALUE_TYPE_INVALID, path, "The value must be an object.");
            return null;
        }
        return node.asObject();
    }

    private static void checkAllowedFields(
            ObjectNode node,
            Set<String> allowed,
            Set<String> separatelyRejected,
            String path,
            Problems problems) {
        node.propertyNames().stream().sorted().forEach(field -> {
            if (!allowed.contains(field) && !separatelyRejected.contains(field)) {
                problems.add(MANIFEST_UNKNOWN_FIELD, pointer(path, field), "This field is not supported in v1.");
            }
        });
    }

    private static void checkDockerSocketValues(JsonNode node, String path, Problems problems) {
        if (node.isString() && isDockerSocket(node.stringValue())) {
            problems.add(MANIFEST_DOCKER_SOCKET_FORBIDDEN, path.isEmpty() ? "/" : path,
                    "Docker Engine sockets and pipes cannot be used by a lab.");
            return;
        }
        if (node.isObject()) {
            node.properties().forEach(entry ->
                    checkDockerSocketValues(entry.getValue(), pointer(path, entry.getKey()), problems));
        } else if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                checkDockerSocketValues(node.get(index), (path.isEmpty() ? "" : path) + "/" + index, problems);
            }
        }
    }

    private static boolean isDockerSocket(String value) {
        String normalized = value.toLowerCase(Locale.ROOT).replace('\\', '/');
        return normalized.contains("/var/run/docker.sock")
                || normalized.contains("/run/docker.sock")
                || normalized.contains("//./pipe/docker_engine");
    }

    private static void validateWorkspacePath(
            String value, String path, String workspaceMount, Problems problems) {
        if (containsTraversal(value)) {
            problems.add(MANIFEST_TRAVERSAL_FORBIDDEN, path, "Path traversal is not allowed.");
        } else if (!isPortableAbsolutePath(value)
                || !(value.equals(workspaceMount) || value.startsWith(workspaceMount + "/"))) {
            problems.add(MANIFEST_VALUE_INVALID, path, "The working directory must be inside /workspace.");
        }
    }

    private static String validateRelativeProjectPath(
            String value, String path, boolean allowDot, Problems problems) {
        if (containsTraversal(value)) {
            problems.add(MANIFEST_TRAVERSAL_FORBIDDEN, path, "Path traversal is not allowed.");
            return value;
        }
        if (looksLikeHostPath(value)) {
            problems.add(MANIFEST_HOST_PATH_FORBIDDEN, path,
                    "Build paths must be relative to the selected project.");
            return value;
        }
        if (value.isBlank()
                || value.startsWith("./")
                || value.contains("//")
                || value.contains("%")
                || value.contains("$")
                || value.contains("~") || value.contains(":")) {
            problems.add(MANIFEST_VALUE_INVALID, path, "The project-relative path is not valid.");
            return value;
        }
        List<String> segments = new ArrayList<>();
        for (String segment : value.split("/")) {
            if (segment.equals(".") || segment.isEmpty()) {
                continue;
            }
            if (!segment.matches("[A-Za-z0-9._-]+")) {
                problems.add(MANIFEST_VALUE_INVALID, path, "The project-relative path is not portable.");
                return value;
            }
            segments.add(segment);
        }
        if (segments.isEmpty()) {
            if (!allowDot || !value.equals(".")) {
                problems.add(MANIFEST_VALUE_INVALID, path, "The project-relative path is not valid.");
            }
            return ".";
        }
        return String.join("/", segments);
    }

    private static void validateVolumeTarget(String value, String path, Problems problems) {
        if (containsTraversal(value)) {
            problems.add(MANIFEST_TRAVERSAL_FORBIDDEN, path, "Path traversal is not allowed.");
            return;
        }
        if (!isPortableAbsolutePath(value)) {
            problems.add(MANIFEST_VALUE_INVALID, path, "A volume target must be an absolute container path.");
            return;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        for (String sensitive : SENSITIVE_CONTAINER_PATHS) {
            if (normalized.equals(sensitive) || (!sensitive.equals("/") && normalized.startsWith(sensitive + "/"))) {
                problems.add(MANIFEST_SENSITIVE_MOUNT_FORBIDDEN, path,
                        "Named volumes cannot target a sensitive container path.");
                return;
            }
        }
    }

    private static boolean pathsOverlap(String first, String second) {
        return first.equals(second) || first.startsWith(second + "/") || second.startsWith(first + "/");
    }

    private static void classifyUnsupportedVolume(JsonNode node, String path, Problems problems) {
        if (node != null && node.isString()) {
            String value = node.stringValue();
            if (isDockerSocket(value)) {
                problems.add(MANIFEST_DOCKER_SOCKET_FORBIDDEN, path,
                        "Docker Engine sockets and pipes cannot be used by a lab.");
                return;
            }
            String source = value.contains(":") ? value.substring(0, value.indexOf(':')) : value;
            if (isSensitiveHostPath(source)) {
                problems.add(MANIFEST_SENSITIVE_MOUNT_FORBIDDEN, path, "Sensitive host mounts are not supported.");
                return;
            }
            if (looksLikeHostPath(source)) {
                problems.add(MANIFEST_HOST_PATH_FORBIDDEN, path, "Host bind mounts are not supported.");
                return;
            }
        }
        problems.add(MANIFEST_VALUE_TYPE_INVALID, path,
                "Volumes must use named-volume objects with name and target fields.");
    }

    private static boolean isSensitiveHostPath(String value) {
        String normalized = value.replace('\\', '/').toLowerCase(Locale.ROOT);
        return normalized.equals("/")
                || normalized.startsWith("/etc")
                || normalized.startsWith("/root")
                || normalized.startsWith("/home")
                || normalized.startsWith("/users")
                || normalized.startsWith("/proc")
                || normalized.startsWith("/sys")
                || normalized.startsWith("/dev")
                || normalized.startsWith("/run")
                || normalized.startsWith("/var/run");
    }

    private static boolean looksLikeHostPath(String value) {
        return value.startsWith("/")
                || value.startsWith(".")
                || value.startsWith("~")
                || value.startsWith("\\")
                || value.matches("^[A-Za-z]:.*");
    }

    private static boolean containsTraversal(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.replace('\\', '/');
        return List.of(normalized.split("/", -1)).contains("..")
                || normalized.toLowerCase(Locale.ROOT).contains("%2e")
                || normalized.toLowerCase(Locale.ROOT).contains("%2f")
                || normalized.toLowerCase(Locale.ROOT).contains("%5c");
    }

    private static boolean isPortableAbsolutePath(String value) {
        return value.startsWith("/")
                && !value.contains("//")
                && !value.contains("\\")
                && !value.contains("%")
                && value.matches("/[A-Za-z0-9._/-]*");
    }

    private static String forbiddenMessage(ManifestProblemCode code) {
        return switch (code) {
            case MANIFEST_PRIVILEGED_FORBIDDEN -> "Privileged containers are not supported.";
            case MANIFEST_HOST_NAMESPACE_FORBIDDEN -> "Host namespaces are not supported.";
            case MANIFEST_DEVICE_FORBIDDEN -> "Host device access is not supported.";
            case MANIFEST_CAPABILITY_FORBIDDEN -> "Added Linux capabilities are not supported.";
            case MANIFEST_ESCAPE_OPTION_FORBIDDEN -> "This container escape option is not supported.";
            case MANIFEST_HOST_PATH_FORBIDDEN -> "Host bind mounts are not supported.";
            default -> "This field is not supported.";
        };
    }

    private static Map<String, ManifestProblemCode> forbiddenServiceFields() {
        Map<String, ManifestProblemCode> fields = new HashMap<>();
        fields.put("privileged", MANIFEST_PRIVILEGED_FORBIDDEN);
        for (String field : List.of("pid", "network", "network_mode", "ipc", "uts", "userns_mode", "cgroupns")) {
            fields.put(field, MANIFEST_HOST_NAMESPACE_FORBIDDEN);
        }
        for (String field : List.of("devices", "device_cgroup_rules")) {
            fields.put(field, MANIFEST_DEVICE_FORBIDDEN);
        }
        for (String field : List.of("cap_add", "capabilities")) {
            fields.put(field, MANIFEST_CAPABILITY_FORBIDDEN);
        }
        for (String field : List.of("security_opt", "runtime", "sysctls", "docker_args")) {
            fields.put(field, MANIFEST_ESCAPE_OPTION_FORBIDDEN);
        }
        for (String field : List.of("mounts", "binds")) {
            fields.put(field, MANIFEST_HOST_PATH_FORBIDDEN);
        }
        return Map.copyOf(fields);
    }

    private static String pointer(String parent, String field) {
        String safeField = field.matches("[A-Za-z0-9_-]{1,64}") ? field : "<invalid-field>";
        String escaped = safeField.replace("~", "~0").replace("/", "~1");
        return (parent == null || parent.isEmpty() ? "" : parent) + "/" + escaped;
    }

    private static final class Problems {
        private final List<ManifestProblem> values = new ArrayList<>();

        void add(ManifestProblemCode code, String path, String message) {
            values.add(new ManifestProblem(code, path, message));
        }

        boolean hasProblems() {
            return !values.isEmpty();
        }

        ManifestValidationException exception() {
            return new ManifestValidationException(values);
        }
    }
}
