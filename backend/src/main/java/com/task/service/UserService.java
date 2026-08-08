package com.task.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.task.dto.UserRequest;
import com.task.vo.UserVO;

import java.util.List;

public interface UserService {
    Page<UserVO> list(long page, long size, String keyword, String role);

    List<UserVO> candidates();

    Long create(UserRequest req);

    void update(Long id, UserRequest req);

    void delete(Long id);

    void resetPassword(Long id, String password);
}
