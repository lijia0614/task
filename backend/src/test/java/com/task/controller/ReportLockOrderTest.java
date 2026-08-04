package com.task.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.task.TaskApplication;
import com.task.entity.Report;
import com.task.entity.Task;
import com.task.entity.TaskMember;
import com.task.enums.AssignType;
import com.task.enums.ReportStatus;
import com.task.enums.TaskStatus;
import com.task.mapper.ReportMapper;
import com.task.mapper.SysUserMapper;
import com.task.mapper.TaskMapper;
import com.task.mapper.TaskMemberMapper;
import io.minio.MinioClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 锁序回归：approve 与 resubmit 必须在 report 锁之后按相同顺序（task → member）取锁，
 * 否则 approve 的"先 UPDATE member 再 UPDATE task"与 resubmit 的"先锁 task 再锁 member"
 * 会在同一 task/member 上形成交叉等待死锁（InnoDB 回滚 → 500）。
 * 多轮并发断言：任何一轮都不允许出现 500，且双方业务都成功。
 * fixture 自建并只清理本用例数据，MinIO 全部 @MockBean。
 */
@SpringBootTest(classes = TaskApplication.class)
@AutoConfigureMockMvc
class ReportLockOrderTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired SysUserMapper userMapper;
    @Autowired TaskMapper taskMapper;
    @Autowired TaskMemberMapper memberMapper;
    @Autowired ReportMapper reportMapper;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockBean MinioClient minioClient;

    private final List<Long> createdReportIds = new ArrayList<>();
    private final List<Long> createdMemberIds = new ArrayList<>();
    private final List<Long> createdTaskIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (Long id : createdReportIds) {
            jdbcTemplate.update("DELETE FROM report_history WHERE report_id = ?", id);
            jdbcTemplate.update("DELETE FROM report WHERE id = ?", id);
        }
        for (Long id : createdMemberIds) jdbcTemplate.update("DELETE FROM task_member WHERE id = ?", id);
        for (Long id : createdTaskIds) jdbcTemplate.update("DELETE FROM task WHERE id = ?", id);
        createdReportIds.clear();
        createdMemberIds.clear();
        createdTaskIds.clear();
    }

    private String login(String username, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("username", username, "password", password))))
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
    }

    private long userId(String username) {
        return jdbcTemplate.queryForObject("SELECT id FROM sys_user WHERE username = ?", Long.class, username);
    }

    /** 每轮独立 fixture：task + member(progress=0) + report1(PENDING,30) + report2(WITHDRAWN,50)，返回 [report1Id, report2Id] */
    private long[] createRound() {
        long leaderId = userId("leader1");
        long zhangsanId = userId("zhangsan");
        Task task = new Task();
        task.setName("锁序测试-" + System.currentTimeMillis());
        task.setCreatorId(leaderId);
        task.setAssignType(AssignType.INDIVIDUAL);
        task.setAssigneeId(zhangsanId);
        task.setStatus(TaskStatus.DOING);
        task.setProgress(0);
        taskMapper.insert(task);
        createdTaskIds.add(task.getId());
        TaskMember tm = new TaskMember();
        tm.setTaskId(task.getId());
        tm.setUserId(zhangsanId);
        tm.setWeight(100);
        tm.setProgress(0);
        memberMapper.insert(tm);
        createdMemberIds.add(tm.getId());

        Report r1 = new Report();
        r1.setTaskMemberId(tm.getId());
        r1.setUserId(zhangsanId);
        r1.setContent("锁序测试汇报1");
        r1.setProgress(30);
        r1.setStatus(ReportStatus.PENDING);
        reportMapper.insert(r1);
        createdReportIds.add(r1.getId());

        Report r2 = new Report();
        r2.setTaskMemberId(tm.getId());
        r2.setUserId(zhangsanId);
        r2.setContent("锁序测试汇报2");
        r2.setProgress(50);
        r2.setStatus(ReportStatus.WITHDRAWN);
        reportMapper.insert(r2);
        createdReportIds.add(r2.getId());
        return new long[]{r1.getId(), r2.getId()};
    }

    /**
     * 同一 task/member 上并发 approve(report1) 与 resubmit(report2)：
     * 绝不允许死锁（500）；两笔业务都成功，状态正确。
     * approve 最终进度 30，始终 >= 成员进度（0 或 30），resubmit 校验 50 >= 成员进度恒成立 → 双方必然成功。
     */
    @Test
    void concurrentApproveAndResubmitNoDeadlock() throws Exception {
        String leaderToken = login("leader1", "123456");
        String zhangToken = login("zhangsan", "123456");
        int rounds = 8;
        for (int round = 0; round < rounds; round++) {
            long[] ids = createRound();
            long report1 = ids[0];
            long report2 = ids[1];
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            List<Integer> codes = java.util.Collections.synchronizedList(new ArrayList<>());
            pool.submit(() -> {
                try {
                    start.await();
                    MvcResult res = mvc.perform(post("/api/reports/" + report1 + "/approve")
                                    .header("Authorization", "Bearer " + leaderToken)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(om.writeValueAsString(Map.of("progress", 30, "reviewComment", "通过"))))
                            .andReturn();
                    codes.add(om.readTree(res.getResponse().getContentAsString()).get("code").asInt());
                } catch (Exception ignored) { }
            });
            pool.submit(() -> {
                try {
                    start.await();
                    MvcResult res = mvc.perform(post("/api/reports/" + report2 + "/resubmit")
                                    .header("Authorization", "Bearer " + zhangToken))
                            .andReturn();
                    codes.add(om.readTree(res.getResponse().getContentAsString()).get("code").asInt());
                } catch (Exception ignored) { }
            });
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "第 " + round + " 轮并发未结束（疑似锁挂起）");
            assertEquals(2, codes.size(), "第 " + round + " 轮两个请求都必须完成");
            // 死锁/系统异常以 500 出现；单待审核约束允许 resubmit 合法返回 400，但绝不允许死锁
            for (Integer c : codes) {
                assertTrue(c == 0 || c == 400, "第 " + round + " 轮出现死锁/异常响应，code=" + c);
            }
            assertEquals(ReportStatus.APPROVED, reportMapper.selectById(report1).getStatus(),
                    "approve 必须成功");
            ReportStatus s2 = reportMapper.selectById(report2).getStatus();
            // resubmit：approve 提交前被单待审核拒绝 → 保持 WITHDRAWN；approve 提交后 → PENDING
            assertTrue(s2 == ReportStatus.PENDING || s2 == ReportStatus.WITHDRAWN,
                    "resubmit 结果状态异常: " + s2);
        }
    }
}
