package com.suriya.resume_editor.service;

import com.suriya.resume_editor.exception.GitHubCommitException;
import com.suriya.resume_editor.exception.RepoNotFoundException;
import com.suriya.resume_editor.model.GitHubFile;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

@Service
public class GitHubService {

    private static final String GITHUB_API_BASE = "https://api.github.com";

    private final RestClient restClient;

    public GitHubService(RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * Fetches index.html from the given GitHub repo.
     * Returns a GitHubFile with:
     *  - content: the decoded (plain) HTML string
     *  - sha:     the blob SHA required for committing back
     */
    public GitHubFile fetchPortfolioHtml(String owner, String repo, String filePath, String token) {
        String url = GITHUB_API_BASE + "/repos/" + owner + "/" + repo + "/contents/" + filePath;

        GitHubFile file;
        try {
            file = restClient.get()
                    .uri(url)
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .retrieve()
                    .body(GitHubFile.class);
        } catch (HttpClientErrorException.NotFound e) {
            throw new RepoNotFoundException(
                    "'" + filePath + "' not found in repository '" + owner + "/" + repo + "'. " +
                    "Make sure the file exists and the path is correct.");
        } catch (HttpClientErrorException.Unauthorized e) {
            throw new GitHubCommitException(
                    "GitHub token is invalid or expired. Please re-authenticate.");
        } catch (HttpClientErrorException e) {
            throw new GitHubCommitException(
                    "GitHub API error while fetching portfolio: " + e.getStatusCode() + " " + e.getMessage());
        }

        if (file == null) {
            throw new GitHubCommitException("Received null response from GitHub API for '" + owner + "/" + repo + "/contents/index.html'.");
        }

        // GitHub returns content as base64 with newlines — strip them before decoding
        String rawBase64 = file.getContent().replaceAll("\\s", "");
        String decodedHtml = new String(Base64.getDecoder().decode(rawBase64), StandardCharsets.UTF_8);
        file.setContent(decodedHtml);

        return file;
    }

    /**
     * Commits updated HTML back to the repo.
     *
     * @param sha the blob SHA obtained from fetchPortfolioHtml — GitHub requires
     *            this to prevent conflicts (acts like an optimistic lock).
     */
    public String commitPortfolioHtml(String owner, String repo, String token,
                                      String sha, String updatedHtml, String filePath) {

        String url = GITHUB_API_BASE + "/repos/" + owner + "/" + repo + "/contents/" + filePath;

        System.out.println("DEBUG: Committing to GitHub URL: " + url);
        if (token != null && token.length() > 10) {
            System.out.println("DEBUG: Using token starting with: " + token.substring(0, 8) + "...");
        } else {
            System.out.println("DEBUG: Token is null or too short!");
        }

        // GitHub Contents API expects base64-encoded content (no line breaks)
        String encodedContent = Base64.getEncoder().encodeToString(
                updatedHtml.getBytes(StandardCharsets.UTF_8));

        Map<String, String> requestBody = Map.of(
                "message", "Updated portfolio via Portfolio Editor app",
                "content", encodedContent,
                "sha", sha
        );

        try {
            return restClient.put()
                    .uri(url)
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .header("Content-Type", "application/json")
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);
        } catch (HttpClientErrorException.NotFound e) {
            throw new RepoNotFoundException(
                    "Cannot commit — '" + filePath + "' not found in repository '" + owner + "/" + repo + "'. " +
                    "This often happens if your GitHub token lacks 'repo' (write) scope.");
        } catch (HttpClientErrorException.Unauthorized e) {
            throw new GitHubCommitException(
                    "GitHub token is invalid or expired. Please re-authenticate.");
        } catch (HttpClientErrorException.Conflict e) {
            throw new GitHubCommitException(
                    "Commit conflict: the file was modified on GitHub since it was last fetched. " +
                    "Please fetch the latest version and try again.");
        } catch (HttpClientErrorException.Forbidden e) {
            throw new GitHubCommitException(
                    "Permission denied: ensure your GitHub token has 'repo' scope and you have write access to this repository.");
        } catch (HttpClientErrorException e) {
            throw new GitHubCommitException(
                    "GitHub API error while committing portfolio: " + e.getStatusCode() + " " + e.getMessage());
        }
    }

