package com.example.paperassistant.dao;

import com.example.paperassistant.model.dataobject.DocumentDO;
import java.util.List;

/** 资料元数据访问；同库去重由业务检查和数据库唯一约束共同保证。 */
public interface DocumentDAO {
    boolean existsByLibraryAndSha256(long libraryId, String sha256);

    DocumentDO insertDocument(DocumentDO document);

    List<DocumentDO> listDocuments(long libraryId);
}
