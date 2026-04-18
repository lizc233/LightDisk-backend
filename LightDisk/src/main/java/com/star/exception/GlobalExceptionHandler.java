package com.star.exception;

import com.star.common.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器：把后端的“崩溃”翻译成前端看得懂的“人话”
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * 捕获我们自定义的业务异常 BusinessException
     */
    @ExceptionHandler(BusinessException.class)
    public Result<String> handleBusinessException(BusinessException e) {
        log.error("业务异常告警: {}", e.getMessage());
        // 返回 200 或者自定义的错误码，但 Result 里的 code 要体现出失败
        return Result.error(e.getMessage());
    }

    /**
     * 捕获其他的系统级异常 (防止直接给前端吐堆栈信息)
     */
    @ExceptionHandler(Exception.class)
    public Result<String> handleException(Exception e) {
        log.error("系统发生未知异常", e);
        return Result.error("系统繁忙，请稍后再试");
    }
}