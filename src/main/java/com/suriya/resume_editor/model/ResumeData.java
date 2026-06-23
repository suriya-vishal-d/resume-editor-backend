package com.suriya.resume_editor.model;

import lombok.Data;
import java.util.List;

@Data
public class ResumeData {
    private String name;
    private String tagline;
    private String about;
    private String profileImageUrl;
    private List<String> skills;
    private List<Project> projects;
    private List<Experience> experience;
    private List<Education> education;
    private Contact contact;
    private List<String> certifications;
    private List<String> achievements;
    private List<String> languages;
    private List<BlogPost> blogPosts;
    private List<String> hobbies;
    private String resumePdfUrl;
}
