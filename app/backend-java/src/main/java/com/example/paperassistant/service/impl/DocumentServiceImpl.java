package com.example.paperassistant.service.impl;

import com.example.paperassistant.common.exception.ConflictException;
import com.example.paperassistant.common.exception.ResourceNotFoundException;
import com.example.paperassistant.dao.LibraryDAO;
import com.example.paperassistant.dao.DocumentDAO;
import com.example.paperassistant.model.dataobject.DocumentDO;
import com.example.paperassistant.model.dto.DocumentDTO;
import com.example.paperassistant.model.dto.DocumentUploadDTO;
import com.example.paperassistant.service.DocumentService;
import com.example.paperassistant.storage.LocalDocumentStorage;
import com.example.paperassistant.storage.StoredDocument;
import java.util.List;
import com.example.paperassistant.dao.IngestionTaskDAO;
import com.example.paperassistant.model.dto.IngestionTaskDTO;
import com.example.paperassistant.model.dto.DocumentDetailDTO;
import org.springframework.transaction.annotation.Isolation;
import com.example.paperassistant.model.dataobject.IngestionTaskDO;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Profile("postgres")
public class DocumentServiceImpl implements DocumentService {
    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentServiceImpl.class);
    private final LibraryDAO libraryDAO;
    private final DocumentDAO documentDAO;
    private final IngestionTaskDAO taskDAO;
    private final LocalDocumentStorage storage;
    private final TransactionTemplate transactionTemplate;

    public DocumentServiceImpl(LibraryDAO libraryDAO, DocumentDAO documentDAO, LocalDocumentStorage storage,
                            PlatformTransactionManager transactionManager, IngestionTaskDAO taskDAO) {
        this.libraryDAO = libraryDAO;
        this.documentDAO = documentDAO;
        this.taskDAO = taskDAO;
        this.storage = storage;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public DocumentDTO uploadDocument(long libraryId, DocumentUploadDTO upload) {
        requireLibrary(libraryId);
        // 流式保存和哈希计算在数据库事务外，避免上传期间长时间占用锁。
        StoredDocument stored = storage.store(upload);
        AtomicBoolean cleanupRegistered = new AtomicBoolean(false);
        try {
            return transactionTemplate.execute(transaction -> {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (status == STATUS_ROLLED_BACK) {
                            storage.discard(stored);
                        } else if (status == STATUS_UNKNOWN) {
                            // 提交结果不确定时保留文件，避免误删已提交记录指向的文件。
                            LOGGER.error("资料事务状态不确定，需要核对记录与文件：{}", stored.storageKey());
                        }
                    }
                });
                cleanupRegistered.set(true);
                // 第一次存在性检查后可能发生删库；必须在短写事务内重新确认并锁定。
                if (!libraryDAO.lockById(libraryId)) {
                    throw new ResourceNotFoundException("论文库不存在：" + libraryId);
                }
                if (documentDAO.existsByLibraryAndSha256(libraryId, stored.sha256())) {
                    throw new ConflictException("DUPLICATE_DOCUMENT", "该文件已存在于此论文库");
                }
                DocumentDO saved = documentDAO.insertDocument(new DocumentDO(null, libraryId, stored.originalFilename(),
                        stored.storageKey(), stored.size(), stored.sha256(), "UPLOADED", "QUEUED", null, null, null, null));
                taskDAO.enqueue(saved.id());
                return toDTO(saved);
            });
        } catch (RuntimeException exception) {
            // 连事务都未能开始时，也必须清理已经保存的文件。
            if (!cleanupRegistered.get()) {
                storage.discard(stored);
            }
            throw exception;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<DocumentDTO> listDocuments(long libraryId) {
        requireLibrary(libraryId);
        return documentDAO.listDocuments(libraryId).stream().map(this::toDTO).toList();
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public DocumentDetailDTO getDetail(long libraryId, long documentId) {
        // 详情中的文档和任务使用同一数据库快照，避免轮询时显示相互矛盾的状态。
        var document = requireDocument(libraryId, documentId);
        var task = taskDAO.findByDocumentId(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("处理任务不存在"));
        return new DocumentDetailDTO(toDTO(document), toTaskDTO(task));
    }

    @Override
    @Transactional(readOnly = true)
    public DocumentDTO getDocument(long libraryId, long documentId) {
        return toDTO(requireDocument(libraryId, documentId));
    }

    @Override
    @Transactional(readOnly = true)
    public IngestionTaskDTO getTask(long libraryId, long documentId) {
        requireDocument(libraryId, documentId);
        return toTaskDTO(taskDAO.findByDocumentId(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("处理任务不存在")));
    }

    @Override
    @Transactional
    public IngestionTaskDTO retry(long libraryId, long documentId) {
        requireDocument(libraryId, documentId);
        if (!taskDAO.retry(documentId)) {
            throw new ConflictException("TASK_NOT_RETRYABLE", "仅处理失败或不支持的资料可重试");
        }
        documentDAO.updateIndex(documentId, "QUEUED", null);
        return toTaskDTO(taskDAO.findByDocumentId(documentId).orElseThrow());
    }

    private DocumentDO requireDocument(long libraryId, long documentId) {
        return documentDAO.findById(documentId).filter(document -> document.libraryId() == libraryId)
                .orElseThrow(() -> new ResourceNotFoundException("资料不存在于此论文库"));
    }

    private IngestionTaskDTO toTaskDTO(IngestionTaskDO task) {
        return new IngestionTaskDTO(task.id(), task.status(), task.attemptCount(), task.nextAttemptAt(),
                task.errorCode(), task.errorMessage(), task.startedAt(), task.finishedAt());
    }

    private void requireLibrary(long id) {
        if (!libraryDAO.existsById(id)) {
            throw new ResourceNotFoundException("论文库不存在：" + id);
        }
    }

    private DocumentDTO toDTO(DocumentDO document) {
        return new DocumentDTO(document.id(), document.libraryId(), document.originalFilename(), document.fileSize(),
                document.sha256(), document.status(), document.indexStatus(), document.ragDocumentId(), document.indexedAt(), document.gmtCreate(), document.gmtModified());
    }
}
