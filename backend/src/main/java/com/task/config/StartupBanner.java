package com.task.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 应用启动成功后的醒目标识：控制台直接打印地址与端口，
 * 端口/上下文路径均从配置读取，不硬编码。
 */
@Component
public class StartupBanner {

    @Value("${server.port:8080}")
    private int port;

    @Value("${server.servlet.context-path:}")
    private String contextPath;

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        String url = "http://127.0.0.1:" + port + contextPath;
        System.out.println();
        System.out.println("============================================================");
        System.out.println("  ✅ 后端服务启动成功！");
        System.out.println("     地址: " + url);
        System.out.println("     端口: " + port);
        System.out.println("============================================================");
        System.out.println();
    }
}
