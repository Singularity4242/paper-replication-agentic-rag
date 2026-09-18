import { CloudUploadOutlined, FileTextOutlined, InfoCircleOutlined, ReloadOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, App, Button, Drawer, Empty, Spin, Table, Tag, Upload } from 'antd';
import { useState } from 'react';
import { errorMessage } from '../../../shared/api/http';
import { ErrorState, Loading } from '../../../shared/components/Feedback';
import { bytesLabel, dateLabel } from '../../../shared/utils/format';
import { documentsApi } from '../api';
import { documentsQuery } from '../queries';
import { indexLabels, isProcessing, type ResearchDocument } from '../types';
import styles from './DocumentsPanel.module.css';
export function IndexStatus({ status }: { status: string }) {
  return (
    <Tag
      variant="filled"
      color={
        (
          {
            INDEXED: 'success',
            PROCESSING: 'processing',
            QUEUED: 'default',
            FAILED: 'error',
            UNSUPPORTED: 'warning',
          } as Record<string, string>
        )[status]
      }
    >
      {indexLabels[status] || status}
    </Tag>
  );
}
export function DocumentsPanel({ libraryId }: { libraryId: number }) {
  const documents = useQuery(documentsQuery(libraryId));
  const client = useQueryClient();
  const { message } = App.useApp();
  const [detailId, setDetailId] = useState<number>();
  const detail = useQuery({
    queryKey: ['document-detail', libraryId, detailId],
    queryFn: () => documentsApi.detail(libraryId, detailId as number),
    enabled: !!detailId,
    refetchInterval: (q) => (q.state.data && isProcessing(q.state.data.document.indexStatus) ? 3000 : false),
  });
  const [uploads, setUploads] = useState<
    Record<string, { name: string; status: 'uploading' | 'done' | 'error'; error?: string }>
  >({});
  const refresh = () =>
    Promise.all([
      client.invalidateQueries({ queryKey: ['documents', libraryId] }),
      client.invalidateQueries({ queryKey: ['document-detail', libraryId] }),
    ]);
  const retry = useMutation({
    mutationFn: (id: number) => documentsApi.retry(libraryId, id),
    onSuccess: async () => {
      await refresh();
      message.success('已重新加入解析队列');
    },
    onError: (e) => message.error(errorMessage(e)),
  });
  const upload = async (file: File) => {
    const uid = crypto.randomUUID();
    if (file.size === 0) {
      message.error(`${file.name} 是空文件`);
      return;
    }
    if (file.size > 20 * 1024 * 1024) {
      message.error(`${file.name} 超过当前单文件 20 MB 限制`);
      return;
    }
    setUploads((old) => ({ ...old, [uid]: { name: file.name, status: 'uploading' } }));
    try {
      await documentsApi.upload(libraryId, file);
      setUploads((old) => ({ ...old, [uid]: { name: file.name, status: 'done' } }));
      await refresh();
    } catch (e) {
      setUploads((old) => ({ ...old, [uid]: { name: file.name, status: 'error', error: errorMessage(e) } }));
    }
  };
  return (
    <section className={styles.panel}>
      <div className={styles.heading}>
        <div>
          <h2>收集资料，连接想法。</h2>
          <p>文件上传后自动解析。支持解析的资料就绪后，即可用于问答。</p>
        </div>
        <Button icon={<ReloadOutlined />} onClick={() => documents.refetch()} aria-label="刷新文件列表" />
      </div>
      <Upload.Dragger
        multiple
        showUploadList={false}
        beforeUpload={(file) => {
          void upload(file);
          return false;
        }}
        className={styles.dropzone}
      >
        <div className={styles.uploadIcon}>
          <CloudUploadOutlined />
        </div>
        <p className={styles.uploadTitle}>拖入文件，或点击上传</p>
        <p className={styles.uploadHint}>PDF、Markdown、代码、配置文件等 · 单文件不超过 20 MB</p>
        <p className={styles.uploadNote}>允许上传各种类型；不支持解析的文件仍会保存在资料库中。</p>
      </Upload.Dragger>
      {Object.entries(uploads).length > 0 && (
        <div className={styles.uploads}>
          {Object.entries(uploads).map(([id, value]) => (
            <div key={id}>
              <span>{value.name}</span>
              {value.status === 'uploading' ? (
                <span>
                  <Spin size="small" /> 正在上传
                </span>
              ) : value.status === 'done' ? (
                <span className={styles.uploadDone}>上传完成</span>
              ) : (
                <span role="alert" className={styles.uploadError}>
                  {value.error}
                </span>
              )}
            </div>
          ))}
          {!Object.values(uploads).some((u) => u.status === 'uploading') && (
            <Button size="small" type="text" onClick={() => setUploads({})}>
              收起上传记录
            </Button>
          )}
        </div>
      )}
      <div className={styles.listHeading}>
        <h3>
          全部文件 <span>{documents.data?.length ?? 0}</span>
        </h3>
        <span>解析状态会自动更新</span>
      </div>
      {documents.isPending ? (
        <Loading />
      ) : documents.isError ? (
        <ErrorState error={documents.error} retry={() => documents.refetch()} />
      ) : (
        <Table<ResearchDocument>
          rowKey="id"
          size="middle"
          dataSource={documents.data}
          pagination={{ pageSize: 10, hideOnSinglePage: true }}
          scroll={{ x: 650 }}
          locale={{
            emptyText: (
              <Empty
                image={Empty.PRESENTED_IMAGE_SIMPLE}
                description="资料库还是空的，上传一份资料开始吧。"
              />
            ),
          }}
          columns={[
            {
              title: '文件名称',
              dataIndex: 'originalFilename',
              render: (value: string, row) => (
                <button type="button" className={styles.fileName} onClick={() => setDetailId(row.id)}>
                  <FileTextOutlined />
                  <span>{value}</span>
                </button>
              ),
            },
            { title: '大小', width: 90, dataIndex: 'fileSize', render: bytesLabel },
            {
              title: '解析状态',
              dataIndex: 'indexStatus',
              width: 135,
              render: (status) => <IndexStatus status={status} />,
            },
            { title: '上传日期', dataIndex: 'createdAt', width: 100, render: dateLabel },
            {
              title: '操作',
              width: 105,
              render: (_, row) =>
                ['FAILED', 'UNSUPPORTED'].includes(row.indexStatus) ? (
                  <Button
                    type="link"
                    size="small"
                    loading={retry.isPending && retry.variables === row.id}
                    onClick={() => retry.mutate(row.id)}
                  >
                    重新解析
                  </Button>
                ) : (
                  <Button
                    size="small"
                    type="text"
                    icon={<InfoCircleOutlined />}
                    aria-label={`查看 ${row.originalFilename} 详情`}
                    onClick={() => setDetailId(row.id)}
                  />
                ),
            },
          ]}
        />
      )}
      <Drawer title="资料详情" open={!!detailId} onClose={() => setDetailId(undefined)}>
        {detail.isPending ? (
          <Loading />
        ) : detail.isError ? (
          <ErrorState error={detail.error} retry={() => detail.refetch()} />
        ) : (
          detail.data && (
            <div className={styles.detail}>
              <FileTextOutlined className={styles.detailIcon} />
              <h3>{detail.data.document.originalFilename}</h3>
              <IndexStatus status={detail.data.document.indexStatus} />
              <dl>
                <dt>文件大小</dt>
                <dd>{bytesLabel(detail.data.document.fileSize)}</dd>
                <dt>上传时间</dt>
                <dd>{new Date(detail.data.document.createdAt).toLocaleString('zh-CN')}</dd>
                <dt>解析尝试</dt>
                <dd>{detail.data.task?.attemptCount ?? 0} 次</dd>
              </dl>
              {detail.data.task?.errorMessage && (
                <Alert
                  title={detail.data.task.errorMessage}
                  type={detail.data.document.indexStatus === 'UNSUPPORTED' ? 'info' : 'warning'}
                  showIcon
                />
              )}
              {detail.data.document.indexStatus === 'UNSUPPORTED' && (
                <p className="muted">当前解析器不支持此文件。文件已保存，可在支持能力更新后重新解析。</p>
              )}
              {['FAILED', 'UNSUPPORTED'].includes(detail.data.document.indexStatus) && (
                <Button onClick={() => retry.mutate(detail.data.document.id)} loading={retry.isPending}>
                  重新解析
                </Button>
              )}
            </div>
          )
        )}
      </Drawer>
    </section>
  );
}
