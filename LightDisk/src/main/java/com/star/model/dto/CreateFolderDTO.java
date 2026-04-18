package com.star.model.dto;

import lombok.Data;

@Data
public class CreateFolderDTO {
    private String name;
    private Long parentId;
}