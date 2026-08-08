package com.task.dto;

import com.task.enums.Role;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 编辑用户请求：与创建分离，不含 username/password（用户名不可改，密码走独立重置接口） */
@Data
public class UpdateUserRequest {
    @NotBlank(message = "姓名不能为空")
    private String realName;
    private String role = Role.EMPLOYEE.getValue();
    private Long groupId;
}
