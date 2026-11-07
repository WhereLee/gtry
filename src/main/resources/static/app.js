const CATEGORY_LABELS = {
    RETURNS: '退货售后', SHIPPING: '配送物流', INVOICE: '发票财务', PAYMENT: '支付问题',
    ACCOUNT: '账户安全', MEMBER: '会员权益', PRODUCT: '商品知识', PROMOTION: '优惠活动',
    COMPLAINT: '投诉处理', QUOTA: '配额限制', BILLING: '计费套餐', PERMISSIONS: '权限管理',
    INTEGRATION: '集成对接', SECURITY: '安全合规', BACKUP: '备份恢复', UPGRADE: '升级降级',
    EXPORT: '数据导出', DELETION: '数据删除', PRIVACY: '隐私合规', SUPPORT: '服务支持',
    OTHER: '其他',
};

const PRIORITY_LABELS = { LOW: '低', NORMAL: '普通', HIGH: '高', URGENT: '紧急' };

const state = {
    token: sessionStorage.getItem('helpdesk.token'),
    user: null,
    tickets: [],
    activeId: null,
    tab: 'tickets',
    wikiSpaces: [],
    wikiPages: [],
    wikiSpaceId: null,
    wikiPageId: null,
    agents: [],
    platformTenants: [],
    teamUsers: [],
};

const $ = (id) => document.getElementById(id);

function categoryLabel(code) {
    return CATEGORY_LABELS[code] || code || '其他';
}

function priorityLabel(code) {
    return PRIORITY_LABELS[code] || code || '普通';
}

function formatCost(micros) {
    return `¥${(Number(micros || 0) / 1_000_000).toFixed(4)}`;
}

function authHeaders() {
    return { Authorization: `Bearer ${state.token}` };
}

async function api(path, options = {}) {
    const response = await fetch(path, {
        ...options,
        headers: { ...(options.headers || {}), ...authHeaders() },
    });
    if (response.status === 401) {
        logout();
        throw new Error('登录已过期');
    }
    if (!response.ok) {
        const text = await response.text();
        throw new Error(text || `HTTP ${response.status}`);
    }
    return response.status === 204 ? null : response.json();
}

async function login(event) {
    event.preventDefault();
    $('login-error').textContent = '';
    const payload = {
        tenantSlug: $('tenant').value,
        email: $('email').value,
        password: $('password').value,
    };
    const response = await fetch('/api/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
    });
    if (!response.ok) {
        $('login-error').textContent = '登录失败，请检查租户、邮箱和密码';
        return;
    }
    const data = await response.json();
    state.token = data.token;
    state.user = data.user;
    sessionStorage.setItem('helpdesk.token', data.token);
    await enterApp();
}

async function logout() {
    const token = state.token;
    if (token) {
        try {
            await fetch('/api/auth/logout', {
                method: 'POST',
                headers: { Authorization: `Bearer ${token}` },
            });
        } catch (ignored) {
            // local logout should still proceed
        }
    }
    state.token = null;
    state.user = null;
    sessionStorage.removeItem('helpdesk.token');
    $('app-view').classList.add('hidden');
    $('login-view').classList.remove('hidden');
    switchTab('tickets');
}

async function enterApp() {
    $('login-view').classList.add('hidden');
    $('app-view').classList.remove('hidden');
    if (!state.user) {
        state.user = await api('/api/auth/me');
    }
    $('who-tenant').textContent = state.user.tenantSlug;
    $('who-user').textContent = `${state.user.displayName} · ${state.user.role}`;
    const channelKey = state.user.tenantSlug === 'cloudwise' ? 'cloudwise-demo-key' : 'acme-demo-key';
    $('portal-link').href = `/portal.html?channel=${channelKey}`;
    $('portal-link').classList.toggle('hidden', state.user.role === 'PLATFORM_ADMIN');
    $('tab-team').classList.toggle('hidden', state.user.role !== 'TENANT_ADMIN');
    $('tab-ai').classList.toggle('hidden', state.user.role !== 'TENANT_ADMIN');
    $('tab-report').classList.toggle('hidden', state.user.role === 'PLATFORM_ADMIN');
    $('tab-platform').classList.toggle('hidden', state.user.role !== 'PLATFORM_ADMIN');
    initTriageOptions();
    await Promise.all([loadUsage(), loadTickets(), loadAgents()]);
}

async function loadUsage() {
    try {
        const usage = await api('/api/usage/summary');
        $('usage').textContent = `${usage.tokensThisMonth} / ${usage.monthlyTokenBudget} tokens`;
    } catch (error) {
        $('usage').textContent = '用量不可用';
    }
}

async function loadAgents() {
    try {
        state.agents = await api('/api/users/agents');
    } catch (error) {
        state.agents = [];
    }
    const select = $('triage-assignee');
    select.innerHTML = '';
    const none = document.createElement('option');
    none.value = '';
    none.textContent = '不指定';
    select.appendChild(none);
    for (const agent of state.agents) {
        const option = document.createElement('option');
        option.value = agent.id;
        option.textContent = `${agent.displayName} · ${agent.email}`;
        select.appendChild(option);
    }
}

function initTriageOptions() {
    const category = $('triage-category');
    category.innerHTML = '';
    for (const [code, label] of Object.entries(CATEGORY_LABELS)) {
        const option = document.createElement('option');
        option.value = code;
        option.textContent = `${label}（${code}）`;
        category.appendChild(option);
    }
    const priority = $('triage-priority');
    priority.innerHTML = '';
    for (const [code, label] of Object.entries(PRIORITY_LABELS)) {
        const option = document.createElement('option');
        option.value = code;
        option.textContent = `${label}（${code}）`;
        priority.appendChild(option);
    }
}

