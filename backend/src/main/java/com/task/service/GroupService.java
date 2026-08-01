package com.task.service;

import com.task.dto.GroupRequest;
import com.task.vo.UserVO;

import java.util.List;
import java.util.Map;

public interface GroupService {
    List<Map<String, Object>> list();

    List<UserVO> members(Long id);

    Long create(GroupRequest req);

    void update(Long id, GroupRequest req);

    void delete(Long id);

    void addMember(Long id, Long userId);

    void removeMember(Long id, Long userId);
}
