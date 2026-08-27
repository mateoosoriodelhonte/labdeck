package io.labdeck.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/csrf")
public class CsrfController {

    private final CsrfTokenRepository tokens;

    public CsrfController(CsrfTokenRepository tokens) {
        this.tokens = tokens;
    }

    @GetMapping
    public ResponseEntity<CsrfTokenResponse> token(CsrfToken token) {
        return response(token);
    }

    @PostMapping("/rotate")
    public ResponseEntity<CsrfTokenResponse> rotate(
            HttpServletRequest request, HttpServletResponse response) {
        tokens.saveToken(null, request, response);
        CsrfToken token = tokens.generateToken(request);
        tokens.saveToken(token, request, response);
        return response(token);
    }

    private static ResponseEntity<CsrfTokenResponse> response(CsrfToken token) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new CsrfTokenResponse("v1", token.getHeaderName(), token.getToken()));
    }

    public record CsrfTokenResponse(String apiVersion, String headerName, String token) {
        @Override
        public String toString() {
            return "CsrfTokenResponse[apiVersion=" + apiVersion
                    + ", headerName=" + headerName + ", token=<redacted>]";
        }
    }
}