async function loadTickets() {
    const status = $('status-filter').value;
    const suffix = status ? `?status=${encodeURIComponent(status)}` : '';
    state.tickets = await api(`/api/tickets${suffix}`);
    renderTickets();
}

function renderTickets() {
    const list = $('ticket-list');
    list.innerHTML = '';
    for (const ticket of state.tickets) {
        const li = document.createElement('li');
        li.className = 'ticket' + (ticket.id === state.activeId ? ' active' : '');
        if (ticket.slaBreached) {
            li.classList.add('sla-breached');
        }
        li.innerHTML = `
            <div class="title"></div>
            <div class="sub"><span></span><span></span><span></span></div>`;
        li.querySelector('.title').textContent = ticket.subject;
        const [status, priority, category] = li.querySelectorAll('.sub span');
        status.textContent = ticket.status;
        priority.textContent = priorityLabel(ticket.priority);
        category.textContent = categoryLabel(ticket.category);
        li.onclick = () => openTicket(ticket.id);
        list.appendChild(li);
    }
}

async function createTicket(event) {
    event.preventDefault();
    const subject = $('subject').value.trim();
    const description = $('description').value.trim();
    if (!subject || !description) return;
    const ticket = await api('/api/tickets', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ subject, description }),
    });
    $('subject').value = '';
    $('description').value = '';
    await loadTickets();
    await openTicket(ticket.id);
}

async function openTicket(id) {
    state.activeId = id;
    renderTickets();
    const detail = await api(`/api/tickets/${id}`);
    $('empty-detail').classList.add('hidden');
    $('ticket-panel').classList.remove('hidden');
    $('ticket-subject').textContent = detail.ticket.subject;
    const customer = [detail.ticket.customerName, detail.ticket.customerEmail]
        .filter(Boolean).join(' · ');
    $('ticket-meta').textContent =
        `${detail.ticket.status} · ${priorityLabel(detail.ticket.priority)} · ` +
        `${categoryLabel(detail.ticket.category)}` +
        `${customer ? ' · ' + customer : ''}` +
        `${detail.ticket.slaBreached ? ' · SLA 已超时' : ''}` +
        ` · ${new Date(detail.ticket.createdAt).toLocaleString()}`;
    renderTriage(detail.ticket);
    renderCsat(detail.ticket);
    renderMessages(detail.messages);
    loadTicketAttachments().catch(showApiError);
    loadToolCalls().catch(showApiError);
    $('citations').classList.add('hidden');
    $('reply-content').value = '';
    $('internal-note').checked = false;
}

async function loadTicketAttachments() {
    if (!state.activeId) return;
    const list = await api(`/api/tickets/${state.activeId}/attachments`);
    const box = $('ticket-attachments');
    box.innerHTML = '';
    box.classList.toggle('hidden', !list.length);
    for (const attachment of list) {
        const row = document.createElement('div');
        row.className = 'attachment-row';
        const name = document.createElement('span');
        name.textContent = `${attachment.filename} · ${formatSize(attachment.sizeBytes)} · ${attachment.visibility}`;
        const button = document.createElement('button');
        button.className = 'link';
        button.textContent = '下载';
        button.onclick = () => downloadAttachment(attachment.id, attachment.filename).catch(showApiError);
        row.append(name, button);
        box.appendChild(row);
    }
}

async function loadToolCalls() {
    if (!state.activeId) return;
    const list = await api(`/api/tickets/${state.activeId}/tool-calls`);
    const box = $('ticket-tool-calls');
    box.innerHTML = '';
    box.classList.toggle('hidden', !list.length);
    for (const call of list) {
        const row = document.createElement('div');
        row.className = 'attachment-row';
        const text = document.createElement('span');
        let summary = call.result || '';
        try {
            const parsed = JSON.parse(call.result || '{}');
            summary = parsed.orderNo ? `订单 ${parsed.orderNo} · ${parsed.status}` : summary;
        } catch (ignored) {
            // keep raw
        }
        text.textContent = `工具 ${call.tool_name} · ${call.status} · ${summary}`;
        row.appendChild(text);
        box.appendChild(row);
    }
}

async function uploadAttachment(event) {
    event.preventDefault();
    if (!state.activeId) return;
    const file = $('attachment-file').files[0];
    if (!file) return;
    const form = new FormData();
    form.append('file', file);
    form.append('visibility', $('attachment-internal').checked ? 'INTERNAL' : 'PUBLIC');
    await fetch(`/api/tickets/${state.activeId}/attachments`, {
        method: 'POST',
        headers: authHeaders(),
        body: form,
    }).then(async (response) => {
        if (!response.ok) throw new Error(await response.text());
    });
    $('attachment-file').value = '';
    $('attachment-internal').checked = false;
    await loadTicketAttachments();
}

async function downloadAttachment(attachmentId, filename) {
    const response = await fetch(`/api/attachments/${attachmentId}`, { headers: authHeaders() });
    if (!response.ok) throw new Error(await response.text());
    const blob = await response.blob();
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = filename;
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(url);
}

