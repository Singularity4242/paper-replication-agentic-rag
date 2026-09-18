import { BookOutlined, FileSearchOutlined, PlusOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { Alert, Button, Drawer } from 'antd';
import { useEffect, useMemo, useRef, useState } from 'react';
import { ErrorState, Loading } from '../../../shared/components/Feedback';
import { useConversationStream } from '../hooks/useConversationStream';
import { conversationQuery, conversationsQuery, messagesQuery } from '../queries';
import type { Answer } from '../types';
import { CitationPanel } from './CitationPanel';
import styles from './ConversationPanel.module.css';
import { MessageComposer } from './MessageComposer';
import { MessageList } from './MessageList';
export function ConversationList({
  libraryId,
  conversationId,
  onSelect,
  onCreate,
}: {
  libraryId: number;
  conversationId: number;
  onSelect: (id: number) => void;
  onCreate: () => void;
}) {
  const list = useInfiniteQuery(conversationsQuery(libraryId));
  return (
    <nav className={styles.conversations} aria-label="会话列表">
      <div className={styles.conversationHeader}>
        <span>研究对话</span>
        <Button type="text" size="small" aria-label="新建会话" icon={<PlusOutlined />} onClick={onCreate} />
      </div>
      {list.isPending ? (
        <Loading />
      ) : list.isError ? (
        <ErrorState error={list.error} retry={() => list.refetch()} />
      ) : (
        <>
          <div className={styles.conversationItems}>
            {list.data.pages.flat().map((c) => (
              <button
                type="button"
                key={c.id}
                className={c.id === conversationId ? styles.currentConversation : ''}
                onClick={() => onSelect(c.id)}
                title={c.title}
              >
                <span>{c.title}</span>
                <small>
                  {c.documentIds.length} 份资料 ·{' '}
                  {new Date(c.updatedAt).toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit' })}
                </small>
              </button>
            ))}
          </div>
          {list.data.pages[0].length === 0 && (
            <p className={styles.noConversations}>
              每一段对话，
              <br />
              都是一次新的探索。
            </p>
          )}
          {list.hasNextPage && (
            <Button
              type="text"
              size="small"
              loading={list.isFetchingNextPage}
              onClick={() => list.fetchNextPage()}
            >
              更多会话
            </Button>
          )}
        </>
      )}
    </nav>
  );
}
function ActiveConversation({ libraryId, conversationId }: { libraryId: number; conversationId: number }) {
  const conversation = useQuery(conversationQuery(libraryId, conversationId));
  const stream = useConversationStream(libraryId, conversationId);
  const history = useInfiniteQuery({
    ...messagesQuery(libraryId, conversationId),
    refetchInterval: (q) =>
      stream.state?.uncertain || q.state.data?.pages.some((p) => p.some((t) => t.status === 'RUNNING'))
        ? 3000
        : false,
  });
  const turns = useMemo(() => history.data?.pages.flat() || [], [history.data]);
  const [evidence, setEvidence] = useState<{ answer: Answer; index?: number }>();
  const [evidenceOpen, setEvidenceOpen] = useState(false);
  const latest = [...turns].reverse().find((t) => t.status === 'COMPLETED' && t.result)?.result;
  const selectedAnswer = evidence?.answer || latest;
  const historyArea = useRef<HTMLDivElement>(null);
  const followBottom = useRef(true);
  useEffect(() => {
    if (history.hasNextPage && !history.isFetchingNextPage && !history.isFetchNextPageError)
      void history.fetchNextPage();
  }, [history.hasNextPage, history.isFetchingNextPage, history.isFetchNextPageError, history.fetchNextPage]);
  useEffect(() => {
    const found = turns.find((t) => t.requestId === stream.state?.requestId && t.status !== 'RUNNING');
    if (found && !stream.state?.active) stream.acknowledge(libraryId, conversationId, found.requestId);
  }, [turns, stream, libraryId, conversationId]);
  useEffect(() => {
    if (followBottom.current && historyArea.current)
      historyArea.current.scrollTop = historyArea.current.scrollHeight;
  });
  const showEvidence = (answer: Answer, index?: number) => {
    setEvidence({ answer, index });
    if (window.matchMedia('(max-width: 1080px)').matches) setEvidenceOpen(true);
  };
  const waiting = turns.some((t) => t.status === 'RUNNING');
  const disabled =
    stream.busy ||
    waiting ||
    !!stream.state?.uncertain ||
    history.isPending ||
    history.isError ||
    history.hasNextPage;
  const send = (question: string) => {
    if (!disabled) {
      followBottom.current = true;
      void stream.send(libraryId, conversationId, question);
    }
  };
  const panel = (
    <CitationPanel
      citations={selectedAnswer?.citations || []}
      selected={evidence?.index}
      onSelect={(index) => selectedAnswer && setEvidence({ answer: selectedAnswer, index })}
    />
  );
  if (conversation.isPending) return <Loading />;
  if (conversation.isError)
    return <ErrorState error={conversation.error} retry={() => conversation.refetch()} />;
  return (
    <div className={styles.activeWorkspace}>
      <section className={styles.chatColumn}>
        <header className={styles.chatHeader}>
          <div>
            <h2>{conversation.data.title}</h2>
            <span>{conversation.data.documentIds.length} 份资料 · 多轮研究对话</span>
          </div>
          <Button
            size="small"
            aria-label="引用证据"
            className={styles.evidenceToggle}
            icon={<FileSearchOutlined />}
            onClick={() => setEvidenceOpen(true)}
          >
            引用证据
          </Button>
        </header>
        <div
          ref={historyArea}
          className={styles.messages}
          onScroll={(e) => {
            const el = e.currentTarget;
            followBottom.current = el.scrollHeight - el.scrollTop - el.clientHeight < 100;
          }}
        >
          {history.isPending ? (
            <Loading label="正在恢复对话" />
          ) : history.isError ? (
            <ErrorState error={history.error} retry={() => history.refetch()} />
          ) : (
            <>
              {history.hasNextPage && (
                <Button loading={history.isFetchingNextPage} onClick={() => history.fetchNextPage()}>
                  加载剩余对话
                </Button>
              )}
              {turns.length === 0 && !stream.state?.active && (
                <div className={styles.chatWelcome}>
                  <div className={styles.welcomeIcon}>
                    <BookOutlined />
                  </div>
                  <h2>好的研究，从一个问题开始。</h2>
                  <p>
                    概括核心方法、对比实验设置，或追问复现细节。
                    <br />
                    每一次回答，都从你选定的资料出发。
                  </p>
                  <div className={styles.suggestions}>
                    {[
                      '概括这些资料中的核心研究方法',
                      '总结实验设置与关键参数',
                      '有哪些需要进一步核验的细节？',
                    ].map((q) => (
                      <button type="button" key={q} disabled={disabled} onClick={() => send(q)}>
                        {q}
                        <span>↗</span>
                      </button>
                    ))}
                  </div>
                </div>
              )}
              <MessageList turns={turns} stream={stream.state} onEvidence={showEvidence} onRetry={send} />
            </>
          )}
          {stream.state && !stream.state.active && (
            <div className={styles.streamError}>
              <p className="muted">待确认的问题：{stream.state.question}</p>
              <Alert
                showIcon
                type="warning"
                title={stream.state.uncertain ? '本轮结果尚未确认' : '请求未完成'}
                description={stream.state.error || '页面曾中断，请查询这次提问的处理结果。'}
                action={
                  <Button
                    size="small"
                    disabled={stream.busy}
                    onClick={() =>
                      stream.state?.uncertain
                        ? stream.send(
                            libraryId,
                            conversationId,
                            stream.state.question,
                            stream.state.requestId,
                          )
                        : stream.acknowledge(libraryId, conversationId, stream.state.requestId)
                    }
                  >
                    {stream.state.uncertain ? '查询本轮结果' : '知道了'}
                  </Button>
                }
              />
            </div>
          )}
        </div>
        <MessageComposer
          count={conversation.data.documentIds.length}
          disabled={!!disabled}
          busy={stream.busy || waiting}
          onSend={send}
        />
      </section>
      <aside className={styles.evidencePanel}>{panel}</aside>
      <Drawer
        className={styles.evidenceDrawer}
        title="引用证据"
        open={evidenceOpen}
        onClose={() => setEvidenceOpen(false)}
      >
        {panel}
      </Drawer>
    </div>
  );
}
export function ConversationPanel({
  libraryId,
  conversationId,
  onSelect,
  onCreate,
  onDocuments,
}: {
  libraryId: number;
  conversationId: number;
  onSelect: (id: number) => void;
  onCreate: () => void;
  onDocuments: () => void;
}) {
  return (
    <div className={styles.workspace}>
      <ConversationList
        libraryId={libraryId}
        conversationId={conversationId}
        onSelect={onSelect}
        onCreate={onCreate}
      />
      {conversationId ? (
        <ActiveConversation key={conversationId} libraryId={libraryId} conversationId={conversationId} />
      ) : (
        <div className={styles.start}>
          <div className={styles.welcomeIcon}>
            <BookOutlined />
          </div>
          <span className={styles.startEyebrow}>A CONVERSATION WITH YOUR KNOWLEDGE</span>
          <h2>从资料中，找到你的答案。</h2>
          <p>
            选择一组资料，开启可追溯的研究对话。
            <br />
            历史会话会保存在这里，随时回来继续。
          </p>
          <div className={styles.startActions}>
            <Button type="primary" icon={<PlusOutlined />} onClick={onCreate}>
              新建研究会话
            </Button>
            <Button onClick={onDocuments}>查看资料文件</Button>
          </div>
          <div className={styles.startNote}>
            <SafetyCertificateOutlined /> 每一份引用，都可以回到原文核验
          </div>
        </div>
      )}
    </div>
  );
}
