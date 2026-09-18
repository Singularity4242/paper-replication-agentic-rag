import { infiniteQueryOptions, queryOptions } from '@tanstack/react-query';
import { conversationsApi } from './api';
export const conversationsKey = (id: number) => ['conversations', id] as const;
export const conversationsQuery = (id: number) =>
  infiniteQueryOptions({
    queryKey: conversationsKey(id),
    queryFn: ({ pageParam }) => conversationsApi.list(id, pageParam),
    initialPageParam: 0,
    getNextPageParam: (last) => (last.length === 50 ? last.at(-1)?.id : undefined),
  });
export const conversationQuery = (id: number, cid: number) =>
  queryOptions({ queryKey: ['conversation', id, cid], queryFn: () => conversationsApi.get(id, cid) });
export const messagesKey = (id: number, cid: number) => ['messages', id, cid] as const;
export const messagesQuery = (id: number, cid: number) =>
  infiniteQueryOptions({
    queryKey: messagesKey(id, cid),
    queryFn: ({ pageParam }) => conversationsApi.messages(id, cid, pageParam),
    initialPageParam: 0,
    getNextPageParam: (last) => (last.length === 50 ? last.at(-1)?.id : undefined),
    refetchInterval: (q) =>
      q.state.data?.pages.some((page) => page.some((t) => t.status === 'RUNNING')) ? 3000 : false,
  });
