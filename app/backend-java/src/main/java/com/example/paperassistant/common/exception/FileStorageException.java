package com.example.paperassistant.common.exception;

/** 本地存储失败，对外响应不暴露实际路径或底层异常。 */
public class FileStorageException extends RuntimeException {
    public FileStorageException(Throwable cause) {
        super("资料文件保存失败", cause);
    }
}
