import { z } from 'zod';
import { request } from '../../shared/api/http';
import { type LibraryInput, librarySchema } from './types';
export const librariesApi = {
  list: () => request('/libraries', z.array(librarySchema)),
  create: (value: LibraryInput) =>
    request('/libraries', librarySchema, { method: 'POST', body: JSON.stringify(value) }),
  update: (id: number, value: LibraryInput) =>
    request(`/libraries/${id}`, librarySchema, { method: 'PUT', body: JSON.stringify(value) }),
  remove: (id: number) => request(`/libraries/${id}`, z.undefined(), { method: 'DELETE' }),
};