function formatSize(bytes) {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

function renderCsat(ticket) {
    const box = $('ticket-csat');
    if (ticket.csatRating) {
        box.classList.remove('hidden');
        $('ticket-csat-value').textContent = `${ticket.csatRating}/5`;
        $('ticket-csat-comment').textContent = ticket.csatComment || '';
    } else {
        box.classList.add('hidden');
    }
}

function renderTriage(ticket) {
    $('ticket-category').textContent = categoryLabel(ticket.category);
    $('ticket-priority').textContent = priorityLabel(ticket.priority);
    const assignee = state.agents.find((agent) => agent.id === ticket.assigneeId);
    $('ticket-assignee').textContent = assignee
        ? `${assignee.displayName} · ${assignee.email}`
        : (ticket.assigneeId || '未分配');
    $('ticket-triage-reason').textContent =
        `${ticket.triageSource || 'RULE'} · ${ticket.triageReason || '未记录'}`;
    $('triage-category').value = ticket.category || 'OTHER';
    $('triage-priority').value = ticket.priority || 'NORMAL';
    $('triage-assignee').value = ticket.assigneeId || '';
}

async function retriage() {
    if (!state.activeId) return;
    await api(`/api/tickets/${state.activeId}/triage`, { method: 'POST' });
    await loadTickets();
    await openTicket(state.activeId);
}

async function applyTriage() {
    if (!state.activeId) return;
    await api(`/api/tickets/${state.activeId}/triage`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            category: $('triage-category').value,
            priority: $('triage-priority').value,
            assigneeId: $('triage-assignee').value || null,
        }),
    });
    await loadTickets();
    await openTicket(state.activeId);
}

async function sendReply(event) {
    event.preventDefault();
    if (!state.activeId) return;
    const content = $('reply-content').value.trim();
    if (!content) return;
    const internal = $('internal-note').checked;
    const endpoint = internal
        ? `/api/tickets/${state.activeId}/notes`
        : `/api/tickets/${state.activeId}/messages`;
    await api(endpoint, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ content }),
    });
    $('reply-content').value = '';
    $('internal-note').checked = false;
    await loadTickets();
    await openTicket(state.activeId);
}

function renderMessages(messages) {
    const container = $('messages');
    container.innerHTML = '';
    for (const message of messages) {
        container.appendChild(messageNode(
            message.authorType, message.content, message.tokens, message.visibility));
    }
    container.scrollTop = container.scrollHeight;
}

function messageNode(authorType, content, tokens, visibility = 'PUBLIC') {
    const node = document.createElement('div');
    const kind = authorType.toLowerCase();
    node.className = `msg ${kind}` + (visibility === 'INTERNAL' ? ' internal' : '');
    const meta = document.createElement('div');
    meta.className = 'meta';
    meta.textContent = authorType +
        (visibility === 'INTERNAL' ? ' · 内部备注' : '') +
        (tokens ? ` · ${tokens} tokens` : '');
    const body = document.createElement('div');
    body.textContent = content;
    node.append(meta, body);
    return node;
}

async function suggest() {
    if (!state.activeId) return;
    const button = $('ai-button');
    button.disabled = true;
    button.textContent = '生成中…';

    const node = messageNode('AI', '', 0);
    node.querySelector('div:last-child').classList.add('cursor');
    $('messages').appendChild(node);
    const body = node.querySelector('div:last-child');
    const container = $('messages');
    container.scrollTop = container.scrollHeight;

    let citations = [];
    let doneEvent = null;
    try {
        const response = await fetch(`/api/tickets/${state.activeId}/ai-suggest`, {
            method: 'POST',
            headers: authHeaders(),
        });
        if (!response.ok || !response.body) {
            throw new Error(`HTTP ${response.status}`);
        }
        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';
        while (true) {
            const { value, done } = await reader.read();
            if (done) break;
            buffer += decoder.decode(value, { stream: true });
            let index;
            while ((index = buffer.indexOf('\n\n')) >= 0) {
                const chunk = buffer.slice(0, index);
                buffer = buffer.slice(index + 2);
                handleEvent(chunk, body, (event) => {
                    doneEvent = event;
                    citations = JSON.parse(event.citations || '[]');
                });
                container.scrollTop = container.scrollHeight;
            }
        }
    } catch (error) {
        body.textContent += `\n[生成失败：${error.message}]`;
    } finally {
        body.classList.remove('cursor');
        button.disabled = false;
        button.textContent = 'AI 建议回复';
        renderCitations(citations);
        if (doneEvent) {
            renderAiActions(node, body, doneEvent);
        }
        await loadUsage();
    }
}

function renderAiActions(node, body, doneEvent) {
    if (!doneEvent || !doneEvent.messageId) return;
    const actions = document.createElement('div');
    actions.className = 'ai-actions';

    const adopt = document.createElement('button');
    adopt.textContent = '采纳';
    adopt.onclick = () => feedback(doneEvent.messageId, 'ADOPT', null).catch(showApiError);

    const edit = document.createElement('button');
    edit.textContent = '修改后发送';
    edit.onclick = () => {
        const edited = prompt('修改后发送：', body.textContent);
        if (edited && edited.trim()) {
            feedback(doneEvent.messageId, 'EDIT', edited.trim()).catch(showApiError);
        }
    };

    const reject = document.createElement('button');
    reject.textContent = '驳回';
    reject.onclick = () => feedback(doneEvent.messageId, 'REJECT', null).catch(showApiError);

    actions.append(adopt, edit, reject);
    node.appendChild(actions);
}

