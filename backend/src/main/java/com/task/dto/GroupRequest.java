package com.task.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class GroupRequest {
    @NotBlank(message = "组名不能为空")
    private String name;
    @NotNull(message = "组长不能为空")
    private Long leaderId;
    private String description;
}
