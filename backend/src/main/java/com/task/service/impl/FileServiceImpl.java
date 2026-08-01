package com.task.service.impl;

import com.task.auth.UserContext;
import com.task.common.BusinessException;
import com.task.entity.MinioFile;
import com.task.mapper.MinioFileMapper;
import com.task.service.FileService;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FileServiceImpl implements FileService {
    private final MinioClient minioClient;
    private final MinioFileMapper fileMapper;

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

    @Override
    public void delete(Long id) {
        MinioFile f = fileMapper.selectById(id);
        if (f == null) throw new BusinessException("文件不存在");
        fileMapper.deleteById(id);
    }
}