async function feedback(messageId, action, editedContent) {
    await api(`/api/tickets/${state.activeId}/messages/${messageId}/feedback`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ action, editedContent }),
    });
    await loadTickets();
    await openTicket(state.activeId);
}

function handleEvent(chunk, body, onDone) {
    let event = 'message';
    const dataLines = [];
    for (const line of chunk.split('\n')) {
        if (line.startsWith('event:')) event = line.slice(6).trim();
        else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim());
    }
    if (!dataLines.length) return;
    const data = dataLines.join('\n');
    if (event === 'token') {
        body.textContent += JSON.parse(data).text;
    } else if (event === 'done') {
        onDone(JSON.parse(data));
    } else if (event === 'error') {
        body.textContent += `\n[${JSON.parse(data).message}]`;
    }
}

function renderCitations(citations) {
    const box = $('citations');
    if (!citations || !citations.length) {
        box.classList.add('hidden');
        return;
    }
    box.classList.remove('hidden');
    box.innerHTML = '<b>引用来源</b><br/>';
    for (const citation of citations) {
        const span = document.createElement('span');
        span.className = 'cite';
        const heading = citation.headingPath ? citation.headingPath.split(' > ').pop() : '';
        span.textContent = `${citation.title}${heading ? ' > ' + heading : ''} · ${citation.score}`;
        box.appendChild(span);
    }
}

async function closeTicket() {
    if (!state.activeId) return;
    await api(`/api/tickets/${state.activeId}/status?status=CLOSED`, { method: 'PATCH' });
    await loadTickets();
    await openTicket(state.activeId);
}

function switchTab(tab) {
    if (tab === 'platform' && (!state.user || state.user.role !== 'PLATFORM_ADMIN')) {
        tab = 'tickets';
    }
    if (tab === 'team' && (!state.user || state.user.role !== 'TENANT_ADMIN')) {
        tab = 'tickets';
    }
    if (tab === 'ai' && (!state.user || state.user.role !== 'TENANT_ADMIN')) {
        tab = 'tickets';
    }
    if (tab === 'report' && (!state.user || state.user.role === 'PLATFORM_ADMIN')) {
        tab = 'tickets';
    }
    state.tab = tab;
    $('tickets-view').classList.toggle('hidden', tab !== 'tickets');
    $('wiki-view').classList.toggle('hidden', tab !== 'wiki');
    $('usage-view').classList.toggle('hidden', tab !== 'usage');
    $('team-view').classList.toggle('hidden', tab !== 'team');
    $('ai-view').classList.toggle('hidden', tab !== 'ai');
    $('report-view').classList.toggle('hidden', tab !== 'report');
    $('platform-view').classList.toggle('hidden', tab !== 'platform');
    $('tab-tickets').classList.toggle('active', tab === 'tickets');
    $('tab-wiki').classList.toggle('active', tab === 'wiki');
    $('tab-usage').classList.toggle('active', tab === 'usage');
    $('tab-team').classList.toggle('active', tab === 'team');
    $('tab-ai').classList.toggle('active', tab === 'ai');
    $('tab-report').classList.toggle('active', tab === 'report');
    $('tab-platform').classList.toggle('active', tab === 'platform');
    if (tab === 'wiki' && !state.wikiSpaces.length) {
        loadWikiSpaces().catch(showApiError);
    }
    if (tab === 'usage') {
        loadUsagePanel().catch(showApiError);
    }
    if (tab === 'team') {
        loadTeam().catch(showApiError);
    }
    if (tab === 'ai') {
        loadAiConfig().catch(showApiError);
    }
    if (tab === 'report') {
        loadReport().catch(showApiError);
    }
    if (tab === 'platform') {
        loadPlatform().catch(showApiError);
    }
}

async function loadWikiSpaces() {
    state.wikiSpaces = await api('/api/wiki/spaces');
    const list = $('space-list');
    list.innerHTML = '';
    for (const space of state.wikiSpaces) {
        const li = document.createElement('li');
        li.className = 'ticket' + (space.id === state.wikiSpaceId ? ' active' : '');
        li.innerHTML = '<div class="title"></div><div class="sub"><span></span></div>';
        li.querySelector('.title').textContent = space.name;
        li.querySelector('.sub span').textContent = space.slug;
        li.onclick = () => {
            state.wikiSpaceId = space.id;
            state.wikiPageId = null;
            loadWikiSpaces().catch(showApiError);
            loadWikiPages(space.id).catch(showApiError);
        };
        list.appendChild(li);
    }
    if (state.wikiSpaceId) {
        await loadWikiPages(state.wikiSpaceId);
    }
}

async function loadWikiPages(spaceId) {
    if (!spaceId) return;
    state.wikiPages = await api(`/api/wiki/pages?spaceId=${encodeURIComponent(spaceId)}&limit=100`);
    const list = $('page-list');
    list.innerHTML = '';
    for (const page of state.wikiPages) {
        const li = document.createElement('li');
        li.className = 'ticket' + (page.id === state.wikiPageId ? ' active' : '');
        li.innerHTML = '<div class="title"></div><div class="sub"><span></span><span></span></div>';
        li.querySelector('.title').textContent = page.title;
        const [visibility, updated] = li.querySelectorAll('.sub span');
        visibility.textContent = page.visibility;
        updated.textContent = new Date(page.updatedAt).toLocaleDateString();
        li.onclick = () => openWikiPage(page.id).catch(showApiError);
        list.appendChild(li);
    }
}

