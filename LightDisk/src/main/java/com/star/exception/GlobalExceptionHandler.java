package com.star.exception;

import com.star.common.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

/**
 * 全局异常处理，统一返回前端可识别的错误结构。
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    private static final String REDIS_ERROR_MESSAGE = "Redis 连接失败，请检查 Docker 端口映射和 127.0.0.1:6379 是否可用";

    @Value("${spring.servlet.multipart.max-file-size:1MB}")
    private String maxFileSize;

    @ExceptionHandler(BusinessException.class)
    public Result<String> handleBusinessException(BusinessException e) {
        log.error("业务异常: {}", e.getMessage());
        return Result.error(e.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Result<String> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException e) {
        log.warn("上传文件超过服务端限制: {}", maxFileSize, e);
        return Result.error("上传文件过大，当前单文件上限为 " + maxFileSize);
    }

    @ExceptionHandler(MultipartException.class)
    public Result<String> handleMultipartException(MultipartException e) {
        if (e.getCause() instanceof MaxUploadSizeExceededException ||
                (e.getMessage() != null && e.getMessage().contains("Maximum upload size exceeded"))) {
            log.warn("multipart 请求超过上传限制: {}", maxFileSize, e);
            return Result.error("上传文件过大，当前单文件上限为 " + maxFileSize);
        }

        log.error("multipart 请求解析失败", e);
        return Result.error("文件上传请求解析失败，请稍后再试");
    }

    @ExceptionHandler(RedisConnectionFailureException.class)
    public Result<String> handleRedisConnectionFailureException(RedisConnectionFailureException e) {
        log.error("Redis 连接失败", e);
        return Result.error(REDIS_ERROR_MESSAGE);
    }

    @ExceptionHandler(DataAccessResourceFailureException.class)
    public Result<String> handleDataAccessResourceFailureException(DataAccessResourceFailureException e) {
        if (isRedisConnectionProblem(e)) {
            log.error("Redis 资源访问失败", e);
            return Result.error(REDIS_ERROR_MESSAGE);
        }

        log.error("数据资源访问失败", e);
        return Result.error("数据资源访问失败，请稍后再试");
    }

    @ExceptionHandler(Exception.class)
    public Result<String> handleException(Exception e) {
        if (isRedisConnectionProblem(e)) {
            log.error("检测到 Redis 连接异常", e);
            return Result.error(REDIS_ERROR_MESSAGE);
        }

        log.error("系统发生未知异常", e);
        return Result.error("系统繁忙，请稍后再试");
    }

    private boolean isRedisConnectionProblem(Throwable throwable) {
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth < 10) {
            if (current instanceof RedisConnectionFailureException) {
                return true;
            }

            String className = current.getClass().getName();
            String message = current.getMessage();
            if ((className != null && className.contains("RedisConnectionException")) ||
                    (message != null && (message.contains("Unable to connect to Redis")
                            || message.contains("Unable to connect to 127.0.0.1:6379")
                            || message.contains("Connection refused")))) {
                return true;
            }

            current = current.getCause();
            depth++;
        }
        return false;
    }
}
