package com.suriya.resume_editor.service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

/**
 * Strips all noise from raw HTML before the AI sees it.
 *
 * What gets removed:
 *  - <style>   tags (CSS rules — the AI doesn't need colours or fonts)
 *  - <script>  tags (JavaScript — animations, event handlers, etc.)
 *  - <svg>     tags (icon path data — pure maths, thousands of chars)
 *  - <link>    tags (external stylesheets)
 *  - <meta>    tags (charset, viewport, Open Graph — irrelevant for extraction)
 *  - <noscript> tags
 *  - HTML comments
 *  - Presentation / tracking attributes (class, id, style, data-*, aria-*,
 *    on*, tabindex, role) — these add zero semantic value for text extraction
 *
 * What stays:
 *  - All text content (headings, paragraphs, lists, anchor text)
 *  - href / src attributes on <a> and <img> tags (links and image URLs matter)
 *  - background-image URLs from inline style attributes and embedded CSS
 *  - The structural tags (<section>, <article>, <div>, <ul>, <li>, …)
 *
 * Typical reduction: 200-500 KB → 5-30 KB, making the AI call
 * 10-20x faster and essentially eliminating timeout risk.
 */
@Service
public class HtmlCleanerService {

    // Attributes that carry zero meaning for data-extraction but can be very verbose
    private static final String[] NOISE_ATTRIBUTES = {
            "class", "id",
            "tabindex", "role", "aria-label", "aria-hidden", "aria-expanded",
            "aria-controls", "aria-describedby", "aria-labelledby",
            "data-aos", "data-wow-duration", "data-wow-delay", "data-wow-offset",
            "data-toggle", "data-target", "data-dismiss", "data-ride",
            "data-slide", "data-slide-to", "data-parent",
            "data-src", "data-lazy",
            "onclick", "onload", "onmouseover", "onmouseout",
            "onchange", "oninput", "onsubmit", "onkeyup", "onkeydown",
            "inputmode", "autocomplete", "autocorrect", "autocapitalize", "spellcheck",
            "draggable", "contenteditable", "translate"
    };

    /**
     * Cleans the given raw HTML string and returns a compact, AI-friendly version.
     *
     * @param rawHtml the full HTML fetched from GitHub
     * @return stripped HTML string suitable for sending to the AI
     */
    public String clean(String rawHtml) {
        Document doc = Jsoup.parse(rawHtml);

        // 1a. Promote CSS background-image URLs into synthetic <img> hints for the AI
        for (Element styleTag : doc.select("style")) {
            injectBackgroundImageHints(styleTag);
        }

        // 1b. Remove entire tags that add zero text value
        doc.select("style, svg, link, meta, noscript, iframe, canvas, " +
                   "template, map, track, object, embed")
           .remove();

        // 1b. Remove external scripts (which are just file references), but keep inline scripts
        // because portfolios sometimes store data (like skills arrays) inside inline JS variables.
        doc.select("script[src]").remove();

        // 2. Remove HTML comments
        doc.getAllElements().forEach(el ->
            el.childNodes().stream()
              .filter(n -> n instanceof org.jsoup.nodes.Comment)
              .toList()
              .forEach(org.jsoup.nodes.Node::remove)
        );

        // 3. Strip noisy attributes from every remaining element
        Elements allElements = doc.getAllElements();
        for (Element el : allElements) {
            preserveProfileBackgroundStyle(el);
            for (String attr : NOISE_ATTRIBUTES) {
                el.removeAttr(attr);
            }
            // Also strip any remaining data-* and on* attributes
            el.attributes().asList().stream()
              .map(org.jsoup.nodes.Attribute::getKey)
              .filter(k -> k.startsWith("data-") || k.startsWith("on"))
              .toList()
              .forEach(el::removeAttr);
        }

        // 4. Collapse excessive whitespace
        String cleaned = doc.outerHtml()
                .replaceAll("(?m)^[ \t]+", "")     // leading whitespace per line
                .replaceAll("\n{3,}", "\n\n");       // max 2 blank lines in a row

        int originalLen = rawHtml.length();
        int cleanedLen  = cleaned.length();
        System.out.printf("INFO: HtmlCleanerService reduced HTML from %,d -> %,d chars (%.0f%% reduction)%n",
                originalLen, cleanedLen,
                100.0 * (originalLen - cleanedLen) / originalLen);

        return cleaned;
    }

    /**
     * Keeps only {@code background-image: url(...)} from inline styles so the AI
     * can detect profile photos rendered via CSS on {@code <div>} elements.
     */
    private void preserveProfileBackgroundStyle(Element el) {
        String style = el.attr("style");
        if (style.isBlank()) {
            return;
        }
        String backgroundUrl = PortfolioImageUtils.extractBackgroundImageUrl(style);
        if (backgroundUrl != null) {
            el.attr("style", "background-image: url('" + backgroundUrl + "')");
        } else {
            el.removeAttr("style");
        }
    }

    /**
     * Converts {@code background-image: url(...)} rules from a {@code <style>}
     * block into synthetic {@code <img>} tags the AI can read as profile photos.
     */
    private void injectBackgroundImageHints(Element styleTag) {
        String css = styleTag.data();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "background-image\\s*:\\s*url\\(\\s*['\"]?([^)'\"\\s]+)['\"]?\\s*\\)",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(css);

        StringBuilder hints = new StringBuilder();
        while (matcher.find()) {
            String path = matcher.group(1);
            if (PortfolioImageUtils.isRelativeImagePath(path)) {
                hints.append("<img src=\"")
                        .append(path)
                        .append("\" data-source=\"css-background-image\">");
            }
        }

        if (!hints.isEmpty()) {
            styleTag.after(hints.toString());
        }
    }
}
