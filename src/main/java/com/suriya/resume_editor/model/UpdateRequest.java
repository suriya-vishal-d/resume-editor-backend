package com.suriya.resume_editor.model;

import lombok.Data;

@Data
public class UpdateRequest {
    private String owner;
    private String repo;
    private String filePath;
    private String sha;
    private String originalHtml;
    private ResumeData resumeData;
}