function newWikiPage() {
    if (!state.wikiSpaceId) {
        alert('先选择一个知识空间');
        return;
    }
    state.wikiPageId = null;
    $('wiki-title').textContent = '新建页面';
    $('wiki-meta').textContent = '填写标题和小节后保存';
    $('wiki-page-title').value = '';
    $('wiki-visibility').value = 'PUBLIC';
    renderWikiSections([{ heading: '', content: '' }]);
    $('wiki-results').innerHTML = '';
}

async function openWikiPage(id) {
    const detail = await api(`/api/wiki/pages/${id}`);
    state.wikiPageId = id;
    $('wiki-title').textContent = detail.page.title;
    $('wiki-meta').textContent =
        `${detail.page.spaceName} · ${detail.page.visibility} · ${new Date(detail.page.updatedAt).toLocaleString()}`;
    $('wiki-page-title').value = detail.page.title;
    $('wiki-visibility').value = detail.page.visibility;
    renderWikiSections(detail.sections.map((section) => ({
        heading: (section.headingPath || '').split(' > ').pop(),
        content: section.content,
    })));
    await loadWikiPages(state.wikiSpaceId);
}

function renderWikiSections(sections) {
    const box = $('wiki-sections');
    box.innerHTML = '';
    for (const section of sections) {
        addSectionCard(section.heading, section.content);
    }
}

function addSectionCard(heading = '', content = '') {
    const card = document.createElement('div');
    card.className = 'section-card';
    card.innerHTML = `
        <div class="section-head">
            <input class="section-heading" placeholder="小节标题，例如：适用条件"/>
            <button class="link remove-section">删除</button>
        </div>
        <textarea class="section-content" rows="4" placeholder="小节正文"></textarea>`;
    card.querySelector('.section-heading').value = heading;
    card.querySelector('.section-content').value = content;
    card.querySelector('.remove-section').onclick = () => card.remove();
    $('wiki-sections').appendChild(card);
}

function collectWikiSections() {
    const sections = [];
    for (const card of $('wiki-sections').querySelectorAll('.section-card')) {
        const heading = card.querySelector('.section-heading').value.trim();
        const content = card.querySelector('.section-content').value.trim();
        if (heading && content) {
            sections.push({ heading, content });
        }
    }
    return sections;
}

async function saveWikiPage() {
    if (!state.wikiSpaceId && !state.wikiPageId) {
        alert('先选择一个知识空间');
        return;
    }
    const title = $('wiki-page-title').value.trim();
    const visibility = $('wiki-visibility').value;
    const sections = collectWikiSections();
    if (!title || !sections.length) {
        alert('标题和至少一个小节不能为空');
        return;
    }
    const payload = { title, visibility, sections };
    if (state.wikiPageId) {
        const detail = await api(`/api/wiki/pages/${state.wikiPageId}`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload),
        });
        $('wiki-meta').textContent = `已保存 · ${detail.page.visibility} · ${new Date(detail.page.updatedAt).toLocaleString()}`;
    } else {
        payload.spaceId = state.wikiSpaceId;
        const detail = await api('/api/wiki/pages', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload),
        });
        state.wikiPageId = detail.page.id;
        $('wiki-title').textContent = detail.page.title;
        $('wiki-meta').textContent = `已创建 · ${detail.page.visibility}`;
    }
    await loadWikiPages(state.wikiSpaceId);
}

async function deleteWikiPage() {
    if (!state.wikiPageId) return;
    if (!confirm('确认删除这个页面？')) return;
    await api(`/api/wiki/pages/${state.wikiPageId}`, { method: 'DELETE' });
    state.wikiPageId = null;
    $('wiki-title').textContent = '知识库';
    $('wiki-meta').textContent = '页面已删除';
    renderWikiSections([]);
    await loadWikiPages(state.wikiSpaceId);
}

async function reindexWikiPage() {
    if (!state.wikiPageId) return;
    const result = await api(`/api/wiki/pages/${state.wikiPageId}/reindex`, { method: 'POST' });
    $('wiki-meta').textContent = `索引已重建 · ${result.sections} 个小节`;
}

async function wikiSearch() {
    const query = $('wiki-query').value.trim();
    if (!query) return;
    const result = await api('/api/wiki/search', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ query, topK: 5 }),
    });
    const box = $('wiki-results');
    box.innerHTML = '';
    const meta = document.createElement('div');
    meta.className = 'search-meta';
    meta.textContent = `traceId=${result.traceId} · vector=${result.stats.vectorCount} · fts=${result.stats.ftsCount} · ${result.stats.totalMs}ms`;
    box.appendChild(meta);
    for (const hit of result.hits) {
        const row = document.createElement('div');
        row.className = 'search-hit';
        row.innerHTML = '<div class="hit-title"></div><div class="hit-path"></div><div class="hit-body"></div>';
        row.querySelector('.hit-title').textContent = `${hit.pageTitle} · ${hit.score}`;
        row.querySelector('.hit-path').textContent = hit.headingPath;
        row.querySelector('.hit-body').textContent = hit.content;
        box.appendChild(row);
    }
}

async function loadUsagePanel() {
    const [summary, daily] = await Promise.all([
        api('/api/usage/summary'),
        api('/api/usage/daily?days=14'),
    ]);
    renderUsageCards(summary);
    renderUsageChart(daily);
}

