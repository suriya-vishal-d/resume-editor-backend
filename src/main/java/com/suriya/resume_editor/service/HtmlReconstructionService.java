package com.suriya.resume_editor.service;

import com.suriya.resume_editor.model.ResumeData;
import com.suriya.resume_editor.exception.HtmlReconstructionException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;

@Service
public class HtmlReconstructionService {

    /**
     * Surgically replaces only text/href content in the original HTML using the
     * edited ResumeData. CSS, JS, structure, class names and IDs are never touched.
     *
     * @param originalHtml the raw HTML string fetched from GitHub
     * @param resumeData   the edited data coming back from the Android app
     * @return the updated HTML string ready to commit back to GitHub
     */
    public String reconstructHtml(String originalHtml, ResumeData resumeData) {
        try {
            Document doc = Jsoup.parse(originalHtml);

            // ----------------------------------------------------------------
            // Basic identity fields
            // ----------------------------------------------------------------
            updateText(doc, "h1, .name, #name, [class*=name]",
                    resumeData.getName());

            updateText(doc, ".tagline, .subtitle, #tagline, [class*=tagline]",
                    resumeData.getTagline());

            updateText(doc, ".about, #about, [class*=about] p, #about p",
                    resumeData.getAbout());

            // ----------------------------------------------------------------
            // Profile image — <img src> and CSS background-image on divs
            // ----------------------------------------------------------------
            if (resumeData.getProfileImageUrl() != null && !resumeData.getProfileImageUrl().isBlank()) {
                updateProfileImage(doc, resumeData.getProfileImageUrl());
            }

            // ----------------------------------------------------------------
            // Contact links — update href only, preserve link display text
            // ----------------------------------------------------------------
            if (resumeData.getContact() != null) {
                if (resumeData.getContact().getEmail() != null) {
                    updateAttr(doc, "a[href^=mailto]",
                            "href", "mailto:" + resumeData.getContact().getEmail());
                }
                if (resumeData.getContact().getGithub() != null) {
                    updateAttr(doc, "a[href*=github.com]",
                            "href", resumeData.getContact().getGithub());
                }
                if (resumeData.getContact().getLinkedin() != null) {
                    updateAttr(doc, "a[href*=linkedin.com]",
                            "href", resumeData.getContact().getLinkedin());
                }
            }

            // ----------------------------------------------------------------
            // Skills (static-HTML path) — only runs when the skills section
            // already contains <li>/<span> elements in the DOM.  JS-driven
            // templates (empty shell + DEFAULT_DATA script) are handled by
            // replaceJsSkillsArray() after outerHtml() below.
            // ----------------------------------------------------------------
            if (resumeData.getSkills() != null && !resumeData.getSkills().isEmpty()) {
                Element skillsSection = doc.select(
                        ".skills, #skills, [class*=skills]").first();
                if (skillsSection != null) {
                    var skillItems = skillsSection.select("li, span, .skill-item, [class*=skill-item]");
                    // Only proceed if there are actual pre-rendered skill elements to overwrite.
                    // An empty container means this is a JS-driven template — skip DOM rewrite.
                    if (!skillItems.isEmpty()) {
                        java.util.List<String> flatSkills = new java.util.ArrayList<>();
                        for (com.suriya.resume_editor.model.SkillGroup group : resumeData.getSkills()) {
                            if (group.getItems() != null) flatSkills.addAll(group.getItems());
                        }
                        for (int i = 0; i < Math.min(skillItems.size(), flatSkills.size()); i++) {
                            skillItems.get(i).text(flatSkills.get(i));
                        }
                    }
                }
            }

            // ----------------------------------------------------------------
            // Projects — update title and description of existing project cards
            // ----------------------------------------------------------------
            if (resumeData.getProjects() != null && !resumeData.getProjects().isEmpty()) {
                Element projectsSection = doc.select(
                        ".projects, #projects, [class*=projects]").first();
                if (projectsSection != null) {
                    var cards = projectsSection.select(
                            ".project, .card, [class*=project-card], [class*=project-item], article");
                    for (int i = 0; i < Math.min(cards.size(), resumeData.getProjects().size()); i++) {
                        Element card = cards.get(i);
                        var project = resumeData.getProjects().get(i);

                        if (project.getTitle() != null) {
                            Element title = card.select("h2, h3, h4, .title, [class*=title]").first();
                            if (title != null) title.text(project.getTitle());
                        }
                        if (project.getDescription() != null) {
                            Element desc = card.select("p, .description, [class*=desc]").first();
                            if (desc != null) desc.text(project.getDescription());
                        }
                        if (project.getLink() != null) {
                            Element link = card.select("a[href]").first();
                            if (link != null) link.attr("href", project.getLink());
                        }
                    }
                }
            }

            // ----------------------------------------------------------------
            // Experience — update existing entries
            // ----------------------------------------------------------------
            if (resumeData.getExperience() != null && !resumeData.getExperience().isEmpty()) {
                Element expSection = doc.select(
                        ".experience, #experience, [class*=experience]").first();
                if (expSection != null) {
                    var entries = expSection.select(
                            ".job, .entry, [class*=experience-item], [class*=job-item], article");
                    for (int i = 0; i < Math.min(entries.size(), resumeData.getExperience().size()); i++) {
                        Element entry = entries.get(i);
                        var exp = resumeData.getExperience().get(i);

                        if (exp.getRole() != null) {
                            Element role = entry.select("h2, h3, h4, .role, .title, [class*=role]").first();
                            if (role != null) role.text(exp.getRole());
                        }
                        if (exp.getCompany() != null) {
                            Element company = entry.select(".company, [class*=company], span").first();
                            if (company != null) company.text(exp.getCompany());
                        }
                    }
                }
            }

            // ----------------------------------------------------------------
            // Education — update existing entries
            // ----------------------------------------------------------------
            if (resumeData.getEducation() != null && !resumeData.getEducation().isEmpty()) {
                Element eduSection = doc.select(
                        ".education, #education, [class*=education]").first();
                if (eduSection != null) {
                    var entries = eduSection.select(
                            ".edu-item, .entry, [class*=education-item], article, li");
                    for (int i = 0; i < Math.min(entries.size(), resumeData.getEducation().size()); i++) {
                        Element entry = entries.get(i);
                        var edu = resumeData.getEducation().get(i);

                        if (edu.getInstitution() != null) {
                            Element inst = entry.select("h2, h3, h4, .institution, [class*=institution]").first();
                            if (inst != null) inst.text(edu.getInstitution());
                        }
                        if (edu.getDegree() != null) {
                            Element degree = entry.select(".degree, [class*=degree], span, p").first();
                            if (degree != null) degree.text(edu.getDegree());
                        }
                    }
                }
            }

            String finalHtml = doc.outerHtml();

            // ----------------------------------------------------------------
            // JavaScript Data Layer — for templates that store ALL data inside
            // an inline <script> (e.g. DEFAULT_DATA / pf_data / similar)
            // ----------------------------------------------------------------

            // Skills — replaces the entire skills:[...] array in the JS object.
            // This correctly handles added/removed skills and category changes.
            if (resumeData.getSkills() != null && !resumeData.getSkills().isEmpty()) {
                finalHtml = replaceJsSkillsArray(finalHtml, resumeData.getSkills());
            }

            // Scalar JS fields — use safe string-boundary parser instead of
            // naive [^"']* regex, which breaks on apostrophes (e.g. "I'm")
            if (resumeData.getProfileImageUrl() != null && !resumeData.getProfileImageUrl().isBlank()) {
                finalHtml = replaceJsScalarField(finalHtml, "avatar", resumeData.getProfileImageUrl());
            }
            if (resumeData.getName() != null && !resumeData.getName().isBlank()) {
                finalHtml = replaceJsScalarField(finalHtml, "name", resumeData.getName());
            }
            if (resumeData.getTagline() != null && !resumeData.getTagline().isBlank()) {
                finalHtml = replaceJsScalarField(finalHtml, "tagline", resumeData.getTagline());
            }
            if (resumeData.getAbout() != null && !resumeData.getAbout().isBlank()) {
                finalHtml = replaceJsScalarField(finalHtml, "about", resumeData.getAbout());
            }

            return finalHtml;

        } catch (Exception e) {
            throw new HtmlReconstructionException("Failed to reconstruct HTML: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Helpers — null-safe single-element updaters
    // -------------------------------------------------------------------------

    /**
     * Updates the text content of the first element matching the CSS selector.
     * Skips if value is null/blank or no element is found.
     */
    private void updateText(Document doc, String cssSelector, String value) {
        if (value == null || value.isBlank()) return;
        Element el = doc.select(cssSelector).first();
        if (el != null) el.text(value);
    }

    /**
     * Updates a single attribute of the first element matching the CSS selector.
     * Skips if value is null/blank or no element is found.
     */
    private void updateAttr(Document doc, String cssSelector, String attrName, String value) {
        if (value == null || value.isBlank()) return;
        Element el = doc.select(cssSelector).first();
        if (el != null) el.attr(attrName, value);
    }

    /**
     * Updates profile photos rendered as {@code <img src>} or as CSS
     * {@code background-image} on profile-like {@code <div>} elements.
     */
    private void updateProfileImage(Document doc, String profileImageUrl) {
        String relativeUrl = PortfolioImageUtils.toRelativeImagePath(profileImageUrl);

        String profileSelector = ".avatar, .profile, .profile-photo, .profile-pic, .profile-img, "
                + "[class*=avatar], [class*=profile], [class*=headshot], [class*=photo], "
                + "#avatar, #profile, #profile-photo";

        for (Element img : doc.select(
                "img.avatar, img.profile, img[class*=avatar], img[class*=profile], "
                        + "img[id*=avatar], img[id*=profile], .avatar img, .profile img, "
                        + "#avatar img, #profile img")) {
            img.attr("src", relativeUrl);
        }

        boolean updatedBackground = false;
        for (Element el : doc.select(profileSelector)) {
            String style = el.attr("style");
            if (PortfolioImageUtils.extractBackgroundImageUrl(style) != null) {
                el.attr("style", PortfolioImageUtils.replaceBackgroundImageUrl(style, relativeUrl));
                updatedBackground = true;
            }
        }

        if (!updatedBackground) {
            for (Element el : doc.select("[style*=background-image]")) {
                String style = el.attr("style");
                String currentPath = PortfolioImageUtils.extractBackgroundImageUrl(style);
                if (currentPath != null && looksLikePhotoPath(currentPath)) {
                    el.attr("style", PortfolioImageUtils.replaceBackgroundImageUrl(style, relativeUrl));
                    break;
                }
            }
        }

        for (Element styleEl : doc.select("style")) {
            styleEl.html(updateProfileBackgroundInCss(styleEl.html(), relativeUrl));
        }
    }

    private boolean looksLikePhotoPath(String path) {
        return path.matches("(?i).*\\.(jpg|jpeg|png|webp|gif|avif)$");
    }

    private String updateProfileBackgroundInCss(String css, String newUrl) {
        java.util.regex.Pattern rulePattern = java.util.regex.Pattern.compile(
                "([^{}]*(?:avatar|profile|photo|headshot|picture|pfp|hero[-_]?image)[^{}]*)\\{([^}]*)\\}",
                java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher matcher = rulePattern.matcher(css);
        StringBuffer result = new StringBuffer();
        boolean changed = false;

        while (matcher.find()) {
            String selector = matcher.group(1);
            String body = matcher.group(2);
            if (body.contains("background-image")) {
                body = java.util.regex.Pattern.compile(
                        "background-image\\s*:\\s*url\\(\\s*['\"]?[^)'\"\\s]+['\"]?\\s*\\)",
                        java.util.regex.Pattern.CASE_INSENSITIVE)
                        .matcher(body)
                        .replaceFirst("background-image: url('" + java.util.regex.Matcher.quoteReplacement(newUrl) + "')");
                changed = true;
            }
            matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(selector + "{" + body + "}"));
        }

        if (!changed) {
            return css;
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * Safely replaces a JS scalar string field (e.g. about:"...") in an inline
     * script block. Unlike a simple regex, this walks the string character by
     * character to find the exact quote boundaries, so apostrophes inside the
     * VALUE ("I'm a developer") never confuse the parser.
     *
     * Handles all three forms:
     *   fieldName: "old value"
     *   "fieldName": "old value"
     *   fieldName: null
     */
    private String replaceJsScalarField(String html, String fieldName, String newValue) {
        // Build a pattern that matches the key + colon + optional whitespace
        // then either null or an opening quote (" or ')
        java.util.regex.Pattern keyPattern = java.util.regex.Pattern.compile(
                "[\"']?" + java.util.regex.Pattern.quote(fieldName) + "[\"']?\\s*:\\s*"
        );
        java.util.regex.Matcher m = keyPattern.matcher(html);
        if (!m.find()) return html; // field not present — leave unchanged

        int afterColon = m.end(); // index right after the colon+whitespace

        // Handle null literal
        if (html.startsWith("null", afterColon)) {
            String escaped = escapeJs(newValue);
            return html.substring(0, afterColon) + "\"" + escaped + "\"" + html.substring(afterColon + 4);
        }

        // Must start with a quote character
        if (afterColon >= html.length()) return html;
        char openQuote = html.charAt(afterColon);
        if (openQuote != '"' && openQuote != '\'') return html;

        // Walk forward from the character after the opening quote to find closing quote,
        // skipping over backslash-escaped characters
        int valueStart = afterColon + 1;
        int valueEnd = -1;
        for (int i = valueStart; i < html.length(); i++) {
            char c = html.charAt(i);
            if (c == '\\') {
                i++; // skip escaped character
            } else if (c == openQuote) {
                valueEnd = i; // found closing quote
                break;
            }
        }
        if (valueEnd == -1) return html; // malformed — leave unchanged

        // Splice: key + opening quote + escaped new value + closing quote + rest
        String escaped = escapeJs(newValue);
        return html.substring(0, valueStart) + escaped + html.substring(valueEnd);
    }

    // -------------------------------------------------------------------------
    // JS data-layer helpers — for templates that store data in inline <script>
    // -------------------------------------------------------------------------

    /**
     * Finds the {@code skills:[...]} array inside any inline {@code <script>} block
     * and replaces it with the serialized {@code skills} list.
     *
     * <p>Uses bracket-depth counting instead of regex to handle multi-line arrays
     * and nested {@code items:[...]} arrays without false positives.
     *
     * <p>Detects whether the template uses {@code group} or {@code category} as
     * the object key name and preserves that convention in the output.
     *
     * <p>If no {@code skills:} key is found in the HTML the original string is
     * returned unchanged (static-HTML templates are unaffected).
     */
    private String replaceJsSkillsArray(
            String html, java.util.List<com.suriya.resume_editor.model.SkillGroup> skills) {

        // Match: skills:[ or "skills":[ or 'skills':[ with optional whitespace/newlines
        java.util.regex.Pattern startPattern = java.util.regex.Pattern.compile(
                "[\"']?skills[\"']?\\s*:\\s*\\[",
                java.util.regex.Pattern.CASE_INSENSITIVE
        );
        java.util.regex.Matcher m = startPattern.matcher(html);
        if (!m.find()) {
            // No inline JS skills array — nothing to replace
            return html;
        }

        // arrayStart = index of the opening '['
        int arrayStart = m.end() - 1;

        // Walk forward counting bracket depth to find the matching ']'
        int depth = 0;
        int arrayEnd = -1;
        boolean inString = false;
        char stringChar = 0;
        for (int i = arrayStart; i < html.length(); i++) {
            char c = html.charAt(i);
            // Track string boundaries so brackets inside strings are ignored
            if (!inString && (c == '"' || c == '\'')) {
                inString = true;
                stringChar = c;
            } else if (inString && c == stringChar && (i == 0 || html.charAt(i - 1) != '\\')) {
                inString = false;
            } else if (!inString) {
                if (c == '[') depth++;
                else if (c == ']') {
                    depth--;
                    if (depth == 0) {
                        arrayEnd = i + 1; // position after the closing ']'
                        break;
                    }
                }
            }
        }
        if (arrayEnd == -1) {
            // Malformed JS — leave unchanged rather than corrupting the file
            System.err.println("WARN: replaceJsSkillsArray — could not find closing ']' for skills array; skipping.");
            return html;
        }

        // Detect the key name used in the original template ("group" vs "category")
        String originalArray = html.substring(arrayStart, arrayEnd);
        String groupKey = originalArray.contains("\"group\"") || originalArray.contains("group:")
                ? "group" : "category";

        // Build the replacement array string
        String newArray = buildSkillsJsArray(skills, groupKey);

        // Splice: everything before '[' + new array + everything after old ']'
        return html.substring(0, arrayStart) + newArray + html.substring(arrayEnd);
    }

    /**
     * Serialises a {@code List<SkillGroup>} into a compact JS/JSON array string
     * using the given {@code groupKey} ({@code "group"} or {@code "category"}).
     *
     * <p>Output is valid JSON, which is also valid JavaScript, so it is safe to
     * embed inside any inline {@code <script>} block.
     */
    private String buildSkillsJsArray(
            java.util.List<com.suriya.resume_editor.model.SkillGroup> skills,
            String groupKey) {

        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < skills.size(); i++) {
            com.suriya.resume_editor.model.SkillGroup g = skills.get(i);
            if (i > 0) sb.append(",");
            sb.append("{\"")
              .append(groupKey)
              .append("\":\"")
              .append(escapeJs(g.getCategory() != null ? g.getCategory() : "General"))
              .append("\",\"items\":");

            java.util.List<String> items = g.getItems() != null ? g.getItems() : java.util.List.of();
            sb.append("[");
            for (int j = 0; j < items.size(); j++) {
                if (j > 0) sb.append(",");
                sb.append("\"").append(escapeJs(items.get(j))).append("\"");
            }
            sb.append("]}");
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Escapes a string value for safe embedding inside a JS double-quoted string.
     */
    private String escapeJs(String value) {
        if (value == null) return "";
        return value
                .replace("\\", "\\\\")   // backslash first
                .replace("\"", "\\\"")   // double-quote
                .replace("\n", "\\n")    // newline
                .replace("\r", "");      // carriage return
    }
}
