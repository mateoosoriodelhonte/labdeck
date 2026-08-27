package io.labdeck.config;

import io.labdeck.api.ApiProblemWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain localSecurity(
            HttpSecurity http,
            CsrfTokenRepository csrfTokens,
            ApiProblemWriter problems) throws Exception {
        http.authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
        http.csrf(csrf -> csrf
                .csrfTokenRepository(csrfTokens)
                .csrfTokenRequestHandler(new HeaderOnlyCsrfTokenRequestHandler()));
        http.exceptionHandling(exceptions -> exceptions.accessDeniedHandler((request, response, denied) ->
                problems.write(
                        response,
                        403,
                        "CSRF_REJECTED",
                        "Request verification failed",
                        "Refresh LabDeck and retry the action.")));
        http.addFilterBefore(new LocalRequestFilter(problems), CsrfFilter.class);
        http.headers(headers -> headers
                .contentSecurityPolicy(csp -> csp.policyDirectives(
                        "default-src 'self'; base-uri 'self'; connect-src 'self'; "
                                + "font-src 'self'; form-action 'self'; frame-ancestors 'none'; "
                                + "img-src 'self' data:; object-src 'none'; script-src 'self'; style-src 'self'"))
                .frameOptions(frame -> frame.deny())
                .permissionsPolicyHeader(policy -> policy.policy(
                        "camera=(), geolocation=(), microphone=(), payment=(), usb=()"))
                .referrerPolicy(referrer -> referrer.policy(
                        org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)));
        return http.build();
    }

    @Bean
    CsrfTokenRepository csrfTokenRepository() {
        HttpSessionCsrfTokenRepository repository = new HttpSessionCsrfTokenRepository();
        repository.setHeaderName("X-LabDeck-CSRF");
        repository.setParameterName("_labdeck_csrf_disabled");
        return repository;
    }
}
