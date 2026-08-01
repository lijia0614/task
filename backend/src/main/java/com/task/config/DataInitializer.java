package com.task.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.task.entity.SysGroup;
import com.task.entity.SysUser;
import com.task.mapper.SysGroupMapper;
import com.task.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {
    private final SysUserMapper userMapper;
    private final SysGroupMapper groupMapper;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Override
    @Transactional
    public void run(String... args) {
        if (userMapper.selectCount(new LambdaQueryWrapper<SysUser>()) > 0) return;
        SysUser admin = user("admin", "admin123", "系统管理员", "ADMIN", null);
        userMapper.insert(admin);
        SysUser leader = user("leader1", "123456", "李组长", "LEADER", null);
        userMapper.insert(leader);
        SysGroup g1 = new SysGroup();
        g1.setName("研发一组");
        g1.setLeaderId(leader.getId());
        g1.setDescription("后端研发小组");
        groupMapper.insert(g1);
        leader.setGroupId(g1.getId());
        userMapper.updateById(leader);
        userMapper.insert(user("zhangsan", "123456", "张三", "EMPLOYEE", g1.getId()));
        userMapper.insert(user("lisi", "123456", "李四", "EMPLOYEE", g1.getId()));
        userMapper.insert(user("wangwu", "123456", "王五", "EMPLOYEE", g1.getId()));
        log.info("种子数据初始化完成");
    }

    private SysUser user(String username, String pwd, String name, String role, Long groupId) {
        SysUser u = new SysUser();
        u.setUsername(username);
        u.setPassword(encoder.encode(pwd));
        u.setRealName(name);
        u.setRole(role);
        u.setGroupId(groupId);
        return u;
    }
}
