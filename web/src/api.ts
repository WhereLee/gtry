const TOKEN_KEY = 'helpdesk.token'

const pagePath = window.location.pathname
const pageBase = pagePath.endsWith('/')
  ? pagePath
  : pagePath.slice(0, pagePath.lastIndexOf('/') + 1)
export const API_BASE = `${pageBase}api`

export function apiUrl(path: string): string {
  if (path.startsWith('/api/')) return `${API_BASE}${path.slice(4)}`
  return path
}

export class ApiError extends Error {
  status: number

  constructor(message: string, status: number) {
    super(message)
    this.status = status
  }
}

export function getToken(): string | null {
  return sessionStorage.getItem(TOKEN_KEY)
}

export function setToken(token: string | null) {
  if (token) sessionStorage.setItem(TOKEN_KEY, token)
  else sessionStorage.removeItem(TOKEN_KEY)
}

export async function api<T>(path: string, options: RequestInit = {}): Promise<T> {
  const headers = new Headers(options.headers)
  const token = getToken()
  if (token) headers.set('Authorization', `Bearer ${token}`)
  if (options.body && !(options.body instanceof FormData) && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }

  const response = await fetch(apiUrl(path), { ...options, headers })
  if (response.status === 401) {
    setToken(null)
    window.dispatchEvent(new CustomEvent('helpdesk:unauthorized'))
  }
  if (!response.ok) {
    throw new ApiError(await response.text(), response.status)
  }
  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}

export async function download(path: string, filename: string): Promise<void> {
  const headers = new Headers()
  const token = getToken()
  if (token) headers.set('Authorization', `Bearer ${token}`)
  const response = await fetch(apiUrl(path), { headers })
  if (!response.ok) throw new ApiError(await response.text(), response.status)
  const blob = await response.blob()
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(url)
}

export function errorCode(error: unknown): string {
  let message = error instanceof Error ? error.message : '请求失败'
  try {
    const parsed = JSON.parse(message)
    message = parsed.error || message
  } catch {
    // keep raw message
  }
  return message
}

export function friendlyError(error: unknown): string {
  const code = errorCode(error)
  const map: Record<string, string> = {
    tenant_admin_required: '需要租户管理员账号（admin@acme.test / admin123）',
    platform_admin_required: '需要平台管理员账号（platform@helpdesk.test / platform123）',
    tenant_exists: '该租户 slug 已存在',
    confirm_slug_required: '删除租户需要输入正确的 slug 确认',
    cannot_delete_platform: '平台租户不能删除',
    cannot_suspend_platform: '平台租户不能停用',
    user_exists: '该邮箱已存在',
    invalid_role: '角色只能是 AGENT 或 TENANT_ADMIN',
    cannot_disable_self: '不能停用当前登录账号',
    last_tenant_admin: '至少保留一个启用的租户管理员',
    invalid_channel: '渠道无效，请检查访问链接',
    invalid_request: '请求不合法',
    rate_limited: '请求过于频繁，请稍后再试',
    ticket_not_found: '工单不存在或查询码已过期',
    ticket_not_closed: '工单关闭后才能评价',
    login_failed: '登录失败，请检查租户、邮箱和密码',
  }
  return map[code] || code
}
