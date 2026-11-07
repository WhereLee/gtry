export type Role = 'AGENT' | 'TENANT_ADMIN' | 'PLATFORM_ADMIN'

export interface CurrentUser {
  id: string
  email: string
  displayName: string
  role: Role
  tenantId: string
  tenantSlug: string
}

export interface LoginResponse {
  token: string
  user: CurrentUser
}

export interface Ticket {
  id: string
  subject: string
  description: string
  status: string
  priority: string
  category: string
  triageSource?: string
  triageReason?: string
  customerName?: string
  customerEmail?: string
  csatRating?: number | null
  csatComment?: string | null
  csatAt?: string | null
  firstResponseAt?: string | null
  resolvedAt?: string | null
  slaDueAt?: string | null
  slaBreached: boolean
  assigneeId?: string | null
  createdAt: string
  updatedAt: string
}

export interface TicketMessage {
  id: string
  authorType: string
  authorId?: string | null
  content: string
  visibility: string
  citations?: string | null
  tokens: number
  createdAt: string
}

export interface TicketDetail {
  ticket: Ticket
  messages: TicketMessage[]
}

export interface TicketPage {
  items: Ticket[]
  page: number
  size: number
  total: number
  totalPages: number
  hasNext: boolean
}

export interface Agent {
  id: string
  displayName: string
  email: string
  role: string
}

export interface TeamUser {
  id: string
  email: string
  displayName: string
  role: string
  status: string
}

export interface Attachment {
  id: string
  filename: string
  contentType: string
  sizeBytes: number
  visibility: string
  createdAt: string
}

export interface ToolCall {
  id: string
  tool_name: string
  status: string
  result?: string | null
  createdAt?: string
}

export interface WikiSpace {
  id: string
  slug: string
  name: string
  description?: string
}

export interface WikiPage {
  id: string
  spaceId: string
  spaceName: string
  slug: string
  title: string
  visibility: string
  status: string
  lastReviewedAt?: string | null
  updatedAt: string
}

export interface WikiSection {
  id: string
  seq: number
  headingPath: string
  content: string
}

export interface WikiPageDetail {
  page: WikiPage
  sections: WikiSection[]
}

export interface WikiPageVersion {
  id: string
  version: number
  title: string
  visibility: string
  status: string
  createdBy?: string | null
  createdAt: string
}

export interface WikiPageVersionDetail {
  version: WikiPageVersion
  sections: Array<{ heading: string; content: string }>
}

export interface WikiHit {
  sectionId: string
  pageId: string
  pageTitle: string
  headingPath: string
  content: string
  visibility: string
  updatedAt: string
  score: number
  fusedScore: number
  rerankScore: number
}

export interface WikiSearchResult {
  traceId: string
  hits: WikiHit[]
  stats: {
    vectorCount: number
    ftsCount: number
    totalMs: number
  }
}

export interface UsageSummary {
  tenantSlug: string
  tokensThisMonth: number
  monthlyTokenBudget: number
  costMicrosThisMonth: number
  rateLimitPerMinute: number
}

export interface DailyUsage {
  date: string
  tokens: number
  costMicros: number
}

export interface Report {
  days: number
  tickets: {
    total: number
    open: number
    pending: number
    closed: number
    sla_breached: number
    first_response_avg_seconds: number | null
    resolution_avg_seconds: number | null
    csat_avg: number | null
    csat_count: number
  }
  aiFeedback: {
    adopt: number
    edit: number
    reject: number
    total: number
    adoptionRate: number
  }
  byCategory: Array<{ category: string; count: number }>
  byPriority: Array<{ priority: string; count: number }>
  usage: UsageSummary
}

export interface Tenant {
  id: string
  slug: string
  name: string
  plan: string
  status: string
  monthlyTokenBudget: number
  userCount: number
  ticketCount: number
  tokensThisMonth: number
  costMicrosThisMonth: number
  createdAt: string
  deletedAt?: string | null
}

export interface AiConfig {
  model: string
  topK: number
  extraInstructions: string
  enabled: boolean
}

export interface NotificationItem {
  id: string
  type: string
  title: string
  body: string
  targetType?: string | null
  targetId?: string | null
  readAt?: string | null
  createdAt: string
}

export interface PublicMessage {
  authorType: string
  content: string
  createdAt: string
}

export interface PublicTicket {
  id: string
  subject: string
  status: string
  category: string
  createdAt: string
  updatedAt: string
  csatRating?: number | null
  csatComment?: string | null
  messages: PublicMessage[]
}

export interface CreatePublicTicketResponse {
  ticketId: string
  token: string
  expiresAt: string
  status: string
}
