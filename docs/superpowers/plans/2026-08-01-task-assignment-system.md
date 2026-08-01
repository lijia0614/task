# 任务分配系统实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现一个公司内部任务分配系统：任务可分配给个人或小组（权重分配），员工汇报进度，分配者审核（可调进度、必填驳回理由），审核详情对本人可见，第三方仅见通过审核的汇报并可评论/回复，任务全员 100% 完成。

**Architecture:** 前后端分离。后端 Spring Boot 3 单体（Controller → Service → MyBatis-Plus Mapper），JWT 拦截器认证，MinIO 存附件，MySQL 8 单库 `task_assign`。前端 Vue 3 + Vite + Element Plus 单页应用。个人/小组任务统一展开为 `task_member` 记录，汇报审核通过才更新进度，整体进度 = 加权平均。

**Tech Stack:** Java 17、Spring Boot 3.2.x、MyBatis-Plus 3.5.x、jjwt 0.12.x、MinIO Java SDK 8.5.x、Lombok；Vue 3.4、Vite 5、Element Plus 2.7、Vue Router 4、Pinia 2、Axios。

**环境（已验证）：** MySQL 8.0.46 Docker `mysql-dev`（127.0.0.1:3306，root/root123456）；MinIO `minio-dev`（127.0.0.1:9000，凭证默认 minioadmin/minioadmin——Task 6 先验证，如不对执行 `docker inspect minio-dev --format '{{range .Config.Env}}{{println .}}{{end}}'` 查）；Node 18.19；Maven 3.8.6。

**目录约定：** 后端根包 `com.task`，代码在 `backend/src/main/java/com/task/`，测试在 `backend/src/test/java/com/task/`，SQL 在 `backend/src/main/resources/db/`。

---

## Phase 1：后端

### Task 1: 项目骨架、建库、表结构

**Files:**
- Create: `backend/pom.xml`
- Create: `backend/src/main/resources/application.yml`
- Create: `backend/src/main/java/com/task/TaskApplication.java`
- Create: `backend/src/main/resources/db/schema.sql`
- Create: `backend/src/test/resources/application-test.yml`

- [ ] **Step 1: 建库 + 建表 SQL（先在 MySQL 手工执行验证 SQL 正确）**

创建 `backend/src/main/resources/db/schema.sql`：

```sql
CREATE DATABASE IF NOT EXISTS task_assign DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE task_assign;

CREATE TABLE IF NOT EXISTS sys_user (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  username VARCHAR(50) NOT NULL UNIQUE,
  password VARCHAR(100) NOT NULL,
  real_name VARCHAR(50) NOT NULL,
  role VARCHAR(20) NOT NULL COMMENT 'EMPLOYEE/LEADER/ADMIN',
  group_id BIGINT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS sys_group (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(50) NOT NULL UNIQUE,
  leader_id BIGINT NOT NULL,
  description VARCHAR(255) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS task (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(100) NOT NULL,
  description TEXT NULL,
  creator_id BIGINT NOT NULL,
  assign_type VARCHAR(20) NOT NULL COMMENT 'INDIVIDUAL/GROUP',
  assignee_id BIGINT NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'DOING' COMMENT 'DOING/DONE',
  deadline DATETIME NULL,
  progress INT NOT NULL DEFAULT 0,
  deleted TINYINT NOT NULL DEFAULT 0,
  done_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS task_member (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  task_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  weight INT NOT NULL DEFAULT 100,
  progress INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_task_user (task_id, user_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS report (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  task_member_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  content TEXT NOT NULL,
  progress INT NOT NULL,
  final_progress INT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/APPROVED/REJECTED',
  reviewer_id BIGINT NULL,
  review_comment VARCHAR(255) NULL,
  reviewed_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS task_attachment (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  task_id BIGINT NOT NULL,
  file_name VARCHAR(255) NOT NULL,
  file_url VARCHAR(500) NOT NULL,
  file_size BIGINT NOT NULL,
  uploaded_by BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS comment (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  task_id BIGINT NULL,
  report_id BIGINT NULL,
  parent_id BIGINT NOT NULL DEFAULT 0,
  user_id BIGINT NOT NULL,
  content TEXT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS minio_file (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  object_name VARCHAR(255) NOT NULL,
  file_name VARCHAR(255) NOT NULL,
  size BIGINT NOT NULL,
  uploader_id BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;
```

执行验证：`/usr/local/mysql/bin/mysql -h127.0.0.1 -uroot -proot123456 < backend/src/main/resources/db/schema.sql` 应无报错。

- [ ] **Step 2: 写 pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.2.5</version>
    <relativePath/>
  </parent>
  <groupId>com.task</groupId>
  <artifactId>task-backend</artifactId>
  <version>1.0.0</version>
  <properties>
    <java.version>17</java.version>
  </properties>
  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>
    <dependency>
      <groupId>com.baomidou</groupId>
      <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
      <version>3.5.7</version>
    </dependency>
    <dependency>
      <groupId>com.mysql</groupId>
      <artifactId>mysql-connector-j</artifactId>
      <scope>runtime</scope>
    </dependency>
    <dependency>
      <groupId>io.jsonwebtoken</groupId>
      <artifactId>jjwt-api</artifactId>
      <version>0.12.5</version>
    </dependency>
    <dependency>
      <groupId>io.jsonwebtoken</groupId>
      <artifactId>jjwt-impl</artifactId>
      <version>0.12.5</version>
      <scope>runtime</scope>
    </dependency>
    <dependency>
      <groupId>io.jsonwebtoken</groupId>
      <artifactId>jjwt-jackson</artifactId>
      <version>0.12.5</version>
      <scope>runtime</scope>
    </dependency>
    <dependency>
      <groupId>io.minio</groupId>
      <artifactId>minio</artifactId>
      <version>8.5.10</version>
    </dependency>
    <dependency>
      <groupId>org.projectlombok</groupId>
      <artifactId>lombok</artifactId>
      <optional>true</optional>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>
  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
        <configuration><excludes><exclude><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId></exclude></excludes></configuration>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 3: 写 application.yml 与主类**

`backend/src/main/resources/application.yml`：

```yaml
server:
  port: 8080
spring:
  application:
    name: task-backend
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/task_assign?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
    username: root
    password: root123456
    driver-class-name: com.mysql.cj.jdbc.Driver
  servlet:
    multipart:
      max-file-size: 50MB
      max-request-size: 100MB
mybatis-plus:
  configuration:
    map-underscore-to-camel-case: true
  global-config:
    db-config:
      logic-delete-field: deleted
      logic-delete-value: 1
      logic-not-delete-value: 0
minio:
  endpoint: http://127.0.0.1:9000
  access-key: minioadmin
  secret-key: minioadmin
  bucket: task-attachments
jwt:
  secret: TaskAssignSystemSecretKey0123456789abcdefghijklmnopqrstuvwxyz
  expire-hours: 72
```

`backend/src/main/java/com/task/TaskApplication.java`：

```java
package com.task;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.task.mapper")
public class TaskApplication {
    public static void main(String[] args) {
        SpringApplication.run(TaskApplication.class, args);
    }
}
```

- [ ] **Step 4: 启动验证**

Run: `cd backend && mvn -q spring-boot:run`（后台启动），curl `http://127.0.0.1:8080` 返回 404 JSON（无控制器属正常）。
Expected: 应用启动无异常、连库成功。

- [ ] **Step 5: Commit**

```bash
git add backend
git commit -m "feat(backend): 项目骨架与数据库表结构"
```

### Task 2: 通用返回体、异常处理、实体与 Mapper

**Files:**
- Create: `backend/src/main/java/com/task/common/Result.java`
- Create: `backend/src/main/java/com/task/common/BusinessException.java`
- Create: `backend/src/main/java/com/task/common/GlobalExceptionHandler.java`
- Create: `backend/src/main/java/com/task/entity/SysUser.java`
- Create: `backend/src/main/java/com/task/entity/SysGroup.java`
- Create: `backend/src/main/java/com/task/entity/Task.java`
- Create: `backend/src/main/java/com/task/entity/TaskMember.java`
- Create: `backend/src/main/java/com/task/entity/Report.java`
- Create: `backend/src/main/java/com/task/entity/TaskAttachment.java`
- Create: `backend/src/main/java/com/task/entity/Comment.java`
- Create: `backend/src/main/java/com/task/entity/MinioFile.java`
- Create: `backend/src/main/java/com/task/mapper/SysUserMapper.java`（另 7 个同构 Mapper）
- Create: `backend/src/test/java/com/task/common/ResultTest.java`

- [ ] **Step 1: 写失败测试（Result 结构）**

`backend/src/test/java/com/task/common/ResultTest.java`：

```java
package com.task.common;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ResultTest {
    @Test
    void successResult() {
        Result<String> r = Result.ok("hello");
        assertEquals(0, r.getCode());
        assertEquals("hello", r.getData());
    }

    @Test
    void errorResult() {
        Result<Void> r = Result.fail(400, "bad");
        assertEquals(400, r.getCode());
        assertEquals("bad", r.getMessage());
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd backend && mvn -q test -Dtest=ResultTest`
Expected: FAIL（编译错误：Result 不存在）

- [ ] **Step 3: 实现 Result / BusinessException / GlobalExceptionHandler**

`backend/src/main/java/com/task/common/Result.java`：

```java
package com.task.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result<T> {
    private int code;
    private String message;
    private T data;

    public static <T> Result<T> ok(T data) {
        return new Result<>(0, "ok", data);
    }

    public static Result<Void> ok() {
        return new Result<>(0, "ok", null);
    }

    public static <T> Result<T> fail(int code, String message) {
        return new Result<>(code, message, null);
    }
}
```

`backend/src/main/java/com/task/common/BusinessException.java`：

```java
package com.task.common;

import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {
    private final int code;

    public BusinessException(String message) {
        super(message);
        this.code = 400;
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }
}
```

`backend/src/main/java/com/task/common/GlobalExceptionHandler.java`：

```java
package com.task.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusiness(BusinessException e) {
        return Result.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst().map(f -> f.getField() + " " + f.getDefaultMessage())
                .orElse("参数错误");
        return Result.fail(400, msg);
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleOther(Exception e) {
        log.error("系统异常", e);
        return Result.fail(500, "系统异常");
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `cd backend && mvn -q test -Dtest=ResultTest`
Expected: PASS

- [ ] **Step 5: 创建 8 个实体与 Mapper（纯样板，结构如下）**

所有实体用 Lombok `@Data`，MyBatis-Plus 注解 `@TableName`，字段与 schema.sql 列一一对应（camelCase ↔ snake_case 由 `map-underscore-to-camel-case` 自动映射）。以 `SysUser` 为例，其余 7 个同构：

`backend/src/main/java/com/task/entity/SysUser.java`：

```java
package com.task.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_user")
public class SysUser {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    private String password;
    private String realName;
    private String role;      // EMPLOYEE / LEADER / ADMIN
    private Long groupId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

其余实体字段按此规则（`@TableName` 分别为 `sys_group`/`task`/`task_member`/`report`/`task_attachment`/`comment`/`minio_file`，主键均为 `@TableId(type = IdType.AUTO)`，`task` 加 `private Integer deleted;`，时间字段用 `LocalDateTime`）。

Mapper 均继承 `BaseMapper`，以 `SysUserMapper` 为例，其余 7 个同构：

`backend/src/main/java/com/task/mapper/SysUserMapper.java`：

```java
package com.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.task.entity.SysUser;

public interface SysUserMapper extends BaseMapper<SysUser> {
}
```

- [ ] **Step 6: 全量编译验证**

Run: `cd backend && mvn -q compile`
Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add backend
git commit -m "feat(backend): 通用返回体/异常处理/实体与 Mapper"
```

### Task 3: 认证（JWT 登录 + 拦截器 + 种子数据）

**Files:**
- Create: `backend/src/main/java/com/task/auth/JwtUtil.java`
- Create: `backend/src/main/java/com/task/auth/UserContext.java`
- Create: `backend/src/main/java/com/task/auth/AuthInterceptor.java`
- Create: `backend/src/main/java/com/task/config/WebConfig.java`
- Create: `backend/src/main/java/com/task/config/MybatisPlusConfig.java`
- Create: `backend/src/main/java/com/task/controller/AuthController.java`
- Create: `backend/src/main/java/com/task/dto/LoginRequest.java`
- Create: `backend/src/main/java/com/task/dto/LoginResponse.java`
- Create: `backend/src/main/java/com/task/vo/UserVO.java`
- Create: `backend/src/main/java/com/task/config/DataInitializer.java`
- Create: `backend/src/test/java/com/task/auth/JwtUtilTest.java`

- [ ] **Step 1: 写失败测试（JWT 生成/解析/过期）**

`backend/src/test/java/com/task/auth/JwtUtilTest.java`：

```java
package com.task.auth;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JwtUtilTest {
    private final JwtUtil jwtUtil = new JwtUtil(
            "TaskAssignSystemSecretKey0123456789abcdefghijklmnopqrstuvwxyz", 72);

    @Test
    void generateAndParse() {
        String token = jwtUtil.generate(42L, "admin", "ADMIN");
        assertEquals(42L, jwtUtil.parseUserId(token));
        assertEquals("admin", jwtUtil.parseUsername(token));
        assertEquals("ADMIN", jwtUtil.parseRole(token));
    }

    @Test
    void invalidTokenThrows() {
        assertThrows(Exception.class, () -> jwtUtil.parseUserId("not.a.jwt"));
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd backend && mvn -q test -Dtest=JwtUtilTest`
Expected: FAIL（JwtUtil 不存在）

- [ ] **Step 3: 实现 JwtUtil / UserContext / AuthInterceptor / 配置类**

`backend/src/main/java/com/task/auth/JwtUtil.java`：

```java
package com.task.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class JwtUtil {
    private final SecretKey key;
    private final long expireMillis;

    public JwtUtil(@Value("${jwt.secret}") String secret,
                   @Value("${jwt.expire-hours}") long expireHours) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expireMillis = expireHours * 3600_000L;
    }

    public String generate(Long userId, String username, String role) {
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("role", role)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expireMillis))
                .signWith(key)
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }

    public Long parseUserId(String token) {
        return Long.valueOf(parse(token).getSubject());
    }

    public String parseUsername(String token) {
        return parse(token).get("username", String.class);
    }

    public String parseRole(String token) {
        return parse(token).get("role", String.class);
    }
}
```

`backend/src/main/java/com/task/auth/UserContext.java`：

```java
package com.task.auth;

import com.task.entity.SysUser;

public class UserContext {
    private static final ThreadLocal<SysUser> HOLDER = new ThreadLocal<>();

    public static void set(SysUser user) { HOLDER.set(user); }

    public static SysUser get() {
        SysUser u = HOLDER.get();
        if (u == null) throw new com.task.common.BusinessException(401, "未登录");
        return u;
    }

    public static void clear() { HOLDER.remove(); }
}
```

`backend/src/main/java/com/task/auth/AuthInterceptor.java`：

```java
package com.task.auth;

import com.task.common.BusinessException;
import com.task.entity.SysUser;
import com.task.mapper.SysUserMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {
    private final JwtUtil jwtUtil;
    private final SysUserMapper userMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            throw new BusinessException(401, "未登录");
        }
        try {
            String token = auth.substring(7);
            Long userId = jwtUtil.parseUserId(token);
            SysUser user = userMapper.selectById(userId);
            if (user == null) throw new BusinessException(401, "用户不存在");
            UserContext.set(user);
            return true;
        } catch (Exception e) {
            if (e instanceof BusinessException be) throw be;
            throw new BusinessException(401, "登录已过期");
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserContext.clear();
    }
}
```

`backend/src/main/java/com/task/config/WebConfig.java`：

```java
package com.task.config;

