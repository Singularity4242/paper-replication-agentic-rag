import { z } from 'zod';
export const citationSchema = z.object({
  index: z.number(),
  documentId: z.number(),
  filename: z.string(),
  chunkId: z.string(),
  pageNumbers: z.array(z.number()).nullable(),
  content: z.string(),
});
export const answerSchema = z.object({
  answer: z.string(),
  outcome: z.enum(['ANSWERED', 'INSUFFICIENT_EVIDENCE']),
  citations: z.array(citationSchema),
});
export const conversationSchema = z.object({
  id: z.number(),
  libraryId: z.number(),
  title: z.string(),
  documentIds: z.array(z.number()),
  revision: z.number(),
  createdAt: z.string(),
  updatedAt: z.string(),
});
export const turnSchema = z.object({
  id: z.number(),
  requestId: z.string(),
  question: z.string(),
  status: z.enum(['RUNNING', 'COMPLETED', 'FAILED']),
  result: answerSchema.nullable(),
  errorCode: z.string().nullable(),
  errorMessage: z.string().nullable(),
  createdAt: z.string(),
  finishedAt: z.string().nullable(),
});
export type Conversation = z.infer<typeof conversationSchema>;
export type Turn = z.infer<typeof turnSchema>;
export type Answer = z.infer<typeof answerSchema>;
export type Citation = z.infer<typeof citationSchema>;
