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
