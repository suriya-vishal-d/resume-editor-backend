package com.suriya.resume_editor.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final OAuth2AuthorizedClientService authorizedClientService;
    private final com.suriya.resume_editor.service.JwtService jwtService;



    public AuthController(OAuth2AuthorizedClientService authorizedClientService,
                          com.suriya.resume_editor.service.JwtService jwtService) {
        this.authorizedClientService = authorizedClientService;
        this.jwtService = jwtService;
    }

    /**
     * GET /auth/callback
     * Called automatically after GitHub OAuth2 login succeeds (configured as
     * defaultSuccessUrl in SecurityConfig). Returns the GitHub username, access
     * token, and avatar URL for the Android app to store and use in future calls.
     *
     * Note: this endpoint is mapped to /auth/callback to match the
     * defaultSuccessUrl set in SecurityConfig.
     */
    @GetMapping("/callback")
    public ResponseEntity<?> authCallback(OAuth2AuthenticationToken authentication) {
        try {
            if (authentication == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "Not authenticated. Please complete GitHub login first."));
            }

            // Load the authorized client to extract the actual OAuth2 access token
            OAuth2AuthorizedClient authorizedClient = authorizedClientService.loadAuthorizedClient(
                    authentication.getAuthorizedClientRegistrationId(),
                    authentication.getName()
            );

            if (authorizedClient == null || authorizedClient.getAccessToken() == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "Could not retrieve GitHub access token. Please re-login."));
            }

            // GitHub-specific attributes come in the OAuth2 principal
            Map<String, Object> attributes = authentication.getPrincipal().getAttributes();

            String githubUsername = (String) attributes.get("login");
            String avatarUrl      = (String) attributes.get("avatar_url");
            String accessToken    = authorizedClient.getAccessToken().getTokenValue();
            String jwt            = jwtService.generateToken(githubUsername != null ? githubUsername : "user", accessToken);

            return ResponseEntity.ok(Map.of(
                    "githubUsername", githubUsername != null ? githubUsername : "",
                    "jwt",            jwt,
                    "avatarUrl",      avatarUrl != null ? avatarUrl : ""
            ));

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to process authentication: " + e.getMessage()));
        }
    }

    /**
     * GET /auth/success
     * Called after GitHub OAuth2 login succeeds (configured as defaultSuccessUrl
     * in SecurityConfig). Redirects to the Android app deep link with credentials
     * so Chrome Custom Tabs can hand control back to the app.
     */
    @GetMapping("/success")
    public void authSuccess(OAuth2AuthenticationToken authentication, HttpServletResponse response)
            throws IOException {
        if (authentication == null) {
            response.sendError(HttpStatus.UNAUTHORIZED.value(), "Not authenticated.");
            return;
        }

        OAuth2AuthorizedClient authorizedClient = authorizedClientService.loadAuthorizedClient(
                authentication.getAuthorizedClientRegistrationId(),
                authentication.getName()
        );

        if (authorizedClient == null || authorizedClient.getAccessToken() == null) {
            response.sendError(HttpStatus.UNAUTHORIZED.value(), "Could not retrieve access token.");
            return;
        }

        Map<String, Object> attributes = authentication.getPrincipal().getAttributes();
        String githubUsername = attributes.get("login") != null ? (String) attributes.get("login") : "";
        String avatarUrl = attributes.get("avatar_url") != null ? (String) attributes.get("avatar_url") : "";
        String accessToken = authorizedClient.getAccessToken().getTokenValue();
        String jwt = jwtService.generateToken(githubUsername.isEmpty() ? "user" : githubUsername, accessToken);

        String redirectUri = "com.app.re://auth/success"
                + "?githubUsername=" + URLEncoder.encode(githubUsername, StandardCharsets.UTF_8)
                + "&jwt=" + URLEncoder.encode(jwt, StandardCharsets.UTF_8)
                + "&avatarUrl=" + URLEncoder.encode(avatarUrl, StandardCharsets.UTF_8);

        response.sendRedirect(redirectUri);
    }
}
