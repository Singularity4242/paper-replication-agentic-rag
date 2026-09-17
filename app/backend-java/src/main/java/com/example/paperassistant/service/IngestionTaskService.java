package com.example.paperassistant.service;

import com.example.paperassistant.client.RagClient;
import com.example.paperassistant.client.RagClientException;
import com.example.paperassistant.model.dataobject.IngestionTaskDO;
import java.util.Optional;

/** 后台任务生命周期；每次状态转换与文档状态在同一事务中提交。 */
public interface IngestionTaskService {
    Optional<IngestionTaskDO> claim();
    boolean complete(IngestionTaskDO task, RagClient.Result result);
    boolean fail(IngestionTaskDO task, RagClientException error);
}
