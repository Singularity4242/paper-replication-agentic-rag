import { fireEvent, render, screen } from '@testing-library/react';
import { expect, it, vi } from 'vitest';
import { MessageComposer } from './MessageComposer';

it('keeps Chinese IME confirmation and Shift+Enter from sending, trims intentional submissions', () => {
  const send = vi.fn();
  render(<MessageComposer count={2} disabled={false} busy={false} onSend={send} />);
  const input = screen.getByRole('textbox');
  fireEvent.change(input, { target: { value: '  研究问题  ' } });
  fireEvent.compositionStart(input);
  fireEvent.keyDown(input, { key: 'Enter' });
  expect(send).not.toHaveBeenCalled();
  fireEvent.compositionEnd(input);
  fireEvent.keyDown(input, { key: 'Enter', shiftKey: true });
  expect(send).not.toHaveBeenCalled();
  fireEvent.keyDown(input, { key: 'Enter' });
  expect(send).toHaveBeenCalledExactlyOnceWith('研究问题');
  expect(input).toHaveValue('');
});
it('prevents sending while another turn is running', () => {
  const send = vi.fn();
  render(<MessageComposer count={1} disabled busy onSend={send} />);
  fireEvent.change(screen.getByRole('textbox'), { target: { value: '下一问' } });
  fireEvent.keyDown(screen.getByRole('textbox'), { key: 'Enter' });
  expect(send).not.toHaveBeenCalled();
  expect(screen.getByRole('button', { name: '发送问题' })).toBeDisabled();
});
