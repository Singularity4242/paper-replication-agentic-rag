import { expect, test } from '@playwright/test';
import { mockApi } from './fixtures';

test('library create and edit, generic upload, frozen-scope conversation, answer and reload', async ({
  page,
}) => {
  const errors: string[] = [];
  page.on('pageerror', (e) => errors.push(e.message));
  const state = await mockApi(page, false);
  await page.goto('/');
  await page.getByRole('button', { name: '创建资料库', exact: true }).last().click();
  await page.getByLabel('资料库名称').fill('复现工作台');
  await page.getByLabel('简介').fill('论文和配置文件');
  await page.getByRole('dialog').getByRole('button', { name: '创建资料库' }).click();
  await expect(page.getByRole('heading', { name: '复现工作台' })).toBeVisible();
  await page.getByRole('button', { name: '资料库设置' }).click();
  await page.getByText('编辑资料库', { exact: true }).click();
  await page.getByLabel('资料库名称').fill('复现实验室');
  await page.getByRole('button', { name: '保存更改' }).click();
  await expect(page.getByRole('heading', { name: '复现实验室' })).toBeVisible();
  await page.getByRole('button', { name: '上传资料' }).click();
  await page.locator('input[type=file]').setInputFiles({
    name: 'experiment.yaml',
    mimeType: 'application/octet-stream',
    buffer: Buffer.from('learning_rate: 0.001'),
  });
  await expect(page.getByText('上传完成', { exact: true })).toBeVisible();
  await expect(page.getByText('等待解析', { exact: true })).toBeVisible();
  state.documents[0].indexStatus = 'INDEXED';
  state.documents[0].ragDocumentId = 'rag-1';
  state.documents[0].indexedAt = state.documents[0].createdAt;
  await page.getByRole('button', { name: '刷新文件列表' }).click();
  await expect(page.getByText('可用于问答', { exact: true })).toBeVisible();
  await page.getByText('研究问答', { exact: true }).click();
  await page.getByRole('button', { name: '新建研究会话' }).click();
  await page.getByLabel('会话名称').fill('参数复现');
  await page.getByRole('button', { name: '创建会话', exact: true }).click();
  await page.getByRole('textbox', { name: '输入研究问题' }).fill('优化器是什么？');
  await page.getByRole('button', { name: '发送问题' }).click();
  await expect(page.getByText('已保存', { exact: true })).toBeVisible();
  await expect(page.getByRole('main').getByText('AdamW', { exact: true }).first()).toBeVisible();
  await page.reload();
  await expect(page.getByText('优化器是什么？', { exact: true })).toBeVisible();
  expect(state.requests).toHaveLength(1);
  expect(state.conversations[0].documentIds).toEqual([1]);
  expect(errors).toEqual([]);
});
test('nonempty delete is disabled and unsupported files are excluded from conversation scope', async ({
  page,
}) => {
  await mockApi(page);
  await page.goto('/libraries/1');
  await page.getByRole('button', { name: '资料库设置' }).click();
  await page.getByText('删除资料库', { exact: true }).click();
  await expect(page.getByRole('button', { name: '删除资料库', exact: true })).toBeDisabled();
  await page.getByRole('button', { name: '取消', exact: true }).click();
  await page.getByRole('button', { name: '新建会话', exact: true }).click();
  await expect(page.getByRole('dialog').getByText('train.yaml', { exact: true })).toBeVisible();
  await expect(page.getByRole('dialog').getByText('architecture.bin')).toHaveCount(0);
});
test('failed SSE does not display provisional answer as saved', async ({ page }) => {
  const state = await mockApi(page);
  state.failStream = true;
  await page.goto('/libraries/1?conversation=1');
  await page.getByRole('textbox', { name: '输入研究问题' }).fill('下一问');
  await page.getByRole('button', { name: '发送问题' }).click();
  await expect(page.getByText('这一轮未完成', { exact: true })).toBeVisible();
  await expect(page.getByText('未核验的中间结果')).toHaveCount(0);
  await expect(page.getByText('已保存', { exact: true })).toHaveCount(1);
});
test('interrupted response survives reload and reuses request ID when checking result', async ({ page }) => {
  const state = await mockApi(page);
  state.truncate = true;
  await page.goto('/libraries/1?conversation=1');
  await page.getByRole('textbox', { name: '输入研究问题' }).fill('这次请求是否保存？');
  await page.getByRole('button', { name: '发送问题' }).click();
  await expect(page.getByText('本轮结果尚未确认')).toBeVisible();
  await page.reload();
  await expect(page.getByText('本轮结果尚未确认')).toBeVisible();
  state.truncate = false;
  await page.getByRole('button', { name: '查询本轮结果' }).click();
  await expect(page.getByText('已保存', { exact: true })).toHaveCount(2);
  expect(state.requests).toHaveLength(2);
  expect(state.requests[0].requestId).toBe(state.requests[1].requestId);
  await expect(page.getByText('临时生成内容不应保留')).toHaveCount(0);
});
test('desktop overview and evidence workspace render without horizontal overflow', async ({ page }) => {
  await mockApi(page);
  await page.goto('/');
  await expect(page.getByRole('heading', { name: /让资料相聚/ })).toBeVisible();
  await page.screenshot({ path: 'test-results/overview.png', fullPage: true });
  await page.goto('/libraries/1?conversation=1');
  await expect(page.getByRole('heading', { name: '引用证据', exact: true })).toBeVisible();
  await expect(page.getByText('已保存', { exact: true })).toBeVisible();
  await page.screenshot({ path: 'test-results/workspace.png', fullPage: true });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
});
test('mobile navigation and citation drawer are usable', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await mockApi(page);
  await page.goto('/libraries/1?conversation=1');
  await page.getByRole('button', { name: '引用证据', exact: true }).click();
  await expect(page.getByRole('dialog').getByText('第 6 页')).toBeVisible();
  await page
    .getByRole('dialog')
    .getByRole('button', { name: /关闭|Close/ })
    .click();
  await expect(page.getByRole('dialog')).toBeHidden();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.screenshot({ path: 'test-results/mobile.png', fullPage: true });
});
test('backend unavailability shows a recoverable error, not example data', async ({ page }) => {
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    (route) =>
      route.fulfill({ status: 503, json: { code: 'UNAVAILABLE', message: '服务暂时不可用', data: null } }),
  );
  await page.goto('/');
  await expect(page.getByText('服务暂时不可用', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: '重试', exact: true })).toBeVisible();
  await expect(page.getByText('视觉模型复现研究')).toHaveCount(0);
});

