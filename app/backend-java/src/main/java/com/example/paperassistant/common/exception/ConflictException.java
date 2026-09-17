package com.example.paperassistant.common.exception;

/** 与当前业务状态冲突，例如重复资料、删除非空库。 */
public class ConflictException extends RuntimeException {
    private final String code;

    public ConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
