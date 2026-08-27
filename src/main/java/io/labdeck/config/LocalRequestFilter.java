package io.labdeck.config;

import io.labdeck.api.ApiProblemWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.web.filter.OncePerRequestFilter;

public final class LocalRequestFilter extends OncePerRequestFilter {

    private static final Pattern LOCAL_AUTHORITY =
            Pattern.compile("(?i)(localhost|127\\.0\\.0\\.1)(?::([1-9][0-9]{0,4}))?");
    private static final Set<String> FORWARDED_HEADERS = Set.of(
            "Forwarded", "X-Forwarded-For", "X-Forwarded-Host", "X-Forwarded-Port", "X-Forwarded-Proto");

    private final ApiProblemWriter problems;

    public LocalRequestFilter(ApiProblemWriter problems) {
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String authority = localAuthority(request);
        if (authority == null || hasForwardedHeader(request)) {
            problems.write(
                    response,
                    HttpServletResponse.SC_BAD_REQUEST,
                    "LOCAL_REQUEST_REQUIRED",
                    "Local request required",
                    "LabDeck accepts requests only through its local address.");
            return;
        }
        if (!hasAllowedOrigin(request, authority)) {
            problems.write(
                    response,
                    HttpServletResponse.SC_FORBIDDEN,
                    "CROSS_ORIGIN_REJECTED",
                    "Cross-origin request rejected",
                    "Use the LabDeck application on the same local origin.");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static String localAuthority(HttpServletRequest request) {
        List<String> hostHeaders = Collections.list(request.getHeaders("Host"));
        if (hostHeaders.size() > 1) {
            return null;
        }
        String authority = hostHeaders.isEmpty()
                ? defaultAuthority(request)
                : hostHeaders.getFirst();
        if (authority == null || !authority.equals(authority.strip())) {
            return null;
        }
        Matcher matcher = LOCAL_AUTHORITY.matcher(authority);
        if (!matcher.matches()) {
            return null;
        }
        if (matcher.group(2) != null) {
            try {
                if (Integer.parseInt(matcher.group(2)) > 65_535) {
                    return null;
                }
            } catch (NumberFormatException exception) {
                return null;
            }
        }
        return authority.toLowerCase(Locale.ROOT);
    }

    private static String defaultAuthority(HttpServletRequest request) {
        String host = request.getServerName();
        if (host == null) {
            return null;
        }
        boolean defaultPort = ("http".equalsIgnoreCase(request.getScheme()) && request.getServerPort() == 80)
                || ("https".equalsIgnoreCase(request.getScheme()) && request.getServerPort() == 443);
        return defaultPort ? host : host + ":" + request.getServerPort();
    }

    private static boolean hasForwardedHeader(HttpServletRequest request) {
        return FORWARDED_HEADERS.stream().anyMatch(name -> request.getHeader(name) != null);
    }

    private static boolean hasAllowedOrigin(HttpServletRequest request, String authority) {
        List<String> origins = Collections.list(request.getHeaders("Origin"));
        if (origins.isEmpty()) {
            return true;
        }
        if (origins.size() != 1) {
            return false;
        }
        try {
            URI origin = URI.create(origins.getFirst());
            return origin.isAbsolute()
                    && origin.getUserInfo() == null
                    && (origin.getRawPath() == null || origin.getRawPath().isEmpty())
                    && origin.getRawQuery() == null
                    && origin.getRawFragment() == null
                    && request.getScheme().equalsIgnoreCase(origin.getScheme())
                    && authority.equalsIgnoreCase(origin.getRawAuthority());
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
