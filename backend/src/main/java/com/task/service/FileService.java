package com.task.service;

import java.util.Map;

public interface FileService {
    Map<String, Object> upload(org.springframework.web.multipart.MultipartFile file);
    void delete(Long id);
}
