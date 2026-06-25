package com.suriya.resume_editor.service;

import com.suriya.resume_editor.exception.GitHubCommitException;
import com.suriya.resume_editor.exception.RepoNotFoundException;
import com.suriya.resume_editor.model.GitHubFile;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
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
                    "Cannot commit — '" + filePath + "' not found in repository '" + owner + "/" + repo + "'.");
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
}
