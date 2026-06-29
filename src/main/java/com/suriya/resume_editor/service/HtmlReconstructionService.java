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
            // Profile Photo
            // ----------------------------------------------------------------
            if (resumeData.getProfileImageUrl() != null) {
                updateAttr(doc, "img.profile-photo, img.avatar, img.profile-img, img#profile-photo, img#avatar",
                        "src", resumeData.getProfileImageUrl());
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
            // Skills — update text of existing <li> elements inside the skills
            // section; never add or remove items
            // ----------------------------------------------------------------
            if (resumeData.getSkills() != null && !resumeData.getSkills().isEmpty()) {
                Element skillsSection = doc.select(
                        ".skills, #skills, [class*=skills]").first();
                if (skillsSection != null) {
                    var skillItems = skillsSection.select("li, span, .skill-item, [class*=skill-item]");
                    for (int i = 0; i < Math.min(skillItems.size(), resumeData.getSkills().size()); i++) {
                        skillItems.get(i).text(resumeData.getSkills().get(i));
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

            return doc.outerHtml();

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
}
