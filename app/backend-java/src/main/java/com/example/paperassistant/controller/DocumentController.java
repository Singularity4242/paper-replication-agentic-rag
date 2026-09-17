package com.example.paperassistant.controller;

import com.example.paperassistant.common.exception.FileStorageException;
import com.example.paperassistant.common.response.ApiResponse;
import com.example.paperassistant.model.dto.DocumentDTO;
import com.example.paperassistant.model.dto.DocumentUploadDTO;
import com.example.paperassistant.model.vo.DocumentVO;
import com.example.paperassistant.service.DocumentService;
import jakarta.validation.constraints.Positive;
import java.io.IOException;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@Profile("postgres")
@RequestMapping("/api/libraries/{libraryId}/documents")
public class DocumentController {
    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<DocumentVO> uploadDocument(@PathVariable @Positive long libraryId,
                                            @RequestPart("file") MultipartFile file) {
        try (var input = file.getInputStream()) {
            return ApiResponse.success(toVO(documentService.uploadDocument(libraryId,
                    new DocumentUploadDTO(file.getOriginalFilename(), file.getSize(), input))));
        } catch (IOException exception) {
            throw new FileStorageException(exception);
        }
    }

    @GetMapping
    public ApiResponse<List<DocumentVO>> listDocuments(@PathVariable @Positive long libraryId) {
        return ApiResponse.success(documentService.listDocuments(libraryId).stream().map(this::toVO).toList());
    }

    private DocumentVO toVO(DocumentDTO document) {
        return new DocumentVO(document.id(), document.libraryId(), document.originalFilename(), document.fileSize(),
                document.sha256(), document.status(), document.indexStatus(), document.gmtCreate(), document.gmtModified());
    }
}
