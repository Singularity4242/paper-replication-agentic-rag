/** Incremental SSE parser. Network chunks may split lines, CRLF pairs and UTF-8 characters. */
export async function readSse(
  body: ReadableStream<Uint8Array>,
  onEvent: (event: string, data: unknown) => boolean | undefined,
) {
  const reader = body.getReader();
  const decoder = new TextDecoder('utf-8', { fatal: true });
  let buffer = '';
  let event = 'message';
  let data: string[] = [];
  let frameSize = 0;
  let stopped = false;
  const line = (value: string) => {
    if (!value) {
      if (data.length) stopped = onEvent(event, JSON.parse(data.join('\n'))) === false;
      data = [];
      event = 'message';
      frameSize = 0;
      return;
    }
    frameSize += value.length;
    if (frameSize > 4 * 1024 * 1024) throw new Error('响应过大，请重新打开会话。');
    if (value.startsWith(':')) return;
    const split = value.indexOf(':');
    const key = split < 0 ? value : value.slice(0, split);
    let content = split < 0 ? '' : value.slice(split + 1);
    if (content.startsWith(' ')) content = content.slice(1);
    if (key === 'event') event = content;
    if (key === 'data') data.push(content);
  };
  try {
    while (!stopped) {
      const { done, value } = await reader.read();
      buffer += done ? decoder.decode() : decoder.decode(value, { stream: true });
      if (buffer.length > 4 * 1024 * 1024) throw new Error('响应过大，请重新打开会话。');
      let index = 0;
      while (index < buffer.length && !stopped) {
        const ch = buffer[index];
        if (ch !== '\r' && ch !== '\n') {
          index++;
          continue;
        }
        if (ch === '\r' && index === buffer.length - 1 && !done) break;
        line(buffer.slice(0, index));
        const size = ch === '\r' && buffer[index + 1] === '\n' ? 2 : 1;
        buffer = buffer.slice(index + size);
        index = 0;
      }
      if (done) break; // Undelimited final frames are deliberately not treated as acknowledged events.
    }
  } finally {
    await reader.cancel().catch(() => {});
    reader.releaseLock();
  }
}
