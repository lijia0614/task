package com.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.task.entity.TaskAttachment;
import org.apache.ibatis.annotations.Select;

public interface TaskAttachmentMapper extends BaseMapper<TaskAttachment> {

    /** 一个上传文件最多绑定一个任务（uk_attachment_minio_file 保证） */
    @Select("SELECT * FROM task_attachment WHERE minio_file_id = #{fileId} LIMIT 1")
    TaskAttachment selectByMinioFileId(Long fileId);
}
