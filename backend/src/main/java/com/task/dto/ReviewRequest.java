package com.task.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class ReviewRequest {
    @Min(value = 0, message = "进度不能小于 0")
    @Max(value = 100, message = "进度不能超过 100")
    private Integer progress;       // 可空：空 = 用汇报填写的进度（手动调节）
    private String reviewComment;   // 审核内容/意见；驳回时必填
}
