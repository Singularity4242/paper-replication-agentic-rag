import { BookOutlined, CheckOutlined, LoadingOutlined } from '@ant-design/icons';
import { Alert, Button } from 'antd';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import type { StreamState } from '../hooks/useConversationStream';
import type { Answer, Turn } from '../types';
import styles from './ConversationPanel.module.css';
export function Markdown({ children }: { children: string }) {
  return (
    <div className={styles.markdown}>
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={{
          a: ({ children: content, href }) => (
            <a href={href} target="_blank" rel="noopener noreferrer">
              {content}
            </a>
          ),
          img: ({ alt }) => <span className="muted">[图片：{alt || '图片'}]</span>,
        }}
      >
        {children}
      </ReactMarkdown>
    </div>
  );
}
export function MessageList({
  turns,
  stream,
  onEvidence,
  onRetry,
}: {
  turns: Turn[];
  stream?: StreamState;
  onEvidence: (answer: Answer, index?: number) => void;
  onRetry: (question: string) => void;
}) {
  const activeRequest = stream?.active ? stream.requestId : undefined;
  return (
    <>
      {turns
        .filter((t) => t.requestId !== activeRequest)
        .map((turn) => (
          <article key={turn.id} className={styles.turn}>
            <div className={styles.question}>
              <span>你</span>
              <p>{turn.question}</p>
            </div>
            <div className={styles.assistantLabel}>
              <BookOutlined />
              <strong>研阅</strong>
              {turn.status === 'COMPLETED' && (
                <span>
                  <CheckOutlined /> 已保存
                </span>
              )}
            </div>
            {turn.status === 'COMPLETED' && turn.result ? (
              <>
                <Markdown>{turn.result.answer}</Markdown>
                {turn.result.outcome === 'INSUFFICIENT_EVIDENCE' && (
                  <p className={styles.refusal}>资料中的证据不足，可补充资料后新建会话继续研究。</p>
                )}
                {turn.result.citations.length > 0 && (
                  <div className={styles.citationChips}>
                    {turn.result.citations.map((c) => (
                      <button
                        type="button"
                        key={`${c.index}-${c.chunkId}`}
                        onClick={() => onEvidence(turn.result as Answer, c.index)}
                      >
                        <span>{c.index}</span>
                        {c.filename}
                        {c.pageNumbers?.length ? ` · p.${c.pageNumbers.join(',')}` : ''}
                      </button>
                    ))}
                  </div>
                )}
              </>
            ) : turn.status === 'FAILED' ? (
              <Alert
                type="warning"
                showIcon
                title="这一轮未完成"
                description={turn.errorMessage || '请稍后重试'}
                action={
                  <Button size="small" onClick={() => onRetry(turn.question)}>
                    重新提问
                  </Button>
                }
              />
            ) : (
              <div className={styles.running}>
                <LoadingOutlined />
                <span>这一轮仍在处理中，结果会自动更新。</span>
              </div>
            )}
          </article>
        ))}
      {stream?.active && (
        <article className={styles.turn}>
          <div className={styles.question}>
            <span>你</span>
            <p>{stream.question}</p>
          </div>
          <div className={styles.assistantLabel}>
            <BookOutlined />
            <strong>研阅</strong>
            <span>
              <LoadingOutlined /> {stream.phase}
            </span>
          </div>
          {stream.text ? (
            <>
              <Markdown>{stream.text}</Markdown>
              <p className={styles.provisional}>正在生成 · 最终回答保存后将更新</p>
            </>
          ) : (
            <div className={styles.thinking}>
              <i />
              <i />
              <i />
            </div>
          )}
        </article>
      )}
    </>
  );
}
