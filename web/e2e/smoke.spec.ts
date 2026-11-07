import { expect, test, type Page } from '@playwright/test'

async function login(page: Page, tenant: string, email: string, password: string) {
  await page.goto('/')
  if (tenant !== 'acme') {
    await page.locator('.el-select__wrapper').first().click()
    await page.getByRole('option', { name: new RegExp(`^${tenant}`) }).click()
  }
  await page.getByRole('textbox', { name: '邮箱' }).fill(email)
  await page.getByRole('textbox', { name: '密码' }).fill(password)
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page.getByRole('menuitem', { name: '工单' })).toBeVisible()
}

test('agent can log in and see the workbench', async ({ page }) => {
  await login(page, 'acme', 'agent@acme.test', 'agent123')
  await expect(page.getByRole('menuitem', { name: '知识库' })).toBeVisible()
  await expect(page.getByRole('menuitem', { name: '用量' })).toBeVisible()
  await expect(page.getByRole('menuitem', { name: '报表' })).toBeVisible()
  await expect(page.getByTestId('ticket-create')).toBeVisible()
})

test('agent can create a ticket, reply and close it', async ({ page }) => {
  await login(page, 'acme', 'agent@acme.test', 'agent123')
  const subject = `E2E 工单 ${Date.now()}`
  await page.getByTestId('ticket-create').click()
  await page.getByPlaceholder('工单标题').fill(subject)
  await page.getByPlaceholder('客户问题描述').fill('Playwright 自动化创建工单。')
  await page.getByRole('button', { name: '新建工单' }).click()
  await expect(page.getByTestId('ticket-detail-subject')).toHaveText(subject)

  await page.getByPlaceholder('人工回复客户，Ctrl+Enter 发送').fill('自动化回复：已收到，正在处理。')
  await page.getByRole('button', { name: '发送回复' }).click()
  await expect(page.getByText('自动化回复：已收到，正在处理。')).toBeVisible()

  await page.getByRole('button', { name: '关闭工单' }).click()
  await page.getByRole('button', { name: '确定' }).click()
  await expect(page.getByText('已关闭').first()).toBeVisible()
})

test('wiki, usage and report pages load', async ({ page }) => {
  await login(page, 'acme', 'agent@acme.test', 'agent123')

  await page.getByRole('menuitem', { name: '知识库' }).click()
  await expect(page.getByText('知识空间')).toBeVisible()
  await expect(page.getByRole('button', { name: '保存' })).toBeVisible()
  await page.getByPlaceholder('测试检索：输入一个客户问题').fill('退货')
  await page.getByRole('button', { name: '检索' }).click()
  await expect(page.getByText(/traceId=/)).toBeVisible()

  await page.getByRole('menuitem', { name: '用量' }).click()
  await expect(page.getByRole('heading', { name: '用量与成本' })).toBeVisible()

  await page.getByRole('menuitem', { name: '报表' }).click()
  await expect(page.getByRole('heading', { name: '报表' })).toBeVisible()
  await expect(page.getByText('分类分布')).toBeVisible()
})

test('tenant admin can open team and AI settings', async ({ page }) => {
  await login(page, 'acme', 'admin@acme.test', 'admin123')

  await page.getByRole('menuitem', { name: '团队' }).click()
  await expect(page.getByRole('heading', { name: '团队' })).toBeVisible()
  await expect(page.getByText('admin@acme.test')).toBeVisible()

  await page.getByRole('menuitem', { name: 'AI 设置' }).click()
  await expect(page.getByRole('heading', { name: 'AI 设置' })).toBeVisible()
  await expect(page.getByRole('textbox', { name: '模型' })).toHaveValue('deepseek-flash')
  await page.getByRole('button', { name: '保存 AI 设置' }).click()
  await expect(page.getByText('AI 设置已保存')).toBeVisible()
})

test('platform admin can open tenant management', async ({ page }) => {
  await login(page, 'platform', 'platform@helpdesk.test', 'platform123')
  await expect(page.getByRole('menuitem', { name: '平台' })).toBeVisible()
  await expect(page.getByRole('menuitem', { name: '报表' })).toHaveCount(0)
  await page.getByRole('menuitem', { name: '平台' }).click()
  await expect(page.getByRole('heading', { name: '平台管理' })).toBeVisible()
  await expect(page.getByText('Helpdesk Platform')).toBeVisible()
})

test('customer portal creates a ticket and replies', async ({ page }) => {
  await page.goto('/portal.html?channel=acme-demo-key')
  const subject = `E2E 门户 ${Date.now()}`
  await page.getByPlaceholder('例如：订单退货咨询').fill(subject)
  await page.getByPlaceholder('请描述遇到的问题、订单号或期望的处理方式').fill('客户门户自动化提交。')
  await page.getByRole('button', { name: '提交工单' }).click()

  await expect(page.getByTestId('portal-ticket-subject')).toHaveText(subject)
  await expect(page.getByTestId('portal-token')).not.toBeEmpty()
  await page.getByPlaceholder('补充说明或回复客服').fill('客户补充：请尽快回复。')
  await page.getByRole('button', { name: '发送', exact: true }).click()
  await expect(page.getByText('客户补充：请尽快回复。')).toBeVisible()
})

test('customer portal rejects an invalid lookup', async ({ page }) => {
  await page.goto('/portal.html?channel=acme-demo-key')
  await page.getByPlaceholder('创建工单时返回的 ID').fill('00000000-0000-0000-0000-000000000000')
  await page.getByPlaceholder('创建工单时返回的查询码').fill('invalid-token')
  await page.getByRole('button', { name: '查询' }).click()
  await expect(page.getByText('工单不存在或查询码已过期')).toBeVisible()
})
