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
