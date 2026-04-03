package com.star.common;

import lombok.Data;
import java.io.Serializable;

/**
 * 后端统一返回结果封装类
 * @param <T>
 */
@Data
public class Result<T> implements Serializable {

    private Integer code; // 编码：1代表成功，0代表失败
    private String msg;   // 错误信息
    private T data;       // 返回给前端的真实数据

    // 成功（不带数据）
    public static <T> Result<T> success() {
        Result<T> result = new Result<T>();
        result.code = 1;
        return result;
    }

    // 成功（带数据返回）
    public static <T> Result<T> success(T object) {
        Result<T> result = new Result<T>();
        result.data = object;
        result.code = 1;
        return result;
    }

    // 失败（带失败信息）
    public static <T> Result<T> error(String msg) {
        Result<T> result = new Result<T>();
        result.msg = msg;
        result.code = 0;
        return result;
    }
}
