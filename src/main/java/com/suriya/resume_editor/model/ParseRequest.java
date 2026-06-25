package com.suriya.resume_editor.model;

import lombok.Data;

@Data
public class ParseRequest {
    private String owner;
    private String repo;
    private String filePath;
}
