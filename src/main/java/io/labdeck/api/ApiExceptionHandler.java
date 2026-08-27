package io.labdeck.api;

import io.labdeck.docker.DockerEngineCapabilityException;
import io.labdeck.docker.DockerActiveServiceNotFoundException;
import io.labdeck.docker.DockerImagePullException;
import io.labdeck.docker.DockerLogAccessException;
import io.labdeck.docker.DockerImagesRequiredException;
import io.labdeck.docker.DockerOperationCancelledException;
import io.labdeck.docker.DockerObservationTimeoutException;
import io.labdeck.docker.DockerOwnershipException;
import io.labdeck.docker.DockerPortCollisionException;
import io.labdeck.docker.DockerServiceReadinessException;
import io.labdeck.docker.DockerStorageFullException;
import io.labdeck.docker.DockerTestStartException;
import io.labdeck.manifest.ManifestProblem;
import io.labdeck.manifest.ManifestValidationException;
import io.labdeck.manifest.WorkspaceManifestException;
import io.labdeck.lab.TestRunCoordinatorException;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> api(ApiException exception) {
        return problem(
                exception.status(),
                exception.code(),
                exception.title(),
                exception.getMessage(),
                exception.properties());
    }

    @ExceptionHandler(TestRunCoordinatorException.class)
    ResponseEntity<ProblemDetail> testRun(TestRunCoordinatorException exception) {
        HttpStatus status = switch (exception.reason()) {
            case TEST_NOT_CONFIGURED, TEST_ALREADY_RUNNING -> HttpStatus.CONFLICT;
            case PROCESS_LIMIT_REACHED -> HttpStatus.TOO_MANY_REQUESTS;
            case RUNNER_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case TEST_RUN_NOT_FOUND -> HttpStatus.NOT_FOUND;
        };
        String code = switch (exception.reason()) {
            case TEST_NOT_CONFIGURED -> "TEST_NOT_CONFIGURED";
            case TEST_ALREADY_RUNNING -> "TEST_ALREADY_RUNNING";
            case PROCESS_LIMIT_REACHED -> "TEST_PROCESS_LIMIT_REACHED";
            case RUNNER_UNAVAILABLE -> "TEST_RUNNER_UNAVAILABLE";
            case TEST_RUN_NOT_FOUND -> "TEST_RUN_NOT_FOUND";
        };
        return problem(
                status,
                code,
                "Assignment test unavailable",
                exception.getMessage(),
                Map.of());
    }

    @ExceptionHandler(DockerTestStartException.class)
    ResponseEntity<ProblemDetail> testStart(DockerTestStartException exception) {
        return problem(
                HttpStatus.CONFLICT,
                "TEST_" + exception.reason().name(),
                "Assignment test cannot start",
                exception.getMessage(),
                Map.of());
    }

    @ExceptionHandler(ManifestValidationException.class)
    ResponseEntity<ProblemDetail> manifest(ManifestValidationException exception) {
        List<Map<String, String>> violations = exception.problems().stream()
                .map(ApiExceptionHandler::manifestProblem)
                .toList();
        return problem(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "MANIFEST_INVALID",
                "Manifest is not valid",
                "Fix the listed manifest problems and retry.",
                Map.of("problems", violations));
    }

    @ExceptionHandler(WorkspaceManifestException.class)
    ResponseEntity<ProblemDetail> workspaceManifest(WorkspaceManifestException exception) {
        HttpStatus status = exception.reason() == WorkspaceManifestException.Reason.NOT_FOUND
                ? HttpStatus.NOT_FOUND
                : HttpStatus.UNPROCESSABLE_CONTENT;
        return problem(
                status,
                "WORKSPACE_MANIFEST_" + exception.reason().name(),
                "Workspace manifest is not available",
                exception.getMessage(),
                Map.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> requestValidation(MethodArgumentNotValidException exception) {
        List<String> fields = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField())
                .distinct()
                .sorted()
                .toList();
        return problem(
                HttpStatus.BAD_REQUEST,
                "REQUEST_VALIDATION_FAILED",
                "Request validation failed",
                "Fix the invalid request fields and retry.",
                Map.of("fields", fields));
    }

    @ExceptionHandler({HandlerMethodValidationException.class, ConstraintViolationException.class})
    ResponseEntity<ProblemDetail> methodValidation(Exception exception) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "REQUEST_VALIDATION_FAILED",
                "Request validation failed",
                "Fix the invalid path or query value and retry.",
                Map.of());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> unreadable(HttpMessageNotReadableException exception) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "MALFORMED_JSON",
                "Malformed JSON",
                "Send one valid JSON object with no duplicate or unknown fields.",
                Map.of());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ProblemDetail> mediaType(HttpMediaTypeNotSupportedException exception) {
        return problem(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "JSON_REQUIRED",
                "JSON content required",
                "Send the request with Content-Type application/json.",
                Map.of());
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, TypeMismatchException.class})
    ResponseEntity<ProblemDetail> requestShape(Exception exception) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "INVALID_REQUEST",
                "Invalid request",
                "Fix the request path, query, or body and retry.",
                Map.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ProblemDetail> notFound(NoResourceFoundException exception) {
        return problem(
                HttpStatus.NOT_FOUND,
                "API_ROUTE_NOT_FOUND",
                "API route not found",
                "No LabDeck API route matches this request.",
                Map.of());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> dataConflict(DataIntegrityViolationException exception) {
        return problem(
                HttpStatus.CONFLICT,
                "LAB_CONFLICT",
                "Lab conflict",
                "The lab conflicts with an existing local record.",
                Map.of());
    }

    @ExceptionHandler(DockerImagesRequiredException.class)
    ResponseEntity<ProblemDetail> imagesRequired(DockerImagesRequiredException exception) {
        return problem(
                HttpStatus.CONFLICT,
                "IMAGE_CONFIRMATION_REQUIRED",
                "Image confirmation required",
                exception.getMessage(),
                Map.of("images", exception.missingImages()));
    }

    @ExceptionHandler(DockerEngineCapabilityException.class)
    ResponseEntity<ProblemDetail> dockerCapability(DockerEngineCapabilityException exception) {
        String title = switch (exception.reason()) {
            case UNAVAILABLE -> "Docker is not available";
            case VERSION_UNSUPPORTED -> "Docker version is not supported";
            case RESOURCE_LIMITS_UNSUPPORTED -> "Docker resource limits are not supported";
        };
        return problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                "DOCKER_" + exception.reason().name(),
                title,
                exception.getMessage(),
                Map.of());
    }

    @ExceptionHandler(DockerActiveServiceNotFoundException.class)
    ResponseEntity<ProblemDetail> inactiveService(DockerActiveServiceNotFoundException exception) {
        return problem(
                HttpStatus.NOT_FOUND,
                "LAB_SERVICE_NOT_ACTIVE",
                "Lab service is not active",
                exception.getMessage(),
                Map.of());
    }

    @ExceptionHandler(DockerLogAccessException.class)
    ResponseEntity<ProblemDetail> logAccess(DockerLogAccessException exception) {
        return problem(
                HttpStatus.BAD_GATEWAY,
                "DOCKER_LOGS_UNAVAILABLE",
                "Docker logs are unavailable",
                exception.getMessage(),
                Map.of());
    }

    @ExceptionHandler(DockerObservationTimeoutException.class)
    ResponseEntity<ProblemDetail> observationTimeout(DockerObservationTimeoutException exception) {
        return problem(
                HttpStatus.GATEWAY_TIMEOUT,
                "DOCKER_OBSERVATION_TIMEOUT",
                "Docker observation timed out",
                exception.getMessage(),
                Map.of());
    }

    @ExceptionHandler(DockerImagePullException.class)
    ResponseEntity<ProblemDetail> imagePull(DockerImagePullException exception) {
        return problem(
                HttpStatus.BAD_GATEWAY,
                "IMAGE_DOWNLOAD_" + exception.reason().name(),
                "Image download failed",
                exception.getMessage(),
                Map.of());
    }

    @ExceptionHandler(DockerStorageFullException.class)
    ResponseEntity<ProblemDetail> dockerStorage(DockerStorageFullException exception) {
        return problem(
                HttpStatus.INSUFFICIENT_STORAGE,
                "DOCKER_STORAGE_FULL",
                "Docker storage is full",
                exception.getMessage(),
                Map.of());
    }

    @ExceptionHandler(DockerPortCollisionException.class)
    ResponseEntity<ProblemDetail> portCollision(DockerPortCollisionException exception) {
        return problem(
                HttpStatus.CONFLICT,
                "HOST_PORT_IN_USE",
                "Local port is in use",
                exception.getMessage(),
                Map.of("service", exception.service(), "hostPorts", exception.hostPorts()));
    }

    @ExceptionHandler(DockerServiceReadinessException.class)
    ResponseEntity<ProblemDetail> readiness(DockerServiceReadinessException exception) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("reason", exception.reason().name());
        properties.put("services", exception.services());
        if (exception.exitCode().isPresent()) {
            properties.put("exitCode", exception.exitCode().getAsInt());
        }
        return problem(
                HttpStatus.CONFLICT,
                "SERVICE_NOT_READY",
                "Service did not become ready",
                exception.getMessage(),
                properties);
    }

    @ExceptionHandler(DockerOwnershipException.class)
    ResponseEntity<ProblemDetail> ownership(DockerOwnershipException exception) {
        return problem(
                HttpStatus.CONFLICT,
                "DOCKER_OWNERSHIP_MISMATCH",
                "Docker ownership check failed",
                "A Docker resource no longer matches LabDeck's ownership record.",
                Map.of());
    }

    @ExceptionHandler(DockerOperationCancelledException.class)
    ResponseEntity<ProblemDetail> cancelled(DockerOperationCancelledException exception) {
        return problem(
                HttpStatus.CONFLICT,
                "OPERATION_CANCELLED",
                "Operation cancelled",
                "The lab operation was cancelled safely.",
                Map.of());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ProblemDetail> illegalArgument(IllegalArgumentException exception) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "INVALID_REQUEST",
                "Invalid request",
                "The request is not valid for this operation.",
                Map.of());
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ProblemDetail> illegalState(IllegalStateException exception) {
        return problem(
                HttpStatus.CONFLICT,
                "OPERATION_CONFLICT",
                "Operation conflict",
                "The lab changed or the local engine could not complete the operation. Refresh and retry.",
                Map.of());
    }

    private static Map<String, String> manifestProblem(ManifestProblem problem) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("code", problem.code().name());
        result.put("path", problem.path());
        result.put("message", problem.message());
        return Map.copyOf(result);
    }

    private static ResponseEntity<ProblemDetail> problem(
            HttpStatus status,
            String code,
            String title,
            String detail,
            Map<String, Object> properties) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("urn:labdeck:problem:" + code.toLowerCase(Locale.ROOT)));
        problem.setTitle(title);
        problem.setInstance(URI.create("/api/v1"));
        properties.forEach(problem::setProperty);
        problem.setProperty("code", code);
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl(CacheControl.noStore());
        return new ResponseEntity<>(problem, headers, status);
    }
}
