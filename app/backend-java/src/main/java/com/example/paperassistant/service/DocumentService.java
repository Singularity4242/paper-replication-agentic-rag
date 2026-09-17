package com.example.paperassistant.service;

import com.example.paperassistant.model.dto.DocumentDTO;
import com.example.paperassistant.model.dto.DocumentUploadDTO;
import java.util.List;

/** 文件保存和元数据管理，本阶段不调用 Python 或生成向量。 */
public interface DocumentService {
    DocumentDTO uploadDocument(long libraryId, DocumentUploadDTO upload);

    List<DocumentDTO> listDocuments(long libraryId);
}
