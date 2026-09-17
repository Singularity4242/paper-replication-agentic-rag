package com.example.paperassistant.common.exception;

/** 上传文件不满足文件名、非空等存储要求；不负责判断能否解析。 */
public class InvalidDocumentException extends RuntimeException {
    public InvalidDocumentException(String message) {
        super(message);
    }
}
