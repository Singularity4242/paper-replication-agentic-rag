package com.example.paperassistant.common.exception;

/** 实际文件大小超限。 */
public class DocumentTooLargeException extends RuntimeException {
    public DocumentTooLargeException() {
        super("上传文件超过大小限制");
    }
}
