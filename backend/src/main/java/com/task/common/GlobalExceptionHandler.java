package com.task.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

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

    /** 缺少 multipart file 或 query 参数 → 400（避免 500） */
    @ExceptionHandler({MissingServletRequestPartException.class, MissingServletRequestParameterException.class})
    public Result<Void> handleMissingParam(Exception e) {
        return Result.fail(400, "缺少参数");
    }

    /** 请求不是 multipart（真实容器中缺 file 参数时的实际异常）→ 400（避免 500） */
    @ExceptionHandler(org.springframework.web.multipart.MultipartException.class)
    public Result<Void> handleMultipart(org.springframework.web.multipart.MultipartException e) {
        return Result.fail(400, "缺少参数");
    }

    /** 请求体不是合法 JSON → 400（避免 500） */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleUnreadable(HttpMessageNotReadableException e) {
        return Result.fail(400, "请求体格式错误");
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleOther(Exception e) {
        log.error("系统异常", e);
        return Result.fail(500, "系统异常");
    }
}
