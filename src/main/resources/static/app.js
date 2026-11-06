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
};

const $ = (id) => document.getElementById(id);

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

function logout() {
    state.token = null;
    state.user = null;
    sessionStorage.removeItem('helpdesk.token');
    $('app-view').classList.add('hidden');
    $('login-view').classList.remove('hidden');
}

async function enterApp() {
    $('login-view').classList.add('hidden');
    $('app-view').classList.remove('hidden');
    if (!state.user) {
        state.user = await api('/api/auth/me');
    }
    $('who-tenant').textContent = state.user.tenantSlug;
    $('who-user').textContent = `${state.user.displayName} · ${state.user.role}`;
    await Promise.all([loadUsage(), loadTickets()]);
}

async function loadUsage() {
    try {
        const usage = await api('/api/usage/summary');
        $('usage').textContent = `${usage.tokensThisMonth} / ${usage.monthlyTokenBudget} tokens`;
    } catch (error) {
        $('usage').textContent = '用量不可用';
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
        li.innerHTML = `
            <div class="title"></div>
            <div class="sub"><span></span><span></span></div>`;
        li.querySelector('.title').textContent = ticket.subject;
        const [status, priority] = li.querySelectorAll('.sub span');
        status.textContent = ticket.status;
        priority.textContent = ticket.priority;
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
    $('ticket-meta').textContent = `${detail.ticket.status} · ${detail.ticket.priority} · ${new Date(detail.ticket.createdAt).toLocaleString()}`;
    renderMessages(detail.messages);
    $('citations').classList.add('hidden');
}

function renderMessages(messages) {
    const container = $('messages');
    container.innerHTML = '';
    for (const message of messages) {
        container.appendChild(messageNode(message.authorType, message.content, message.tokens));
    }
    container.scrollTop = container.scrollHeight;
}

function messageNode(authorType, content, tokens) {
    const node = document.createElement('div');
    const kind = authorType.toLowerCase();
    node.className = `msg ${kind}`;
    const meta = document.createElement('div');
    meta.className = 'meta';
    meta.textContent = authorType + (tokens ? ` · ${tokens} tokens` : '');
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
                handleEvent(chunk, body, (doneEvent) => {
                    citations = JSON.parse(doneEvent.citations || '[]');
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
        await loadUsage();
    }
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
    state.tab = tab;
    $('tickets-view').classList.toggle('hidden', tab !== 'tickets');
    $('wiki-view').classList.toggle('hidden', tab !== 'wiki');
    $('tab-tickets').classList.toggle('active', tab === 'tickets');
    $('tab-wiki').classList.toggle('active', tab === 'wiki');
    if (tab === 'wiki' && !state.wikiSpaces.length) {
        loadWikiSpaces().catch(showApiError);
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
$('new-page').addEventListener('click', newWikiPage);
$('add-section').addEventListener('click', () => addSectionCard());
$('wiki-save').addEventListener('click', () => saveWikiPage().catch(showApiError));
$('wiki-delete').addEventListener('click', () => deleteWikiPage().catch(showApiError));
$('wiki-reindex').addEventListener('click', () => reindexWikiPage().catch(showApiError));
$('wiki-search').addEventListener('click', () => wikiSearch().catch(showApiError));

if (state.token) {
    enterApp().catch(logout);
}
