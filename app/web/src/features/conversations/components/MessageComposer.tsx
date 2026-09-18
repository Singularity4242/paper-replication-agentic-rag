import { ArrowUpOutlined, LinkOutlined } from '@ant-design/icons';
import { Button, Input, Tooltip } from 'antd';
import { useRef, useState } from 'react';
import styles from './ConversationPanel.module.css';
export function MessageComposer({
  count,
  disabled,
  busy,
  onSend,
}: {
  count: number;
  disabled: boolean;
  busy: boolean;
  onSend: (question: string) => void;
}) {
  const [draft, setDraft] = useState('');
  const composing = useRef(false);
  const submit = () => {
    if (!draft.trim() || disabled || draft.length > 8000) return;
    onSend(draft.trim());
    setDraft('');
  };
  return (
    <div className={styles.composerWrap}>
      <div className={styles.composer}>
        <Input.TextArea
          aria-label="输入研究问题"
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          onCompositionStart={() => {
            composing.current = true;
          }}
          onCompositionEnd={() => {
            composing.current = false;
          }}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing && !composing.current) {
              e.preventDefault();
              submit();
            }
          }}
          maxLength={8000}
          autoSize={{ minRows: 2, maxRows: 6 }}
          placeholder={busy ? '回答正在生成，你可以先写下一个问题…' : '继续追问，让理解更进一步…'}
          variant="borderless"
        />
        <div className={styles.composerBottom}>
          <span>
            <LinkOutlined /> {count} 份资料 · 会话范围已固定
          </span>
          <Tooltip title="发送（Enter），换行（Shift + Enter）">
            <Button
              type="primary"
              shape="circle"
              aria-label="发送问题"
              disabled={disabled || !draft.trim()}
              icon={<ArrowUpOutlined />}
              onClick={submit}
            />
          </Tooltip>
        </div>
      </div>
      <p className={styles.composerHint}>
        AI 的回答可能有误，请结合引用原文核验。
        <span>{draft.length > 7000 ? `${draft.length} / 8000` : 'Enter 发送 · Shift + Enter 换行'}</span>
      </p>
    </div>
  );
}
