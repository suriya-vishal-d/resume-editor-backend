package com.suriya.resume_editor.model;

import lombok.Data;
import java.util.List;

@Data
public class SkillGroup {
    private String category;
    private List<String> items;
}
