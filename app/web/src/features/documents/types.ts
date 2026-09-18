import { z } from 'zod';
export const documentSchema = z.object({
  id: z.number(),
  libraryId: z.number(),
  originalFilename: z.string(),
  fileSize: z.number(),
  sha256: z.string(),
  status: z.string(),
  indexStatus: z.string(),
  ragDocumentId: z.string().nullable(),
  indexedAt: z.string().nullable(),
  createdAt: z.string(),
  updatedAt: z.string(),
});
export const taskSchema = z.object({
  id: z.number(),
  status: z.string(),
  attemptCount: z.number(),
  nextAttemptAt: z.string().nullable(),
  errorCode: z.string().nullable(),
  errorMessage: z.string().nullable(),
  startedAt: z.string().nullable(),
  finishedAt: z.string().nullable(),
});
export const detailSchema = z.object({ document: documentSchema, task: taskSchema.nullable() });
export type ResearchDocument = z.infer<typeof documentSchema>;
export const indexLabels: Record<string, string> = {
  QUEUED: '等待解析',
  PROCESSING: '解析中',
  INDEXED: '可用于问答',
  FAILED: '解析失败',
  UNSUPPORTED: '不支持解析',
};
export const isProcessing = (status: string) => ['QUEUED', 'PROCESSING'].includes(status);
