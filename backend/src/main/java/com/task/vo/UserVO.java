package com.task.vo;

import com.task.entity.SysUser;
import lombok.Data;

@Data
public class UserVO {
    private Long id;
    private String username;
    private String realName;
    private String role;
    private Long groupId;
    private String groupName;

    public static UserVO from(SysUser u, String groupName) {
        UserVO vo = new UserVO();
        vo.setId(u.getId());
        vo.setUsername(u.getUsername());
        vo.setRealName(u.getRealName());
        vo.setRole(u.getRole() == null ? null : u.getRole().name());
        vo.setGroupId(u.getGroupId());
        vo.setGroupName(groupName);
        return vo;
    }
}
