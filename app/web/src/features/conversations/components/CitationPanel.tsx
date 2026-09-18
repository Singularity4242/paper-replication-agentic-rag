import { FileTextOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import { Button, Empty } from 'antd';
import type { Citation } from '../types';
import styles from './ConversationPanel.module.css';
export function CitationPanel({
  citations,
  selected,
  onSelect,
}: {
  citations: Citation[];
  selected?: number;
  onSelect: (index: number) => void;
}) {
  return (
    <div className={styles.evidenceContent}>
      <div className={styles.evidenceHeader}>
        <div>
          <SafetyCertificateOutlined />
          <h2>引用证据</h2>
        </div>
        <span>{citations.length ? `${citations.length} 处来源` : '有据可依'}</span>
      </div>
      <p className={styles.evidenceIntro}>回到资料原文，核验每一个答案。</p>
      {citations.length ? (
        citations.map((c) => (
          <section
            key={`${c.index}-${c.chunkId}`}
            className={`${styles.source} ${selected === c.index ? styles.sourceSelected : ''}`}
          >
            <Button type="text" className={styles.sourceTitle} onClick={() => onSelect(c.index)}>
              <span className={styles.sourceNumber}>{c.index}</span>
              <FileTextOutlined />
              <span title={c.filename}>{c.filename}</span>
            </Button>
            <div className={styles.sourceLocation}>
              {c.pageNumbers?.length ? `第 ${c.pageNumbers.join('、')} 页` : '原文片段'}
            </div>
            <blockquote>{c.content}</blockquote>
          </section>
        ))
      ) : (
        <div className={styles.evidenceEmpty}>
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="回答引用的原文会显示在这里" />
          <p>
            你也可以点击历史回答下方的来源，
            <br />
            查看对应的证据片段。
          </p>
        </div>
      )}
      <div className={styles.evidenceFoot}>
        <SafetyCertificateOutlined /> 回答仅基于当前会话选定的资料
      </div>
    </div>
  );
}
