package com.task.dto;

import com.task.vo.UserVO;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class LoginResponse {
    private String token;
    private UserVO user;
}
