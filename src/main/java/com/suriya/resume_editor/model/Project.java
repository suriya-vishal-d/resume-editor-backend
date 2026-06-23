package com.suriya.resume_editor.model;

import lombok.Data;
import java.util.List;

@Data
public class Project {
    private String title;
    private String description;
    private List<String> techStack;
    private String link;
}