test('empty library can be deleted with server confirmation', async ({ page }) => {
  const state = await mockApi(page, false);
  await page.goto('/libraries/2');
  await page.getByRole('button', { name: '资料库设置' }).click();
  await page.getByText('删除资料库', { exact: true }).click();
  await page.getByRole('button', { name: '删除资料库', exact: true }).click();
  await expect(page).toHaveURL('/');
  expect(state.libraries.map((l) => l.id)).toEqual([1]);
});

test('unsupported files stay visible and manual retry re-enters processing', async ({ page }) => {
  const state = await mockApi(page);
  await page.goto('/libraries/1?tab=files');
  await expect(page.getByText('不支持解析', { exact: true })).toBeVisible();
  await page
    .getByRole('row')
    .filter({ hasText: 'architecture.bin' })
    .getByRole('button', { name: '重新解析' })
    .click();
  await expect(page.getByRole('row').filter({ hasText: 'architecture.bin' })).toContainText('等待解析');
  expect(state.documents[2].indexStatus).toBe('QUEUED');
});

test('restores history beyond the first page and polls an in-flight turn', async ({ page }) => {
  const state = await mockApi(page);
  const template = state.turns[0];
  state.turns = Array.from({ length: 51 }, (_, i) => ({
    ...template,
    id: i + 1,
    requestId: `request-${i}`,
    question: `历史问题 ${i + 1}`,
    status: i === 50 ? 'RUNNING' : 'COMPLETED',
    result: i === 50 ? null : template.result,
  }));
  await page.goto('/libraries/1?conversation=1');
  await expect(page.getByText('历史问题 51', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: '发送问题' })).toBeDisabled();
  state.turns[50].status = 'COMPLETED';
  state.turns[50].result = template.result;
  await expect(page.getByText('已保存', { exact: true })).toHaveCount(51, { timeout: 10000 });
});
