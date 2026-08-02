package com.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.task.entity.MinioFile;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface MinioFileMapper extends BaseMapper<MinioFile> {

    /** 锁行读取，防止删除/绑定并发竞态 */
    @Select("SELECT * FROM minio_file WHERE id = #{id} FOR UPDATE")
    MinioFile selectByIdForUpdate(Long id);

    /** 按 id 升序批量锁行（统一加锁顺序，降低死锁风险） */
    @Select("<script>SELECT * FROM minio_file WHERE id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
            + "ORDER BY id FOR UPDATE</script>")
    List<MinioFile> selectByIdsForUpdate(@Param("ids") List<Long> ids);
}
