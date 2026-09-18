import { expect as baseExpect, test } from '@playwright/test';

// Docker parsing and browser rendering share the local machine; allow cold-start headroom.
const expect = baseExpect.configure({ timeout: 20_000 });

test('real Java/Python/PostgreSQL: browser upload, automatic parsing, two turns and refresh', async ({
  page,
}) => {
  test.skip(!process.env.E2E_REAL_BACKEND, 'Run through tests/acceptance.py against disposable services');
  test.setTimeout(240_000);
  const errors: string[] = [];
  page.on('pageerror', (e) => errors.push(e.message));
  await page.goto('/');
  await page.getByRole('main').getByRole('button', { name: '创建资料库', exact: true }).click();
  await page.getByLabel('资料库名称').fill('浏览器全链路验收');
  await page.getByLabel('简介').fill('隔离测试数据：上传、自动解析、多轮问答与证据持久化');
  await page.getByRole('dialog').getByRole('button', { name: '创建资料库' }).click();
  await page.getByRole('button', { name: '上传资料', exact: true }).click();
  await page.locator('input[type=file]').setInputFiles({
    name: 'alpha.md',
    mimeType: 'text/markdown',
    buffer: Buffer.from('# ALPHA731\nThe ALPHA731 experiment uses AdamW. Its learning rate is 0.001.'),
  });
  await expect(page.getByText('可用于问答', { exact: true })).toBeVisible({ timeout: 150_000 });
  await page.getByText('研究问答', { exact: true }).click();
  await page.getByRole('button', { name: '新建研究会话' }).click();
  await page.getByLabel('会话名称').fill('实验参数验证');
  await page.getByRole('button', { name: '创建会话', exact: true }).click();
  await page.getByRole('textbox', { name: '输入研究问题' }).fill('Remember MEMORY731; ALPHA731 optimizer?');
  await page.getByRole('button', { name: '发送问题' }).click();
  await expect(page.getByText('已保存', { exact: true })).toHaveCount(1, { timeout: 60_000 });
  await expect(page.getByRole('main')).toContainText('AdamW');
  await page.reload();
  await expect(page.getByText('Remember MEMORY731; ALPHA731 optimizer?', { exact: true })).toBeVisible();
  await page
    .getByRole('textbox', { name: '输入研究问题' })
    .fill('expect-remember: what is its learning rate?');
  await page.getByRole('button', { name: '发送问题' }).click();
  await expect(page.getByText('已保存', { exact: true })).toHaveCount(2, { timeout: 60_000 });
  await expect(page.getByRole('main')).toContainText('0.001');
  const url = new URL(page.url());
  const libraryId = url.pathname.split('/').at(-1);
  const conversationId = url.searchParams.get('conversation');
  const response = await page.request.get(
    `/api/libraries/${libraryId}/conversations/${conversationId}/messages`,
  );
  const history = (await response.json()).data;
  expect(history).toHaveLength(2);
  expect(history.every((turn: { status: string }) => turn.status === 'COMPLETED')).toBe(true);
  expect(history[1].result.citations[0].filename).toBe('alpha.md');
  await page.reload();
  await expect(page.getByText('已保存', { exact: true })).toHaveCount(2);
  await page.screenshot({ path: 'test-results/real-backend.png', fullPage: true });
  expect(errors).toEqual([]);
});
