import { expect, test, type Page } from '@playwright/test'

async function login(page: Page, tenant: string, email: string, password: string) {
  await page.goto('/')
  if (tenant !== 'acme') {
    await page.locator('.el-select__wrapper').first().click()
    if (tenant === 'cloudwise' || tenant === 'platform') {
      await page.getByRole('option', { name: new RegExp(`^${tenant}`) }).click()
    } else {
      await page.locator('.el-select__input').first().fill(tenant)
      await page.keyboard.press('Enter')
    }
  }
  await page.getByRole('textbox', { name: '邮箱' }).fill(email)
  await page.getByRole('textbox', { name: '密码' }).fill(password)
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page.getByRole('menuitem', { name: '工单' })).toBeVisible()
}

test('wiki approval, versions, rollback and tenant recycle bin', async ({ page, request }) => {
  const suffix = Date.now().toString(36)
  const slug = `e2e-${suffix}`
  const tenantName = `E2E Tenant ${suffix}`
  const adminEmail = `admin-${suffix}@e2e.test`
  const password = 'password123'

  const platformLogin = await request.post('/api/auth/login', {
    data: { tenantSlug: 'platform', email: 'platform@helpdesk.test', password: 'platform123' },
  })
  expect(platformLogin.ok()).toBeTruthy()
  const platformToken = (await platformLogin.json()).token as string
  const created = await request.post('/api/platform/tenants', {
    headers: { Authorization: `Bearer ${platformToken}` },
    data: {
      slug,
      name: tenantName,
      plan: 'free',
      monthlyTokenBudget: 1000,
      adminEmail,
      adminPassword: password,
      adminDisplayName: 'E2E Admin',
    },
  })
  expect(created.ok()).toBeTruthy()
  const tenantId = (await created.json()).id as string

  try {
    await login(page, slug, adminEmail, password)

    await page.getByRole('menuitem', { name: '知识库' }).click()
    await expect(page.getByRole('button', { name: '通知' })).toBeVisible()
    await page.getByRole('button', { name: '新建' }).click()
    await page.getByPlaceholder('页面标题').fill('E2E 审批页面')
    await page.getByPlaceholder('小节标题，例如：适用条件').fill('规则')
    await page.getByPlaceholder('小节正文').fill('第一版内容')
    await page.getByTestId('wiki-save').click()
    await expect(page.getByRole('button', { name: '提交审核' })).toBeVisible()
    await expect(page.getByText('草稿').first()).toBeVisible()

    await page.getByRole('button', { name: '提交审核' }).click()
    await expect(page.getByRole('button', { name: '通过' })).toBeVisible()
    await page.getByRole('button', { name: '通过' }).click()
    await expect(page.getByText('已发布').first()).toBeVisible()

    await page.getByRole('button', { name: '版本历史' }).click()
    await expect(page.getByText('v1 · E2E 审批页面')).toBeVisible()
    await page.keyboard.press('Escape')

    await page.getByPlaceholder('小节正文').fill('第二版内容')
    await page.getByTestId('wiki-save').click()
    await expect(page.getByText('草稿').first()).toBeVisible()

    await page.getByRole('button', { name: '版本历史' }).click()
    await expect(page.getByText('v1 · E2E 审批页面')).toBeVisible()
    await expect(page.getByText('v2 · E2E 审批页面')).toBeVisible()
    await page.getByText('v1 · E2E 审批页面').click()
    await page.getByRole('button', { name: '与当前内容对比' }).click()
    const diffDialog = page.getByRole('dialog', { name: '版本差异（版本 → 当前内容）' })
    await expect(diffDialog.getByText('第一版内容')).toBeVisible()
    await expect(diffDialog.getByText('第二版内容')).toBeVisible()
    await page.keyboard.press('Escape')
    await page.getByRole('button', { name: '回滚到此版本' }).click()
    await page.getByRole('button', { name: '确定' }).click()
    await expect(page.getByText('已发布').first()).toBeVisible()

    await page.getByRole('button', { name: '退出' }).click()
    await login(page, 'platform', 'platform@helpdesk.test', 'platform123')
    await page.getByRole('menuitem', { name: '平台' }).click()

    const row = page.getByRole('row', { name: new RegExp(tenantName) })
    await expect(row).toBeVisible()
    await row.getByRole('button', { name: '移入回收站' }).click()
    await page.locator('.el-message-box__input input').fill(slug)
    await page.getByRole('button', { name: '确认移入' }).click()
    await expect(row).toHaveCount(0)

    await page.locator('.el-switch').click()
    const deletedRow = page.getByRole('row', { name: new RegExp(tenantName) })
    await expect(deletedRow).toBeVisible()
    await expect(deletedRow.getByText('DELETED')).toBeVisible()
    await deletedRow.getByRole('button', { name: '恢复' }).click()
    await expect(deletedRow.getByText('ACTIVE')).toBeVisible()
    await expect(page.getByText('tenant.soft_delete').first()).toBeVisible()
    await expect(page.getByText('tenant.restore').first()).toBeVisible()
  } finally {
    const cleanupLogin = await request.post('/api/auth/login', {
      data: { tenantSlug: 'platform', email: 'platform@helpdesk.test', password: 'platform123' },
    })
    if (cleanupLogin.ok()) {
      const token = (await cleanupLogin.json()).token as string
      const headers = { Authorization: `Bearer ${token}` }
      await request.delete(`/api/platform/tenants/${tenantId}?confirm=${encodeURIComponent(slug)}`, { headers })
      await request.delete(`/api/platform/tenants/${tenantId}/purge?confirm=${encodeURIComponent(slug)}`, { headers })
    }
  }
})
