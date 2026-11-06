const CATEGORY_LABELS = {
    RETURNS: '退货售后', SHIPPING: '配送物流', INVOICE: '发票财务', PAYMENT: '支付问题',
    ACCOUNT: '账户安全', MEMBER: '会员权益', PRODUCT: '商品知识', PROMOTION: '优惠活动',
    COMPLAINT: '投诉处理', QUOTA: '配额限制', BILLING: '计费套餐', PERMISSIONS: '权限管理',
    INTEGRATION: '集成对接', SECURITY: '安全合规', BACKUP: '备份恢复', UPGRADE: '升级降级',
    EXPORT: '数据导出', DELETION: '数据删除', PRIVACY: '隐私合规', SUPPORT: '服务支持',
    OTHER: '其他',
};

const STATUS_LABELS = { OPEN: '待处理', PENDING: '处理中', CLOSED: '已关闭' };
const AUTHOR_LABELS = { CUSTOMER: '我', AGENT: '客服', AI: 'AI' };
const params = new URLSearchParams(location.search);
const channelKey = params.get('channel') || 'acme-demo-key';
const state = {
    ticketId: sessionStorage.getItem('helpdesk.portal.ticketId'),
    token: sessionStorage.getItem('helpdesk.portal.token'),
    rating: 0,
};

const $ = (id) => document.getElementById(id);

function saveSession(ticketId, token) {
    state.ticketId = ticketId;
    state.token = token;
    sessionStorage.setItem('helpdesk.portal.ticketId', ticketId);
    sessionStorage.setItem('helpdesk.portal.token', token);
}

async function portalApi(path, options = {}, withToken = false) {
    const headers = { ...(options.headers || {}) };
    if (withToken) {
        headers['X-Ticket-Token'] = state.token;
    } else {
        headers['X-Channel-Key'] = channelKey;
    }
    const response = await fetch(path, { ...options, headers });
    if (!response.ok) {
        const text = await response.text();
        throw new Error(text || `HTTP ${response.status}`);
    }
    return response.status === 204 ? null : response.json();
}

async function createTicket(event) {
    event.preventDefault();
    $('create-error').textContent = '';
    const payload = {
        subject: $('p-subject').value.trim(),
        description: $('p-description').value.trim(),
        name: $('p-name').value.trim(),
        email: $('p-email').value.trim(),
        website: $('p-website').value,
    };
    try {
        const created = await portalApi('/api/public/tickets', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload),
        });
        saveSession(created.ticketId, created.token);
        $('create-panel').classList.add('hidden');
        await loadTicket(created.ticketId, created.token);
    } catch (error) {
        $('create-error').textContent = friendlyError(error);
    }
}

async function loadTicket(ticketId, token) {
    state.token = token;
    state.ticketId = ticketId;
    const ticket = await portalApi(`/api/public/tickets/${ticketId}`, {}, true);
    renderTicket(ticket);
}

function renderTicket(ticket) {
    $('ticket-panel').classList.remove('hidden');
    $('p-ticket-subject').textContent = ticket.subject;
    $('p-ticket-meta').textContent =
        `${STATUS_LABELS[ticket.status] || ticket.status} · ` +
        `${CATEGORY_LABELS[ticket.category] || ticket.category} · ` +
        `${new Date(ticket.createdAt).toLocaleString()}`;
    $('p-token').textContent = state.token || '';
    $('p-messages').innerHTML = '';
    for (const message of ticket.messages) {
        const node = document.createElement('div');
        node.className = `portal-msg ${message.authorType.toLowerCase()}`;
        const meta = document.createElement('div');
        meta.className = 'meta';
        meta.textContent = `${AUTHOR_LABELS[message.authorType] || message.authorType} · ` +
            new Date(message.createdAt).toLocaleString();
        const body = document.createElement('div');
        body.textContent = message.content;
        node.append(meta, body);
        $('p-messages').appendChild(node);
    }
    const closed = ticket.status === 'CLOSED';
    $('p-reply-form').classList.toggle('hidden', closed);
    if (closed) {
        $('p-csat').classList.remove('hidden');
        if (ticket.csatRating) {
            $('p-csat-title').textContent = `感谢评价：${ticket.csatRating}/5`;
            $('p-csat-comment').classList.add('hidden');
            $('p-csat-submit').classList.add('hidden');
        } else {
            $('p-csat-title').textContent = '问题已关闭，请评价本次服务';
            $('p-csat-comment').classList.remove('hidden');
            $('p-csat-submit').classList.remove('hidden');
        }
    } else {
        $('p-csat').classList.add('hidden');
    }
    renderStars();
}

function renderStars() {
    const box = $('p-stars');
    box.innerHTML = '';
    for (let i = 1; i <= 5; i++) {
        const star = document.createElement('button');
        star.type = 'button';
        star.className = 'star' + (i <= state.rating ? ' active' : '');
        star.textContent = '★';
        star.onclick = () => {
            state.rating = i;
            renderStars();
        };
        box.appendChild(star);
    }
}

async function reply(event) {
    event.preventDefault();
    const content = $('p-reply').value.trim();
    if (!content) return;
    try {
        const ticket = await portalApi(`/api/public/tickets/${state.ticketId}/messages`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ content }),
        }, true);
        $('p-reply').value = '';
        renderTicket(ticket);
    } catch (error) {
        alert(friendlyError(error));
    }
}

async function submitCsat() {
    if (!state.rating) {
        alert('请先选择评分');
        return;
    }
    try {
        await portalApi(`/api/public/tickets/${state.ticketId}/csat`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ rating: state.rating, comment: $('p-csat-comment').value.trim() }),
        }, true);
        await loadTicket(state.ticketId, state.token);
    } catch (error) {
        alert(friendlyError(error));
    }
}

async function lookup(event) {
    event.preventDefault();
    $('lookup-error').textContent = '';
    const id = $('p-lookup-id').value.trim();
    const token = $('p-lookup-token').value.trim();
    try {
        saveSession(id, token);
        $('create-panel').classList.add('hidden');
        await loadTicket(id, token);
    } catch (error) {
        $('lookup-error').textContent = friendlyError(error);
    }
}

function friendlyError(error) {
    let message = error && error.message ? error.message : '请求失败';
    try {
        const parsed = JSON.parse(message);
        message = parsed.error || message;
    } catch (ignored) {
        // keep raw message
    }
    const map = {
        invalid_channel: '渠道无效，请检查访问链接',
        invalid_request: '请求不合法',
        rate_limited: '请求过于频繁，请稍后再试',
        ticket_not_found: '工单不存在或查询码已过期',
        ticket_not_closed: '工单关闭后才能评价',
    };
    return map[message] || message;
}

$('portal-channel').textContent = `渠道：${channelKey}`;
$('create-form').addEventListener('submit', createTicket);
$('lookup-form').addEventListener('submit', lookup);
$('p-reply-form').addEventListener('submit', reply);
$('p-csat-submit').addEventListener('click', submitCsat);
$('p-refresh').addEventListener('click', () => loadTicket(state.ticketId, state.token).catch((error) => alert(friendlyError(error))));
$('p-copy').addEventListener('click', () => {
    navigator.clipboard.writeText(state.token || '').then(() => {
        $('p-copy').textContent = '已复制';
        setTimeout(() => { $('p-copy').textContent = '复制'; }, 1500);
    });
});

if (state.ticketId && state.token) {
    loadTicket(state.ticketId, state.token).catch(() => {
        sessionStorage.removeItem('helpdesk.portal.ticketId');
        sessionStorage.removeItem('helpdesk.portal.token');
    });
}
