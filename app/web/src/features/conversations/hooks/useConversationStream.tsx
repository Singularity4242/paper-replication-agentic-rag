import { useQueryClient } from '@tanstack/react-query';
import { createContext, type ReactNode, useContext, useRef, useState } from 'react';
import { z } from 'zod';
import { ApiError, errorMessage } from '../../../shared/api/http';
import { conversationsApi } from '../api';
import { conversationsKey, messagesKey } from '../queries';
import { type Answer, answerSchema } from '../types';

const pendingSchema = z.object({
  libraryId: z.number(),
  conversationId: z.number(),
  requestId: z.string(),
  question: z.string(),
});
type Pending = z.infer<typeof pendingSchema>;
export type StreamState = Pending & {
  phase: string;
  text: string;
  error?: string;
  uncertain: boolean;
  active: boolean;
};
const storageKey = 'paper-assistant.pending-turns.v1';
function restore(): Record<string, StreamState> {
  try {
    const pending = z.array(pendingSchema).parse(JSON.parse(sessionStorage.getItem(storageKey) || '[]'));
    return Object.fromEntries(
      pending.map((p) => [
        key(p.libraryId, p.conversationId),
        { ...p, phase: '', text: '', uncertain: true, active: false },
      ]),
    );
  } catch {
    return {};
  }
}
const key = (id: number, cid: number) => `${id}:${cid}`;
function persist(states: Record<string, StreamState>) {
  try {
    sessionStorage.setItem(
      storageKey,
      JSON.stringify(
        Object.values(states).map(({ libraryId, conversationId, requestId, question }) => ({
          libraryId,
          conversationId,
          requestId,
          question,
        })),
      ),
    );
  } catch {
    /* Server history remains authoritative when browser storage is unavailable. */
  }
}
type ContextValue = {
  states: Record<string, StreamState>;
  busy: boolean;
  send: (id: number, cid: number, question: string, requestId?: string) => Promise<void>;
  acknowledge: (id: number, cid: number, requestId: string) => void;
};
const StreamContext = createContext<ContextValue | null>(null);
export function ConversationStreamProvider({ children }: { children: ReactNode }) {
  const client = useQueryClient();
  const [states, setStates] = useState(restore);
  const lock = useRef(false);
  const update = (k: string, value: StreamState | null) =>
    setStates((old) => {
      const next = { ...old };
      if (value) next[k] = value;
      else delete next[k];
      persist(next);
      return next;
    });
  const acknowledge = (id: number, cid: number, requestId: string) =>
    setStates((old) => {
      const k = key(id, cid);
      if (old[k]?.active || old[k]?.requestId !== requestId) return old;
      const next = { ...old };
      delete next[k];
      persist(next);
      return next;
    });
  const send = async (id: number, cid: number, question: string, requestId: string = crypto.randomUUID()) => {
    if (lock.current) return;
    lock.current = true;
    const k = key(id, cid);
    let current: StreamState = {
      libraryId: id,
      conversationId: cid,
      requestId,
      question,
      phase: '正在连接',
      text: '',
      uncertain: false,
      active: true,
    };
    update(k, current);
    let answer: Answer | undefined;
    let terminal = false;
    try {
      await conversationsApi.stream(id, cid, requestId, question, (event, value) => {
        if (event === 'status') {
          const { phase } = z.object({ phase: z.string() }).parse(value);
          current = {
            ...current,
            phase:
              (
                { STARTED: '正在理解问题', SEARCHING: '正在检索资料', CITING: '正在核验证据' } as Record<
                  string,
                  string
                >
              )[phase] || '正在整理回答',
          };
        } else if (event === 'delta') {
          current = { ...current, text: current.text + z.object({ text: z.string() }).parse(value).text };
        } else if (event === 'answer') {
          answer = answerSchema.parse(value);
          current = { ...current, text: answer.answer, phase: '正在确认保存' };
        } else if (event === 'done') {
          if (!answer || z.object({ status: z.literal('COMPLETED') }).safeParse(value).success === false)
            throw new Error('回答尚未确认保存，请查询本轮状态。');
          terminal = true;
          return false;
        } else if (event === 'error') {
          const error = z.object({ code: z.string(), message: z.string() }).parse(value);
          terminal = true;
          current = { ...current, text: '', error: error.message, active: false };
          update(k, current);
          return false;
        }
        update(k, current);
      });
      if (!terminal) throw new Error('连接已中断，正在生成的内容尚未确认保存。请查询本轮状态。');
      if (answer && !current.error) update(k, null);
    } catch (error) {
      const uncertain =
        !(error instanceof ApiError) || error.status >= 500 || ['REQUEST_IN_PROGRESS'].includes(error.code);
      current = { ...current, active: false, uncertain, text: '', error: errorMessage(error) };
      update(k, current);
    } finally {
      lock.current = false;
      setStates((old) => {
        const entry = old[k];
        if (!entry) return old;
        const next = { ...old, [k]: { ...entry, active: false } };
        persist(next);
        return next;
      });
      await Promise.all([
        client.invalidateQueries({ queryKey: messagesKey(id, cid) }),
        client.invalidateQueries({ queryKey: conversationsKey(id) }),
        client.invalidateQueries({ queryKey: ['conversation', id, cid] }),
      ]);
    }
  };
  return (
    <StreamContext.Provider
      value={{ states, busy: Object.values(states).some((s) => s.active), send, acknowledge }}
    >
      {children}
    </StreamContext.Provider>
  );
}
export function useConversationStream(id: number, cid: number) {
  const context = useContext(StreamContext);
  if (!context) throw new Error('ConversationStreamProvider is missing');
  return { ...context, state: context.states[key(id, cid)] };
}