function usageCard(label, value, hint) {
    const card = document.createElement('div');
    card.className = 'card';
    const labelNode = document.createElement('div');
    labelNode.className = 'label';
    labelNode.textContent = label;
    const valueNode = document.createElement('div');
    valueNode.className = 'value';
    valueNode.textContent = value;
    card.append(labelNode, valueNode);
    if (hint) {
        const hintNode = document.createElement('div');
        hintNode.className = 'hint';
        hintNode.textContent = hint;
        card.appendChild(hintNode);
    }
    return card;
}

function renderUsageCards(summary) {
    const box = $('usage-cards');
    box.innerHTML = '';
    const utilization = summary.monthlyTokenBudget > 0
        ? summary.tokensThisMonth / summary.monthlyTokenBudget
        : 0;
    box.appendChild(usageCard(
        '本月 tokens',
        Number(summary.tokensThisMonth).toLocaleString(),
        `预算 ${Number(summary.monthlyTokenBudget).toLocaleString()}`
    ));
    box.appendChild(usageCard(
        '预算使用率',
        `${(utilization * 100).toFixed(1)}%`,
        utilization >= 0.9 ? '接近或超过预算' : '预算充足'
    ));
    box.appendChild(usageCard(
        '本月成本',
        formatCost(summary.costMicrosThisMonth),
        '按当前单价估算'
    ));
    box.appendChild(usageCard(
        '限流',
        `${summary.rateLimitPerMinute} 次/分钟`,
        '租户级固定窗口'
    ));
    const progressCard = usageCard('预算进度', `${(utilization * 100).toFixed(1)}%`, '');
    const progress = document.createElement('div');
    progress.className = 'progress';
    const fill = document.createElement('span');
    fill.style.width = `${Math.min(100, Math.max(0, utilization * 100))}%`;
    progress.appendChild(fill);
    progressCard.appendChild(progress);
    box.appendChild(progressCard);
}

function renderUsageChart(daily) {
    const box = $('usage-chart');
    box.innerHTML = '';
    const max = Math.max(1, ...daily.map((day) => Number(day.tokens)));
    for (const day of daily) {
        const column = document.createElement('div');
        column.className = 'chart-bar';
        const bar = document.createElement('div');
        bar.className = 'bar' + (Number(day.tokens) === 0 ? ' zero' : '');
        bar.style.height = `${Math.max(2, Math.round((Number(day.tokens) / max) * 150))}px`;
        bar.title = `${day.date}: ${Number(day.tokens).toLocaleString()} tokens · ${formatCost(day.costMicros)}`;
        const date = document.createElement('div');
        date.className = 'date';
        date.textContent = day.date.slice(5);
        column.append(bar, date);
        box.appendChild(column);
    }
}

async function loadPlatform() {
    state.platformTenants = await api('/api/platform/tenants');
    renderPlatformTenants();
}

function renderPlatformTenants() {
    const body = $('tenant-table');
    body.innerHTML = '';
    for (const tenant of state.platformTenants) {
        const row = document.createElement('tr');

        const nameCell = document.createElement('td');
        nameCell.innerHTML = '<div class="title"></div><div class="muted small"></div>';
        nameCell.querySelector('.title').textContent = tenant.name;
        nameCell.querySelector('.muted').textContent =
            `${tenant.slug} · ${new Date(tenant.createdAt).toLocaleDateString()}`;

        const statusCell = document.createElement('td');
        const status = document.createElement('span');
        status.className = `badge ${tenant.status.toLowerCase()}`;
        status.textContent = tenant.status;
        statusCell.appendChild(status);

        const planCell = document.createElement('td');
        planCell.textContent = tenant.plan;

        const userCell = document.createElement('td');
        userCell.textContent = tenant.userCount;

        const ticketCell = document.createElement('td');
        ticketCell.textContent = tenant.ticketCount;

        const usageCell = document.createElement('td');
        usageCell.textContent =
            `${Number(tenant.tokensThisMonth).toLocaleString()} / ${Number(tenant.monthlyTokenBudget).toLocaleString()}`;

        const actions = document.createElement('td');
        actions.className = 'actions';

        const toggle = document.createElement('button');
        toggle.textContent = tenant.status === 'ACTIVE' ? '停用' : '启用';
        toggle.disabled = tenant.slug === 'platform';
        toggle.onclick = () => toggleTenant(tenant).catch(showApiError);
        actions.appendChild(toggle);

        const exportButton = document.createElement('button');
        exportButton.textContent = '导出';
        exportButton.onclick = () => downloadTenant(tenant).catch(showApiError);
        actions.appendChild(exportButton);

        const deleteButton = document.createElement('button');
        deleteButton.textContent = '删除';
        deleteButton.disabled = tenant.slug === 'platform';
        deleteButton.onclick = () => deleteTenant(tenant).catch(showApiError);
        actions.appendChild(deleteButton);

        row.append(nameCell, statusCell, planCell, userCell, ticketCell, usageCell, actions);
        body.appendChild(row);
    }
}

async function toggleTenant(tenant) {
    const next = tenant.status === 'ACTIVE' ? 'SUSPENDED' : 'ACTIVE';
    await api(`/api/platform/tenants/${tenant.id}/status?status=${next}`, { method: 'PATCH' });
    await loadPlatform();
}

async function downloadTenant(tenant) {
    const response = await fetch(`/api/platform/tenants/${tenant.id}/export`, {
        headers: authHeaders(),
    });
    if (!response.ok) {
        throw new Error(await response.text());
    }
    const blob = await response.blob();
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `tenant-${tenant.slug}.json`;
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(url);
}

