package com.example.paperassistant.service;

import com.example.paperassistant.model.dto.DocumentDTO;
import com.example.paperassistant.model.dto.DocumentUploadDTO;
import java.util.List;
import com.example.paperassistant.model.dto.IngestionTaskDTO;
import com.example.paperassistant.model.dto.DocumentDetailDTO;

/** 文件保存和元数据管理，上传时自动创建后台索引任务。 */
public interface DocumentService {
    DocumentDTO uploadDocument(long libraryId, DocumentUploadDTO upload);

    DocumentDetailDTO getDetail(long libraryId, long documentId);

    DocumentDTO getDocument(long libraryId, long documentId);

    IngestionTaskDTO getTask(long libraryId, long documentId);

    IngestionTaskDTO retry(long libraryId, long documentId);

    List<DocumentDTO> listDocuments(long libraryId);
}
