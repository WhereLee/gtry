export const CATEGORY_LABELS: Record<string, string> = {
  RETURNS: '退货售后', SHIPPING: '配送物流', INVOICE: '发票财务', PAYMENT: '支付问题',
  ACCOUNT: '账户安全', MEMBER: '会员权益', PRODUCT: '商品知识', PROMOTION: '优惠活动',
  COMPLAINT: '投诉处理', QUOTA: '配额限制', BILLING: '计费套餐', PERMISSIONS: '权限管理',
  INTEGRATION: '集成对接', SECURITY: '安全合规', BACKUP: '备份恢复', UPGRADE: '升级降级',
  EXPORT: '数据导出', DELETION: '数据删除', PRIVACY: '隐私合规', SUPPORT: '服务支持',
  OTHER: '其他',
}

export const PRIORITY_LABELS: Record<string, string> = {
  LOW: '低', NORMAL: '普通', HIGH: '高', URGENT: '紧急',
}

export const STATUS_LABELS: Record<string, string> = {
  OPEN: '待处理', PENDING: '处理中', CLOSED: '已关闭',
}

export const AUTHOR_LABELS: Record<string, string> = {
  CUSTOMER: '客户', AGENT: '客服', AI: 'AI',
}

export function categoryLabel(code?: string | null) {
  return CATEGORY_LABELS[code || ''] || code || '其他'
}

export function priorityLabel(code?: string | null) {
  return PRIORITY_LABELS[code || ''] || code || '普通'
}

export function statusLabel(code?: string | null) {
  return STATUS_LABELS[code || ''] || code || '未知'
}

export function authorLabel(code?: string | null) {
  return AUTHOR_LABELS[code || ''] || code || '系统'
}

export function formatCost(micros?: number | null) {
  return `¥${(Number(micros || 0) / 1_000_000).toFixed(4)}`
}

export function formatSize(bytes?: number | null) {
  const value = Number(bytes || 0)
  if (value < 1024) return `${value} B`
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`
  return `${(value / 1024 / 1024).toFixed(1)} MB`
}

export function formatSeconds(value?: number | null) {
  if (value === null || value === undefined) return '暂无'
  const seconds = Number(value)
  return seconds < 60 ? `${Math.round(seconds)} 秒` : `${(seconds / 60).toFixed(1)} 分钟`
}

export function formatDate(value?: string | null) {
  return value ? new Date(value).toLocaleDateString('zh-CN') : '—'
}

export function formatDateTime(value?: string | null) {
  return value
    ? new Date(value).toLocaleString('zh-CN', {
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
        hour12: false,
      })
    : '—'
}

export function priorityType(priority?: string | null) {
  if (priority === 'URGENT') return 'danger' as const
  if (priority === 'HIGH') return 'warning' as const
  if (priority === 'LOW') return 'info' as const
  return 'primary' as const
}

export function statusType(status?: string | null) {
  if (status === 'CLOSED') return 'info' as const
  if (status === 'PENDING') return 'warning' as const
  return 'success' as const
}
