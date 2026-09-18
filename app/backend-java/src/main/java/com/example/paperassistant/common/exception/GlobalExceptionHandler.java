package com.example.paperassistant.common.exception;

import com.example.paperassistant.common.response.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 将已知的输入错误、资源不存在转为一致的 HTTP 错误响应。 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ChatNotReadyException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<?> handleChatNotReady(ChatNotReadyException exception) {
        return new ApiResponse<>(exception.getCode(), exception.getMessage(),
                java.util.Map.of("documents", exception.getDocuments()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleValidation(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getDefaultMessage())
                .sorted()
                .findFirst()
                .orElse("请求参数不合法");
        return ApiResponse.failure("INVALID_ARGUMENT", message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleUnreadableBody(HttpMessageNotReadableException exception) {
        return ApiResponse.failure("INVALID_ARGUMENT", "请求体缺失或 JSON 格式不合法");
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleMethodValidation(HandlerMethodValidationException exception) {
        return ApiResponse.failure("INVALID_ARGUMENT", "请求参数不合法，请检查论文库 ID、名称和描述");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        return ApiResponse.failure("INVALID_ARGUMENT", "论文库 ID 必须为有效的正整数");
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleNotFound(ResourceNotFoundException exception) {
        return ApiResponse.failure("NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<Void> handleConflict(ConflictException exception) {
        return ApiResponse.failure(exception.getCode(), exception.getMessage());
    }

    @ExceptionHandler(InvalidDocumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleInvalidPaper(InvalidDocumentException exception) {
        return ApiResponse.failure("INVALID_FILE", exception.getMessage());
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleMissingFile(MissingServletRequestPartException exception) {
        return ApiResponse.failure("INVALID_FILE", "请通过 file 字段上传文件");
    }

    @ExceptionHandler({MaxUploadSizeExceededException.class, DocumentTooLargeException.class})
    @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    public ApiResponse<Void> handleLargeUpload(Exception exception) {
        return ApiResponse.failure("FILE_TOO_LARGE", "上传文件或请求超过大小限制");
    }

    @ExceptionHandler(FileStorageException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleStorage(FileStorageException exception) {
        LOGGER.error("资料文件保存失败", exception);
        return ApiResponse.failure("FILE_STORAGE_ERROR", "资料文件保存失败，请稍后重试");
    }
}
