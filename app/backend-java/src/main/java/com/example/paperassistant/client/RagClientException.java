package com.example.paperassistant.client;

/** 仅包含可向用户展示的固定错误描述，不转发上游响应或密钥。 */
public class RagClientException extends RuntimeException {
    private final String code;
    private final boolean retryable;
    public RagClientException(String code, String message, boolean retryable) {
        super(message);
        this.code = code;
        this.retryable = retryable;
    }
    public String code() { return code; }
    public boolean retryable() { return retryable; }
}
