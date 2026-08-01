package com.task.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.task.TaskApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest(classes = TaskApplication.class)
@AutoConfigureMockMvc
class FileControllerTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;

    @Test
    void uploadRequiresLogin() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "a.txt", "text/plain", "hello".getBytes());
        mvc.perform(multipart("/api/files/upload").file(file))
                .andExpect(jsonPath("$.code").value(401));
    }
}
