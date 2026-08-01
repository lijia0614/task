package com.task.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class MemberRequest {
    @NotNull(message = "用户不能为空")
    private Long userId;
}
