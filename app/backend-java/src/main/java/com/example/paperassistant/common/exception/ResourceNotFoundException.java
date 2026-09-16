package com.example.paperassistant.common.exception;

/** 请求操作的业务资源不存在。 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
