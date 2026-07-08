package com.suriya.resume_editor.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.suriya.resume_editor.model.GitHubFile;
import com.suriya.resume_editor.model.ParseRequest;
import com.suriya.resume_editor.model.ParseResponse;
import com.suriya.resume_editor.model.ResumeData;
import com.suriya.resume_editor.model.UpdateRequest;
import com.suriya.resume_editor.service.GitHubService;
import com.suriya.resume_editor.service.HtmlReconstructionService;
import com.suriya.resume_editor.service.HuggingFaceService;
import org.springframework.http.ResponseEntity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/resume")
public class ResumeController {

    private final GitHubService gitHubService;
    private final HuggingFaceService huggingFaceService;
    private final HtmlReconstructionService htmlReconstructionService;
    private final com.suriya.resume_editor.service.JwtService jwtService;
    private final ObjectMapper objectMapper;

    public ResumeController(GitHubService gitHubService,
                            HuggingFaceService huggingFaceService,
                            HtmlReconstructionService htmlReconstructionService,
                            com.suriya.resume_editor.service.JwtService jwtService) {
        this.gitHubService = gitHubService;
        this.huggingFaceService = huggingFaceService;
        this.htmlReconstructionService = htmlReconstructionService;
        this.jwtService = jwtService;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * POST /resume/parse
     * Fetches index.html from the given GitHub repo, sends it to HuggingFace (Llama-3.1-8B-Instruct),
     * and returns the structured ResumeData alongside the sha and originalHtml
     * that the Android app must echo back in the update request.
     *
     * The GitHub access token is retrieved from the authenticated OAuth2 session —
     * clients never need to send it.
     *
     * Request body: { "owner": "...", "repo": "..." }
     */
    @PostMapping("/parse")
    public ResponseEntity<?> parse(@RequestBody ParseRequest request,
                                   HttpServletRequest httpRequest) {

        String token = resolveGitHubToken(httpRequest);

        // 1. Fetch HTML from GitHub (also returns sha for future commit)
        String filePath = (request.getFilePath() != null && !request.getFilePath().isBlank())
                ? request.getFilePath().replaceAll("^/+", "") // strip leading slashes
                : "index.html";
        String repoName = (request.getRepo() != null) ? request.getRepo().replaceAll("[/.]+$", "") : "";
        GitHubFile gitHubFile = gitHubService.fetchPortfolioHtml(
                request.getOwner(), repoName, filePath, token);

        String rawHtml = gitHubFile.getContent();
        String sha     = gitHubFile.getSha();

        // 2. Send HTML to HuggingFace Llama 3 and get back structured data
        ResumeData resumeData = huggingFaceService.parseHtml(rawHtml);

        // 3. Return all three — Android must store sha and originalHtml
        //    and send them back in the update request
        ParseResponse response = new ParseResponse(sha, rawHtml, resumeData);
        return ResponseEntity.ok(response);
    }

    /**
     * POST /resume/fetch
     * Fetches index.html from the given GitHub repo without sending it to AI.
     * This is a fast check to verify the file exists during initial setup.
     */
    @PostMapping("/fetch")
    public ResponseEntity<?> fetch(@RequestBody ParseRequest request,
                                   HttpServletRequest httpRequest) {

        String token = resolveGitHubToken(httpRequest);

        String filePath = (request.getFilePath() != null && !request.getFilePath().isBlank())
                ? request.getFilePath().replaceAll("^/+", "") // strip leading slashes
                : "index.html";
        String repoName = (request.getRepo() != null) ? request.getRepo().replaceAll("[/.]+$", "") : "";
        
        // This will throw if not found, automatically handled by global exception handler
        GitHubFile gitHubFile = gitHubService.fetchPortfolioHtml(
                request.getOwner(), repoName, filePath, token);

        return ResponseEntity.ok(Map.of("message", "Portfolio found successfully", "sha", gitHubFile.getSha()));
    }

    /**
     * POST /resume/update
     * Takes the edited ResumeData, reconstructs the HTML using the original as a
     * template, then commits the updated file back to GitHub.
     *
     * Request body:
     * {
     *   "owner": "...", "repo": "...",
     *   "sha": "abc123",          ← from parse response
     *   "originalHtml": "...",    ← from parse response
     *   "resumeData": { ... }     ← user-edited data
     * }
     */
    @PostMapping("/update")
    public ResponseEntity<?> update(@RequestBody UpdateRequest request,
                                    HttpServletRequest httpRequest) {

        String token = resolveGitHubToken(httpRequest);

        // 1. Surgically inject edited data back into the original HTML template
        String updatedHtml = htmlReconstructionService.reconstructHtml(
                request.getOriginalHtml(), request.getResumeData());

        // 2. Commit the updated HTML back to GitHub
        String filePath = (request.getFilePath() != null && !request.getFilePath().isBlank())
                ? request.getFilePath().replaceAll("^/+", "")
                : "index.html";
        String repoName = (request.getRepo() != null) ? request.getRepo().replaceAll("[/.]+$", "") : "";
        String commitResponseJson = gitHubService.commitPortfolioHtml(
                request.getOwner(), repoName, token,
                request.getSha(), updatedHtml, filePath);

        // 3. Extract the commit URL and new SHA from the GitHub API response
        String commitUrl = extractCommitUrl(commitResponseJson);
        String newSha = extractContentSha(commitResponseJson);

        return ResponseEntity.ok(Map.of(
                "message",     "Portfolio updated successfully",
                "commitUrl",   commitUrl,
                "newSha",      newSha,
                "updatedHtml", updatedHtml
        ));
    }

    /**
     * POST /resume/upload-image
     * Uploads an image file to the GitHub repo's images/ directory and returns
     * the public GitHub Pages URL. The HTML update is deferred — the Android app
     * stores the returned URL in resumeData.profileImageUrl and commits it on
     * the next "Save & Publish" via the existing /resume/update endpoint.
     *
     * Request: multipart/form-data
     *   - image: the image file (MultipartFile)
     *   - repo:  the GitHub repository name
     */
    @PostMapping("/upload-image")
    public ResponseEntity<?> uploadImage(
            @RequestParam("image") MultipartFile image,
            @RequestParam("repo") String repo,
            HttpServletRequest httpRequest) {

        String token = resolveGitHubToken(httpRequest);
        String owner = jwtService.extractUsername(
                httpRequest.getHeader("Authorization").substring(7));

        String repoName = (repo != null) ? repo.replaceAll("[/.]+$", "") : "";

        byte[] imageBytes;
        try {
            imageBytes = image.getBytes();
        } catch (IOException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Failed to read image bytes: " + e.getMessage()));
        }

        String imageName = "profile.jpg";
        String imageUrl = gitHubService.uploadProfileImage(owner, repoName, token, imageName, imageBytes);

        return ResponseEntity.ok(Map.of("imageUrl", imageUrl));
    }

    // -------------------------------------------------------------------------
    // Stats
    // -------------------------------------------------------------------------

    @GetMapping("/stats")
    public ResponseEntity<?> getStats(
            @RequestParam("repo") String repo,
            HttpServletRequest httpRequest) {

        String token = resolveGitHubToken(httpRequest);
        String owner = jwtService.extractUsername(
                httpRequest.getHeader("Authorization").substring(7));
        String repoName = (repo != null) ? repo.replaceAll("[/.]+$", "") : "";

        Map<String, Integer> stats = gitHubService.getRepoStats(owner, repoName, token);
        return ResponseEntity.ok(stats);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Resolves the GitHub access token from the JWT in the Authorization header.
     */
    private String resolveGitHubToken(HttpServletRequest request) {
        String token = jwtService.extractGitHubToken(request);
        if (token == null) {
            throw new RuntimeException("GitHub access token not found in JWT claims.");
        }
        return token;
    }

    /**
     * Extracts the commit HTML URL from GitHub's PUT /contents response JSON.
     * Falls back to an empty string if the field is not present.
     *
     * GitHub response shape:
     * { "commit": { "html_url": "https://github.com/..." }, "content": { ... } }
     */
    private String extractCommitUrl(String commitResponseJson) {
        try {
            JsonNode root = objectMapper.readTree(commitResponseJson);
            JsonNode htmlUrl = root.path("commit").path("html_url");
            return htmlUrl.isMissingNode() ? "" : htmlUrl.asText();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Extracts the new SHA of the updated file from GitHub's PUT /contents response JSON.
     */
    private String extractContentSha(String commitResponseJson) {
        try {
            JsonNode root = objectMapper.readTree(commitResponseJson);
            JsonNode sha = root.path("content").path("sha");
            return sha.isMissingNode() ? "" : sha.asText();
        } catch (Exception e) {
            return "";
        }
    }
}