async function deleteTenant(tenant) {
    const confirmSlug = prompt(
        `删除租户 ${tenant.slug} 会级联删除所有数据，且不可恢复。输入 slug 确认：`);
    if (confirmSlug === null) return;
    if (confirmSlug !== tenant.slug) {
        alert('slug 不匹配，已取消');
        return;
    }
    const job = await api(
        `/api/platform/tenants/${tenant.id}?confirm=${encodeURIComponent(confirmSlug)}`,
        { method: 'DELETE' }
    );
    if (job.state === 'RUNNING' || job.state === 'IDLE') {
        await pollDeletion(tenant.id);
    }
    await loadPlatform();
}

async function pollDeletion(tenantId) {
    for (let i = 0; i < 30; i++) {
        const job = await api(`/api/platform/tenants/${tenantId}/delete-status`);
        if (job.state === 'DONE') return;
        if (job.state === 'FAILED') {
            throw new Error(job.message || '删除失败');
        }
        await new Promise((resolve) => setTimeout(resolve, 1000));
    }
    throw new Error('删除任务超时');
}

async function createTenant(event) {
    event.preventDefault();
    const payload = {
        slug: $('tenant-slug').value.trim(),
        name: $('tenant-name').value.trim(),
        plan: $('tenant-plan').value,
        monthlyTokenBudget: Number($('tenant-budget').value || 200000),
        adminEmail: $('tenant-admin-email').value.trim(),
        adminPassword: $('tenant-admin-password').value,
        adminDisplayName: $('tenant-admin-name').value.trim(),
    };
    await api('/api/platform/tenants', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
    });
    $('tenant-form').reset();
    $('tenant-budget').value = '200000';
    $('tenant-plan').value = 'pro';
    await loadPlatform();
}

async function loadTeam() {
    state.teamUsers = await api('/api/users');
    renderTeam();
}

function renderTeam() {
    const body = $('team-table');
    body.innerHTML = '';
    for (const user of state.teamUsers) {
        const row = document.createElement('tr');

        const nameCell = document.createElement('td');
        nameCell.innerHTML = '<div class="title"></div><div class="muted small"></div>';
        nameCell.querySelector('.title').textContent = user.displayName;
        nameCell.querySelector('.muted').textContent = user.email;

        const roleCell = document.createElement('td');
        const roleSelect = document.createElement('select');
        for (const role of ['AGENT', 'TENANT_ADMIN']) {
            const option = document.createElement('option');
            option.value = role;
            option.textContent = role;
            roleSelect.appendChild(option);
        }
        roleSelect.value = user.role;
        roleCell.appendChild(roleSelect);

        const statusCell = document.createElement('td');
        const badge = document.createElement('span');
        badge.className = `badge ${user.status === 'ACTIVE' ? 'active' : 'suspended'}`;
        badge.textContent = user.status;
        statusCell.appendChild(badge);

        const actions = document.createElement('td');
        actions.className = 'actions';
        const saveRole = document.createElement('button');
        saveRole.textContent = '保存角色';
        saveRole.onclick = () => updateTeamUser(user.id, { role: roleSelect.value }).catch(showApiError);
        const toggle = document.createElement('button');
        toggle.textContent = user.status === 'ACTIVE' ? '停用' : '启用';
        toggle.onclick = () => updateTeamUser(
            user.id, { status: user.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE' }).catch(showApiError);
        actions.append(saveRole, toggle);

        row.append(nameCell, roleCell, statusCell, actions);
        body.appendChild(row);
    }
}

async function createTeamUser(event) {
    event.preventDefault();
    const payload = {
        email: $('team-email').value.trim(),
        displayName: $('team-name').value.trim(),
        role: $('team-role').value,
        password: $('team-password').value,
    };
    await api('/api/users', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
    });
    $('team-form').reset();
    $('team-role').value = 'AGENT';
    await loadTeam();
}

async function updateTeamUser(id, payload) {
    await api(`/api/users/${id}`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
    });
    await loadTeam();
}

async function loadAiConfig() {
    const config = await api('/api/ai/config');
    $('ai-model').value = config.model || '';
    $('ai-topk').value = config.topK || 5;
    $('ai-extra').value = config.extraInstructions || '';
    $('ai-enabled').value = String(config.enabled);
}

async function saveAiConfig(event) {
    event.preventDefault();
    await api('/api/ai/config', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            model: $('ai-model').value.trim(),
            topK: Number($('ai-topk').value || 5),
            extraInstructions: $('ai-extra').value.trim(),
            enabled: $('ai-enabled').value === 'true',
        }),
    });
    alert('AI 设置已保存');
}

