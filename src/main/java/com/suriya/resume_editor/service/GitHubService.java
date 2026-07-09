package com.suriya.resume_editor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.suriya.resume_editor.exception.GitHubCommitException;
import com.suriya.resume_editor.exception.RepoNotFoundException;
import com.suriya.resume_editor.model.GitHubFile;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
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
     * - content: the decoded (plain) HTML string
     * - sha: the blob SHA required for committing back
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
        } catch (HttpServerErrorException e) {
            // GitHub Contents API returns 5xx for files larger than ~1MB.
            // Fall back to the Git Blobs API which supports files of any size.
            System.out.println("INFO: Contents API returned " + e.getStatusCode()
                    + " for '" + filePath + "' — falling back to Git Blobs API (file may be >1MB).");
            return fetchPortfolioHtmlViaBlobs(owner, repo, filePath, token);
        } catch (ResourceAccessException e) {
            throw new GitHubCommitException(
                    "Timed out while connecting to GitHub. Please check your network and try again.");
        }

        if (file == null) {
            throw new GitHubCommitException("Received null response from GitHub API for '"
                    + owner + "/" + repo + "/contents/" + filePath + "'.");
        }

        // GitHub Contents API returns null content (and a download_url) for files >1MB.
        // In that case fall back to the Git Blobs API.
        if (file.getContent() == null || file.getContent().isBlank()) {
            System.out.println("INFO: Contents API returned empty content for '" + filePath
                    + "' (sha=" + file.getSha() + ") — falling back to Git Blobs API.");
            return fetchPortfolioHtmlViaBlobs(owner, repo, filePath, token);
        }

        // GitHub returns content as base64 with newlines — strip them before decoding
        String rawBase64 = file.getContent().replaceAll("\\s", "");
        String decodedHtml = new String(Base64.getDecoder().decode(rawBase64), StandardCharsets.UTF_8);
        file.setContent(decodedHtml);

        return file;
    }

    /**
     * Fallback fetch using the Git Trees + Blobs API.
     * The Contents API fails (502/empty body) for files larger than ~1MB.
     * The Blobs API has no such restriction and always returns the full file.
     *
     * Steps:
     * 1. GET /repos/{owner}/{repo}/git/trees/HEAD?recursive=1 → find the blob SHA
     * for filePath
     * 2. GET /repos/{owner}/{repo}/git/blobs/{blobSha} → fetch full base64 content
     */
    private GitHubFile fetchPortfolioHtmlViaBlobs(String owner, String repo, String filePath, String token) {
        // Step 1: Resolve blob SHA from the tree
        String treeUrl = GITHUB_API_BASE + "/repos/" + owner + "/" + repo + "/git/trees/HEAD?recursive=1";
        JsonNode tree;
        try {
            tree = restClient.get()
                    .uri(treeUrl)
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .retrieve()
                    .body(JsonNode.class);
        } catch (HttpClientErrorException.NotFound e) {
            throw new RepoNotFoundException(
                    "Repository '" + owner + "/" + repo + "' not found or default branch is not 'HEAD'.");
        } catch (HttpClientErrorException e) {
            throw new GitHubCommitException(
                    "GitHub API error while fetching repository tree: " + e.getStatusCode());
        } catch (ResourceAccessException e) {
            throw new GitHubCommitException(
                    "Timed out while fetching repository tree from GitHub.");
        }

        if (tree == null || !tree.has("tree")) {
            throw new GitHubCommitException("GitHub returned an empty tree for '" + owner + "/" + repo + "'.");
        }

        // Find the blob SHA for the requested file path
        String blobSha = null;
        for (JsonNode entry : tree.get("tree")) {
            if (filePath.equals(entry.path("path").asText())) {
                blobSha = entry.path("sha").asText();
                break;
            }
        }

        if (blobSha == null) {
            throw new RepoNotFoundException(
                    "'" + filePath + "' not found in repository '" + owner + "/" + repo + "'. "
                            + "Make sure the file exists and the path is correct.");
        }

        // Step 2: Fetch the blob content
        String blobUrl = GITHUB_API_BASE + "/repos/" + owner + "/" + repo + "/git/blobs/" + blobSha;
        JsonNode blob;
        try {
            blob = restClient.get()
                    .uri(blobUrl)
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .retrieve()
                    .body(JsonNode.class);
        } catch (HttpClientErrorException e) {
            throw new GitHubCommitException(
                    "GitHub API error while fetching file blob: " + e.getStatusCode());
        } catch (ResourceAccessException e) {
            throw new GitHubCommitException(
                    "Timed out while fetching file blob from GitHub.");
        }

        if (blob == null) {
            throw new GitHubCommitException("Received null blob response from GitHub for '" + filePath + "'.");
        }

        // Decode the base64 content (blobs always use base64 encoding)
        String rawBase64 = blob.path("content").asText("").replaceAll("\\s", "");
        if (rawBase64.isEmpty()) {
            throw new GitHubCommitException("GitHub blob for '" + filePath + "' has empty content.");
        }
        String decodedHtml = new String(Base64.getDecoder().decode(rawBase64), StandardCharsets.UTF_8);

        // We also need the Contents API SHA (not blob SHA) for future commits.
        // Re-fetch just the metadata (no body decoding) from Contents API.
        String contentsSha = resolveContentsSha(owner, repo, filePath, token, blobSha);

        GitHubFile file = new GitHubFile();
        file.setContent(decodedHtml);
        file.setSha(contentsSha);
        return file;
    }

    /**
     * Fetches only the SHA of a file from the Contents API (needed for committing
     * back).
     * Falls back to the blob SHA if the Contents API is unavailable.
     */
    private String resolveContentsSha(String owner, String repo, String filePath, String token, String fallbackSha) {
        String url = GITHUB_API_BASE + "/repos/" + owner + "/" + repo + "/contents/" + filePath;
        try {
            GitHubFile meta = restClient.get()
                    .uri(url)
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .retrieve()
                    .body(GitHubFile.class);
            return (meta != null && meta.getSha() != null) ? meta.getSha() : fallbackSha;
        } catch (Exception e) {
            // If Contents API is still failing, the blob SHA itself IS the file SHA
            // in GitHub's object model — it will work for the commit PUT body.
            System.err.println("WARN: Could not resolve contents SHA, using blob SHA as fallback: " + e.getMessage());
            return fallbackSha;
        }
    }

    /**
     * Commits updated HTML back to the repo.
     *
     * @param sha    the blob SHA obtained from fetchPortfolioHtml — GitHub requires
     *               this to prevent conflicts (acts like an optimistic lock).
     * @param branch the branch to commit to (e.g. "main", "gh-pages"). If null or
     *               blank, the repo's default branch is used.
     */
    public String commitPortfolioHtml(String owner, String repo, String token,
            String sha, String updatedHtml, String filePath, String branch) {

        String url = GITHUB_API_BASE + "/repos/" + owner + "/" + repo + "/contents/" + filePath;

        System.out.println("DEBUG: Committing to GitHub URL: " + url + " (branch=" + branch + ")");
        if (token != null && token.length() > 10) {
            System.out.println("DEBUG: Using token starting with: " + token.substring(0, 8) + "...");
        } else {
            System.out.println("DEBUG: Token is null or too short!");
        }

        // GitHub Contents API expects base64-encoded content (no line breaks)
        String encodedContent = Base64.getEncoder().encodeToString(
                updatedHtml.getBytes(StandardCharsets.UTF_8));

        // Build mutable body so we can conditionally include 'branch'
        Map<String, String> requestBody = new HashMap<>();
        requestBody.put("message", "Updated portfolio via Portfolio Editor app");
        requestBody.put("content", encodedContent);
        requestBody.put("sha", sha);
        if (branch != null && !branch.isBlank()) {
            requestBody.put("branch", branch);
        }

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
     * Checks if a file exists in the repo and returns its sha, or null if it
     * doesn't exist.
     * Used before uploading an image to know whether to include a sha in the PUT
     * body.
     *
     * @param filePath path relative to repo root, e.g. "images/profile.jpg"
     * @param branch   the branch to check (null → repo default)
     * @return the blob sha string if the file exists, or null if it returns 404
     */
    public String getFileShaIfExists(String owner, String repo, String token, String filePath, String branch) {
        String url = GITHUB_API_BASE + "/repos/" + owner + "/" + repo + "/contents/" + filePath;
        // Append ?ref= so we query the correct branch
        if (branch != null && !branch.isBlank()) {
            url = url + "?ref=" + branch;
        }
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
     * @param imageName  filename to use inside the images/ directory, e.g.
     *                   "profile.jpg"
     * @param imageBytes raw bytes of the image (pre-compressed by the Android app)
     * @param branch     the branch to commit to (e.g. "gh-pages"). If null or
     *                   blank, the repo's default branch is used.
     * @return the public GitHub Pages URL for the uploaded image,
     *         e.g. "https://{owner}.github.io/{repo}/images/profile.jpg"
     */
    public String uploadProfileImage(String owner, String repo, String token,
            String imageName, byte[] imageBytes, String branch) {

        String filePath = "images/" + imageName;
        String url = GITHUB_API_BASE + "/repos/" + owner + "/" + repo + "/contents/" + filePath;

        // Base64-encode the raw image bytes — no line breaks, GitHub requires clean
        // base64
        String encodedContent = Base64.getEncoder().encodeToString(imageBytes);

        // Check whether the file already exists on the target branch; include sha if so
        String existingSha = getFileShaIfExists(owner, repo, token, filePath, branch);

        // Build the request body — sha is only included when updating an existing file
        Map<String, String> requestBody = new HashMap<>();
        requestBody.put("message", "Updated profile photo via Portfolio Editor app");
        requestBody.put("content", encodedContent);
        if (existingSha != null) {
            requestBody.put("sha", existingSha);
        }
        if (branch != null && !branch.isBlank()) {
            requestBody.put("branch", branch);
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
                        "forks", response.path("forks_count").asInt(0));
            }
        } catch (Exception e) {
            System.err.println("Failed to fetch repo stats: " + e.getMessage());
        }
        return Map.of("stars", 0, "watchers", 0, "forks", 0);
    }
}
