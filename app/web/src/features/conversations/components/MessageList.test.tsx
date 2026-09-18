import { render } from '@testing-library/react';
import { expect, it } from 'vitest';
import { Markdown } from './MessageList';

it('does not execute HTML, permit javascript links, or load model-supplied remote images', () => {
  const { container } = render(
    <Markdown>
      {
        '<script>alert(1)</script>\n\n[x](javascript:alert(1))\n\n![tracking](https://evil.example/track)\n\n| 参数 | 值 |\n| --- | --- |\n| lr | 0.001 |'
      }
    </Markdown>,
  );
  expect(container.querySelector('script')).toBeNull();
  expect(container.querySelector('img')).toBeNull();
  expect(container.querySelector('a')?.getAttribute('href')).not.toMatch(/^javascript:/);
  expect(container.querySelector('table')).not.toBeNull();
});
