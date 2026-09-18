import { z } from 'zod';
import { request, responseError } from '../../shared/api/http';
import { readSse } from '../../shared/api/sse';
import { conversationSchema, turnSchema } from './types';

const base = (libraryId: number) => `/libraries/${libraryId}/conversations`;
export const conversationsApi = {
  list: (id: number, beforeId = 0) =>
    request(`${base(id)}?beforeId=${beforeId}&limit=50`, z.array(conversationSchema)),
  get: (id: number, conversationId: number) => request(`${base(id)}/${conversationId}`, conversationSchema),
  create: (id: number, title: string, documentIds: number[]) =>
    request(base(id), conversationSchema, { method: 'POST', body: JSON.stringify({ title, documentIds }) }),
  messages: (id: number, conversationId: number, afterId = 0) =>
    request(`${base(id)}/${conversationId}/messages?afterId=${afterId}&limit=50`, z.array(turnSchema)),
  stream: async (
    id: number,
    conversationId: number,
    requestId: string,
    question: string,
    onEvent: (event: string, value: unknown) => boolean | undefined,
  ) => {
    const response = await fetch(`/api${base(id)}/${conversationId}/messages`, {
      method: 'POST',
      signal: AbortSignal.timeout(300_000),
      headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
      body: JSON.stringify({ requestId, question }),
    });
    if (!response.ok) throw await responseError(response);
    if (!response.headers.get('content-type')?.includes('text/event-stream') || !response.body)
      throw new Error('服务未返回有效的对话流，请查询本轮状态。');
    await readSse(response.body, onEvent);
  },
};
