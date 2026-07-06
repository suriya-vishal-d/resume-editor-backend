package com.suriya.resume_editor.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.suriya.resume_editor.model.ResumeData;
import com.suriya.resume_editor.exception.CloudflareAIException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Service
public class CloudflareAIService {

    private final RestClient restClient;
    private final String accountId;
    private final String apiToken;
    private final String model;
    private final ObjectMapper objectMapper;
    private final HtmlCleanerService htmlCleaner;

    public CloudflareAIService(RestClient restClient,
                               @Value("${cloudflare.account.id}") String accountId,
                               @Value("${cloudflare.api.token}") String apiToken,
                               @Value("${cloudflare.model}") String model,
                               HtmlCleanerService htmlCleaner) {
        this.restClient = restClient;
        this.accountId = accountId;
        this.apiToken = apiToken;
        this.model = model;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.htmlCleaner = htmlCleaner;
    }

    /**
     * Sends raw HTML to Cloudflare Workers AI (GLM-5.2) and parses the AI response into a ResumeData object.
     *
     * @param rawHtml the decoded HTML string fetched from GitHub
     * @return ResumeData populated with all fields the AI could extract
     */
    public ResumeData parseHtml(String rawHtml) {

        // Strip CSS, JS, SVG, and noisy attributes before sending to the AI.
        // This typically reduces file size by 80-95%, making the call much faster
        // and virtually eliminating timeout risk on large portfolio pages.
        String cleanedHtml = htmlCleaner.clean(rawHtml);

        String systemPrompt = "You are an HTML parser. Extract portfolio details from HTML and return " +
                "ONLY a valid JSON object. No explanation, no markdown, no code blocks. Just raw JSON. " +
                "IMPORTANT: Some data (like skills) may not be in the HTML body, but stored inside inline <script> " +
                "tags as JavaScript objects (e.g., DEFAULT_DATA). You MUST read the inline scripts to extract missing data.";

        String userPrompt = "Parse this HTML and extract all available details into this exact JSON structure. " +
                "If a field is not found in the HTML tags, carefully check any inline <script> tags for JavaScript arrays/objects containing the data. " +
                "IMPORTANT: If data (like skills, certifications) is grouped or represented as objects, you MUST flatten it into a simple list of strings. " +
                "If a field is truly not found anywhere, leave it null or empty list:\n" +
                "{\n" +
                "  \"name\": \"\",\n" +
                "  \"tagline\": \"\",\n" +
                "  \"about\": \"\",\n" +
                "  \"profileImageUrl\": \"\",\n" +
                "  \"skills\": [\"string1\", \"string2\"],\n" +
                "  \"projects\": [{\"title\":\"\",\"description\":\"\",\"techStack\":[],\"link\":\"\"}],\n" +
                "  \"experience\": [{\"company\":\"\",\"role\":\"\",\"startDate\":\"\",\"endDate\":\"\"}],\n" +
                "  \"education\": [{\"institution\":\"\",\"degree\":\"\",\"field\":\"\",\"startYear\":\"\",\"endYear\":\"\",\"grade\":\"\"}],\n" +
                "  \"contact\": {\"email\":\"\",\"linkedin\":\"\",\"github\":\"\"},\n" +
                "  \"certifications\": [\"string1\"],\n" +
                "  \"achievements\": [\"string1\"],\n" +
                "  \"languages\": [\"string1\"],\n" +
                "  \"blogPosts\": [{\"title\":\"\",\"link\":\"\",\"date\":\"\"}],\n" +
                "  \"hobbies\": [\"string1\"],\n" +
                "  \"resumePdfUrl\": \"\"\n" +
                "}\n\nHTML:\n" + cleanedHtml;

        // Cloudflare Workers AI uses a messages-based request body
        Map<String, Object> requestBody = Map.of(
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user",   "content", userPrompt)
                )
        );

        // Build the endpoint URL dynamically from accountId and model (stripping accidental quotes/spaces)
        String cleanAccountId = accountId.trim().replace("\"", "");
        String cleanModel = model.trim().replace("\"", "");
        String endpoint = "https://api.cloudflare.com/client/v4/accounts/" + cleanAccountId + "/ai/run/" + cleanModel;

        String rawResponse;
        try {
            rawResponse = restClient.post()
                    .uri(endpoint)
                    .header("Authorization", "Bearer " + apiToken)
                    .header("Content-Type", "application/json")
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);
        } catch (HttpClientErrorException.Unauthorized e) {
            throw new CloudflareAIException(
                    "Cloudflare API token is invalid or expired. Check 'cloudflare.api.token' in application.properties.");
        } catch (HttpClientErrorException.TooManyRequests e) {
            throw new CloudflareAIException(
                    "Cloudflare Workers AI rate limit reached. Please wait before retrying.");
        } catch (HttpClientErrorException e) {
            throw new CloudflareAIException(
                    "Cloudflare Workers AI API error: " + e.getStatusCode() + " — " + e.getMessage());
        } catch (ResourceAccessException e) {
            throw new CloudflareAIException(
                    "Request to Cloudflare Workers AI timed out. The HTML may be too large or the AI service is temporarily slow. " +
                    "Please try again shortly.");
        }

        // Navigate: response → result → response
        // Cloudflare Workers AI returns: { "result": { "response": "<text>" }, "success": true, ... }
        String jsonContent = extractContentFromResponse(rawResponse);

        // Strip markdown code fences if the model included them despite instructions
        jsonContent = stripMarkdownFences(jsonContent);

        return parseResumeData(jsonContent);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String extractContentFromResponse(String rawResponse) {
        try {
            JsonNode root = objectMapper.readTree(rawResponse);
            JsonNode result = root.path("result");

            // GLM-5.2 and other OpenAI-compatible models return:
            // { "result": { "choices": [{ "message": { "content": "..." } }] } }
            JsonNode choices = result.path("choices");
            if (!choices.isMissingNode() && choices.isArray() && choices.size() > 0) {
                JsonNode content = choices.get(0).path("message").path("content");
                if (!content.isMissingNode() && !content.isNull()) {
                    return content.asText();
                }
            }

            // Legacy Cloudflare models (e.g. Llama 3) return:
            // { "result": { "response": "..." } }
            JsonNode legacyContent = result.path("response");
            if (!legacyContent.isMissingNode() && !legacyContent.isNull()) {
                return legacyContent.asText();
            }

            throw new CloudflareAIException(
                    "Cloudflare AI response was missing both 'result.choices[0].message.content' " +
                    "and 'result.response'. Raw response: " + rawResponse);
        } catch (CloudflareAIException e) {
            throw e;
        } catch (Exception e) {
            throw new CloudflareAIException(
                    "Failed to extract content from Cloudflare AI response. Raw response: " + rawResponse);
        }
    }

    /**
     * LLMs sometimes wrap output in ```json ... ``` blocks even when instructed not to.
     * This strips those fences so Jackson can parse the JSON cleanly.
     */
    private String stripMarkdownFences(String text) {
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            // Remove opening fence (```json or just ```)
            trimmed = trimmed.replaceFirst("^```[a-zA-Z]*\\n?", "");
            // Remove closing fence
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.lastIndexOf("```")).trim();
            }
        }
        return trimmed;
    }

    private ResumeData parseResumeData(String jsonContent) {
        try {
            return objectMapper.readValue(jsonContent, ResumeData.class);
        } catch (Exception e) {
            throw new CloudflareAIException(
                    "Failed to parse AI response into ResumeData. " +
                    "The model may have returned malformed JSON. Content received:\n" + jsonContent);
        }
    }
}
