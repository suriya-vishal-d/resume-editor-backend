package com.suriya.resume_editor.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects portfolio image paths from {@code <img src>} tags, inline
 * {@code background-image} styles, and embedded {@code <style>} blocks.
 */
public final class PortfolioImageUtils {

    private static final Pattern IMG_SRC = Pattern.compile(
            "<img[^>]+src=[\"'](?!data:|https?://|//)([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern BACKGROUND_IMAGE_URL = Pattern.compile(
            "background-image\\s*:\\s*url\\(\\s*['\"]?([^)'\"\\s]+)['\"]?\\s*\\)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern PROFILE_HINT = Pattern.compile(
            "(avatar|profile|photo|headshot|picture|pfp|hero[-_]?image|about[-_]?img)",
            Pattern.CASE_INSENSITIVE);

    private PortfolioImageUtils() {
    }

    /**
     * Returns the directory portion of the first relative image path found in the
     * HTML, checking profile-like elements first, then any image reference.
     */
    public static String detectImageBasePath(String html) {
        if (html == null || html.isBlank()) {
            return "images";
        }

        for (String path : collectRelativeImagePaths(html)) {
            String folder = folderFromPath(path);
            if (folder != null) {
                System.out.println("INFO: Detected image base path from HTML: '" + folder + "'");
                return folder;
            }
        }

        System.out.println("INFO: No relative image path with a folder found — defaulting to 'images'.");
        return "images";
    }

    /**
     * Collects relative image paths, prioritising profile-like {@code <img>} tags
     * and elements whose class/id/style suggest a profile photo.
     */
    public static List<String> collectRelativeImagePaths(String html) {
        LinkedHashSet<String> ordered = new LinkedHashSet<>();

        collectProfileLikeImgPaths(html, ordered);
        collectBackgroundImagePaths(html, ordered, true);
        collectImgPaths(html, ordered);
        collectBackgroundImagePaths(html, ordered, false);

        return new ArrayList<>(ordered);
    }

    /**
     * Extracts the first {@code background-image: url(...)} value from an inline
     * style attribute, or {@code null} if none is present.
     */
    public static String extractBackgroundImageUrl(String style) {
        if (style == null || style.isBlank()) {
            return null;
        }
        Matcher matcher = BACKGROUND_IMAGE_URL.matcher(style);
        return matcher.find() ? normalizePath(matcher.group(1)) : null;
    }

    /**
     * Replaces the URL inside {@code background-image: url(...)} while preserving
     * the rest of the inline style declaration.
     */
    public static String replaceBackgroundImageUrl(String style, String newUrl) {
        if (style == null || style.isBlank()) {
            return "background-image: url('" + newUrl + "')";
        }
        if (BACKGROUND_IMAGE_URL.matcher(style).find()) {
            return BACKGROUND_IMAGE_URL.matcher(style)
                    .replaceFirst("background-image: url('" + Matcher.quoteReplacement(newUrl) + "')");
        }
        String trimmed = style.trim();
        if (trimmed.endsWith(";")) {
            return trimmed + " background-image: url('" + newUrl + "')";
        }
        return trimmed + "; background-image: url('" + newUrl + "')";
    }

    /**
     * Converts a GitHub Pages absolute URL to a repo-relative path when possible.
     */
    public static String toRelativeImagePath(String url) {
        if (url == null || url.isBlank()) {
            return url;
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return normalizePath(url);
        }

        Matcher matcher = Pattern.compile(
                "https?://[^/]+\\.github\\.io/(?:[^/]+/)?(.+)$",
                Pattern.CASE_INSENSITIVE).matcher(url);
        if (matcher.find()) {
            return normalizePath(matcher.group(1));
        }
        return url;
    }

    public static boolean isRelativeImagePath(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String normalized = path.trim();
        return !normalized.startsWith("data:")
                && !normalized.startsWith("http://")
                && !normalized.startsWith("https://")
                && !normalized.startsWith("//");
    }

    public static String folderFromPath(String path) {
        String normalized = normalizePath(path);
        int lastSlash = normalized.lastIndexOf('/');
        if (lastSlash > 0) {
            return normalized.substring(0, lastSlash);
        }
        return null;
    }

    private static void collectProfileLikeImgPaths(String html, LinkedHashSet<String> out) {
        Pattern profileImg = Pattern.compile(
                "<(?:img|div|section|figure|span)[^>]*(?:class|id)=[\"'][^\"']*"
                        + PROFILE_HINT.pattern()
                        + "[^\"']*[\"'][^>]*(?:src=[\"'](?!data:|https?://|//)([^\"']+)[\"']"
                        + "|style=[\"'][^\"']*background-image\\s*:\\s*url\\(\\s*['\"]?([^)'\"\\s]+))",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher matcher = profileImg.matcher(html);
        while (matcher.find()) {
            addIfRelative(out, matcher.group(1));
            addIfRelative(out, matcher.group(2));
        }
    }

    private static void collectImgPaths(String html, LinkedHashSet<String> out) {
        Matcher matcher = IMG_SRC.matcher(html);
        while (matcher.find()) {
            addIfRelative(out, matcher.group(1));
        }
    }

    private static void collectBackgroundImagePaths(String html, LinkedHashSet<String> out, boolean profileLikeOnly) {
        Pattern taggedElement = Pattern.compile(
                "<([a-z][a-z0-9]*)\\b([^>]*)>",
                Pattern.CASE_INSENSITIVE);
        Matcher elementMatcher = taggedElement.matcher(html);
        while (elementMatcher.find()) {
            String attrs = elementMatcher.group(2);
            if (profileLikeOnly && !PROFILE_HINT.matcher(attrs).find()) {
                continue;
            }

            Matcher styleMatcher = Pattern.compile(
                    "style=[\"']([^\"']*)[\"']",
                    Pattern.CASE_INSENSITIVE).matcher(attrs);
            while (styleMatcher.find()) {
                addBackgroundImagePath(out, styleMatcher.group(1));
            }
        }

        Matcher cssMatcher = BACKGROUND_IMAGE_URL.matcher(html);
        while (cssMatcher.find()) {
            if (!profileLikeOnly) {
                addIfRelative(out, cssMatcher.group(1));
            }
        }
    }

    private static void addBackgroundImagePath(LinkedHashSet<String> out, String style) {
        String path = extractBackgroundImageUrl(style);
        addIfRelative(out, path);
    }

    private static void addIfRelative(LinkedHashSet<String> out, String path) {
        if (path == null) {
            return;
        }
        String normalized = normalizePath(path);
        if (isRelativeImagePath(normalized)) {
            out.add(normalized);
        }
    }

    private static String normalizePath(String path) {
        if (path == null) {
            return "";
        }
        String normalized = path.trim();
        int query = normalized.indexOf('?');
        if (query != -1) {
            normalized = normalized.substring(0, query);
        }
        int fragment = normalized.indexOf('#');
        if (fragment != -1) {
            normalized = normalized.substring(0, fragment);
        }
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }
}
