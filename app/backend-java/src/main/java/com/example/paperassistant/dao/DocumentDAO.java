package com.example.paperassistant.dao;

import com.example.paperassistant.model.dataobject.DocumentDO;
import java.util.List;
import java.util.Optional;

/** 资料元数据访问；同库去重由业务检查和数据库唯一约束共同保证。 */
public interface DocumentDAO {
    boolean existsByLibraryAndSha256(long libraryId, String sha256);

    DocumentDO insertDocument(DocumentDO document);

    Optional<DocumentDO> findById(long id);

    void updateIndex(long id, String status, String ragDocumentId);

    List<DocumentDO> listDocuments(long libraryId);
}
