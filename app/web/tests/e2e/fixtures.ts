import type { Page } from '@playwright/test';

const now = '2026-09-18T09:00:00+08:00';
export const exampleAnswer = {
  answer:
    'ALPHA 实验使用 **AdamW** 优化器，初始学习率为 **0.001**。[1]\n\n### 关键实验设置\n\n| 参数 | 配置 |\n| --- | --- |\n| 优化器 | AdamW |\n| 学习率 | 0.001 |\n| Batch size | 16 |\n\n复现时，建议同时核对训练配置文件，确保实验条件一致。',
  outcome: 'ANSWERED',
  citations: [
    {
      index: 1,
      documentId: 1,
      filename: 'ALPHA-experiment.md',
      chunkId: 'chunk-1',
      pageNumbers: [6],
      content:
        'We use the AdamW optimizer with an initial learning rate of 0.001 and a batch size of 16. All experiments are conducted under the same training configuration.',
    },
  ],
};
export function makeDocument(id: number, name: string, indexStatus: string) {
  return {
    id,
    libraryId: 1,
    originalFilename: name,
    fileSize: 2048,
    sha256: 'a'.repeat(64),
    status: 'UPLOADED',
    indexStatus,
    ragDocumentId: indexStatus === 'INDEXED' ? `rag-${id}` : null,
    indexedAt: indexStatus === 'INDEXED' ? now : null,
    createdAt: now,
    updatedAt: now,
  };
}
export async function mockApi(page: Page, populated = true) {
  const state = {
    libraries: [
      {
        id: 1,
        name: '视觉模型复现研究',
        description: '从论文方法到实验细节，让每一步复现都有依据。',
        createdAt: now,
        updatedAt: now,
      },
      {
        id: 2,
        name: '检索增强生成 · 阅读笔记',
        description: '关于检索、推理与可靠回答的持续探索。',
        createdAt: now,
        updatedAt: now,
      },
    ],
    documents: populated
      ? [
          makeDocument(1, 'ALPHA-experiment.md', 'INDEXED'),
          makeDocument(2, 'train.yaml', 'INDEXED'),
          makeDocument(3, 'architecture.bin', 'UNSUPPORTED'),
        ]
      : [],
    conversations: populated
      ? [
          {
            id: 1,
            libraryId: 1,
            title: '训练配置与实验细节',
            documentIds: [1, 2],
            revision: 1,
            createdAt: now,
            updatedAt: now,
          },
        ]
      : [],
    turns: populated
      ? [
          {
            id: 1,
            requestId: '00000000-0000-4000-8000-000000000001',
            question: 'ALPHA 的优化器与学习率如何设置？',
            status: 'COMPLETED',
            result: exampleAnswer as typeof exampleAnswer | null,
            errorCode: null as string | null,
            errorMessage: null as string | null,
            createdAt: now,
            finishedAt: now as string | null,
          },
        ]
      : [],
    requests: [] as { requestId: string; question: string }[],
    failStream: false,
    truncate: false,
  };
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      const req = route.request();
      const path = new URL(req.url()).pathname;
      const method = req.method();
      const ok = (data: unknown, status = 200) =>
        route.fulfill({ status, json: { code: 'SUCCESS', message: '成功', data } });
      if (path === '/api/libraries') {
        if (method === 'GET') return ok(state.libraries);
        const value = req.postDataJSON();
        const library = { ...value, id: state.libraries.length + 1, createdAt: now, updatedAt: now };
        state.libraries.push(library);
        return ok(library, 201);
      }
      if (/^\/api\/libraries\/\d+$/.test(path)) {
        if (method === 'PUT') {
          const library = state.libraries.find((l) => l.id === Number(path.split('/').at(-1)));
          Object.assign(library || {}, req.postDataJSON());
          return ok(library);
        }
        const id = Number(path.split('/').at(-1));
        if (
          !state.documents.some((d) => d.libraryId === id) &&
          !state.conversations.some((c) => c.libraryId === id)
        ) {
          state.libraries = state.libraries.filter((l) => l.id !== id);
          return route.fulfill({ status: 204 });
        }
        return route.fulfill({
          status: 409,
          json: { code: 'LIBRARY_NOT_EMPTY', message: '资料库非空，暂时不能删除', data: null },
        });
      }
      if (path.endsWith('/retry')) {
        const doc = state.documents.find((d) => d.id === Number(path.split('/').at(-2)));
        if (doc) doc.indexStatus = 'QUEUED';
        return ok(
          {
            id: 1,
            status: 'QUEUED',
            attemptCount: 0,
            nextAttemptAt: now,
            errorCode: null,
            errorMessage: null,
            startedAt: null,
            finishedAt: null,
          },
          202,
        );
      }
      if (/\/documents\/\d+$/.test(path)) {
        const doc = state.documents.find((d) => d.id === Number(path.split('/').at(-1)));
        return ok({ document: doc, task: null });
      }
      if (path.endsWith('/documents')) {
        if (method === 'GET')
          return ok(state.documents.filter((d) => d.libraryId === Number(path.split('/')[3])));
        const name =
          req
            .postDataBuffer()
            ?.toString()
            .match(/filename="([^"]+)"/)?.[1] || 'upload.md';
        const doc = {
          ...makeDocument(state.documents.length + 1, name, 'QUEUED'),
          libraryId: Number(path.split('/')[3]),
        };
        state.documents.push(doc);
        return ok(doc, 201);
      }
      if (path.endsWith('/messages')) {
        if (method === 'GET') {
          const after = Number(new URL(req.url()).searchParams.get('afterId')) || 0;
          return ok(state.turns.filter((t) => t.id > after).slice(0, 50));
        }
        const value = req.postDataJSON();
        state.requests.push(value);
        const result = exampleAnswer;
        const frames = (event: string, data: unknown) => `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
        if (state.truncate)
          return route.fulfill({
            contentType: 'text/event-stream',
            body: frames('delta', { text: '临时生成内容不应保留' }),
          });
        const existing = state.turns.find((t) => t.requestId === value.requestId);
        if (!existing)
          state.turns.push({
            id: state.turns.length + 1,
            requestId: value.requestId,
            question: value.question,
            status: state.failStream ? 'FAILED' : 'COMPLETED',
            result: state.failStream ? null : result,
            errorCode: state.failStream ? 'RAG_TIMEOUT' : null,
            errorMessage: state.failStream ? '回答超时，请稍后重试' : null,
            createdAt: now,
            finishedAt: now,
          });
        return route.fulfill({
          contentType: 'text/event-stream',
          body:
            frames('status', { phase: 'SEARCHING' }) +
            frames('delta', { text: '未核验的中间结果' }) +
            (state.failStream
              ? frames('error', { code: 'RAG_TIMEOUT', message: '回答超时，请稍后重试' })
              : frames('answer', result) + frames('done', { status: 'COMPLETED' })),
        });
      }
      if (/\/conversations\/\d+$/.test(path))
        return ok(state.conversations.find((c) => c.id === Number(path.split('/').at(-1))));
      if (path.endsWith('/conversations')) {
        if (method === 'GET') {
          const before = Number(new URL(req.url()).searchParams.get('beforeId')) || Infinity;
          return ok(
            state.conversations
              .filter((c) => c.id < before && c.libraryId === Number(path.split('/')[3]))
              .sort((a, b) => b.id - a.id)
              .slice(0, 50),
          );
        }
        const value = req.postDataJSON();
        const conversation = {
          ...value,
          id: state.conversations.length + 1,
          libraryId: Number(path.split('/')[3]),
          revision: 0,
          createdAt: now,
          updatedAt: now,
        };
        state.conversations.push(conversation);
        return ok(conversation, 201);
      }
      return route.fulfill({ status: 404, json: { code: 'NOT_FOUND', message: '不存在', data: null } });
    },
  );
  return state;
}
