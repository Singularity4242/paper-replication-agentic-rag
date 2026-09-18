import { z } from 'zod';
import { request } from '../../shared/api/http';
import { detailSchema, documentSchema, taskSchema } from './types';

const base = (libraryId: number) => `/libraries/${libraryId}/documents`;
export const documentsApi = {
  list: (id: number) => request(base(id), z.array(documentSchema)),
  detail: (id: number, documentId: number) => request(`${base(id)}/${documentId}`, detailSchema),
  retry: (id: number, documentId: number) =>
    request(`${base(id)}/${documentId}/retry`, taskSchema, { method: 'POST' }),
  upload: (id: number, file: File) => {
    const data = new FormData();
    data.append('file', file);
    return request(base(id), documentSchema, { method: 'POST', body: data });
  },
};
