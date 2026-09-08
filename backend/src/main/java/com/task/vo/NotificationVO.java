package com.task.vo;

import com.task.entity.Notification;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class NotificationVO {
    private Long id;
    private String type;
    private String title;
    private String content;
    private Long taskId;
    private Long reportId;
    private boolean read;
    private LocalDateTime createdAt;

    public static NotificationVO from(Notification n) {
        NotificationVO v = new NotificationVO();
        v.setId(n.getId());
        v.setType(n.getType());
        v.setTitle(n.getTitle());
        v.setContent(n.getContent());
        v.setTaskId(n.getTaskId());
        v.setReportId(n.getReportId());
        v.setRead(n.getIsRead() != null && n.getIsRead() == 1);
        v.setCreatedAt(n.getCreatedAt());
        return v;
    }
}
