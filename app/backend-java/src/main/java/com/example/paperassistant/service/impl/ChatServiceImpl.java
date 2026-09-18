package com.example.paperassistant.service.impl;

import com.example.paperassistant.common.exception.ChatNotReadyException;
import com.example.paperassistant.common.exception.ConflictException;
import com.example.paperassistant.common.exception.ResourceNotFoundException;
import com.example.paperassistant.dao.DocumentDAO;
import com.example.paperassistant.dao.LibraryDAO;
import com.example.paperassistant.model.dto.ChatRequestDTO;
import com.example.paperassistant.model.dto.ChatScopeDTO;
import com.example.paperassistant.service.ChatService;
import java.util.ArrayList;
import java.util.HashSet;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("postgres")
public class ChatServiceImpl implements ChatService {
    private final LibraryDAO libraries;
    private final DocumentDAO documents;

    public ChatServiceImpl(LibraryDAO libraries, DocumentDAO documents) {
        this.libraries = libraries;
        this.documents = documents;
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ChatScopeDTO prepare(long libraryId, ChatRequestDTO request) {
        if (!libraries.existsById(libraryId)) {
            throw new ResourceNotFoundException("资料库不存在");
        }
        var all = documents.listDocuments(libraryId);
        if (request.documentIds() != null) {
            var selected = new HashSet<>(request.documentIds());
            if (!all.stream().map(d -> d.id()).collect(java.util.stream.Collectors.toSet()).containsAll(selected)) {
                throw new ResourceNotFoundException("指定文档不存在或不属于当前资料库");
            }
            all = all.stream().filter(d -> selected.contains(d.id())).toList();
        }
        var ready = new ArrayList<ChatScopeDTO.Document>();
        var excluded = new ArrayList<ChatScopeDTO.ExcludedDocument>();
        for (var doc : all) {
            if ("INDEXED".equals(doc.indexStatus()) && doc.ragDocumentId() != null && !doc.ragDocumentId().isBlank()) {
                ready.add(new ChatScopeDTO.Document(doc.id(), doc.ragDocumentId(), doc.sha256(), doc.originalFilename()));
            } else {
                excluded.add(new ChatScopeDTO.ExcludedDocument(doc.id(), doc.indexStatus()));
            }
        }
        if (request.documentIds() != null && !excluded.isEmpty()) {
            throw new ChatNotReadyException("DOCUMENTS_NOT_READY", "指定文档尚不可查询，请查看处理状态", excluded);
        }
        if (ready.isEmpty()) {
            throw new ChatNotReadyException("NO_INDEXED_DOCUMENTS", "当前资料库没有完成索引的文档", excluded);
        }
        if (ready.size() > 500) {
            throw new ConflictException("CHAT_SCOPE_TOO_LARGE", "本次最多查询 500 份文档，请指定文档缩小范围");
        }
        return new ChatScopeDTO(libraryId, request.question().strip(), ready, excluded);
    }
}
