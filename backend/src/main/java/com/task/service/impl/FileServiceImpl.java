package com.task.service.impl;

import com.task.auth.UserContext;
import com.task.common.BusinessException;
import com.task.entity.MinioFile;
import com.task.entity.TaskAttachment;
import com.task.mapper.MinioFileMapper;
import com.task.mapper.TaskAttachmentMapper;
import com.task.service.FileService;
import com.task.service.MinioObjectService;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FileServiceImpl implements FileService {
    private final MinioClient minioClient;
    private final MinioFileMapper fileMapper;
    private final TaskAttachmentMapper attachmentMapper;
    private final MinioObjectService minioObjectService;

    @Value("${minio.bucket}") private String bucket;
    @Value("${minio.public-url}") private String publicUrl;

    @Override
    public Map<String, Object> upload(MultipartFile file) {
        try {
            if (file == null || file.isEmpty()) throw new BusinessException("文件不能为空");
            String objectName = UUID.randomUUID() + "_" + file.getOriginalFilename();
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectName)
                    .stream(file.getInputStream(), file.getSize(), -1)
                    .contentType(file.getContentType())
                    .build());
            MinioFile f = new MinioFile();
            f.setObjectName(objectName);
            f.setFileName(file.getOriginalFilename());
            f.setSize(file.getSize());
            f.setUploaderId(UserContext.get().getId());
            fileMapper.insert(f);
            String url = publicUrl + "/" + bucket + "/" + objectName;
            return Map.of("id", f.getId(), "url", url, "fileName", f.getFileName(), "fileSize", f.getSize());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("上传失败: " + e.getMessage());
        }
    }

    /**
     * 只允许上传者本人删除；已绑定任务的附件禁止直接删除；
     * 先删 MinIO 对象成功后再删数据库行，MinIO 失败则整件事务回滚，行保留。
     */
    @Override
    @Transactional
    public void delete(Long id) {
        MinioFile f = fileMapper.selectByIdForUpdate(id);
        if (f == null) throw new BusinessException("文件不存在");
        Long currentUserId = UserContext.get().getId();
        if (!currentUserId.equals(f.getUploaderId())) {
            throw new BusinessException(403, "只能删除自己上传的文件");
        }
        TaskAttachment bound = attachmentMapper.selectByMinioFileId(id);
        if (bound != null) {
            throw new BusinessException(400, "文件已绑定任务，不能直接删除");
        }
        minioObjectService.delete(f.getObjectName());
        fileMapper.deleteById(id);
    }
}
