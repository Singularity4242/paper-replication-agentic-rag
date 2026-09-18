import { describe, expect, it } from 'vitest';
import { readSse } from './sse';

function chunks(text: string, size = 1) {
  const data = new TextEncoder().encode(text);
  return new ReadableStream<Uint8Array>({
    start(controller) {
      for (let i = 0; i < data.length; i += size) controller.enqueue(data.slice(i, i + size));
      controller.close();
    },
  });
}
describe('SSE framing', () => {
  it('reconstructs byte-split Chinese and CRLF lines, skips comments, joins data lines', async () => {
    const received: unknown[] = [];
    await readSse(
      chunks(
        ': ping\r\nevent: answer\r\ndata: {"answer":\r\ndata: "中文回答"}\r\n\r\nevent: done\r\ndata: {"status":"COMPLETED"}\r\n\r\n',
      ),
      (event, data) => {
        received.push([event, data]);
      },
    );
    expect(received).toEqual([
      ['answer', { answer: '中文回答' }],
      ['done', { status: 'COMPLETED' }],
    ]);
  });
  it('does not acknowledge an unterminated event', async () => {
    const received: unknown[] = [];
    await readSse(chunks('event: done\ndata: {}'), (_, value) => {
      received.push(value);
    });
    expect(received).toEqual([]);
  });
  it('stops reading after terminal callback and rejects malformed JSON', async () => {
    const received: string[] = [];
    await readSse(chunks('event: error\ndata: {}\n\nevent: done\ndata: {}\n\n', 200), (event) => {
      received.push(event);
      return false;
    });
    expect(received).toEqual(['error']);
    await expect(readSse(chunks('data: broken\n\n'), () => {})).rejects.toThrow();
  });
  it('rejects excessive frames without rendering unbounded output', async () => {
    await expect(
      readSse(chunks(`data: ${'x'.repeat(4 * 1024 * 1024 + 1)}`, 65536), () => {}),
    ).rejects.toThrow('响应过大');
  });
});