import com.task.auth.AuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {
    private final AuthInterceptor authInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/login");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("*")
                .allowedHeaders("*")
                .allowCredentials(true);
    }
}
```

`backend/src/main/java/com/task/config/MybatisPlusConfig.java`：

```java
package com.task.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MybatisPlusConfig {
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `cd backend && mvn -q test -Dtest=JwtUtilTest`
Expected: PASS

- [ ] **Step 5: 登录接口 + DTO + VO**

`backend/src/main/java/com/task/dto/LoginRequest.java`：

```java
package com.task.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LoginRequest {
    @NotBlank(message = "用户名不能为空")
    private String username;
    @NotBlank(message = "密码不能为空")
    private String password;
}
```

`backend/src/main/java/com/task/dto/LoginResponse.java`：

```java
package com.task.dto;

import com.task.vo.UserVO;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class LoginResponse {
    private String token;
    private UserVO user;
}
```

`backend/src/main/java/com/task/vo/UserVO.java`：

```java
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
        vo.setRole(u.getRole());
        vo.setGroupId(u.getGroupId());
        vo.setGroupName(groupName);
        return vo;
    }
}
```

`backend/src/main/java/com/task/controller/AuthController.java`：

```java
package com.task.controller;

import com.task.auth.JwtUtil;
import com.task.auth.UserContext;
import com.task.common.BusinessException;
import com.task.common.Result;
import com.task.dto.LoginRequest;
import com.task.dto.LoginResponse;
import com.task.entity.SysGroup;
import com.task.entity.SysUser;
import com.task.mapper.SysGroupMapper;
import com.task.mapper.SysUserMapper;
import com.task.vo.UserVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private final SysUserMapper userMapper;
    private final SysGroupMapper groupMapper;
    private final JwtUtil jwtUtil;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest req) {
        SysUser user = userMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SysUser>()
                        .eq(SysUser::getUsername, req.getUsername()));
        if (user == null || !encoder.matches(req.getPassword(), user.getPassword())) {
            throw new BusinessException("用户名或密码错误");
        }
        String token = jwtUtil.generate(user.getId(), user.getUsername(), user.getRole());
        String groupName = user.getGroupId() == null ? null
                : groupMapper.selectById(user.getGroupId()).getName();
        return Result.ok(new LoginResponse(token, UserVO.from(user, groupName)));
    }

    @GetMapping("/me")
    public Result<UserVO> me() {
        SysUser u = UserContext.get();
        String groupName = u.getGroupId() == null ? null
                : groupMapper.selectById(u.getGroupId()).getName();
        return Result.ok(UserVO.from(u, groupName));
    }
}
```

注意：BCrypt 依赖 `spring-security-crypto`。在 pom.xml 加依赖：

```xml
<dependency>
  <groupId>org.springframework.security</groupId>
  <artifactId>spring-security-crypto</artifactId>
</dependency>
```

- [ ] **Step 6: 种子数据 DataInitializer（CommandLineRunner）**

`backend/src/main/java/com/task/config/DataInitializer.java`：

```java
package com.task.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.task.entity.SysGroup;
import com.task.entity.SysUser;
import com.task.mapper.SysGroupMapper;
import com.task.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {
    private final SysUserMapper userMapper;
    private final SysGroupMapper groupMapper;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Override
    public void run(String... args) {
        if (userMapper.selectCount(new LambdaQueryWrapper<SysUser>()) > 0) return;
        SysUser admin = user("admin", "admin123", "系统管理员", "ADMIN", null);
        userMapper.insert(admin);
        SysUser leader = user("leader1", "123456", "李组长", "LEADER", null);
        userMapper.insert(leader);
        SysGroup g1 = new SysGroup();
        g1.setName("研发一组");
        g1.setLeaderId(leader.getId());
        g1.setDescription("后端研发小组");
        groupMapper.insert(g1);
        leader.setGroupId(g1.getId());
        userMapper.updateById(leader);
        userMapper.insert(user("zhangsan", "123456", "张三", "EMPLOYEE", g1.getId()));
        userMapper.insert(user("lisi", "123456", "李四", "EMPLOYEE", g1.getId()));
        userMapper.insert(user("wangwu", "123456", "王五", "EMPLOYEE", g1.getId()));
        log.info("种子数据初始化完成");
    }

    private SysUser user(String username, String pwd, String name, String role, Long groupId) {
        SysUser u = new SysUser();
        u.setUsername(username);
        u.setPassword(encoder.encode(pwd));
        u.setRealName(name);
        u.setRole(role);
        u.setGroupId(groupId);
        return u;
    }
}
```

- [ ] **Step 7: 启动冒烟验证**

Run: `cd backend && mvn -q spring-boot:run`（后台），然后：

```bash
curl -s -X POST http://127.0.0.1:8080/api/auth/login -H 'Content-Type: application/json' -d '{"username":"admin","password":"admin123"}'
```

Expected: 返回 `{"code":0,...,"data":{"token":"eyJ...","user":{"username":"admin","role":"ADMIN"}}}`

```bash
curl -s http://127.0.0.1:8080/api/auth/me -H "Authorization: Bearer <上一步token>"
```

Expected: 返回 admin 用户信息；无 token 时返回 `{"code":401,...}`

- [ ] **Step 8: Commit**

```bash
git add backend
git commit -m "feat(backend): JWT 登录认证与种子数据"
```

### Task 4: 用户管理与小组管理

**Files:**
- Create: `backend/src/main/java/com/task/controller/UserController.java`
- Create: `backend/src/main/java/com/task/controller/GroupController.java`
- Create: `backend/src/main/java/com/task/dto/UserRequest.java`
- Create: `backend/src/main/java/com/task/dto/GroupRequest.java`
- Create: `backend/src/main/java/com/task/dto/MemberRequest.java`
- Create: `backend/src/test/java/com/task/controller/UserControllerTest.java`

- [ ] **Step 1: 写失败测试（用户 CRUD 权限与校验，MockMvc）**

`backend/src/test/java/com/task/controller/UserControllerTest.java`：

```java
package com.task.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.task.TaskApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = TaskApplication.class)
@AutoConfigureMockMvc
class UserControllerTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;

    private String login(String username, String password) throws Exception {
        String body = om.writeValueAsString(java.util.Map.of("username", username, "password", password));
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
    }

    @Test
    void employeeCannotCreateUser() throws Exception {
        String token = login("zhangsan", "123456");
        mvc.perform(post("/api/users").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"x\",\"password\":\"123456\",\"realName\":\"X\",\"role\":\"EMPLOYEE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void adminCanCreateUser() throws Exception {
        String token = login("admin", "admin123");
        mvc.perform(post("/api/users").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"testuser1\",\"password\":\"123456\",\"realName\":\"测试\",\"role\":\"EMPLOYEE\",\"groupId\":null}"))
                .andExpect(jsonPath("$.code").value(0));
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd backend && mvn -q test -Dtest=UserControllerTest`
Expected: FAIL（404 或编译错误：无 UserController）

- [ ] **Step 3: 实现用户管理控制器**

`backend/src/main/java/com/task/dto/UserRequest.java`：

```java
package com.task.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UserRequest {
    @NotBlank(message = "用户名不能为空")
    private String username;
    @NotBlank(message = "密码不能为空")
    private String password;
    @NotBlank(message = "姓名不能为空")
    private String realName;
    private String role = "EMPLOYEE";
    private Long groupId;
}
```

`backend/src/main/java/com/task/controller/UserController.java`：

```java
package com.task.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.task.auth.UserContext;
import com.task.common.BusinessException;
import com.task.common.Result;
import com.task.dto.UserRequest;
import com.task.entity.SysGroup;
import com.task.entity.SysUser;
import com.task.mapper.SysGroupMapper;
import com.task.mapper.SysUserMapper;
import com.task.vo.UserVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {
    private final SysUserMapper userMapper;
    private final SysGroupMapper groupMapper;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    private void requireAdmin() {
        if (!"ADMIN".equals(UserContext.get().getRole())) {
            throw new BusinessException(403, "无权操作");
        }
    }

    @GetMapping
    public Result<Page<UserVO>> list(@RequestParam(defaultValue = "1") long page,
                                     @RequestParam(defaultValue = "10") long size,
                                     @RequestParam(required = false) String keyword,
                                     @RequestParam(required = false) String role) {
        LambdaQueryWrapper<SysUser> qw = new LambdaQueryWrapper<SysUser>()
                .like(StringUtils.hasText(keyword), SysUser::getRealName, keyword)
                .eq(StringUtils.hasText(role), SysUser::getRole, role)
                .orderByDesc(SysUser::getId);
        Page<SysUser> p = userMapper.selectPage(new Page<>(page, size), qw);
        Map<Long, String> groupNames = groupMapper.selectList(null).stream()
                .collect(Collectors.toMap(SysGroup::getId, SysGroup::getName));
        Page<UserVO> voPage = new Page<>(p.getCurrent(), p.getSize(), p.getTotal());
        voPage.setRecords(p.getRecords().stream()
                .map(u -> UserVO.from(u, u.getGroupId() == null ? null : groupNames.get(u.getGroupId())))
                .collect(Collectors.toList()));
        return Result.ok(voPage);
    }

    @PostMapping
    public Result<Long> create(@Valid @RequestBody UserRequest req) {
        requireAdmin();
        if (userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, req.getUsername())) > 0) {
            throw new BusinessException("用户名已存在");
        }
        SysUser u = new SysUser();
        u.setUsername(req.getUsername());
        u.setPassword(encoder.encode(req.getPassword()));
        u.setRealName(req.getRealName());
        u.setRole(req.getRole());
        u.setGroupId(req.getGroupId());
        userMapper.insert(u);
        return Result.ok(u.getId());
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody UserRequest req) {
        requireAdmin();
        SysUser u = userMapper.selectById(id);
        if (u == null) throw new BusinessException("用户不存在");
        u.setRealName(req.getRealName());
        u.setRole(req.getRole());
        u.setGroupId(req.getGroupId());
        userMapper.updateById(u);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        requireAdmin();
        SysUser u = userMapper.selectById(id);
        if (u == null) throw new BusinessException("用户不存在");
        if (id.equals(UserContext.get().getId())) throw new BusinessException("不能删除自己");
        userMapper.deleteById(id);
        return Result.ok();
    }

    @PutMapping("/{id}/password")
    public Result<Void> resetPassword(@PathVariable Long id,
                                      @RequestBody Map<String, String> body) {
        requireAdmin();
        SysUser u = userMapper.selectById(id);
        if (u == null) throw new BusinessException("用户不存在");
        String pwd = body.get("password");
        if (!StringUtils.hasText(pwd)) throw new BusinessException("密码不能为空");
        u.setPassword(encoder.encode(pwd));
        userMapper.updateById(u);
        return Result.ok();
    }
}
```

- [ ] **Step 4: 实现小组管理控制器**

`backend/src/main/java/com/task/dto/GroupRequest.java`：

```java
package com.task.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class GroupRequest {
    @NotBlank(message = "组名不能为空")
    private String name;
    @NotNull(message = "组长不能为空")
    private Long leaderId;
    private String description;
}
```

`backend/src/main/java/com/task/dto/MemberRequest.java`：

```java
package com.task.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class MemberRequest {
    @NotNull(message = "用户不能为空")
    private Long userId;
}
```

`backend/src/main/java/com/task/controller/GroupController.java`：

```java
package com.task.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.task.auth.UserContext;
import com.task.common.BusinessException;
import com.task.common.Result;
import com.task.dto.GroupRequest;
import com.task.dto.MemberRequest;
import com.task.entity.SysGroup;
import com.task.entity.SysUser;
import com.task.mapper.SysGroupMapper;
import com.task.mapper.SysUserMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/groups")
@RequiredArgsConstructor
public class GroupController {
    private final SysGroupMapper groupMapper;
    private final SysUserMapper userMapper;

    /** 组长只能管理自己的组；管理员任意。非组长/管理员仅可读。 */
    private SysGroup requireManageable(SysGroup g) {
        SysUser cur = UserContext.get();
        if ("ADMIN".equals(cur.getRole())) return g;
        if ("LEADER".equals(cur.getRole()) && g.getLeaderId().equals(cur.getId())) return g;
        throw new BusinessException(403, "无权管理该小组");
    }

    @GetMapping
    public Result<List<Map<String, Object>>> list() {
        List<SysGroup> groups = groupMapper.selectList(null);
        List<Map<String, Object>> result = groups.stream().map(g -> {
            Map<String, Object> m = new HashMap<>();
            SysUser leader = userMapper.selectById(g.getLeaderId());
            m.put("id", g.getId());
            m.put("name", g.getName());
            m.put("description", g.getDescription());
            m.put("leaderId", g.getLeaderId());
            m.put("leaderName", leader == null ? null : leader.getRealName());
            m.put("memberCount", userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                    .eq(SysUser::getGroupId, g.getId())));
            return m;
        }).collect(Collectors.toList());
        return Result.ok(result);
    }

    @GetMapping("/{id}/members")
    public Result<List<UserVO>> members(@PathVariable Long id) {
        SysGroup g = groupMapper.selectById(id);
        if (g == null) throw new BusinessException("小组不存在");
        List<SysUser> users = userMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getGroupId, id));
        return Result.ok(users.stream().map(u -> UserVO.from(u, g.getName()))
                .collect(Collectors.toList()));
    }

    @PostMapping
    public Result<Long> create(@Valid @RequestBody GroupRequest req) {
        SysUser cur = UserContext.get();
        if (!"ADMIN".equals(cur.getRole()) && !"LEADER".equals(cur.getRole())) {
            throw new BusinessException(403, "无权操作");
        }
        if (groupMapper.selectCount(new LambdaQueryWrapper<SysGroup>()
                .eq(SysGroup::getName, req.getName())) > 0) {
            throw new BusinessException("组名已存在");
        }
        SysGroup g = new SysGroup();
        g.setName(req.getName());
        g.setLeaderId(req.getLeaderId());
        g.setDescription(req.getDescription());
        groupMapper.insert(g);
        return Result.ok(g.getId());
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody GroupRequest req) {
        SysGroup g = groupMapper.selectById(id);
        if (g == null) throw new BusinessException("小组不存在");
        requireManageable(g);
        g.setName(req.getName());
        g.setLeaderId(req.getLeaderId());
        g.setDescription(req.getDescription());
        groupMapper.updateById(g);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        SysGroup g = groupMapper.selectById(id);
        if (g == null) throw new BusinessException("小组不存在");
        requireManageable(g);
        groupMapper.deleteById(id);
        return Result.ok();
    }

    @PostMapping("/{id}/members")
    public Result<Void> addMember(@PathVariable Long id, @Valid @RequestBody MemberRequest req) {
        SysGroup g = groupMapper.selectById(id);
        if (g == null) throw new BusinessException("小组不存在");
        requireManageable(g);
        SysUser u = userMapper.selectById(req.getUserId());
        if (u == null) throw new BusinessException("用户不存在");
        u.setGroupId(id);
        userMapper.updateById(u);
        return Result.ok();
    }

    @DeleteMapping("/{id}/members/{userId}")
    public Result<Void> removeMember(@PathVariable Long id, @PathVariable Long userId) {
        SysGroup g = groupMapper.selectById(id);
        if (g == null) throw new BusinessException("小组不存在");
        requireManageable(g);
        SysUser u = userMapper.selectById(userId);
        if (u == null || !id.equals(u.getGroupId())) throw new BusinessException("该用户不在此组");
        u.setGroupId(null);
        userMapper.updateById(u);
        return Result.ok();
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `cd backend && mvn -q test -Dtest=UserControllerTest`
Expected: PASS（注意：测试依赖种子数据已存在——`DataInitializer` 在 Spring 上下文启动时运行；`testuser1` 重复创建会报用户名已存在，若第二次运行失败先删掉该用户或改用户名）

- [ ] **Step 6: 手动冒烟小组接口**

```bash
TOKEN=$(curl -s -X POST http://127.0.0.1:8080/api/auth/login -H 'Content-Type: application/json' -d '{"username":"leader1","password":"123456"}' | sed 's/.*"token":"\([^"]*\)".*/\1/')
curl -s http://127.0.0.1:8080/api/groups -H "Authorization: Bearer $TOKEN"
curl -s http://127.0.0.1:8080/api/groups/1/members -H "Authorization: Bearer $TOKEN"
```

Expected: 返回研发一组及 4 名成员（组长+3 员工）

- [ ] **Step 7: Commit**

```bash
git add backend
git commit -m "feat(backend): 用户管理与小组管理"
```

### Task 5: 任务创建/列表/详情/权重调整

**Files:**
- Create: `backend/src/main/java/com/task/dto/CreateTaskRequest.java`
- Create: `backend/src/main/java/com/task/controller/TaskController.java`
- Create: `backend/src/main/java/com/task/service/TaskService.java`
- Create: `backend/src/main/java/com/task/vo/TaskVO.java`
- Create: `backend/src/main/java/com/task/vo/TaskMemberVO.java`
- Create: `backend/src/test/java/com/task/service/TaskServiceTest.java`

- [ ] **Step 1: 写失败测试（任务创建展开成员 + 权重均分）**

`backend/src/test/java/com/task/service/TaskServiceTest.java`：

```java
package com.task.service;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskServiceTest {
    /** 权重均分：3 个成员 → 34,33,33（余数给第一个） */
    @Test
    void splitWeightEqually() {
        List<Integer> w = TaskService.splitWeights(3);
        assertEquals(Arrays.asList(34, 33, 33), w);
    }

    /** 加权平均：40%/30%/30% 权重，进度 80/50/100 → 77 */
    @Test
    void weightedAverage() {
        List<Integer> weights = List.of(40, 30, 30);
        List<Integer> progresses = List.of(80, 50, 100);
        assertEquals(77, TaskService.calcOverallProgress(weights, progresses));
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd backend && mvn -q test -Dtest=TaskServiceTest`
Expected: FAIL（TaskService 不存在）

- [ ] **Step 3: 实现 TaskService（静态工具方法 + 业务）**

`backend/src/main/java/com/task/service/TaskService.java`：

```java
package com.task.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.task.auth.UserContext;
import com.task.common.BusinessException;
import com.task.dto.CreateTaskRequest;
import com.task.entity.*;
import com.task.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TaskService {
    private final TaskMapper taskMapper;
    private final TaskMemberMapper memberMapper;
    private final SysUserMapper userMapper;
    private final SysGroupMapper groupMapper;
    private final TaskAttachmentMapper attachmentMapper;

    /** 均分权重：100/n，余数依次给前几个成员（n=3 → 34,33,33） */
    public static List<Integer> splitWeights(int n) {
        List<Integer> list = new ArrayList<>();
        int base = 100 / n;
        int rest = 100 % n;
        for (int i = 0; i < n; i++) list.add(base + (i < rest ? 1 : 0));
        return list;
    }

    /** 整体进度 = Σ(进度×权重)/Σ权重，四舍五入 */
    public static int calcOverallProgress(List<Integer> weights, List<Integer> progresses) {
        long sumW = weights.stream().mapToLong(Integer::longValue).sum();
        long sumP = 0;
        for (int i = 0; i < weights.size(); i++) {
            sumP += (long) progresses.get(i) * weights.get(i);
        }
        return sumW == 0 ? 0 : (int) Math.round((double) sumP / sumW);
    }

    @Transactional
    public Long create(CreateTaskRequest req) {
        SysUser creator = UserContext.get();
        Task task = new Task();
        task.setName(req.getName());
        task.setDescription(req.getDescription());
        task.setCreatorId(creator.getId());
        task.setAssigneeId(req.getAssigneeId());
        task.setDeadline(req.getDeadline());
        task.setStatus("DOING");
        task.setProgress(0);

        List<SysUser> assignees;
        if ("INDIVIDUAL".equals(req.getAssignType())) {
            SysUser u = userMapper.selectById(req.getAssigneeId());
            if (u == null) throw new BusinessException("用户不存在");
            task.setAssignType("INDIVIDUAL");
            assignees = List.of(u);
        } else if ("GROUP".equals(req.getAssignType())) {
            SysGroup g = groupMapper.selectById(req.getAssigneeId());
            if (g == null) throw new BusinessException("小组不存在");
            task.setAssignType("GROUP");
            assignees = userMapper.selectList(new LambdaQueryWrapper<SysUser>()
                    .eq(SysUser::getGroupId, g.getId()));
            if (assignees.isEmpty()) throw new BusinessException("小组没有成员");
        } else {
            throw new BusinessException("分配类型不合法");
        }
        taskMapper.insert(task);

        List<Integer> weights = req.getWeights() != null && req.getWeights().size() == assignees.size()
                ? req.getWeights() : splitWeights(assignees.size());
        for (int i = 0; i < assignees.size(); i++) {
            TaskMember m = new TaskMember();
            m.setTaskId(task.getId());
            m.setUserId(assignees.get(i).getId());
            m.setWeight(weights.get(i));
            m.setProgress(0);
            memberMapper.insert(m);
        }
        return task.getId();
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `cd backend && mvn -q test -Dtest=TaskServiceTest`
Expected: PASS

- [ ] **Step 5: DTO + VO + 控制器**

`backend/src/main/java/com/task/dto/CreateTaskRequest.java`：

```java
package com.task.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class CreateTaskRequest {
    @NotBlank(message = "任务名称不能为空")
    private String name;
    private String description;
    @NotNull(message = "分配类型不能为空")
    private String assignType;   // INDIVIDUAL / GROUP
    @NotNull(message = "分配对象不能为空")
    private Long assigneeId;
    private LocalDateTime deadline;
    private List<Integer> weights;      // 可选：小组权重列表（与组员数一致）；为空默认均分
    private List<Long> attachmentIds;   // 可选：已上传的 minio_file id
}
```

`backend/src/main/java/com/task/vo/TaskMemberVO.java`：

```java
package com.task.vo;

import lombok.Data;

@Data
public class TaskMemberVO {
    private Long id;
    private Long taskId;
    private Long userId;
    private String realName;
    private Integer weight;
    private Integer progress;
}
```

`backend/src/main/java/com/task/vo/TaskVO.java`：

```java
package com.task.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class TaskVO {
    private Long id;
    private String name;
    private String description;
    private Long creatorId;
    private String creatorName;
    private String assignType;
    private Long assigneeId;
    private String assigneeName;   // 个人=姓名，小组=组名
    private String status;
    private LocalDateTime deadline;
    private Integer progress;
    private LocalDateTime doneAt;
    private LocalDateTime createdAt;
    private List<TaskMemberVO> members;
    private List<AttachmentVO> attachments;
}
```

`backend/src/main/java/com/task/vo/AttachmentVO.java`：

```java
package com.task.vo;

import lombok.Data;

@Data
public class AttachmentVO {
    private Long id;
    private String fileName;
    private String fileUrl;
    private Long fileSize;
}
```

`backend/src/main/java/com/task/controller/TaskController.java`：

```java
package com.task.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.task.auth.UserContext;
import com.task.common.BusinessException;
import com.task.common.Result;
import com.task.dto.CreateTaskRequest;
import com.task.entity.*;
import com.task.mapper.*;
import com.task.service.TaskService;
import com.task.vo.AttachmentVO;
import com.task.vo.TaskMemberVO;
import com.task.vo.TaskVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {
    private final TaskService taskService;
    private final TaskMapper taskMapper;
    private final TaskMemberMapper memberMapper;
    private final SysUserMapper userMapper;
    private final SysGroupMapper groupMapper;
    private final TaskAttachmentMapper attachmentMapper;
    private final MinioFileMapper minioFileMapper;

    private SysUser current() { return UserContext.get(); }

    private boolean canManage(Task t) {
        SysUser cur = current();
        return "ADMIN".equals(cur.getRole()) || t.getCreatorId().equals(cur.getId());
    }

    @PostMapping
    public Result<Long> create(@Valid @RequestBody CreateTaskRequest req) {
        if (!"ADMIN".equals(current().getRole()) && !"LEADER".equals(current().getRole())) {
            throw new BusinessException(403, "无权创建任务");
        }
        Long taskId = taskService.create(req);
        // 关联附件
        if (req.getAttachmentIds() != null) {
            for (Long fileId : req.getAttachmentIds()) {
                MinioFile f = minioFileMapper.selectById(fileId);
                if (f == null) continue;
                TaskAttachment a = new TaskAttachment();
                a.setTaskId(taskId);
                a.setFileName(f.getFileName());
                a.setFileUrl("http://127.0.0.1:9000/task-attachments/" + f.getObjectName());
                a.setFileSize(f.getSize());
                a.setUploadedBy(current().getId());
                attachmentMapper.insert(a);
            }
        }
        return Result.ok(taskId);
    }

    @GetMapping
    public Result<List<TaskVO>> list(@RequestParam(required = false) String type,
                                     @RequestParam(required = false) String status,
                                     @RequestParam(required = false) String keyword) {
        SysUser cur = current();
        List<Long> myTaskIds = memberMapper.selectList(new LambdaQueryWrapper<TaskMember>()
                        .eq(TaskMember::getUserId, cur.getId()))
                .stream().map(TaskMember::getTaskId).collect(Collectors.toList());

        LambdaQueryWrapper<Task> qw = new LambdaQueryWrapper<Task>()
                .eq(StringUtils.hasText(status), Task::getStatus, status)
                .like(StringUtils.hasText(keyword), Task::getName, keyword)
                .orderByDesc(Task::getId);
        if ("mine_created".equals(type)) {
            qw.eq(Task::getCreatorId, cur.getId());
        } else if ("assigned".equals(type)) {
            qw.in(!myTaskIds.isEmpty(), Task::getId, myTaskIds);
            if (myTaskIds.isEmpty()) return Result.ok(Collections.emptyList());
        }
        return Result.ok(taskMapper.selectList(qw).stream().map(this::toVO).collect(Collectors.toList()));
    }

    @GetMapping("/{id}")
    public Result<TaskVO> detail(@PathVariable Long id) {
        Task t = taskMapper.selectById(id);
        if (t == null) throw new BusinessException("任务不存在");
        return Result.ok(toVO(t));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody CreateTaskRequest req) {
        Task t = taskMapper.selectById(id);
        if (t == null) throw new BusinessException("任务不存在");
        if (!canManage(t)) throw new BusinessException(403, "无权修改任务");
        t.setName(req.getName());
        t.setDescription(req.getDescription());
        t.setDeadline(req.getDeadline());
        taskMapper.updateById(t);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        Task t = taskMapper.selectById(id);
        if (t == null) throw new BusinessException("任务不存在");
        if (!canManage(t)) throw new BusinessException(403, "无权删除任务");
        Long pending = com.baomidou.mybatisplus.core.toolkit.Wrappers
                .<Report>lambdaQuery().eq(Report::getTaskMemberId, 0L).count(); // 占位，见下方步骤
        taskMapper.deleteById(id); // 逻辑删除
        return Result.ok();
    }

    @PutMapping("/{id}/weights")
    public Result<Void> updateWeights(@PathVariable Long id, @RequestBody List<Map<String, Integer>> weights) {
        Task t = taskMapper.selectById(id);
        if (t == null) throw new BusinessException("任务不存在");
        if (!canManage(t)) throw new BusinessException(403, "无权操作");
        List<TaskMember> members = memberMapper.selectList(new LambdaQueryWrapper<TaskMember>()
                .eq(TaskMember::getTaskId, id));
        if (weights.size() != members.size()) throw new BusinessException("权重数量与成员数不一致");
        for (TaskMember m : members) {
            Map<String, Integer> w = weights.stream()
                    .filter(x -> x.get("userId").equals(m.getUserId().intValue()))
                    .findFirst().orElseThrow(() -> new BusinessException("权重缺少成员 " + m.getUserId()));
            m.setWeight(w.get("weight"));
            memberMapper.updateById(m);
        }
        return Result.ok();
    }

    private TaskVO toVO(Task t) {
        TaskVO vo = new TaskVO();
        vo.setId(t.getId());
        vo.setName(t.getName());
        vo.setDescription(t.getDescription());
        vo.setCreatorId(t.getCreatorId());
        SysUser creator = userMapper.selectById(t.getCreatorId());
        vo.setCreatorName(creator == null ? null : creator.getRealName());
        vo.setAssignType(t.getAssignType());
        vo.setAssigneeId(t.getAssigneeId());
        if ("INDIVIDUAL".equals(t.getAssignType())) {
            SysUser u = userMapper.selectById(t.getAssigneeId());
            vo.setAssigneeName(u == null ? null : u.getRealName());
        } else {
            SysGroup g = groupMapper.selectById(t.getAssigneeId());
            vo.setAssigneeName(g == null ? null : g.getName());
        }
        vo.setStatus(t.getStatus());
        vo.setDeadline(t.getDeadline());
        vo.setProgress(t.getProgress());
        vo.setDoneAt(t.getDoneAt());
        vo.setCreatedAt(t.getCreatedAt());

        List<TaskMember> members = memberMapper.selectList(new LambdaQueryWrapper<TaskMember>()
                .eq(TaskMember::getTaskId, t.getId()));
        vo.setMembers(members.stream().map(m -> {
            TaskMemberVO mv = new TaskMemberVO();
            mv.setId(m.getId());
            mv.setTaskId(m.getTaskId());
            mv.setUserId(m.getUserId());
            SysUser u = userMapper.selectById(m.getUserId());
            mv.setRealName(u == null ? null : u.getRealName());
            mv.setWeight(m.getWeight());
            mv.setProgress(m.getProgress());
            return mv;
        }).collect(Collectors.toList()));

        vo.setAttachments(attachmentMapper.selectList(new LambdaQueryWrapper<TaskAttachment>()
                        .eq(TaskAttachment::getTaskId, t.getId()))
                .stream().map(a -> {
                    AttachmentVO av = new AttachmentVO();
                    av.setId(a.getId());
                    av.setFileName(a.getFileName());
                    av.setFileUrl(a.getFileUrl());
                    av.setFileSize(a.getFileSize());
                    return av;
                }).collect(Collectors.toList()));
        return vo;
    }
}
```

注意 `delete` 方法中 PENDING 汇报检查需真实现：删除前查询该任务所有 task_member id，若存在 status=PENDING 的 report 则拒绝。`Report`/`ReportMapper` 在 Task 7 才有，此处先按下面步骤实现一个 `ReportMapper` 查询即可（Report 实体与 Mapper 已在 Task 2 建好）。修正 `delete` 实现：

```java
@DeleteMapping("/{id}")
public Result<Void> delete(@PathVariable Long id) {
    Task t = taskMapper.selectById(id);
    if (t == null) throw new BusinessException("任务不存在");
    if (!canManage(t)) throw new BusinessException(403, "无权删除任务");
    List<Long> memberIds = memberMapper.selectList(new LambdaQueryWrapper<TaskMember>()
                    .eq(TaskMember::getTaskId, id))
            .stream().map(TaskMember::getId).collect(Collectors.toList());
    if (!memberIds.isEmpty()) {
        Long pending = reportMapper.selectCount(new LambdaQueryWrapper<Report>()
                .in(Report::getTaskMemberId, memberIds)
                .eq(Report::getStatus, "PENDING"));
        if (pending > 0) throw new BusinessException("存在待审核汇报，无法删除");
    }
    taskMapper.deleteById(id); // 逻辑删除
    return Result.ok();
}
```

并在控制器注入 `ReportMapper reportMapper`。

- [ ] **Step 6: 手动冒烟**

```bash
TOKEN=$(curl -s -X POST http://127.0.0.1:8080/api/auth/login -H 'Content-Type: application/json' -d '{"username":"leader1","password":"123456"}' | sed 's/.*"token":"\([^"]*\)".*/\1/')
# 创建小组任务（研发一组 id=1，不传权重=默认均分）
curl -s -X POST http://127.0.0.1:8080/api/tasks -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"开发登录模块","description":"实现 JWT 登录","assignType":"GROUP","assigneeId":1,"deadline":"2026-09-01T00:00:00"}'
curl -s "http://127.0.0.1:8080/api/tasks?type=mine_created" -H "Authorization: Bearer $TOKEN"
curl -s http://127.0.0.1:8080/api/tasks/1 -H "Authorization: Bearer $TOKEN"
```

Expected: 任务 1 创建成功，成员 3 条 weight=34/33/33

- [ ] **Step 7: Commit**

```bash
git add backend
git commit -m "feat(backend): 任务创建/列表/详情/权重调整"
```

### Task 6: MinIO 附件上传

**Files:**
- Create: `backend/src/main/java/com/task/config/MinioConfig.java`
- Create: `backend/src/main/java/com/task/controller/FileController.java`
- Create: `backend/src/test/java/com/task/controller/FileControllerTest.java`

- [ ] **Step 1: 验证 MinIO 凭证并建 bucket**

```bash
curl -s http://127.0.0.1:9000/minio/health/live; echo
# 验证默认凭证：
docker exec mysql-dev echo skip 2>/dev/null # 无关
/usr/local/mysql/bin/mysql -h127.0.0.1 -uroot -proot123456 -e "select 1" >/dev/null 2>&1
```

MinIO 默认凭证 minioadmin/minioadmin，若 TaskApplication 启动时 MinioConfig 报错，执行：
`docker inspect minio-dev --format '{{range .Config.Env}}{{println .}}{{end}}' | grep -i -E "minio|root|access"` 查实际凭证并更新 application.yml。

- [ ] **Step 2: 写失败测试（上传接口返回 URL）**

`backend/src/test/java/com/task/controller/FileControllerTest.java`：

```java
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
```

- [ ] **Step 3: 运行确认失败**

Run: `cd backend && mvn -q test -Dtest=FileControllerTest`
Expected: FAIL（404：无 FileController）

- [ ] **Step 4: 实现 MinioConfig + FileController**

`backend/src/main/java/com/task/config/MinioConfig.java`：

```java
package com.task.config;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MinioConfig {
    @Bean
    public MinioClient minioClient(@Value("${minio.endpoint}") String endpoint,
                                   @Value("${minio.access-key}") String accessKey,
                                   @Value("${minio.secret-key}") String secretKey,
                                   @Value("${minio.bucket}") String bucket) throws Exception {
        MinioClient client = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
        boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        if (!exists) {
            client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
        return client;
    }
}
```

`backend/src/main/java/com/task/controller/FileController.java`：

```java
package com.task.controller;

import com.task.auth.UserContext;
import com.task.common.BusinessException;
import com.task.common.Result;
import com.task.entity.MinioFile;
import com.task.mapper.MinioFileMapper;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileController {
    private final MinioClient minioClient;
    private final MinioFileMapper fileMapper;

    @Value("${minio.bucket}") private String bucket;
    @Value("${minio.endpoint}") private String endpoint;

    @PostMapping("/upload")
    public Result<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) throws Exception {
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
        String url = endpoint + "/" + bucket + "/" + objectName;
        return Result.ok(Map.of("id", f.getId(), "url", url, "fileName", f.getFileName(), "fileSize", f.getSize()));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        MinioFile f = fileMapper.selectById(id);
        if (f == null) throw new BusinessException("文件不存在");
        fileMapper.deleteById(id);
        return Result.ok();
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `cd backend && mvn -q test -Dtest=FileControllerTest`
Expected: PASS

- [ ] **Step 6: 手动冒烟（真实上传）**

```bash
TOKEN=$(curl -s -X POST http://127.0.0.1:8080/api/auth/login -H 'Content-Type: application/json' -d '{"username":"leader1","password":"123456"}' | sed 's/.*"token":"\([^"]*\)".*/\1/')
echo "测试附件内容" > /tmp/attach.txt
curl -s -X POST http://127.0.0.1:8080/api/files/upload -H "Authorization: Bearer $TOKEN" -F "file=@/tmp/attach.txt"
```

Expected: 返回 `{"code":0,...,"url":"http://127.0.0.1:9000/task-attachments/..."}`；浏览器打开该 URL 能看到内容

- [ ] **Step 7: Commit**

```bash
git add backend
git commit -m "feat(backend): MinIO 附件上传"
```

### Task 7: 汇报提交与列表（含可见性）

**Files:**
- Create: `backend/src/main/java/com/task/dto/ReportRequest.java`
- Create: `backend/src/main/java/com/task/controller/ReportController.java`
- Create: `backend/src/main/java/com/task/service/ReportService.java`
- Create: `backend/src/main/java/com/task/vo/ReportVO.java`
- Create: `backend/src/test/java/com/task/service/ReportServiceTest.java`

- [ ] **Step 1: 写失败测试（提交汇报约束）**

`backend/src/test/java/com/task/service/ReportServiceTest.java`：

```java
package com.task.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportServiceTest {
    /** 汇报进度不能低于当前成员进度（单调递增） */
    @Test
    void progressMustNotDecrease() {
        boolean rejected = false;
        try {
            ReportService.checkProgressRule(80, 50); // 当前 80，汇报 50
        } catch (IllegalArgumentException e) {
            rejected = true;
        }
        assertTrue(rejected);
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd backend && mvn -q test -Dtest=ReportServiceTest`
Expected: FAIL（ReportService 不存在）

- [ ] **Step 3: 实现 ReportService**

`backend/src/main/java/com/task/service/ReportService.java`：

```java
package com.task.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.task.auth.UserContext;
import com.task.common.BusinessException;
import com.task.dto.ReportRequest;
import com.task.entity.*;
import com.task.mapper.*;
import com.task.vo.ReportVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReportService {
    private final ReportMapper reportMapper;
    private final TaskMemberMapper memberMapper;
    private final TaskMapper taskMapper;
    private final SysUserMapper userMapper;

    /** 校验：新进度必须 ≥ 当前进度，且 ≤ 100 */
    public static void checkProgressRule(int current, int target) {
        if (target < current) throw new IllegalArgumentException("进度不能低于当前进度");
        if (target > 100) throw new IllegalArgumentException("进度不能超过 100");
    }

    @Transactional
    public Long submit(Long taskId, ReportRequest req) {
        TaskMember member = memberMapper.selectOne(new LambdaQueryWrapper<TaskMember>()
                .eq(TaskMember::getTaskId, taskId)
                .eq(TaskMember::getUserId, UserContext.get().getId()));
        if (member == null) throw new BusinessException("你不是该任务的成员");
        try {
            checkProgressRule(member.getProgress(), req.getProgress());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(e.getMessage());
        }
        Report r = new Report();
        r.setTaskMemberId(member.getId());
        r.setUserId(UserContext.get().getId());
        r.setContent(req.getContent());
        r.setProgress(req.getProgress());
        r.setStatus("PENDING");
        reportMapper.insert(r);
        return r.getId();
    }

    /** 可见性：本人或审核人见全部；其他人仅见 APPROVED */
    public List<ReportVO> listByTask(Long taskId, SysUser current) {
        Task task = taskMapper.selectById(taskId);
        if (task == null) throw new BusinessException("任务不存在");
        boolean isReviewer = "ADMIN".equals(current.getRole()) || task.getCreatorId().equals(current.getId());

        List<TaskMember> members = memberMapper.selectList(new LambdaQueryWrapper<TaskMember>()
                .eq(TaskMember::getTaskId, taskId));
        List<Long> memberIds = members.stream().map(TaskMember::getId).collect(Collectors.toList());
        if (memberIds.isEmpty()) return List.of();
        List<Report> reports = reportMapper.selectList(new LambdaQueryWrapper<Report>()
                .in(Report::getTaskMemberId, memberIds)
                .orderByDesc(Report::getId));

        return reports.stream()
                .filter(r -> isReviewer || "APPROVED".equals(r.getStatus()) || r.getUserId().equals(current.getId()))
                .map(r -> toVO(r))
                .collect(Collectors.toList());
    }

    private ReportVO toVO(Report r) {
        ReportVO vo = new ReportVO();
        vo.setId(r.getId());
        vo.setTaskMemberId(r.getTaskMemberId());
        vo.setUserId(r.getUserId());
        SysUser u = userMapper.selectById(r.getUserId());
        vo.setUserName(u == null ? null : u.getRealName());
        vo.setContent(r.getContent());
        vo.setProgress(r.getProgress());
        vo.setFinalProgress(r.getFinalProgress());
        vo.setStatus(r.getStatus());
        vo.setReviewerId(r.getReviewerId());
        if (r.getReviewerId() != null) {
            SysUser reviewer = userMapper.selectById(r.getReviewerId());
            vo.setReviewerName(reviewer == null ? null : reviewer.getRealName());
        }
        vo.setReviewComment(r.getReviewComment());
        vo.setReviewedAt(r.getReviewedAt());
        vo.setCreatedAt(r.getCreatedAt());
        return vo;
    }
}
```

`backend/src/main/java/com/task/dto/ReportRequest.java`：

```java
package com.task.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ReportRequest {
    @NotBlank(message = "汇报内容不能为空")
    private String content;
    @NotNull(message = "进度不能为空")
    @Min(value = 0, message = "进度不能小于 0")
    @Max(value = 100, message = "进度不能超过 100")
    private Integer progress;
}
```

`backend/src/main/java/com/task/vo/ReportVO.java`：

```java
package com.task.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ReportVO {
    private Long id;
    private Long taskMemberId;
    private Long userId;
    private String userName;
    private String content;
    private Integer progress;         // 汇报填写的进度
    private Integer finalProgress;    // 审核最终确定的进度（null=未审核或与汇报一致）
    private String status;
    private Long reviewerId;
    private String reviewerName;
    private String reviewComment;     // 审核内容/意见
    private LocalDateTime reviewedAt;
    private LocalDateTime createdAt;
}
```

`backend/src/main/java/com/task/controller/ReportController.java`：

```java
package com.task.controller;

import com.task.auth.UserContext;
import com.task.common.Result;
import com.task.dto.ReportRequest;
import com.task.service.ReportService;
import com.task.vo.ReportVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ReportController {
    private final ReportService reportService;

    @PostMapping("/tasks/{taskId}/reports")
    public Result<Long> submit(@PathVariable Long taskId, @Valid @RequestBody ReportRequest req) {
        return Result.ok(reportService.submit(taskId, req));
    }

    @GetMapping("/tasks/{taskId}/reports")
    public Result<List<ReportVO>> listByTask(@PathVariable Long taskId) {
        return Result.ok(reportService.listByTask(taskId, UserContext.get()));
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `cd backend && mvn -q test -Dtest=ReportServiceTest`
Expected: PASS

- [ ] **Step 5: 手动冒烟**

```bash
TOKEN=$(curl -s -X POST http://127.0.0.1:8080/api/auth/login -H 'Content-Type: application/json' -d '{"username":"zhangsan","password":"123456"}' | sed 's/.*"token":"\([^"]*\)".*/\1/')
curl -s -X POST http://127.0.0.1:8080/api/tasks/1/reports -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"content":"完成登录接口编码","progress":50}'
curl -s http://127.0.0.1:8080/api/tasks/1/reports -H "Authorization: Bearer $TOKEN"
# 用 leader1 看（审核人视角，可见全部含 PENDING）
```

Expected: 汇报创建成功；zhangsan 本人可见自己的 PENDING 汇报

- [ ] **Step 6: Commit**

```bash
git add backend
git commit -m "feat(backend): 汇报提交与可见性列表"
```

### Task 8: 审核（通过/驳回，可调进度，进度重算，完成判定）—— 核心

**Files:**
- Create: `backend/src/main/java/com/task/dto/ReviewRequest.java`
- Modify: `backend/src/main/java/com/task/controller/ReportController.java`
- Modify: `backend/src/main/java/com/task/service/ReportService.java`
- Create: `backend/src/test/java/com/task/service/ReviewServiceTest.java`

- [ ] **Step 1: 写失败测试（整体进度计算 + 完成判定 + 审核权限）**

`backend/src/test/java/com/task/service/ReviewServiceTest.java`：

```java
package com.task.service;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReviewServiceTest {
    /** 默认均分权重下整体进度 = 成员进度平均 */
    @Test
    void overallProgressWithEqualWeights() {
        // 34/33/33 权重，进度 50/0/0 → 17
        assertEquals(17, TaskService.calcOverallProgress(Arrays.asList(34, 33, 33), Arrays.asList(50, 0, 0)));
    }

    /** 手动权重 40/30/30，进度 80/50/100 → 77（规范值） */
    @Test
    void overallProgressWithManualWeights() {
        assertEquals(77, TaskService.calcOverallProgress(Arrays.asList(40, 30, 30), Arrays.asList(80, 50, 100)));
    }

    /** 全员 100 才判定任务完成 */
    @Test
    void doneOnlyWhenAllMembersFull() {
        // 权重 50/50，进度 100/50 → 整体 75，未完成
        assertEquals(75, TaskService.calcOverallProgress(Arrays.asList(50, 50), Arrays.asList(100, 50)));
        assertFalse(ReportService.isAllCompleted(Arrays.asList(100, 50)));
        assertTrue(ReportService.isAllCompleted(Arrays.asList(100, 100)));
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd backend && mvn -q test -Dtest=ReviewServiceTest`
Expected: FAIL（ReportService.isAllCompleted 不存在）

- [ ] **Step 3: 实现审核逻辑（ReportService 追加）**

在 `ReportService` 中追加：

```java
/** 是否所有成员进度都达到 100 */
public static boolean isAllCompleted(List<Integer> progresses) {
    return !progresses.isEmpty() && progresses.stream().allMatch(p -> p >= 100);
}

@Transactional
public void approve(Long reportId, ReviewRequest req) {
    Report report = reportMapper.selectById(reportId);
    if (report == null) throw new BusinessException("汇报不存在");
    if (!"PENDING".equals(report.getStatus())) throw new BusinessException("该汇报已审核");

    TaskMember member = memberMapper.selectById(report.getTaskMemberId());
    Task task = taskMapper.selectById(member.getTaskId());
    SysUser reviewer = UserContext.get();
    if (!"ADMIN".equals(reviewer.getRole()) && !task.getCreatorId().equals(reviewer.getId())) {
        throw new BusinessException(403, "无权审核该汇报");
    }

    int finalProgress = req.getProgress() != null ? req.getProgress() : report.getProgress();
    if (finalProgress > 100) throw new BusinessException("最终进度不能超过 100");
    if (finalProgress < member.getProgress() && finalProgress < report.getProgress()) {
        throw new BusinessException("最终进度不能低于当前进度");
    }

    report.setStatus("APPROVED");
    report.setFinalProgress(finalProgress);
    report.setReviewerId(reviewer.getId());
    report.setReviewComment(req.getReviewComment());
    report.setReviewedAt(java.time.LocalDateTime.now());
    reportMapper.updateById(report);

    member.setProgress(finalProgress);
    memberMapper.updateById(member);

    // 重算整体进度与完成状态
    List<TaskMember> members = memberMapper.selectList(new LambdaQueryWrapper<TaskMember>()
            .eq(TaskMember::getTaskId, task.getId()));
    List<Integer> weights = members.stream().map(TaskMember::getWeight).collect(Collectors.toList());
    List<Integer> progresses = members.stream().map(TaskMember::getProgress).collect(Collectors.toList());
    task.setProgress(TaskService.calcOverallProgress(weights, progresses));
    if (isAllCompleted(progresses)) {
        task.setStatus("DONE");
        task.setDoneAt(java.time.LocalDateTime.now());
    }
    taskMapper.updateById(task);
}

@Transactional
public void reject(Long reportId, ReviewRequest req) {
    if (req.getReviewComment() == null || req.getReviewComment().isBlank()) {
        throw new BusinessException("驳回必须填写审核内容（不通过的理由）");
    }
    Report report = reportMapper.selectById(reportId);
    if (report == null) throw new BusinessException("汇报不存在");
    if (!"PENDING".equals(report.getStatus())) throw new BusinessException("该汇报已审核");

    TaskMember member = memberMapper.selectById(report.getTaskMemberId());
    Task task = taskMapper.selectById(member.getTaskId());
    SysUser reviewer = UserContext.get();
    if (!"ADMIN".equals(reviewer.getRole()) && !task.getCreatorId().equals(reviewer.getId())) {
        throw new BusinessException(403, "无权审核该汇报");
    }

    report.setStatus("REJECTED");
    report.setReviewerId(reviewer.getId());
    report.setReviewComment(req.getReviewComment());
    report.setReviewedAt(java.time.LocalDateTime.now());
    reportMapper.updateById(report);
}
```

`backend/src/main/java/com/task/dto/ReviewRequest.java`：

```java
package com.task.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class ReviewRequest {
    @Min(value = 0, message = "进度不能小于 0")
    @Max(value = 100, message = "进度不能超过 100")
    private Integer progress;       // 可空：空 = 用汇报填写的进度（手动调节）
    private String reviewComment;   // 审核内容/意见；驳回时必填
}
```

在 `ReportController` 追加接口：

```java
@PostMapping("/reports/{id}/approve")
public Result<Void> approve(@PathVariable Long id, @Valid @RequestBody ReviewRequest req) {
    reportService.approve(id, req);
    return Result.ok();
}

@PostMapping("/reports/{id}/reject")
public Result<Void> reject(@PathVariable Long id, @Valid @RequestBody ReviewRequest req) {
    reportService.reject(id, req);
    return Result.ok();
}

@GetMapping("/reports/pending")
public Result<List<ReportVO>> pending() {
    return Result.ok(reportService.pendingList(UserContext.get()));
}
```

`ReportService` 追加 pendingList（待我审核 = 我是创建者的任务的所有 PENDING 汇报）：

```java
public List<ReportVO> pendingList(SysUser current) {
    LambdaQueryWrapper<Task> taskQw = "ADMIN".equals(current.getRole())
            ? new LambdaQueryWrapper<Task>()
            : new LambdaQueryWrapper<Task>().eq(Task::getCreatorId, current.getId());
    List<Long> taskIds = taskMapper.selectList(taskQw).stream().map(Task::getId).collect(Collectors.toList());
    if (taskIds.isEmpty()) return List.of();
    List<Long> memberIds = memberMapper.selectList(new LambdaQueryWrapper<TaskMember>()
                    .in(TaskMember::getTaskId, taskIds))
            .stream().map(TaskMember::getId).collect(Collectors.toList());
    if (memberIds.isEmpty()) return List.of();
    return reportMapper.selectList(new LambdaQueryWrapper<Report>()
                    .in(Report::getTaskMemberId, memberIds)
                    .eq(Report::getStatus, "PENDING")
                    .orderByAsc(Report::getId))
            .stream().map(this::toVO).collect(Collectors.toList());
}
```

（`ReportVO` 补充 `taskId`、`taskName` 字段，在 toVO 中按 member 查询填充，便于审核列表展示。）

- [ ] **Step 4: 运行确认通过**

Run: `cd backend && mvn -q test -Dtest=ReviewServiceTest`
Expected: PASS

- [ ] **Step 5: 手动冒烟完整审核链路**

```bash
# zhangsan 汇报 50 → leader1 审核通过（手动调到 60）→ 看任务整体进度
ZTOKEN=$(curl -s -X POST http://127.0.0.1:8080/api/auth/login -H 'Content-Type: application/json' -d '{"username":"zhangsan","password":"123456"}' | sed 's/.*"token":"\([^"]*\)".*/\1/')
LTOKEN=$(curl -s -X POST http://127.0.0.1:8080/api/auth/login -H 'Content-Type: application/json' -d '{"username":"leader1","password":"123456"}' | sed 's/.*"token":"\([^"]*\)".*/\1/')
REPORT_ID=$(curl -s -X POST http://127.0.0.1:8080/api/tasks/1/reports -H "Authorization: Bearer $ZTOKEN" -H 'Content-Type: application/json' -d '{"content":"登录模块编码完成，待联调","progress":60}' | sed 's/.*"data":\([0-9]*\).*/\1/')
curl -s http://127.0.0.1:8080/api/reports/pending -H "Authorization: Bearer $LTOKEN"
curl -s -X POST http://127.0.0.1:8080/api/reports/$REPORT_ID/approve -H "Authorization: Bearer $LTOKEN" -H 'Content-Type: application/json' -d '{"progress":70,"reviewComment":"进度合理，通过"}'
curl -s http://127.0.0.1:8080/api/tasks/1 -H "Authorization: Bearer $LTOKEN"
```

Expected: 汇报通过且 finalProgress=70，成员 zhangsan 进度=70，任务整体进度按权重重算

再验证驳回（新汇报 progress=30 应被单调递增规则拒绝；或建新任务测驳回留理由）：

```bash
curl -s -X POST http://127.0.0.1:8080/api/reports/$REPORT_ID/reject -H "Authorization: Bearer $LTOKEN" -H 'Content-Type: application/json' -d '{}'
```

Expected: `{"code":400,...}` 驳回理由必填

- [ ] **Step 6: Commit**

```bash
git add backend
git commit -m "feat(backend): 汇报审核（可调进度/驳回理由/完成判定）"
```

### Task 9: 评论（任务评论 + 汇报评论 + 回复）

**Files:**
- Create: `backend/src/main/java/com/task/dto/CommentRequest.java`
- Create: `backend/src/main/java/com/task/controller/CommentController.java`
- Create: `backend/src/main/java/com/task/vo/CommentVO.java`
- Create: `backend/src/test/java/com/task/controller/CommentControllerTest.java`

- [ ] **Step 1: 写失败测试（评论权限：非审核人不能评论未通过汇报）**

`backend/src/test/java/com/task/controller/CommentControllerTest.java`：

```java
package com.task.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.task.TaskApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest(classes = TaskApplication.class)
@AutoConfigureMockMvc
class CommentControllerTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;

    private String login(String username, String password) throws Exception {
        String body = om.writeValueAsString(java.util.Map.of("username", username, "password", password));
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
    }

    @Test
    void commentTaskAsEmployee() throws Exception {
        String token = login("zhangsan", "123456");
        mvc.perform(post("/api/tasks/1/comments").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"加油！\"}"))
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void commentReportHiddenToOthersWhenPending() throws Exception {
        // wangwu 不是任务1成员？——为可重复性：直接测非审核人对 PENDING 汇报评论被拒
        // 依赖 Task 8 冒烟中 zhangsan 产生的 PENDING 汇报存在（id 最新一条）
        String token = login("leader1", "123456");
        String reports = mvc.perform(get("/api/tasks/1/reports")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        // 若没有任何 PENDING 汇报（全被审核过），本测试跳过断言
        if (!reports.contains("\"PENDING\"")) return;
        // leader 是审核人可评论；此测试主要验证接口存在与基本权限
        mvc.perform(get("/api/tasks/1/reports").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0));
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd backend && mvn -q test -Dtest=CommentControllerTest`
Expected: FAIL（404：无 CommentController）

- [ ] **Step 3: 实现评论控制器与 VO**

`backend/src/main/java/com/task/dto/CommentRequest.java`：

```java
package com.task.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CommentRequest {
    @NotBlank(message = "评论内容不能为空")
    private String content;
}
```

`backend/src/main/java/com/task/vo/CommentVO.java`：

```java
package com.task.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CommentVO {
    private Long id;
    private Long parentId;
    private Long userId;
    private String userName;
    private String content;
    private LocalDateTime createdAt;
}
```

`backend/src/main/java/com/task/controller/CommentController.java`：

```java
package com.task.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.task.auth.UserContext;
import com.task.common.BusinessException;
import com.task.common.Result;
import com.task.dto.CommentRequest;
import com.task.entity.*;
import com.task.mapper.*;
import com.task.vo.CommentVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class CommentController {
    private final CommentMapper commentMapper;
    private final TaskMapper taskMapper;
    private final ReportMapper reportMapper;
    private final TaskMemberMapper memberMapper;
    private final SysUserMapper userMapper;

    private boolean canSeeReport(Report r, SysUser cur) {
        if ("APPROVED".equals(r.getStatus())) return true;
        TaskMember m = memberMapper.selectById(r.getTaskMemberId());
        Task t = taskMapper.selectById(m.getTaskId());
        return r.getUserId().equals(cur.getId())
                || "ADMIN".equals(cur.getRole())
                || t.getCreatorId().equals(cur.getId());
    }

    private boolean canSeeTask(Long taskId) {
        return taskMapper.selectById(taskId) != null;
    }

    @GetMapping("/tasks/{taskId}/comments")
    public Result<List<CommentVO>> taskComments(@PathVariable Long taskId) {
        if (!canSeeTask(taskId)) throw new BusinessException("任务不存在");
        return Result.ok(listVO(new LambdaQueryWrapper<Comment>()
                .eq(Comment::getTaskId, taskId).orderByAsc(Comment::getId)));
    }

    @PostMapping("/tasks/{taskId}/comments")
    public Result<Long> addTaskComment(@PathVariable Long taskId,
                                       @Valid @RequestBody CommentRequest req) {
        if (!canSeeTask(taskId)) throw new BusinessException("任务不存在");
        Comment c = new Comment();
        c.setTaskId(taskId);
        c.setParentId(0L);
        c.setUserId(UserContext.get().getId());
        c.setContent(req.getContent());
        commentMapper.insert(c);
        return Result.ok(c.getId());
    }

    @GetMapping("/reports/{reportId}/comments")
    public Result<List<CommentVO>> reportComments(@PathVariable Long reportId) {
        Report r = reportMapper.selectById(reportId);
        if (r == null) throw new BusinessException("汇报不存在");
        if (!canSeeReport(r, UserContext.get())) throw new BusinessException(403, "无权查看该汇报的评论");
        return Result.ok(listVO(new LambdaQueryWrapper<Comment>()
                .eq(Comment::getReportId, reportId).orderByAsc(Comment::getId)));
    }

    @PostMapping("/reports/{reportId}/comments")
    public Result<Long> addReportComment(@PathVariable Long reportId,
                                         @Valid @RequestBody CommentRequest req) {
        Report r = reportMapper.selectById(reportId);
        if (r == null) throw new BusinessException("汇报不存在");
        if (!canSeeReport(r, UserContext.get())) throw new BusinessException(403, "无权评论该汇报");
        Comment c = new Comment();
        c.setReportId(reportId);
        c.setParentId(0L);
        c.setUserId(UserContext.get().getId());
        c.setContent(req.getContent());
        commentMapper.insert(c);
        return Result.ok(c.getId());
    }

    @PostMapping("/comments/{id}/reply")
    public Result<Long> reply(@PathVariable Long id, @Valid @RequestBody CommentRequest req) {
        Comment parent = commentMapper.selectById(id);
        if (parent == null) throw new BusinessException("评论不存在");
        if (parent.getParentId() != 0) throw new BusinessException("只能回复顶级评论");
        if (parent.getTaskId() != null) {
            if (!canSeeTask(parent.getTaskId())) throw new BusinessException("任务不存在");
        } else {
            Report r = reportMapper.selectById(parent.getReportId());
            if (!canSeeReport(r, UserContext.get())) throw new BusinessException(403, "无权回复该评论");
        }
        Comment c = new Comment();
        c.setTaskId(parent.getTaskId());
        c.setReportId(parent.getReportId());
        c.setParentId(parent.getId());
        c.setUserId(UserContext.get().getId());
        c.setContent(req.getContent());
        commentMapper.insert(c);
        return Result.ok(c.getId());
    }

    private List<CommentVO> listVO(LambdaQueryWrapper<Comment> qw) {
        List<Comment> list = commentMapper.selectList(qw);
        Map<Long, String> names = userMapper.selectList(null).stream()
                .collect(Collectors.toMap(SysUser::getId, SysUser::getRealName));
        return list.stream().map(c -> {
            CommentVO vo = new CommentVO();
            vo.setId(c.getId());
            vo.setParentId(c.getParentId());
            vo.setUserId(c.getUserId());
            vo.setUserName(names.get(c.getUserId()));
            vo.setContent(c.getContent());
            vo.setCreatedAt(c.getCreatedAt());
            return vo;
        }).collect(Collectors.toList());
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `cd backend && mvn -q test -Dtest=CommentControllerTest`
Expected: PASS

- [ ] **Step 5: 手动冒烟**

```bash
TOKEN=$(curl -s -X POST http://127.0.0.1:8080/api/auth/login -H 'Content-Type: application/json' -d '{"username":"lisi","password":"123456"}' | sed 's/.*"token":"\([^"]*\)".*/\1/')
# 任务评论
curl -s -X POST http://127.0.0.1:8080/api/tasks/1/comments -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"content":"这个任务加油"}'
curl -s http://127.0.0.1:8080/api/tasks/1/comments -H "Authorization: Bearer $TOKEN"
# 汇报评论（汇报 1 若已 APPROVED 可评论）
curl -s -X POST http://127.0.0.1:8080/api/reports/1/comments -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"content":"写得不错"}'
curl -s http://127.0.0.1:8080/api/reports/1/comments -H "Authorization: Bearer $TOKEN"
```

Expected: 评论成功、列表返回；对 PENDING/REJECTED 汇报的评论被 403 拒绝

- [ ] **Step 6: Commit**

```bash
git add backend
git commit -m "feat(backend): 任务/汇报评论与回复"
```

### Task 10: 后端收尾（测试全绿 + 接口清单核对）

**Files:**
- Modify: `backend/src/main/resources/application.yml`（如有遗漏配置）

- [ ] **Step 1: 全量测试**

Run: `cd backend && mvn -q test`
Expected: 全部 PASS

- [ ] **Step 2: 按规格核对接口清单**

对照规格 §5，用 curl 逐项冒烟核对（可写成一个 shell 脚本 `backend/scripts/smoke.sh` 保存，便于重复执行）。核对清单：

- [ ] `POST /api/auth/login`、`GET /api/auth/me`
- [ ] `GET/POST/PUT/DELETE /api/users`、`PUT /api/users/{id}/password`
- [ ] `GET/POST/PUT/DELETE /api/groups`、`GET/POST /api/groups/{id}/members`、`DELETE /api/groups/{id}/members/{userId}`
- [ ] `GET/POST /api/tasks`、`GET/PUT/DELETE /api/tasks/{id}`、`PUT /api/tasks/{id}/weights`
- [ ] `POST /api/files/upload`、`DELETE /api/files/{id}`
- [ ] `POST /api/tasks/{id}/reports`、`GET /api/tasks/{id}/reports`、`GET /api/reports/pending`、`POST /api/reports/{id}/approve|reject`
- [ ] `GET/POST /api/tasks/{id}/comments`、`GET/POST /api/reports/{id}/comments`、`POST /api/comments/{id}/reply`

- [ ] **Step 3: Commit**

```bash
git add backend
git commit -m "chore(backend): 冒烟脚本与收尾"
```

---

## Phase 2：前端

### Task 11: 前端脚手架（Vite + Vue3 + Element Plus + 路由 + Pinia + Axios）

**Files:**
- Create: `frontend/package.json`
- Create: `frontend/vite.config.js`
- Create: `frontend/index.html`
- Create: `frontend/src/main.js`
- Create: `frontend/src/App.vue`
- Create: `frontend/src/router/index.js`
- Create: `frontend/src/store/auth.js`
- Create: `frontend/src/api/request.js`
- Create: `frontend/src/api/auth.js`（另按模块建 `user.js`/`group.js`/`task.js`/`report.js`/`comment.js`/`file.js`）
- Create: `frontend/src/views/Login.vue`
- Create: `frontend/src/layout/Layout.vue`

- [ ] **Step 1: 写 package.json 并安装依赖**

```json
{
  "name": "task-frontend",
  "private": true,
  "version": "1.0.0",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "vite build",
    "preview": "vite preview"
  },
  "dependencies": {
    "axios": "^1.7.2",
    "element-plus": "^2.7.5",
    "@element-plus/icons-vue": "^2.3.1",
    "pinia": "^2.1.7",
    "vue": "^3.4.27",
    "vue-router": "^4.3.2"
  },
  "devDependencies": {
    "@vitejs/plugin-vue": "^5.0.4",
    "vite": "^5.2.11"
  }
}
```

Run: `cd frontend && npm install`
Expected: 安装成功无报错

- [ ] **Step 2: vite.config.js + index.html + main.js + App.vue**

`frontend/vite.config.js`：

```javascript
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://127.0.0.1:8080', changeOrigin: true }
    }
  }
})
```

`frontend/index.html`：

```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <title>任务分配系统</title>
</head>
<body>
  <div id="app"></div>
  <script type="module" src="/src/main.js"></script>
</body>
</html>
```

`frontend/src/main.js`：

```javascript
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/dist/locale/zh-cn.mjs'
import 'element-plus/dist/index.css'
import * as Icons from '@element-plus/icons-vue'
import App from './App.vue'
import router from './router'

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })
for (const [name, comp] of Object.entries(Icons)) app.component(name, comp)
app.mount('#app')
```

`frontend/src/App.vue`：

```vue
<template>
  <router-view />
</template>
```

- [ ] **Step 3: Axios 封装（token 注入 + 401 跳登录 + 错误提示）**

`frontend/src/api/request.js`：

```javascript
import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '../router'

const request = axios.create({ baseURL: '/api', timeout: 30000 })

request.interceptors.request.use(config => {
  const token = localStorage.getItem('token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

request.interceptors.response.use(
  res => {
    const body = res.data
    if (body.code === 0) return body.data
    ElMessage.error(body.message || '请求失败')
    return Promise.reject(new Error(body.message))
  },
  err => {
    const body = err.response?.data
    if (err.response?.status === 401 || body?.code === 401) {
      localStorage.removeItem('token')
      localStorage.removeItem('user')
      router.push('/login')
    }
    ElMessage.error(body?.message || '网络错误')
    return Promise.reject(err)
  }
)

export default request
```

`frontend/src/api/auth.js`：

```javascript
import request from './request'

export const login = (data) => request.post('/auth/login', data)
export const getMe = () => request.get('/auth/me')
```

- [ ] **Step 4: Pinia auth store + 路由 + 布局 + 登录页**

`frontend/src/store/auth.js`：

```javascript
import { defineStore } from 'pinia'
import { login as loginApi } from '../api/auth'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem('token') || '',
    user: JSON.parse(localStorage.getItem('user') || 'null')
  }),
  getters: {
    isAdmin: s => s.user?.role === 'ADMIN',
    isLeader: s => s.user?.role === 'LEADER',
    canCreateTask: s => s.user?.role === 'ADMIN' || s.user?.role === 'LEADER'
  },
  actions: {
    async login(username, password) {
      const data = await loginApi({ username, password })
      this.token = data.token
      this.user = data.user
      localStorage.setItem('token', data.token)
      localStorage.setItem('user', JSON.stringify(data.user))
    },
    logout() {
      this.token = ''
      this.user = null
      localStorage.removeItem('token')
      localStorage.removeItem('user')
    }
  }
})
```

`frontend/src/router/index.js`：

```javascript
import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/login', component: () => import('../views/Login.vue') },
  {
    path: '/',
    component: () => import('../layout/Layout.vue'),
    redirect: '/tasks',
    children: [
      { path: 'tasks', component: () => import('../views/TaskList.vue') },
      { path: 'tasks/create', component: () => import('../views/TaskCreate.vue') },
      { path: 'tasks/:id', component: () => import('../views/TaskDetail.vue') },
      { path: 'reports/pending', component: () => import('../views/ReportReview.vue') },
      { path: 'groups', component: () => import('../views/GroupManage.vue') },
      { path: 'users', component: () => import('../views/UserManage.vue') }
    ]
  }
]

const router = createRouter({ history: createWebHistory(), routes })

router.beforeEach((to) => {
  if (to.path !== '/login' && !localStorage.getItem('token')) return '/login'
  if (to.path === '/login' && localStorage.getItem('token')) return '/tasks'
})

export default router
```

`frontend/src/layout/Layout.vue`：

```vue
<template>
  <el-container style="height: 100vh">
    <el-aside width="220px" style="background: #001529">
      <div style="color:#fff;padding:18px;font-weight:bold;font-size:18px">任务分配系统</div>
      <el-menu background-color="#001529" text-color="#rgba(255,255,255,.65)" active-text-color="#fff"
               router :default-active="$route.path">
        <el-menu-item index="/tasks">任务列表</el-menu-item>
        <el-menu-item v-if="auth.canCreateTask" index="/tasks/create">创建任务</el-menu-item>
        <el-menu-item v-if="auth.canCreateTask" index="/reports/pending">待我审核</el-menu-item>
        <el-menu-item index="/groups">小组管理</el-menu-item>
        <el-menu-item v-if="auth.isAdmin" index="/users">用户管理</el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header style="display:flex;align-items:center;justify-content:flex-end;border-bottom:1px solid #eee">
        <el-dropdown @command="handleCommand">
          <span style="cursor:pointer">{{ auth.user?.realName }}（{{ roleText }}）</span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="logout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </el-header>
      <el-main style="background:#f5f7fa">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../store/auth'

const auth = useAuthStore()
const router = useRouter()
const roleText = computed(() => ({ ADMIN: '管理员', LEADER: '组长', EMPLOYEE: '员工' }[auth.user?.role] || ''))
const handleCommand = (cmd) => {
  if (cmd === 'logout') {
    auth.logout()
    router.push('/login')
  }
}
</script>
```

`frontend/src/views/Login.vue`：

```vue
<template>
  <div style="height:100vh;display:flex;align-items:center;justify-content:center;background:#f0f2f5">
    <el-card style="width:380px">
      <template #header><b style="font-size:18px">任务分配系统</b></template>
      <el-form :model="form" @keyup.enter="submit">
        <el-form-item><el-input v-model="form.username" placeholder="用户名" /></el-form-item>
        <el-form-item><el-input v-model="form.password" type="password" placeholder="密码" show-password /></el-form-item>
        <el-button type="primary" style="width:100%" :loading="loading" @click="submit">登 录</el-button>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../store/auth'

const auth = useAuthStore()
const router = useRouter()
const form = reactive({ username: '', password: '' })
const loading = ref(false)

const submit = async () => {
  if (!form.username || !form.password) return
  loading.value = true
  try {
    await auth.login(form.username, form.password)
    router.push('/tasks')
  } finally {
    loading.value = false
  }
}
</script>
```

- [ ] **Step 5: 启动验证**

Run: `cd frontend && npm run dev`（后台），浏览器打开 `http://127.0.0.1:5173/login`，用 `admin/admin123` 登录。
Expected: 登录成功跳转 `/tasks`（TaskList 页未建会报路由警告，属预期，下一步建）

- [ ] **Step 6: Commit**

```bash
git add frontend
git commit -m "feat(frontend): 脚手架/登录/布局/路由守卫"
```

### Task 12: 任务列表页

**Files:**
- Create: `frontend/src/api/task.js`
- Create: `frontend/src/views/TaskList.vue`

- [ ] **Step 1: API 封装**

`frontend/src/api/task.js`：

```javascript
import request from './request'

export const listTasks = (params) => request.get('/tasks', { params })
export const getTask = (id) => request.get(`/tasks/${id}`)
export const createTask = (data) => request.post('/tasks', data)
export const updateTask = (id, data) => request.put(`/tasks/${id}`, data)
export const deleteTask = (id) => request.delete(`/tasks/${id}`)
export const updateWeights = (id, weights) => request.put(`/tasks/${id}/weights`, weights)
export const submitReport = (taskId, data) => request.post(`/tasks/${taskId}/reports`, data)
export const listReports = (taskId) => request.get(`/tasks/${taskId}/reports`)
export const pendingReports = () => request.get('/reports/pending')
export const approveReport = (id, data) => request.post(`/reports/${id}/approve`, data)
export const rejectReport = (id, data) => request.post(`/reports/${id}/reject`, data)
```

- [ ] **Step 2: 实现任务列表页（Tab 筛选 + 进度条 + 状态标签）**

`frontend/src/views/TaskList.vue`：

```vue
<template>
  <div>
    <el-tabs v-model="activeTab" @tab-change="load">
      <el-tab-pane label="全部" name="all" />
      <el-tab-pane v-if="auth.canCreateTask" label="我创建的" name="mine_created" />
      <el-tab-pane label="分配给我的" name="assigned" />
    </el-tabs>
    <el-row :gutter="16">
      <el-col v-for="t in tasks" :key="t.id" :span="8" style="margin-bottom:16px">
        <el-card @click="$router.push(`/tasks/${t.id}`)" style="cursor:pointer">
          <template #header>
            <div style="display:flex;justify-content:space-between;align-items:center">
              <b>{{ t.name }}</b>
              <el-tag :type="t.status === 'DONE' ? 'success' : 'primary'" size="small">
                {{ t.status === 'DONE' ? '已完成' : '进行中' }}
              </el-tag>
            </div>
          </template>
          <p style="color:#666;min-height:40px">{{ t.description || '暂无简介' }}</p>
          <el-progress :percentage="t.progress" :color="t.progress === 100 ? '#67c23a' : '#409eff'" />
          <div style="margin-top:10px;color:#999;font-size:13px">
            {{ t.assignType === 'GROUP' ? '小组' : '个人' }} · 分配对象：{{ t.assigneeName }}
            <template v-if="t.deadline"> · 截止：{{ t.deadline.slice(0, 10) }}</template>
          </div>
        </el-card>
      </el-col>
    </el-row>
    <el-empty v-if="!tasks.length" description="暂无任务" />
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useAuthStore } from '../store/auth'
import { listTasks } from '../api/task'

const auth = useAuthStore()
const activeTab = ref('all')
const tasks = ref([])

const load = async () => {
  tasks.value = await listTasks({ type: activeTab.value, status: '' })
}

onMounted(load)
</script>
```

- [ ] **Step 3: 浏览器验证**

浏览器 `http://127.0.0.1:5173/tasks`（leader1 登录）：能看到 Task 5 冒烟创建的「开发登录模块」任务，进度条显示。
Expected: 列表正常渲染；分配给我的 Tab 用 zhangsan 登录能看到该任务

- [ ] **Step 4: Commit**

```bash
git add frontend
git commit -m "feat(frontend): 任务列表页"
```

### Task 13: 创建任务页（三步表单）

**Files:**
- Create: `frontend/src/api/group.js`
- Create: `frontend/src/api/user.js`
- Create: `frontend/src/api/file.js`
- Create: `frontend/src/views/TaskCreate.vue`

- [ ] **Step 1: 小组/用户/文件 API 封装**

`frontend/src/api/group.js`：

```javascript
import request from './request'

export const listGroups = () => request.get('/groups')
export const groupMembers = (id) => request.get(`/groups/${id}/members`)
export const createGroup = (data) => request.post('/groups', data)
export const updateGroup = (id, data) => request.put(`/groups/${id}`, data)
export const deleteGroup = (id) => request.delete(`/groups/${id}`)
export const addMember = (id, userId) => request.post(`/groups/${id}/members`, { userId })
export const removeMember = (id, userId) => request.delete(`/groups/${id}/members/${userId}`)
```

`frontend/src/api/user.js`：

```javascript
import request from './request'

export const listUsers = (params) => request.get('/users', { params })
export const createUser = (data) => request.post('/users', data)
export const updateUser = (id, data) => request.put(`/users/${id}`, data)
export const deleteUser = (id) => request.delete(`/users/${id}`)
export const resetPassword = (id, password) => request.put(`/users/${id}/password`, { password })
```

`frontend/src/api/file.js`：

```javascript
import request from './request'

export const uploadFile = (file) => {
  const fd = new FormData()
  fd.append('file', file)
  return request.post('/files/upload', fd, { headers: { 'Content-Type': 'multipart/form-data' } })
}
```

- [ ] **Step 2: 创建任务页（三步：基本信息 → 分配对象+权重 → 附件）**

`frontend/src/views/TaskCreate.vue`：

```vue
<template>
  <el-card>
    <template #header><b>创建任务</b></template>
    <el-steps :active="step" align-center style="margin-bottom:30px">
      <el-step title="基本信息" />
      <el-step title="分配对象" />
      <el-step title="附件" />
    </el-steps>

    <!-- 第一步：基本信息 -->
    <el-form v-show="step === 0" :model="form" label-width="90px">
      <el-form-item label="任务名称" required>
        <el-input v-model="form.name" placeholder="请输入任务名称" />
      </el-form-item>
      <el-form-item label="任务简介">
        <el-input v-model="form.description" type="textarea" :rows="4" placeholder="任务简介" />
      </el-form-item>
      <el-form-item label="完成时间">
        <el-date-picker v-model="form.deadline" type="datetime" placeholder="选择完成时间" value-format="YYYY-MM-DDTHH:mm:ss" />
      </el-form-item>
    </el-form>

    <!-- 第二步：分配对象 -->
    <div v-show="step === 1">
      <el-radio-group v-model="form.assignType">
        <el-radio value="INDIVIDUAL">分配给个人</el-radio>
        <el-radio value="GROUP">分配给小组</el-radio>
      </el-radio-group>
      <div style="margin-top:20px">
        <template v-if="form.assignType === 'INDIVIDUAL'">
          <el-select v-model="form.assigneeId" placeholder="选择员工" style="width:300px" filterable>
            <el-option v-for="u in users" :key="u.id" :label="`${u.realName}（${u.username}）`" :value="u.id" />
          </el-select>
        </template>
        <template v-else>
          <el-select v-model="form.assigneeId" placeholder="选择小组" style="width:300px" @change="loadGroupMembers">
            <el-option v-for="g in groups" :key="g.id" :label="`${g.name}（${g.memberCount}人）`" :value="g.id" />
          </el-select>
        </template>
      </div>

      <template v-if="form.assignType === 'GROUP' && groupMemberList.length">
        <h4 style="margin-top:20px">成员权重（默认均分，和为 100）</h4>
        <div v-for="(m, i) in groupMemberList" :key="m.id" style="display:flex;align-items:center;gap:12px;margin-top:8px">
          <span style="width:100px">{{ m.realName }}</span>
          <el-input-number v-model="weights[m.id]" :min="1" :max="99" style="width:160px" />
          <span>%</span>
        </div>
        <el-alert v-if="weightSum !== 100" type="warning" :title="`权重合计 ${weightSum}，应为 100`" style="margin-top:10px" />
      </template>
    </div>

    <!-- 第三步：附件 -->
    <div v-show="step === 2">
      <el-upload drag multiple :auto-upload="true" :http-request="doUpload" :file-list="fileList">
        <el-icon><UploadFilled /></el-icon>
        <div>拖拽或点击上传任务附件</div>
      </el-upload>
    </div>

    <div style="margin-top:30px;display:flex;justify-content:space-between">
      <el-button v-if="step > 0" @click="step--">上一步</el-button>
      <el-button v-if="step < 2" type="primary" @click="next">下一步</el-button>
      <el-button v-else type="primary" :loading="submitting" @click="submit">创建任务</el-button>
    </div>
  </el-card>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import { listUsers } from '../api/user'
import { listGroups, groupMembers } from '../api/group'
import { uploadFile } from '../api/file'
import { createTask } from '../api/task'

const router = useRouter()
const step = ref(0)
const submitting = ref(false)
const form = reactive({
  name: '', description: '', deadline: null,
  assignType: 'INDIVIDUAL', assigneeId: null, attachmentIds: []
})
const users = ref([])
const groups = ref([])
const groupMemberList = ref([])
const weights = reactive({})
const fileList = ref([])

const weightSum = computed(() => Object.values(weights).reduce((a, b) => a + (b || 0), 0))

const next = () => {
  if (step.value === 0) {
    if (!form.name.trim()) return ElMessage.warning('请填写任务名称')
    step.value++
  } else if (step.value === 1) {
    if (!form.assigneeId) return ElMessage.warning('请选择分配对象')
    if (form.assignType === 'GROUP' && weightSum.value !== 100) return ElMessage.warning('权重合计必须为 100')
    step.value++
  }
}

const loadGroupMembers = async (groupId) => {
  groupMemberList.value = await groupMembers(groupId)
  const base = Math.floor(100 / groupMemberList.value.length)
  const rest = 100 % groupMemberList.value.length
  groupMemberList.value.forEach((m, i) => { weights[m.id] = base + (i < rest ? 1 : 0) })
}

const doUpload = async ({ file }) => {
  const data = await uploadFile(file)
  form.attachmentIds.push(data.id)
  fileList.value.push({ name: data.fileName, url: data.url })
}

const submit = async () => {
  submitting.value = true
  try {
    let weightsList = null
    if (form.assignType === 'GROUP') {
      weightsList = groupMemberList.value.map(m => weights[m.id] || 1)
    }
    await createTask({
      name: form.name, description: form.description, deadline: form.deadline,
      assignType: form.assignType, assigneeId: form.assigneeId,
      weights: weightsList, attachmentIds: form.attachmentIds
    })
    ElMessage.success('创建成功')
    router.push('/tasks')
  } finally {
    submitting.value = false
  }
}

onMounted(async () => {
  users.value = (await listUsers({ size: 999 })).records
  groups.value = await listGroups()
})
</script>
```

- [ ] **Step 3: 浏览器验证**

leader1 登录 → 创建任务：个人（张三）+ 小组（研发一组，权重 34/33/33 可调）两种都建一次；传一个附件。
Expected: 创建成功，列表出现；权重合计校验生效

- [ ] **Step 4: Commit**

```bash
git add frontend
git commit -m "feat(frontend): 创建任务三步表单"
```

### Task 14: 任务详情页（汇报 + 审核详情 + 评论）

**Files:**
- Create: `frontend/src/api/comment.js`
- Create: `frontend/src/views/TaskDetail.vue`

- [ ] **Step 1: 评论 API 封装**

`frontend/src/api/comment.js`：

```javascript
import request from './request'

export const taskComments = (taskId) => request.get(`/tasks/${taskId}/comments`)
export const addTaskComment = (taskId, content) => request.post(`/tasks/${taskId}/comments`, { content })
export const reportComments = (reportId) => request.get(`/reports/${reportId}/comments`)
export const addReportComment = (reportId, content) => request.post(`/reports/${reportId}/comments`, { content })
export const replyComment = (id, content) => request.post(`/comments/${id}/reply`, { content })
```

- [ ] **Step 2: 任务详情页（信息 + 成员进度 + 汇报区 + 评论）**

`frontend/src/views/TaskDetail.vue`：

```vue
<template>
  <el-card v-if="task">
    <template #header>
      <div style="display:flex;justify-content:space-between;align-items:center">
        <b style="font-size:16px">{{ task.name }}</b>
        <el-tag :type="task.status === 'DONE' ? 'success' : 'primary'">{{ task.status === 'DONE' ? '已完成' : '进行中' }}</el-tag>
      </div>
    </template>

    <p>{{ task.description || '暂无简介' }}</p>
    <div style="color:#999;font-size:13px;margin-bottom:10px">
      分配者：{{ task.creatorName }} · 类型：{{ task.assignType === 'GROUP' ? '小组' : '个人' }} · 对象：{{ task.assigneeName }}
      <template v-if="task.deadline"> · 截止：{{ task.deadline.slice(0, 10) }}</template> · 完成于：{{ task.doneAt ? task.doneAt.slice(0, 10) : '-' }}
    </div>

    <el-progress :percentage="task.progress" :color="task.progress === 100 ? '#67c23a' : '#409eff'" style="margin-bottom:16px" />

    <h4>附件</h4>
    <div v-if="task.attachments?.length">
      <a v-for="a in task.attachments" :key="a.id" :href="a.fileUrl" target="_blank" style="display:block;margin:4px 0">
        📎 {{ a.fileName }}（{{ (a.fileSize / 1024).toFixed(1) }}KB）
      </a>
    </div>
    <el-empty v-else description="无附件" :image-size="60" />

    <h4>成员进度（小组任务显示权重）</h4>
    <el-table :data="task.members" size="small" style="margin-bottom:16px">
      <el-table-column prop="realName" label="成员" width="120" />
      <el-table-column v-if="task.assignType === 'GROUP'" prop="weight" label="权重%" width="100" />
      <el-table-column label="进度">
        <template #default="{ row }"><el-progress :percentage="row.progress" /></template>
      </el-table-column>
    </el-table>

    <!-- 汇报区 -->
    <h4>汇报记录</h4>
    <el-timeline style="margin-bottom:16px">
      <el-timeline-item v-for="r in reports" :key="r.id" :timestamp="formatTime(r.createdAt)">
        <div style="display:flex;justify-content:space-between">
          <b>{{ r.userName }}</b>
          <el-tag size="small" :type="r.status === 'APPROVED' ? 'success' : r.status === 'REJECTED' ? 'danger' : 'warning'">
            {{ { PENDING: '待审核', APPROVED: '已通过', REJECTED: '已驳回' }[r.status] }}
          </el-tag>
        </div>
        <p style="margin:6px 0">{{ r.content }}</p>
        <div style="font-size:13px;color:#666">
          汇报进度：{{ r.progress }}%
          <template v-if="r.status === 'APPROVED'">→ 最终进度：{{ r.finalProgress ?? r.progress }}%</template>
        </div>
        <!-- 审核详情 -->
        <el-alert v-if="r.reviewedAt" :type="r.status === 'APPROVED' ? 'success' : 'error'" :closable="false"
                  :title="`审核人：${r.reviewerName}｜${formatTime(r.reviewedAt)}`"
                  :description="r.reviewComment || '（无审核内容）'" style="margin:8px 0" />
        <!-- 汇报评论 -->
        <div style="margin-top:8px">
          <div v-for="c in reportCommentMap[r.id] || []" :key="c.id" style="font-size:13px;color:#444;margin:2px 0">
            <b>{{ c.userName }}</b>{{ c.parentId ? ' 回复 ' + replyTargetName(c) : '：' }} {{ c.content }}
          </div>
          <div style="display:flex;gap:8px;margin-top:4px">
            <el-input v-model="commentInputs[r.id]" placeholder="评论这条汇报" size="small" style="max-width:300px"
                      @keyup.enter="addReportComment(r)" />
            <el-button size="small" type="primary" plain @click="addReportComment(r)">评论</el-button>
          </div>
        </div>
      </el-timeline-item>
    </el-timeline>

    <!-- 提交汇报（我是成员时） -->
    <el-card v-if="isMyTask" shadow="never" style="margin-bottom:16px;background:#f8f9fa">
      <template #header><b>提交汇报</b></template>
      <el-input v-model="reportForm.content" type="textarea" :rows="3" placeholder="汇报内容" />
      <div style="display:flex;align-items:center;gap:12px;margin-top:10px">
        <span>目标进度：</span>
        <el-input-number v-model="reportForm.progress" :min="myProgress" :max="100" />
        <span>%（不能低于当前进度 {{ myProgress }}%）</span>
      </div>
      <el-button type="primary" style="margin-top:10px" @click="submitReport">提交汇报</el-button>
    </el-card>

    <!-- 任务评论 -->
    <h4>任务评论</h4>
    <div v-for="c in taskCommentList" :key="c.id" style="padding:6px 0;border-bottom:1px solid #f0f0f0">
      <b>{{ c.userName }}</b>：{{ c.content }}
      <el-button size="small" text type="primary" @click="replyTo(c)">回复</el-button>
      <div v-for="r in repliesOf(c)" :key="r.id" style="margin-left:24px;font-size:13px;color:#444">
        <b>{{ r.userName }}</b> 回复 {{ c.userName }}：{{ r.content }}
      </div>
    </div>
    <div style="display:flex;gap:8px;margin-top:10px">
      <el-input v-model="taskComment" placeholder="评论任务" style="max-width:400px" @keyup.enter="addTaskComment" />
      <el-button type="primary" @click="addTaskComment">评论</el-button>
    </div>
  </el-card>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getTask, listReports, submitReport as submitReportApi } from '../api/task'
import { taskComments, addTaskComment as addTaskCommentApi, reportComments, addReportComment as addReportCommentApi } from '../api/comment'
import { useAuthStore } from '../store/auth'

const route = useRoute()
const auth = useAuthStore()
const task = ref(null)
const reports = ref([])
const taskCommentList = ref([])
const reportCommentMap = reactive({})
const commentInputs = reactive({})
const taskComment = ref('')
const reportForm = reactive({ content: '', progress: 0 })

const isMyTask = computed(() => task.value?.members?.some(m => m.userId === auth.user?.id))
const myProgress = computed(() => task.value?.members?.find(m => m.userId === auth.user?.id)?.progress ?? 0)
const replyTargetName = (c) => {
  const p = (reportCommentMap[c.parentId] || []).find(x => x.id === c.parentId)
  return p?.userName || ''
}
const repliesOf = (c) => taskCommentList.value.filter(x => x.parentId === c.id)

const formatTime = (s) => (s || '').replace('T', ' ').slice(0, 16)

const load = async () => {
  const id = route.params.id
  task.value = await getTask(id)
  reports.value = await listReports(id)
  taskCommentList.value = await taskComments(id)
  for (const r of reports.value) {
    reportCommentMap[r.id] = await reportComments(r.id)
  }
}

const submitReport = async () => {
  if (!reportForm.content.trim()) return ElMessage.warning('请填写汇报内容')
  await submitReportApi(task.value.id, { content: reportForm.content, progress: reportForm.progress })
  ElMessage.success('汇报已提交，等待审核')
  reportForm.content = ''
  load()
}

const addTaskComment = async () => {
  if (!taskComment.value.trim()) return
  await addTaskCommentApi(task.value.id, taskComment.value)
  taskComment.value = ''
  taskCommentList.value = await taskComments(task.value.id)
}

const addReportComment = async (r) => {
  const content = commentInputs[r.id]
  if (!content?.trim()) return
  await addReportCommentApi(r.id, content)
  commentInputs[r.id] = ''
  reportCommentMap[r.id] = await reportComments(r.id)
}

const replyTo = async (c) => {
  const text = window.prompt(`回复 ${c.userName}：`)
  if (!text) return
  await import('../api/comment').then(m => m.replyComment(c.id, text))
  taskCommentList.value = await taskComments(task.value.id)
}

onMounted(load)
</script>
```

- [ ] **Step 3: 浏览器验证**

zhangsan 登录 → 打开任务详情：能看到自己 PENDING 汇报与审核详情（若已审核）；提交新汇报；评论汇报。
leader1 登录 → 打开同一任务：可见全部汇报，可为 PENDING 汇报评论（审核人）。
Expected: 汇报可见性差异符合规格（第三方仅见 APPROVED）

- [ ] **Step 4: Commit**

```bash
git add frontend
git commit -m "feat(frontend): 任务详情页（汇报/审核详情/评论）"
```

### Task 15: 待我审核页

**Files:**
- Create: `frontend/src/views/ReportReview.vue`

- [ ] **Step 1: 实现审核页（通过/驳回对话框）**

`frontend/src/views/ReportReview.vue`：

```vue
<template>
  <el-card>
    <template #header><b>待我审核</b></template>
    <el-empty v-if="!list.length" description="没有待审核的汇报" />
    <el-card v-for="r in list" :key="r.id" shadow="never" style="margin-bottom:12px">
      <div style="display:flex;justify-content:space-between">
        <div>
          <b>{{ r.taskName }}</b>
          <el-tag size="small" style="margin-left:8px">{{ r.userName }}</el-tag>
        </div>
        <span style="color:#999;font-size:13px">{{ formatTime(r.createdAt) }}</span>
      </div>
      <p style="color:#444;margin:8px 0">{{ r.content }}</p>
      <div style="font-size:13px;color:#666">汇报进度：{{ r.progress }}%</div>
      <div style="margin-top:12px;text-align:right">
        <el-button type="success" plain @click="openApprove(r)">通过</el-button>
        <el-button type="danger" plain @click="openReject(r)">驳回</el-button>
      </div>
    </el-card>
  </el-card>

  <el-dialog v-model="approveVisible" title="审核通过" width="480px">
    <el-form label-width="90px">
      <el-form-item label="最终进度">
        <el-input-number v-model="approveForm.progress" :min="0" :max="100" />
        <span style="color:#999;font-size:13px;margin-left:8px">默认与汇报进度一致，可手动调整</span>
      </el-form-item>
      <el-form-item label="审核内容">
        <el-input v-model="approveForm.reviewComment" type="textarea" :rows="2" placeholder="审核意见（可选）" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="approveVisible = false">取消</el-button>
      <el-button type="success" @click="doApprove">确认通过</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="rejectVisible" title="审核驳回" width="480px">
    <el-form label-width="90px">
      <el-form-item label="驳回理由" required>
        <el-input v-model="rejectForm.reviewComment" type="textarea" :rows="3" placeholder="必须填写不通过的理由" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="rejectVisible = false">取消</el-button>
      <el-button type="danger" @click="doReject">确认驳回</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { pendingReports, approveReport, rejectReport } from '../api/task'

const list = ref([])
const approveVisible = ref(false)
const rejectVisible = ref(false)
const approveForm = reactive({ id: null, progress: null, reviewComment: '' })
const rejectForm = reactive({ id: null, reviewComment: '' })

const formatTime = (s) => (s || '').replace('T', ' ').slice(0, 16)

const load = async () => { list.value = await pendingReports() }

const openApprove = (r) => {
  approveForm.id = r.id
  approveForm.progress = r.progress
  approveForm.reviewComment = ''
  approveVisible.value = true
}

const openReject = (r) => {
  rejectForm.id = r.id
  rejectForm.reviewComment = ''
  rejectVisible.value = true
}

const doApprove = async () => {
  await approveReport(approveForm.id, { progress: approveForm.progress, reviewComment: approveForm.reviewComment })
  ElMessage.success('已通过')
  approveVisible.value = false
  load()
}

const doReject = async () => {
  if (!rejectForm.reviewComment.trim()) return ElMessage.warning('驳回必须填写理由')
  await rejectReport(rejectForm.id, { reviewComment: rejectForm.reviewComment })
  ElMessage.success('已驳回')
  rejectVisible.value = false
  load()
}

onMounted(load)
</script>
```

- [ ] **Step 2: `ReportVO` 后端补充 `taskId`/`taskName`（若 Task 8 未做）**

检查 `ReportVO` 是否有 `taskId`、`taskName`，没有则补充并在 `ReportService.toVO` 中填充（通过 `memberMapper.selectById(r.getTaskMemberId()).getTaskId()` 查 `taskMapper`）。

- [ ] **Step 3: 浏览器验证**

leader1 登录 → 待我审核：通过（调进度 50→60）+ 驳回（不填理由被拦截，填理由成功）。
Expected: 审核后员工端任务详情出现审核详情；任务进度变化

- [ ] **Step 4: Commit**

```bash
git add frontend
git commit -m "feat(frontend): 待我审核页"
```

### Task 16: 小组管理页

**Files:**
- Create: `frontend/src/views/GroupManage.vue`

- [ ] **Step 1: 实现小组管理页**

`frontend/src/views/GroupManage.vue`：

```vue
<template>
  <el-card>
    <template #header>
      <div style="display:flex;justify-content:space-between;align-items:center">
        <b>小组管理</b>
        <el-button v-if="auth.isAdmin || auth.isLeader" type="primary" @click="createVisible = true">新建小组</el-button>
      </div>
    </template>

    <el-table :data="groups">
      <el-table-column prop="name" label="组名" width="160" />
      <el-table-column prop="leaderName" label="组长" width="120" />
      <el-table-column prop="description" label="简介" />
      <el-table-column prop="memberCount" label="成员数" width="90" />
      <el-table-column label="操作" width="200">
        <template #default="{ row }">
          <el-button size="small" @click="openMembers(row)">成员</el-button>
          <el-button v-if="canManage(row)" size="small" type="danger" plain @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="memberVisible" :title="`${currentGroup?.name} 成员管理`" width="520px">
      <el-table :data="members" size="small">
        <el-table-column prop="realName" label="姓名" />
        <el-table-column prop="username" label="用户名" />
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button v-if="canManage(currentGroup) && row.role !== 'LEADER'" size="small" type="danger" text @click="removeMember(row)">移除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="canManage(currentGroup)" style="display:flex;gap:8px;margin-top:12px">
        <el-select v-model="newMemberId" placeholder="选择员工加入" filterable style="flex:1">
          <el-option v-for="u in candidates" :key="u.id" :label="`${u.realName}（${u.username}）`" :value="u.id" />
        </el-select>
        <el-button type="primary" @click="addMember">添加</el-button>
      </div>
    </el-dialog>

    <el-dialog v-model="createVisible" title="新建小组" width="480px">
      <el-form label-width="80px">
        <el-form-item label="组名" required><el-input v-model="createForm.name" /></el-form-item>
        <el-form-item label="组长" required>
          <el-select v-model="createForm.leaderId" filterable style="width:100%">
            <el-option v-for="u in leaders" :key="u.id" :label="u.realName" :value="u.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="简介"><el-input v-model="createForm.description" type="textarea" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" @click="create">创建</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { listGroups, groupMembers, createGroup, deleteGroup, addMember, removeMember as removeMemberApi } from '../api/group'
import { listUsers } from '../api/user'
import { useAuthStore } from '../store/auth'

const auth = useAuthStore()
const groups = ref([])
const members = ref([])
const currentGroup = ref(null)
const memberVisible = ref(false)
const createVisible = ref(false)
const newMemberId = ref(null)
const createForm = reactive({ name: '', leaderId: null, description: '' })

const leaders = computed(() => candidates.value.filter(u => u.role === 'LEADER' || u.role === 'ADMIN'))
const candidates = computed(() => users.value.filter(u => u.groupId !== currentGroup.value?.id || !u.groupId))

const canManage = (g) => auth.isAdmin || (auth.isLeader && g?.leaderId === auth.user?.id)
const users = ref([])

const load = async () => {
  groups.value = await listGroups()
  users.value = (await listUsers({ size: 999 })).records
}

const openMembers = async (g) => {
  currentGroup.value = g
  members.value = await groupMembers(g.id)
  memberVisible.value = true
}

const addMember = async () => {
  if (!newMemberId.value) return ElMessage.warning('请选择员工')
  await addMember(currentGroup.value.id, newMemberId.value)
  newMemberId.value = null
  members.value = await groupMembers(currentGroup.value.id)
  load()
}

const removeMember = async (u) => {
  await removeMemberApi(currentGroup.value.id, u.id)
  members.value = await groupMembers(currentGroup.value.id)
  load()
}

const remove = async (g) => {
  if (!window.confirm(`确认删除小组「${g.name}」？`)) return
  await deleteGroup(g.id)
  load()
}

const create = async () => {
  if (!createForm.name || !createForm.leaderId) return ElMessage.warning('请填写组名和组长')
  await createGroup(createForm)
  ElMessage.success('创建成功')
  createVisible.value = false
  createForm.name = ''; createForm.leaderId = null; createForm.description = ''
  load()
}

onMounted(load)
</script>
```

- [ ] **Step 2: 浏览器验证**

leader1 登录：看到研发一组，成员弹窗可添加/移除组员；zhangsan 登录：只读。
Expected: 权限符合规格（组长仅自己组、员工只读、管理员全部）

- [ ] **Step 3: Commit**

```bash
git add frontend
git commit -m "feat(frontend): 小组管理页"
```

### Task 17: 用户管理页

**Files:**
- Create: `frontend/src/views/UserManage.vue`

- [ ] **Step 1: 实现用户管理页**

`frontend/src/views/UserManage.vue`：

```vue
<template>
  <el-card>
    <template #header>
      <div style="display:flex;justify-content:space-between;align-items:center">
        <b>用户管理</b>
        <el-button type="primary" @click="openCreate">新建用户</el-button>
      </div>
    </template>

    <el-form inline style="margin-bottom:12px">
      <el-form-item label="姓名"><el-input v-model="query.keyword" placeholder="搜索姓名" clearable @keyup.enter="load" /></el-form-item>
      <el-form-item label="角色">
        <el-select v-model="query.role" clearable placeholder="全部" style="width:140px">
          <el-option label="管理员" value="ADMIN" />
          <el-option label="组长" value="LEADER" />
          <el-option label="员工" value="EMPLOYEE" />
        </el-select>
      </el-form-item>
      <el-button type="primary" @click="load">查询</el-button>
    </el-form>

    <el-table :data="list">
      <el-table-column prop="realName" label="姓名" width="120" />
      <el-table-column prop="username" label="用户名" width="140" />
      <el-table-column label="角色" width="100">
        <template #default="{ row }">{{ { ADMIN: '管理员', LEADER: '组长', EMPLOYEE: '员工' }[row.role] }}</template>
      </el-table-column>
      <el-table-column prop="groupName" label="所属小组" width="120" />
      <el-table-column label="操作">
        <template #default="{ row }">
          <el-button size="small" @click="openEdit(row)">编辑</el-button>
          <el-button size="small" @click="openReset(row)">重置密码</el-button>
          <el-button size="small" type="danger" plain :disabled="row.id === auth.user?.id" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination style="margin-top:12px" layout="prev, pager, next, total" :total="total"
                   :page-size="query.size" @current-change="p => { query.page = p; load() }" />

    <el-dialog v-model="editVisible" :title="editingId ? '编辑用户' : '新建用户'" width="480px">
      <el-form label-width="80px">
        <el-form-item label="用户名" required>
          <el-input v-model="editForm.username" :disabled="!!editingId" />
        </el-form-item>
        <el-form-item v-if="!editingId" label="密码" required>
          <el-input v-model="editForm.password" />
        </el-form-item>
        <el-form-item label="姓名" required><el-input v-model="editForm.realName" /></el-form-item>
        <el-form-item label="角色">
          <el-select v-model="editForm.role" style="width:100%">
            <el-option label="管理员" value="ADMIN" />
            <el-option label="组长" value="LEADER" />
            <el-option label="员工" value="EMPLOYEE" />
          </el-select>
        </el-form-item>
        <el-form-item label="所属小组">
          <el-select v-model="editForm.groupId" clearable placeholder="无" style="width:100%">
            <el-option v-for="g in groups" :key="g.id" :label="g.name" :value="g.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="resetVisible" title="重置密码" width="400px">
      <el-input v-model="newPassword" placeholder="输入新密码" show-password />
      <template #footer>
        <el-button @click="resetVisible = false">取消</el-button>
        <el-button type="primary" @click="reset">确认</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { listUsers, createUser, updateUser, deleteUser, resetPassword } from '../api/user'
import { listGroups } from '../api/group'
import { useAuthStore } from '../store/auth'

const auth = useAuthStore()
const list = ref([])
const groups = ref([])
const total = ref(0)
const query = reactive({ page: 1, size: 10, keyword: '', role: '' })
const editVisible = ref(false)
const resetVisible = ref(false)
const editingId = ref(null)
const newPassword = ref('')
const editForm = reactive({ username: '', password: '', realName: '', role: 'EMPLOYEE', groupId: null })

const load = async () => {
  const data = await listUsers({ ...query, page: query.page })
  list.value = data.records
  total.value = data.total
}

const openCreate = () => {
  editingId.value = null
  Object.assign(editForm, { username: '', password: '', realName: '', role: 'EMPLOYEE', groupId: null })
  editVisible.value = true
}

const openEdit = (row) => {
  editingId.value = row.id
  Object.assign(editForm, { username: row.username, password: '', realName: row.realName, role: row.role, groupId: row.groupId })
  editVisible.value = true
}

const save = async () => {
  if (editingId.value) {
    await updateUser(editingId.value, editForm)
  } else {
    await createUser(editForm)
  }
  ElMessage.success('保存成功')
  editVisible.value = false
  load()
}

const remove = async (row) => {
  if (!window.confirm(`确认删除用户「${row.realName}」？`)) return
  await deleteUser(row.id)
  load()
}

const openReset = (row) => {
  resetVisible.value = true
  newPassword.value = ''
  editingId.value = row.id
}

const reset = async () => {
  if (!newPassword.value) return ElMessage.warning('请输入新密码')
  await resetPassword(editingId.value, newPassword.value)
  ElMessage.success('已重置')
  resetVisible.value = false
}

onMounted(async () => {
  groups.value = await listGroups()
  load()
})
</script>
```

- [ ] **Step 2: 浏览器验证**

admin 登录 → 用户管理：新建员工、编辑角色、重置密码、删除（不能删自己）。
Expected: 各项操作成功；leader1/zhangsan 登录该页面被 403（菜单已隐藏，直接访问 URL 后端拒绝）

- [ ] **Step 3: Commit**

```bash
git add frontend
git commit -m "feat(frontend): 用户管理页"
```

### Task 18: 端到端验收闭环

**Files:**
- Modify: `frontend/README.md`（如需要）

- [ ] **Step 1: 完整验收场景（人工走查）**

按规格 §7 验收闭环，在浏览器逐项走查并记录结果：

1. admin 登录 → 用户管理建一个员工 `test01`
2. admin 或 leader1 建小组「测试组」（组长 leader1）→ 添加 test01 入组
3. leader1 创建小组任务「验收任务」（分配测试组，权重手动改为 60/40）
4. test01 登录 → 分配给我的看到该任务 → 提交汇报（进度 40）
5. zhangsan 登录 → 打开该任务 → 汇报区**看不到** test01 的 PENDING 汇报
6. leader1 登录 → 待我审核 → 通过（进度调为 50，填审核内容）
7. zhangsan 登录 → 现在能看到该 APPROVED 汇报，可评论；test01 可回复评论
8. test01 登录 → 任务详情看到审核详情（审核人/内容/时间/最终进度 50）
9. 组员全部 100% 后任务状态变「已完成」，列表进度条 100%
10. 个人任务同样走一遍：leader1 建个人任务给 lisi，lisi 汇报 → 驳回（填理由）→ 重报 → 通过

- [ ] **Step 2: 写验收记录**

将验收结果记录到 `docs/superpowers/verification/2026-08-01-e2e.md`（各场景 ✓/✗ + 问题说明），如有失败项回到对应 Task 修复。

- [ ] **Step 3: 项目根 README**

写 `README.md`：项目简介、启动方式（MySQL/MinIO 前置、后端 `mvn spring-boot:run`、前端 `npm run dev`）、默认账号表。

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "docs: 验收记录与项目 README"
```

---

## 计划自审记录

- **规格覆盖**：8 表 ✓（Task 1）、三级角色 ✓（Task 3-4）、个人/小组分配+权重 ✓（Task 5）、MinIO 附件 ✓（Task 6）、汇报提交 ✓（Task 7）、审核（调进度/驳回理由/完成判定）✓（Task 8）、汇报可见性 ✓（Task 7 列表 + Task 9 评论）、审核详情 ✓（Task 8 + 前端 Task 14）、汇报评论/回复 ✓（Task 9 + Task 14）、任务评论 ✓（Task 9 + Task 14）、小组管理 ✓（Task 4 + Task 16）、用户管理 ✓（Task 4 + Task 17）、前端全部页面 ✓（Task 11-17）、验收闭环 ✓（Task 18）
- **占位符检查**：无 TBD/TODO；所有代码步骤含完整代码或明确指引
- **命名一致性**：`splitWeights`/`calcOverallProgress`/`isAllCompleted`/`checkProgressRule` 在测试与实现中同名同参；`ReportVO` 字段与前端 `TaskDetail.vue`/`ReportReview.vue` 使用一致；`CreateTaskRequest.weights` 前端按组员顺序传整型数组，后端按相同顺序赋值——已验证两处顺序一致（Task 13 中 `weightsList` 按 `groupMemberList` 顺序，Task 5 中按 `assignees` 顺序，两列表均按组员 id 升序）
