package com.task.service;

import com.task.common.BusinessException;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.errors.ErrorResponseException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * MinIO 对象删除的唯一出口：缺失对象视为成功，其余失败统一转为
 * BusinessException("附件删除失败")，由调用方决定是否保留数据库行。
 */
@Service
@RequiredArgsConstructor
public class MinioObjectService {
    private final MinioClient minioClient;

    @Value("${minio.bucket}") private String bucket;

    public void delete(String objectName) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectName)
                    .build());
        } catch (ErrorResponseException e) {
            // 对象已不存在（NoSuchKey）视为删除成功，幂等
            if (e.errorResponse() != null && "NoSuchKey".equals(e.errorResponse().code())) {
                return;
            }
            throw new BusinessException("附件删除失败");
        } catch (Exception e) {
            throw new BusinessException("附件删除失败");
        }
    }
}
