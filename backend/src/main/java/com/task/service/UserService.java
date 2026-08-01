package com.task.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.task.dto.UserRequest;
import com.task.vo.UserVO;

public interface UserService {
    Page<UserVO> list(long page, long size, String keyword, String role);

    Long create(UserRequest req);

    void update(Long id, UserRequest req);

    void delete(Long id);

    void resetPassword(Long id, String password);
}
