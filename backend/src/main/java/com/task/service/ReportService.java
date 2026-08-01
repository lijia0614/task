package com.task.service;

import com.task.dto.ReportRequest;
import com.task.dto.ReviewRequest;
import com.task.entity.SysUser;
import com.task.vo.ReportVO;

import java.util.List;

public interface ReportService {
    Long submit(Long taskId, ReportRequest req);
    List<ReportVO> listByTask(Long taskId, SysUser current);
    void approve(Long reportId, ReviewRequest req);
    void reject(Long reportId, ReviewRequest req);
    List<ReportVO> pendingList(SysUser current);
}
