package com.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.task.entity.MinioFile;
import org.apache.ibatis.annotations.Select;

public interface MinioFileMapper extends BaseMapper<MinioFile> {

    /** 锁行读取，防止删除/绑定并发竞态 */
    @Select("SELECT * FROM minio_file WHERE id = #{id} FOR UPDATE")
    MinioFile selectByIdForUpdate(Long id);
}
