package com.suriya.resume_editor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.suriya.resume_editor.model.ResumeData;
import com.suriya.resume_editor.exception.HuggingFaceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Service
public class HuggingFaceService {

    private static final String HF_API_URL =
            "https://router.huggingface.co/v1/chat/completions";

    private final RestClient restClient;
    private final String apiKey;
    private final ObjectMapper objectMapper;
    private final String model;
    private final HtmlCleanerService htmlCleaner;

    public HuggingFaceService(RestClient restClient,
                               @Value("${huggingface.api.key}") String apiKey,
                               @Value("${huggingface.model}") String model,
                               HtmlCleanerService htmlCleaner) {
        this.restClient = restClient;
        this.apiKey = apiKey;
        this.model = model;
        this.objectMapper = new ObjectMapper();
        this.htmlCleaner = htmlCleaner;
    }

    /**
     * Sends raw HTML to HuggingFace Llama3 and parses the AI response into a ResumeData object.
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
                "IMPORTANT: If the skills data is grouped (e.g., has 'group' and 'items'), you MUST flatten all the items into a single, flat list of strings for the 'skills' array. " +
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
                "  \"certifications\": [],\n" +
                "  \"achievements\": [],\n" +
                "  \"languages\": [],\n" +
                "  \"blogPosts\": [{\"title\":\"\",\"link\":\"\",\"date\":\"\"}],\n" +
                "  \"hobbies\": [],\n" +
                "  \"resumePdfUrl\": \"\"\n" +
                "}\n\nHTML:\n" + cleanedHtml;

        Map<String, Object> requestBody = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user",   "content", userPrompt)
                ),
                "max_tokens", 2000,
                "temperature", 0.1
        );

        String rawResponse;
        try {
            rawResponse = restClient.post()
                    .uri(HF_API_URL)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);
        } catch (HttpClientErrorException.Unauthorized e) {
            throw new HuggingFaceException(
                    "HuggingFace API key is invalid or expired. Check 'huggingface.api.key' in application.properties.");
        } catch (HttpClientErrorException.TooManyRequests e) {
            throw new HuggingFaceException(
                    "HuggingFace rate limit reached. Please wait before retrying.");
        } catch (HttpClientErrorException e) {
            throw new HuggingFaceException(
                    "HuggingFace API error: " + e.getStatusCode() + " — " + e.getMessage());
        } catch (ResourceAccessException e) {
            throw new HuggingFaceException(
                    "Request to HuggingFace timed out. The HTML may be too large or the AI service is temporarily slow. " +
                    "Please try again shortly.");
        }

        // Navigate: response → choices[0] → message → content
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
            JsonNode content = root
                    .path("choices")
                    .get(0)
                    .path("message")
                    .path("content");

            if (content.isMissingNode() || content.isNull()) {
                throw new HuggingFaceException(
                        "HuggingFace response was missing 'choices[0].message.content'. " +
                        "Raw response: " + rawResponse);
            }
            return content.asText();
        } catch (Exception e) {
            throw new HuggingFaceException(
                    "Failed to extract content from HuggingFace response. Raw response: " + rawResponse);
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
            throw new HuggingFaceException(
                    "Failed to parse AI response into ResumeData. " +
                    "The model may have returned malformed JSON. Content received:\n" + jsonContent);
        }
    }
}
