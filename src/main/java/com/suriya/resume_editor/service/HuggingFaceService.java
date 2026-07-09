package com.suriya.resume_editor.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.suriya.resume_editor.exception.HuggingFaceException;
import com.suriya.resume_editor.model.ResumeData;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Service
public class HuggingFaceService {

    // HuggingFace router exposes an OpenAI-compatible /chat/completions endpoint
    private static final String HF_API_URL = "https://router.huggingface.co/v1/chat/completions";

    private final RestClient restClient;
    private final String apiKey;
    private final String model;
    private final ObjectMapper objectMapper;
    private final HtmlCleanerService htmlCleaner;

    public HuggingFaceService(RestClient restClient,
                               @Value("${huggingface.api.key:${HF_API_KEY:}}") String apiKey,
                               @Value("${huggingface.model:${HF_MODEL:meta-llama/Llama-3.1-8B-Instruct}}") String model,
                               HtmlCleanerService htmlCleaner) {
        this.restClient = restClient;
        this.apiKey = apiKey;
        this.model = model;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.htmlCleaner = htmlCleaner;
    }

    /**
     * Sends raw HTML to HuggingFace (Llama-3.1-8B-Instruct) via the OpenAI-compatible
     * router endpoint and parses the AI response into a ResumeData object.
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
                "IMPORTANT: Group skills into appropriate categories (e.g., 'Languages', 'Frameworks', 'Tools', or 'General'). If they are already grouped in the source, preserve those groups. " +
                "IMPORTANT: If other data (like certifications) is grouped or represented as objects, you MUST flatten it into a simple list of strings. " +
                "If a field is truly not found anywhere, leave it null or empty list:\n" +
                "{\n" +
                "  \"name\": \"\",\n" +
                "  \"tagline\": \"\",\n" +
                "  \"about\": \"\",\n" +
                "  \"profileImageUrl\": \"\",\n" +
                "  \"skills\": [{\"category\":\"Languages\", \"items\":[\"Java\", \"Python\"]}],\n" +
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

        // OpenAI-compatible request body
        Map<String, Object> requestBody = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user",   "content", userPrompt)
                ),
                "max_tokens", 4096
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
                    "HuggingFace API rate limit reached. Please wait before retrying.");
        } catch (HttpClientErrorException e) {
            throw new HuggingFaceException(
                    "HuggingFace API error: " + e.getStatusCode() + " — " + e.getMessage());
        } catch (ResourceAccessException e) {
            throw new HuggingFaceException(
                    "Request to HuggingFace timed out. The HTML may be too large or the service is temporarily slow. " +
                    "Please try again shortly.");
        }

        // HuggingFace router returns standard OpenAI format:
        // { "choices": [{ "message": { "content": "..." } }] }
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

            // Standard OpenAI-compatible format:
            // { "choices": [{ "message": { "content": "..." } }] }
            JsonNode choices = root.path("choices");
            if (!choices.isMissingNode() && choices.isArray() && choices.size() > 0) {
                JsonNode content = choices.get(0).path("message").path("content");
                if (!content.isMissingNode() && !content.isNull()) {
                    return content.asText();
                }
            }

            throw new HuggingFaceException(
                    "HuggingFace response was missing 'choices[0].message.content'. Raw response: " + rawResponse);
        } catch (HuggingFaceException e) {
            throw e;
        } catch (Exception e) {
            throw new HuggingFaceException(
                    "Failed to extract content from HuggingFace response. Raw response: " + rawResponse);
        }
    }

    /**
     * LLMs (especially Llama 3) sometimes include conversational text before or after the JSON,
     * or wrap output in ```json ... ``` blocks even when instructed not to.
     * This safely extracts just the root JSON object/array so Jackson can parse it cleanly.
     */
    private String stripMarkdownFences(String text) {
        if (text == null || text.isBlank()) return "{}";
        
        // Find the first '{' and the last '}'
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        
        // If it looks like an array instead of an object, try '[' and ']'
        if (start == -1 || (text.indexOf('[') != -1 && text.indexOf('[') < start)) {
            start = text.indexOf('[');
            end = text.lastIndexOf(']');
        }
        
        if (start != -1 && end != -1 && end > start) {
            return text.substring(start, end + 1);
        }
        
        return text.trim();
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
