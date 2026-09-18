import { z } from 'zod';
export const librarySchema = z.object({
  id: z.number(),
  name: z.string(),
  description: z.string().nullable(),
  createdAt: z.string(),
  updatedAt: z.string(),
});
export type Library = z.infer<typeof librarySchema>;
export type LibraryInput = Pick<Library, 'name' | 'description'>;