async function loadReport() {
    const report = await api('/api/reports/tenant?days=30');
    const tickets = report.tickets;
    const feedback = report.aiFeedback;
    const usage = report.usage;
    const box = $('report-cards');
    box.innerHTML = '';
    box.appendChild(usageCard('工单总数', tickets.total,
        `待处理 ${tickets.open} · 处理中 ${tickets.pending} · 已关闭 ${tickets.closed}`));
    box.appendChild(usageCard('首次响应均值', formatSeconds(tickets.first_response_avg_seconds), ''));
    box.appendChild(usageCard('解决均值', formatSeconds(tickets.resolution_avg_seconds), ''));
    box.appendChild(usageCard('SLA 超时', tickets.sla_breached, ''));
    box.appendChild(usageCard('AI 采纳率', `${feedback.adoptionRate}%`,
        `采纳 ${feedback.adopt} · 修改 ${feedback.edit} · 驳回 ${feedback.reject}`));
    box.appendChild(usageCard('CSAT', tickets.csat_avg
        ? `${Number(tickets.csat_avg).toFixed(2)} / 5` : '暂无', `${tickets.csat_count} 条评价`));
    box.appendChild(usageCard('本月 tokens', Number(usage.tokensThisMonth).toLocaleString(),
        `预算 ${Number(usage.monthlyTokenBudget).toLocaleString()}`));
    box.appendChild(usageCard('本月成本', formatCost(usage.costMicrosThisMonth), '按当前单价估算'));
    renderDistribution($('report-category'), report.byCategory, 'category', categoryLabel);
    renderDistribution($('report-priority'), report.byPriority, 'priority', priorityLabel);
}

function formatSeconds(value) {
    if (value === null || value === undefined) return '暂无';
    const seconds = Number(value);
    return seconds < 60 ? `${Math.round(seconds)} 秒` : `${(seconds / 60).toFixed(1)} 分钟`;
}

function renderDistribution(tbody, rows, key, labeler) {
    tbody.innerHTML = '';
    for (const row of rows) {
        const tr = document.createElement('tr');
        const name = document.createElement('td');
        name.textContent = labeler(row[key]);
        const count = document.createElement('td');
        count.textContent = row.count;
        tr.append(name, count);
        tbody.appendChild(tr);
    }
}

function showApiError(error) {
    let message = error && error.message ? error.message : '请求失败';
    try {
        const parsed = JSON.parse(message);
        message = parsed.error || message;
    } catch (ignored) {
        // keep the raw message
    }
    if (message.includes('tenant_admin_required')) {
        message = '需要租户管理员账号（admin@acme.test / admin123）';
    }
    if (message.includes('platform_admin_required')) {
        message = '需要平台管理员账号（platform@helpdesk.test / platform123）';
    }
    if (message.includes('tenant_exists')) {
        message = '该租户 slug 已存在';
    }
    if (message.includes('confirm_slug_required')) {
        message = '删除租户需要输入正确的 slug 确认';
    }
    if (message.includes('cannot_delete_platform')) {
        message = '平台租户不能删除';
    }
    if (message.includes('cannot_suspend_platform')) {
        message = '平台租户不能停用';
    }
    if (message.includes('user_exists')) {
        message = '该邮箱已存在';
    }
    if (message.includes('invalid_role')) {
        message = '角色只能是 AGENT 或 TENANT_ADMIN';
    }
    if (message.includes('cannot_disable_self')) {
        message = '不能停用当前登录账号';
    }
    if (message.includes('last_tenant_admin')) {
        message = '至少保留一个启用的租户管理员';
    }
    alert(message);
}

$('login-form').addEventListener('submit', login);
$('create-form').addEventListener('submit', createTicket);
$('status-filter').addEventListener('change', loadTickets);
$('ai-button').addEventListener('click', suggest);
$('close-ticket').addEventListener('click', closeTicket);
$('logout').addEventListener('click', logout);
$('tab-tickets').addEventListener('click', () => switchTab('tickets'));
$('tab-wiki').addEventListener('click', () => switchTab('wiki'));
$('tab-usage').addEventListener('click', () => switchTab('usage'));
$('tab-team').addEventListener('click', () => switchTab('team'));
$('tab-ai').addEventListener('click', () => switchTab('ai'));
$('tab-report').addEventListener('click', () => switchTab('report'));
$('tab-platform').addEventListener('click', () => switchTab('platform'));
$('reply-form').addEventListener('submit', (event) => sendReply(event).catch(showApiError));
$('attachment-form').addEventListener('submit', (event) => uploadAttachment(event).catch(showApiError));
$('reply-content').addEventListener('keydown', (event) => {
    if ((event.ctrlKey || event.metaKey) && event.key === 'Enter') {
        sendReply(event).catch(showApiError);
    }
});
$('retriage').addEventListener('click', () => retriage().catch(showApiError));
$('triage-apply').addEventListener('click', () => applyTriage().catch(showApiError));
$('usage-refresh').addEventListener('click', () => loadUsagePanel().catch(showApiError));
$('platform-refresh').addEventListener('click', () => loadPlatform().catch(showApiError));
$('tenant-form').addEventListener('submit', (event) => createTenant(event).catch(showApiError));
$('team-refresh').addEventListener('click', () => loadTeam().catch(showApiError));
$('team-form').addEventListener('submit', (event) => createTeamUser(event).catch(showApiError));
$('ai-refresh').addEventListener('click', () => loadAiConfig().catch(showApiError));
$('ai-form').addEventListener('submit', (event) => saveAiConfig(event).catch(showApiError));
$('report-refresh').addEventListener('click', () => loadReport().catch(showApiError));
$('new-page').addEventListener('click', newWikiPage);
$('add-section').addEventListener('click', () => addSectionCard());
$('wiki-save').addEventListener('click', () => saveWikiPage().catch(showApiError));
$('wiki-delete').addEventListener('click', () => deleteWikiPage().catch(showApiError));
$('wiki-reindex').addEventListener('click', () => reindexWikiPage().catch(showApiError));
$('wiki-search').addEventListener('click', () => wikiSearch().catch(showApiError));

if (state.token) {
    enterApp().catch(logout);
}
