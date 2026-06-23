package com.suriya.resume_editor.model;

import lombok.Data;

@Data
public class GitHubFile {
    private String name;
    private String path;
    private String sha;
    private String content;
    private String encoding;
    private String downloadUrl;
}