    /**
     * Checks if a file exists in the repo and returns its sha, or null if it doesn't exist.
     * Used before uploading an image to know whether to include a sha in the PUT body.
     *
     * @param filePath path relative to repo root, e.g. "images/profile.jpg"
     * @return the blob sha string if the file exists, or null if it returns 404
     */
    public String getFileShaIfExists(String owner, String repo, String token, String filePath) {
        String url = GITHUB_API_BASE + "/repos/" + owner + "/" + repo + "/contents/" + filePath;
        try {
            GitHubFile file = restClient.get()
                    .uri(url)
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .retrieve()
                    .body(GitHubFile.class);
            return (file != null) ? file.getSha() : null;
        } catch (HttpClientErrorException.NotFound e) {
            // File does not exist yet — not an error, just return null
            return null;
        } catch (HttpClientErrorException.Unauthorized e) {
            throw new GitHubCommitException(
                    "GitHub token is invalid or expired. Please re-authenticate.");
        } catch (HttpClientErrorException e) {
            throw new GitHubCommitException(
                    "GitHub API error while checking file existence: " + e.getStatusCode() + " " + e.getMessage());
        }
    }

    /**
     * Uploads a binary image to the GitHub repo under images/{imageName}.
     * If the file already exists, its sha is fetched first and included in the PUT
     * body so GitHub replaces it cleanly instead of returning a 422 conflict.
     *
     * @param imageName  filename to use inside the images/ directory, e.g. "profile.jpg"
     * @param imageBytes raw bytes of the image (pre-compressed by the Android app)
     * @return the public GitHub Pages URL for the uploaded image,
     *         e.g. "https://{owner}.github.io/{repo}/images/profile.jpg"
     */
    public String uploadProfileImage(String owner, String repo, String token,
                                     String imageName, byte[] imageBytes) {

        String filePath = "images/" + imageName;
        String url = GITHUB_API_BASE + "/repos/" + owner + "/" + repo + "/contents/" + filePath;

        // Base64-encode the raw image bytes — no line breaks, GitHub requires clean base64
        String encodedContent = Base64.getEncoder().encodeToString(imageBytes);

        // Check whether the file already exists; include sha in the body if so
        String existingSha = getFileShaIfExists(owner, repo, token, filePath);

        // Build the request body — sha is only included when updating an existing file
        Map<String, String> requestBody = new HashMap<>();
        requestBody.put("message", "Updated profile photo via Portfolio Editor app");
        requestBody.put("content", encodedContent);
        if (existingSha != null) {
            requestBody.put("sha", existingSha);
        }

        try {
            restClient.put()
                    .uri(url)
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .header("Content-Type", "application/json")
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);
        } catch (HttpClientErrorException.Unauthorized e) {
            throw new GitHubCommitException(
                    "GitHub token is invalid or expired. Please re-authenticate.");
        } catch (HttpClientErrorException.Forbidden e) {
            throw new GitHubCommitException(
                    "Permission denied: ensure your GitHub token has 'repo' scope and write access to this repository.");
        } catch (HttpClientErrorException.UnprocessableEntity e) {
            throw new GitHubCommitException(
                    "GitHub rejected the image upload (422). The file may have been modified externally. Please try again.");
        } catch (HttpClientErrorException e) {
            throw new GitHubCommitException(
                    "GitHub API error while uploading image: " + e.getStatusCode() + " " + e.getMessage());
        }

        // Build and return the GitHub Pages URL
        // Pattern: https://{owner}.github.io/{repo}/images/{imageName}
        // Special case: if repo is the user's root GitHub Pages repo (owner.github.io),
        // the Pages URL has no sub-path prefix.
        String repoLower = repo.toLowerCase();
        String ownerLower = owner.toLowerCase();
        if (repoLower.equals(ownerLower + ".github.io")) {
            return "https://" + ownerLower + ".github.io/images/" + imageName;
        } else {
            return "https://" + ownerLower + ".github.io/" + repo + "/images/" + imageName;
        }
    }

    /**
     * Fetches basic repository statistics (stars, watchers, forks) from GitHub API.
     */
    public Map<String, Integer> getRepoStats(String owner, String repo, String token) {
        String url = GITHUB_API_BASE + "/repos/" + owner + "/" + repo;
        try {
            JsonNode response = restClient.get()
                    .uri(url)
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .retrieve()
                    .body(JsonNode.class);

            if (response != null) {
                return Map.of(
                        "stars", response.path("stargazers_count").asInt(0),
                        "watchers", response.path("subscribers_count").asInt(0),
                        "forks", response.path("forks_count").asInt(0)
                );
            }
        } catch (Exception e) {
            System.err.println("Failed to fetch repo stats: " + e.getMessage());
        }
        return Map.of("stars", 0, "watchers", 0, "forks", 0);
    }
}


