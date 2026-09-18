import { queryOptions } from '@tanstack/react-query';
import { documentsApi } from './api';
import { isProcessing } from './types';
export const documentsQuery = (id: number) =>
  queryOptions({
    queryKey: ['documents', id],
    queryFn: () => documentsApi.list(id),
    refetchInterval: (q) => (q.state.data?.some((d) => isProcessing(d.indexStatus)) ? 3000 : false),
  });
