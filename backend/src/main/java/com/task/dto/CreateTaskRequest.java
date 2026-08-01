package com.task.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class CreateTaskRequest {
    @NotBlank(message = "任务名称不能为空")
    private String name;
    private String description;
    @NotNull(message = "分配类型不能为空")
    private String assignType;   // INDIVIDUAL / GROUP
    @NotNull(message = "分配对象不能为空")
    private Long assigneeId;
    private LocalDateTime deadline;
    private List<Integer> weights;      // 可选：小组权重列表（与组员数一致）；为空默认均分
    private List<Long> attachmentIds;   // 可选：已上传的 minio_file id（Task 6 后可用）
}
