import {
  DeleteOutlined,
  EditOutlined,
  FileTextOutlined,
  MessageOutlined,
  MoreOutlined,
  PlusOutlined,
} from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { App, Button, Dropdown, Segmented } from 'antd';
import { useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { ConversationPanel } from '../features/conversations/components/ConversationPanel';
import { NewConversationDialog } from '../features/conversations/components/NewConversationDialog';
import { DocumentsPanel } from '../features/documents/components/DocumentsPanel';
import { documentsQuery } from '../features/documents/queries';
import { isProcessing } from '../features/documents/types';
import { librariesApi } from '../features/libraries/api';
import { LibraryDialog } from '../features/libraries/components/LibraryDialog';
import { librariesQuery } from '../features/libraries/queries';
import { errorMessage } from '../shared/api/http';
import { ErrorState, Loading } from '../shared/components/Feedback';
import styles from './LibraryWorkspacePage.module.css';
export default function LibraryWorkspacePage() {
  const params = useParams();
  const libraryId = Number(params.libraryId);
  const [search, setSearch] = useSearchParams();
  const conversationId = Number(search.get('conversation')) || 0;
  const tab = search.get('tab') === 'files' ? 'files' : 'chat';
  const libraries = useQuery(librariesQuery);
  const documents = useQuery({
    ...documentsQuery(libraryId),
    enabled: Number.isSafeInteger(libraryId) && libraryId > 0,
  });
  const library = libraries.data?.find((l) => l.id === libraryId);
  const [editing, setEditing] = useState(false);
  const [creating, setCreating] = useState(false);
  const { modal, message } = App.useApp();
  const client = useQueryClient();
  const navigate = useNavigate();
  const remove = useMutation({
    mutationFn: () => librariesApi.remove(libraryId),
    onSuccess: async () => {
      await client.invalidateQueries({ queryKey: ['libraries'] });
      navigate('/');
      message.success('资料库已删除');
    },
  });
  const changeTab = (value: string) =>
    setSearch((prev) => {
      const next = new URLSearchParams(prev);
      next.set('tab', value);
      return next;
    });
  const selectConversation = (id: number) => setSearch({ tab: 'chat', conversation: String(id) });
  const deleteLibrary = () =>
    modal.confirm({
      title: `删除「${library?.name}」？`,
      content: documents.data?.length
        ? '当前资料库包含文件，暂时不能删除非空资料库。'
        : '仅支持删除没有文件和会话的资料库。删除后无法恢复。',
      okText: '删除资料库',
      cancelText: '取消',
      okButtonProps: { danger: true, disabled: (documents.data?.length || 0) > 0 },
      onOk: async () => {
        try {
          await remove.mutateAsync();
        } catch (error) {
          message.error(errorMessage(error));
          throw error;
        }
      },
    });
  if (libraries.isPending) return <Loading />;
  if (libraries.isError) return <ErrorState error={libraries.error} retry={() => libraries.refetch()} />;
  if (!library)
    return (
      <div className="feedback">
        <h2>这个资料库不存在</h2>
        <Link to="/">返回全部资料库</Link>
      </div>
    );
  const docs = documents.data || [];
  const ready = docs.filter((d) => d.indexStatus === 'INDEXED').length;
  const processing = docs.filter((d) => isProcessing(d.indexStatus)).length;
  return (
    <div className={styles.page}>
      <header className={styles.header}>
        <div>
          <div className={styles.breadcrumb}>
            <Link to="/">资料库</Link>
            <span>/</span>
            <span>研究工作台</span>
          </div>
          <h1 title={library.name}>{library.name}</h1>
          <p title={library.description || ''}>
            {library.description || '论文、笔记与复现资料，在这里展开研究。'}
          </p>
        </div>
        <div className={styles.actions}>
          <Button
            aria-label="上传资料"
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => changeTab('files')}
          >
            上传资料
          </Button>
          <Dropdown
            trigger={['click']}
            menu={{
              items: [
                { key: 'edit', label: '编辑资料库', icon: <EditOutlined /> },
                { key: 'delete', label: '删除资料库', danger: true, icon: <DeleteOutlined /> },
              ],
              onClick: ({ key }) => (key === 'edit' ? setEditing(true) : deleteLibrary()),
            }}
          >
            <Button type="text" aria-label="资料库设置" icon={<MoreOutlined />} />
          </Dropdown>
        </div>
      </header>
      <div className={styles.toolbar}>
        <Segmented
          value={tab}
          onChange={changeTab}
          options={[
            {
              value: 'chat',
              label: (
                <span>
                  <MessageOutlined /> 研究问答
                </span>
              ),
            },
            {
              value: 'files',
              label: (
                <span>
                  <FileTextOutlined /> 资料文件 <small>{docs.length}</small>
                </span>
              ),
            },
          ]}
        />
        <div className={styles.statuses}>
          <span>
            <i className={styles.ready} />
            {ready} 份已就绪
          </span>
          {processing > 0 && (
            <span>
              <i className={styles.processing} />
              {processing} 份解析中
            </span>
          )}
          {docs.some((d) => d.indexStatus === 'UNSUPPORTED') && (
            <span className={styles.unsupported}>
              {docs.filter((d) => d.indexStatus === 'UNSUPPORTED').length} 份不支持解析
            </span>
          )}
        </div>
      </div>
      {tab === 'files' ? (
        <DocumentsPanel key={libraryId} libraryId={libraryId} />
      ) : (
        <ConversationPanel
          key={libraryId}
          libraryId={libraryId}
          conversationId={conversationId}
          onSelect={selectConversation}
          onCreate={() => setCreating(true)}
          onDocuments={() => changeTab('files')}
        />
      )}
      {editing && <LibraryDialog library={library} onClose={() => setEditing(false)} />}
      {creating &&
        (documents.isPending ? (
          <Loading />
        ) : documents.isError ? (
          <ErrorState error={documents.error} retry={() => documents.refetch()} />
        ) : (
          <NewConversationDialog
            libraryId={libraryId}
            documents={docs}
            onClose={() => setCreating(false)}
            onCreated={(c) => selectConversation(c.id)}
          />
        ))}
    </div>
  );
}
