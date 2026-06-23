package com.suriya.resume_editor.model;

import lombok.Data;

@Data
public class ParseResponse {
    private String sha;
    private String originalHtml;
    private ResumeData resumeData;

    public ParseResponse(String sha, String originalHtml, ResumeData resumeData) {
        this.sha = sha;
        this.originalHtml = originalHtml;
        this.resumeData = resumeData;
    }
}
