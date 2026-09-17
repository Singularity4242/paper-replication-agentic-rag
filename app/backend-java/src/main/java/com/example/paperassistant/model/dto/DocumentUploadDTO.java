package com.example.paperassistant.model.dto;

import java.io.InputStream;

/** 上传输入；输入流由调用方负责关闭，避免业务层依赖 MultipartFile。 */
public record DocumentUploadDTO(String originalFilename, long declaredSize, InputStream inputStream) {
}
