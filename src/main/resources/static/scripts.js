"use strict";

// ── Constants ─────────────────────────────────────────────────────────────────

const REPORT_TYPES = [
    { key: 'endpoint',    file: 'endpoint_report.json',              label: 'Endpoints'    },
    { key: 'catalogue',   file: 'catalogue_services_report.json',     label: 'Catalogue'    },
    { key: 'argo',        file: 'argo_uptime_report.json',            label: 'ARGO Uptime'  },
    { key: 'frontOffice', file: 'front_office_metrics_report.json',   label: 'Federated Search' },
];

// ── Fetch helpers ─────────────────────────────────────────────────────────────

async function loadRegistry() {
    const data = await tryFetchJSON('/api/data/node_registry_summary.json');
    if (!data) throw new Error('Could not load node_registry_summary.json');
    return data;
}

// Fetch all report files for one node, return an object keyed by
// REPORT_TYPES[].key (e.g. { endpoint, catalogue, argo, frontOffice }).
// Each value is the parsed JSON object, or null if the file was absent /
// errored. Built generically off REPORT_TYPES so adding a new report type
// only needs a single entry there, not a matching positional destructure
// here — a fixed-position destructure previously meant a new REPORT_TYPES
// entry would silently drop its own fetched data.
async function fetchNodeReports(nodeName) {
    const base = `/api/data/${encodeURIComponent(nodeName)}`;
    const values = await Promise.all(
        REPORT_TYPES.map(rt => tryFetchJSON(`${base}/${rt.file}`))
    );
    const result = {};
    REPORT_TYPES.forEach((rt, i) => { result[rt.key] = values[i]; });
    return result;
}

// ── Report summarisers ────────────────────────────────────────────────────────

function summariseEndpoint(data) {
    if (!data) return null;
    const caps  = Array.isArray(data.capabilities) ? data.capabilities : [];
    const total = caps.length;
    const avail = caps.filter(c => c.status === 'Available').length;
    return { total, avail, pct: total > 0 ? Math.round(avail / total * 100) : 0 };
}

function summariseCatalogue(data) {
    if (!data) return null;

    const activeVals = new Set(['active','Active','ACTIVE','available','Available','ok','OK','published','Published']);

    // Total: prefer explicit field, fall back to array length
    const total = typeof data.total_services === 'number'
        ? data.total_services
        : Array.isArray(data.services) ? data.services.length : null;

    if (total === null) return null;

    // Active count: prefer explicit field, then scan the services array for a status/active field
    let active = null;
    if (typeof data.active_services === 'number') {
        active = data.active_services;
    } else if (Array.isArray(data.services) && data.services.length > 0) {
        const first = data.services[0];
        if (typeof first.status === 'string') {
            const n = data.services.filter(s => activeVals.has(s.status)).length;
            if (n > 0) active = n;
        } else if (typeof first.active === 'boolean') {
            active = data.services.filter(s => s.active === true).length;
        }
    }

    return { total, active };
}

function summariseArgo(data) {
    if (!data) return null;
    if (typeof data.uptime_percentage === 'number') {
        return { uptime: Math.round(data.uptime_percentage * 10) / 10, services: data.total_services ?? null };
    }
    // Array key may be 'endpoints' or 'services' depending on report version
    const items = Array.isArray(data.endpoints) ? data.endpoints
        : Array.isArray(data.services)  ? data.services
            : null;
    if (items && items.length > 0) {
        const vals = items.map(s => s.uptime_percentage ?? s.average_availability ?? s.availability ?? null).filter(v => v !== null);
        const avg  = vals.length ? vals.reduce((a, b) => a + b, 0) / vals.length : 0;
        return { uptime: Math.round(avg * 10) / 10, services: items.length };
    }
    return null;
}

// front_office_metrics_report.json (CheckOtherMetrics): Metrics 4, 5, 6,
// 9, 10 and 11 — six metrics in total. Metric 10 is reported as an alias
// of Metric 4 (see CheckOtherMetrics Javadoc) and its "visible" value
// simply mirrors Metric 4's, but it's still counted here, same as on the
// node detail page, so this summary's denominator matches the six
// metric cards shown there.
function summariseFrontOffice(data) {
    if (!data) return null;
    const metrics = Array.isArray(data.metrics) ? data.metrics : [];
    const visible = metrics.filter(m => m.visible).length;
    return { visible, total: metrics.length };
}

// ── Colour / status helpers ───────────────────────────────────────────────────

function dotColour(pct) {
    if (pct === null || pct === undefined) return 'grey';
    if (pct >= 90) return 'green';
    if (pct >= 60) return 'amber';
    return 'red';
}

// Same green/amber/red intent as dotColour, but for an X-of-Y count
// (e.g. Federated Search metrics) rather than a percentage.
function fractionColour(visible, total) {
    if (total === 0) return 'grey';
    if (visible === total) return 'green';
    if (visible > 0) return 'amber';
    return 'red';
}

// Derive overall card badge from the actual fetched report data
function statusBadgeFromReports(ep, ar) {
    const pct = ep ? ep.pct : ar ? ar.uptime : null;
    if (pct === null) return ['unknown',  'No data'];
    if (pct >= 90)   return ['ok',       'Healthy'];
    if (pct >= 50)   return ['degraded', 'Degraded'];
    return               ['offline',  'Down'];
}

function pctColour(pct) {
    if (pct >= 99) return 'green';
    if (pct >= 95) return 'orange';
    return 'red';
}

// ── Card renderer ─────────────────────────────────────────────────────────────

function renderNodeCard(node, reports) {
    const ep  = summariseEndpoint(reports.endpoint);
    const cat = summariseCatalogue(reports.catalogue);
    const ar  = summariseArgo(reports.argo);

    const [statusClass, statusText] = statusBadgeFromReports(ep, ar);

    // File-availability chips
    const chips = REPORT_TYPES.map(rt =>
        `<span class="chip${reports[rt.key] !== null ? '' : ' missing'}">${rt.label}</span>`
    ).join('');

    // Endpoint row
    const epRow = ep
        ? `<div class="report-row">
         <span class="rr-icon">🔌</span>
         <span class="rr-label">Endpoints</span>
         <span class="rr-dot ${dotColour(ep.pct)}"></span>
         <span class="rr-val">${ep.avail} / ${ep.total} available</span>
         <span class="rr-sub">${ep.pct}%</span>
       </div>`
        : `<div class="report-row">
         <span class="rr-icon">🔌</span>
         <span class="rr-label">Endpoints</span>
         <span class="rr-dot grey"></span>
         <span class="rr-val missing">No report</span>
       </div>`;

    // Catalogue row
    const catRow = cat
        ? `<div class="report-row">
         <span class="rr-icon">📚</span>
         <span class="rr-label">Catalogue</span>
         <span class="rr-dot green"></span>
         <span class="rr-val">${cat.active !== null ? cat.active + ' active / ' : ''}${cat.total} services</span>
       </div>`
        : `<div class="report-row">
         <span class="rr-icon">📚</span>
         <span class="rr-label">Catalogue</span>
         <span class="rr-dot grey"></span>
         <span class="rr-val missing">No report</span>
       </div>`;

    // ARGO uptime row
    const arRow = ar
        ? `<div class="report-row">
         <span class="rr-icon">📡</span>
         <span class="rr-label">ARGO Uptime</span>
         <span class="rr-dot ${dotColour(ar.uptime)}"></span>
         <span class="rr-val">${ar.uptime}%</span>
         ${ar.services !== null ? `<span class="rr-sub">${ar.services} services</span>` : ''}
       </div>`
        : `<div class="report-row">
         <span class="rr-icon">📡</span>
         <span class="rr-label">ARGO Uptime</span>
         <span class="rr-dot grey"></span>
         <span class="rr-val missing">No report</span>
       </div>`;

    // Compliance tier row
    const compliance = reports.endpoint ? computeComplianceTiers(reports.endpoint) : null;
    const tierColour = compliance && compliance.highest
        ? (compliance.highest.level === 3 ? 'green' : compliance.highest.level === 2 ? 'orange' : 'amber')
        : null;
    const tierRow = `<div class="report-row">
         <span class="rr-icon">🏆</span>
         <span class="rr-label">Compliance</span>
         ${!compliance
        ? `<span class="rr-dot grey"></span><span class="rr-val missing">No endpoint report</span>`
        : compliance.highest
            ? `<span class="rr-dot ${tierColour}"></span><span class="rr-val" style="color:var(--${tierColour})">${compliance.highest.label} tier</span>`
            : `<span class="rr-dot red"></span><span class="rr-val" style="color:var(--red)">Below MVP</span>`
    }
       </div>`;

    // Federated Search visibility row (Metrics 4, 5, 6, 9, 10, 11 —
    // Exchange maturity evidence, distinct from the Core compliance tiers
    // above). Row label kept short ("Metrics") to fit the fixed-width
    // .rr-label column alongside the other row labels; the fuller name
    // "Federated Search" is used on the node detail page and in
    // the stat card below, which both have more room.
    const fom = summariseFrontOffice(reports.frontOffice);
    const fomRow = fom
        ? `<div class="report-row">
         <span class="rr-icon">🔗</span>
         <span class="rr-label">Metrics</span>
         <span class="rr-dot ${fractionColour(fom.visible, fom.total)}"></span>
         <span class="rr-val">${fom.visible} / ${fom.total} metrics visible</span>
       </div>`
        : `<div class="report-row">
         <span class="rr-icon">🔗</span>
         <span class="rr-label">Metrics</span>
         <span class="rr-dot grey"></span>
         <span class="rr-val missing">No report</span>
       </div>`;

    return `
  <a class="node-card ${statusClass === 'ok' ? '' : statusClass}"
     href="node.html#${encodeURIComponent(node.node_name)}">
    <div class="node-header">
      <div>
        <div class="node-name">${node.node_name}</div>
        <div class="node-id">${node.endpoint || ''}</div>
      </div>
      <span class="status-badge status-${statusClass}">${statusText}</span>
    </div>
    <div class="report-rows">${epRow}${catRow}${arRow}${tierRow}${fomRow}</div>
    <div class="report-chips">${chips}</div>
    <div class="node-footer">
      <span>View node detail</span>
      <span class="arrow">→</span>
    </div>
  </a>`;
}

// ── Summary stats bar ─────────────────────────────────────────────────────────

function renderSummaryStats(registry, allReports) {
    const totalNodes = registry.nodes.length;
    let okNodes = 0, totalCaps = 0, availCaps = 0;

    let mvpNodes = 0, standardNodes = 0, advancedNodes = 0;
    let fomVisible = 0, fomTotal = 0;
    allReports.forEach(r => {
        const ep = summariseEndpoint(r.endpoint);
        const ar = summariseArgo(r.argo);
        if (statusBadgeFromReports(ep, ar)[0] === 'ok') okNodes++;
        // Count caps from the actual capabilities array, not the (potentially stale) registry field
        if (ep) { totalCaps += ep.total; availCaps += ep.avail; }
        const { highest } = computeComplianceTiers(r.endpoint);
        if (highest && highest.level >= 1) mvpNodes++;
        if (highest && highest.level >= 2) standardNodes++;
        if (highest && highest.level >= 3) advancedNodes++;
        const fom = summariseFrontOffice(r.frontOffice);
        if (fom) { fomVisible += fom.visible; fomTotal += fom.total; }
    });

    const degraded = totalNodes - okNodes;
    const upPct    = totalCaps > 0 ? Math.round(availCaps / totalCaps * 100) : 0;
    const fomPct   = fomTotal > 0 ? Math.round(fomVisible / fomTotal * 100) : 0;

    document.getElementById('summary-stats').innerHTML = `
    <div class="stat-card"><div class="stat-value orange">${totalNodes}</div><div class="stat-label">Total nodes</div></div>
    <div class="stat-card"><div class="stat-value green">${okNodes}</div><div class="stat-label">Healthy</div></div>
    <div class="stat-card"><div class="stat-value ${degraded > 0 ? 'red' : 'green'}">${degraded}</div><div class="stat-label">Degraded / no data</div></div>
    <div class="stat-card"><div class="stat-value ${pctColour(upPct)}">${upPct}%</div><div class="stat-label">Capability uptime</div></div>
    <div class="stat-card">
      <div class="stat-value ${pctColour(upPct)}">${availCaps}<span style="font-size:1.25rem;color:var(--grey-400)">/${totalCaps}</span></div>
      <div class="stat-label">Available caps</div>
    </div>
    <div class="stat-card">
      <div class="stat-value ${fomTotal > 0 ? pctColour(fomPct) : ''}" style="${fomTotal === 0 ? 'color:var(--grey-400)' : ''}">${fomTotal > 0 ? fomPct + '%' : '—'}</div>
      <div class="stat-label">Metrics visibility</div>
    </div>
    <div class="stat-card">
      <div class="stat-value ${mvpNodes === totalNodes ? 'green' : mvpNodes > 0 ? 'amber' : 'red'}">${mvpNodes}<span style="font-size:1.25rem;color:var(--grey-400)">/${totalNodes}</span></div>
      <div class="stat-label">Tier 1 &middot; MVP</div>
    </div>
    <div class="stat-card">
      <div class="stat-value ${standardNodes === totalNodes ? 'green' : standardNodes > 0 ? 'orange' : 'red'}">${standardNodes}<span style="font-size:1.25rem;color:var(--grey-400)">/${totalNodes}</span></div>
      <div class="stat-label">Tier 2 &middot; Standard</div>
    </div>
    <div class="stat-card">
      <div class="stat-value ${advancedNodes === totalNodes ? 'green' : advancedNodes > 0 ? 'orange' : 'red'}">${advancedNodes}<span style="font-size:1.25rem;color:var(--grey-400)">/${totalNodes}</span></div>
      <div class="stat-label">Tier 3 &middot; Advanced</div>
    </div>`;
}

// ── file:// guard ─────────────────────────────────────────────────────────────

function showCorsError(containerId) {
    document.getElementById(containerId).innerHTML = `
    <div style="background:var(--white);border:2px solid var(--black);border-left:4px solid var(--orange);padding:2rem 2.5rem;max-width:600px;font-family:var(--mono);">
      <div style="color:var(--orange);font-size:0.7rem;font-weight:600;text-transform:uppercase;letter-spacing:0.1em;margin-bottom:0.75rem;">HTTP server required</div>
      <div style="color:var(--black);font-size:0.8rem;line-height:1.7;margin-bottom:1.25rem;">
        Browsers block file access when opening HTML files directly from disk.
        Serve from <code style="background:var(--grey-100);padding:0.1em 0.3em">src/main/dashboard/</code>
        then open <code style="background:var(--grey-100);padding:0.1em 0.3em">http://localhost:8080</code>.
      </div>
      <div style="display:flex;flex-direction:column;gap:0.5rem;">
        <div style="background:var(--grey-100);padding:0.6rem 0.9rem;border-left:3px solid var(--orange);">
          <span style="color:var(--grey-400);font-size:0.55rem;display:block;margin-bottom:0.2rem;text-transform:uppercase;">Python 3</span>
          <code style="font-size:0.75rem;">python3 -m http.server 8080</code>
        </div>
        <div style="background:var(--grey-100);padding:0.6rem 0.9rem;border-left:3px solid var(--orange);">
          <span style="color:var(--grey-400);font-size:0.55rem;display:block;margin-bottom:0.2rem;text-transform:uppercase;">Node.js</span>
          <code style="font-size:0.75rem;">npx serve .</code>
        </div>
      </div>
    </div>`;
}

// ── Run-checks menu ───────────────────────────────────────────────────────────

const CHECK_LABELS = {
    'node-capabilities': 'Node Capabilities',
    'check-all':         'Check All',
};

// Check All runs Node Capabilities plus three checks for every registered
// node, one node at a time, so it can take much longer than a single check.
const CHECK_POLL_MAX_WAIT_MS = {
    'check-all': 30 * 60 * 1000, // 30 minutes
};

document.addEventListener('click', e => {
    const menu = document.getElementById('run-menu');
    if (menu && !menu.contains(e.target)) menu.setAttribute('aria-expanded', 'false');
});
document.addEventListener('keydown', e => {
    if (e.key === 'Escape') {
        document.getElementById('run-menu')?.setAttribute('aria-expanded', 'false');
        closeAbout();
    }
});

// ── About modal ───────────────────────────────────────────────────────────────
async function openAbout(file) {
    document.getElementById('about-overlay').classList.add('open');
    const body = document.getElementById('about-modal-body');
    body.innerHTML = 'Loading…';
    const r = await fetch(file);
    if (r.ok) {
        body.innerHTML = await r.text();
    } else {
        body.innerHTML = `<p style="color:var(--red)">Could not load ${file}: HTTP ${r.status}</p>`;
    }
}

function closeAbout() {
    document.getElementById('about-overlay').classList.remove('open');
}

function handleAboutOverlayClick(e) {
    if (e.target === document.getElementById('about-overlay')) closeAbout();
}

async function runCheck(type) {
    const btn = document.getElementById('btn-' + type);
    if (btn) { btn.disabled = true; btn.textContent = '…'; }

    const label = CHECK_LABELS[type] || type;
    showToast('info', label, 'Starting…');

    let jobId;
    try {
        const res  = await fetch(`/api/run/${type}`, { method: 'POST' });
        const data = await res.json();
        if (!res.ok) { showToast('err', label, data.error || 'HTTP ' + res.status); resetBtn(btn); return; }
        jobId = data.jobId;
    } catch (e) {
        showToast('err', label, 'Request failed: ' + e.message);
        resetBtn(btn); return;
    }

    showToast('info', label, 'Running… (job ' + jobId + ')');
    const maxWaitMs = CHECK_POLL_MAX_WAIT_MS[type];
    const result = await (maxWaitMs ? pollJobStatus(jobId, 1500, maxWaitMs) : pollJobStatus(jobId));

    if (result.status === 'DONE') { showToast('ok', label, result.message || 'Completed'); init(); }
    else                          { showToast('err', label, result.message || 'Check failed'); }

    resetBtn(btn);
    document.getElementById('run-menu')?.setAttribute('aria-expanded', 'false');
}

async function pollJobStatus(jobId, intervalMs = 1500, maxWaitMs = 300_000) {
    const deadline = Date.now() + maxWaitMs;
    while (Date.now() < deadline) {
        await new Promise(r => setTimeout(r, intervalMs));
        try {
            const r = await fetch(`/api/run/${encodeURIComponent(jobId)}/status`);
            if (!r.ok) continue;
            const d = await r.json();
            if (d.status === 'DONE' || d.status === 'ERROR') return d;
        } catch { /* retry */ }
    }
    return { status: 'ERROR', message: 'Timed out waiting for job ' + jobId };
}

function resetBtn(btn) { if (btn) { btn.disabled = false; btn.textContent = 'Run'; } }

function showToast(kind, title, message, durationMs = 6000) {
    const icons = { ok: '✓', err: '✕', info: '↻' };
    const el = document.createElement('div');
    el.className = `toast toast-${kind}`;
    el.innerHTML = `
    <span class="toast-icon">${icons[kind] || '·'}</span>
    <div class="toast-body">
      <div class="toast-title">${title}</div>
      <div class="toast-msg">${message}</div>
    </div>`;
    document.getElementById('toast-container').appendChild(el);
    setTimeout(() => { el.style.transition = 'opacity 0.3s'; el.style.opacity = '0'; setTimeout(() => el.remove(), 320); }, durationMs);
}

// ── Fetch helper ──────────────────────────────────────────────────────────────

async function tryFetchJSON(url) {
    try {
        const r = await fetch(url);
        return r.ok ? r.json() : null;
    } catch { return null; }
}

// ── Colour helpers ────────────────────────────────────────────────────────────

function uptimeClass(pct) {
    if (pct >= 90) return 'high';
    if (pct >= 60) return 'medium';
    return 'low';
}

function uptimeColour(pct) {
    if (pct >= 90) return 'green';
    if (pct >= 60) return 'amber';
    return 'red';
}

// ── Capability compliance tiers ─────────────────────────────────────────────
// Tier 1 (MVP, required): AAI, Resource Catalogue
// Tier 2 (Standard):      Helpdesk, Service Monitoring
// Tier 3 (Advanced):      Service Accounting, Research Product Accounting,
//                         Order Management, Application Deployment Management
const COMPLIANCE_TIERS = [
    { key: 'mvp',      level: 1, label: 'MVP',      types: ['AAI', 'Resource Catalogue'] },
    { key: 'standard', level: 2, label: 'Standard', types: ['Helpdesk', 'Monitoring'] },
    { key: 'advanced', level: 3, label: 'Advanced', types: ['Service Accounting', 'Research Product Accounting', 'Order Management', 'Application Deployment Management'] },
];

// Returns per-tier counts plus the highest fully-satisfied tier.
// { tiers: [{ key, label, level, total, avail, met }], highest: {key,label,level} | null }
function computeComplianceTiers(endpointReport) {
    const caps  = (endpointReport && Array.isArray(endpointReport.capabilities)) ? endpointReport.capabilities : [];
    const avail = new Set(caps.filter(c => c.status === 'Available').map(c => c.capability_type));

    const tiers = COMPLIANCE_TIERS.map(t => {
        const total = t.types.length;
        const have  = t.types.filter(type => avail.has(type)).length;
        return { ...t, total, avail: have, met: have === total };
    });

    // Highest tier reached: tiers must be satisfied in order (1 then 2 then 3)
    let highest = null;
    for (const t of tiers) {
        if (t.met) highest = t;
        else break;
    }

    return { tiers, highest };
}

// Back-compat helper: a node "meets MVP" if Tier 1 is satisfied.
function meetsMvp(endpointReport) {
    return computeComplianceTiers(endpointReport).tiers[0].met;
}

// ── Overview strip ────────────────────────────────────────────────────────────

function buildOverview(ep, cat, argo) {
    const cards = [];

    // Endpoints
    if (ep && Array.isArray(ep.capabilities)) {
        const total = ep.capabilities.length;
        const avail = ep.capabilities.filter(c => c.status === 'Available').length;
        const pct   = total > 0 ? Math.round(avail / total * 100) : 0;
        const cls   = pct === 100 ? 'green' : pct >= 50 ? 'amber' : 'red';
        cards.push(`
      <div class="ov-card">
        <div class="ov-value ${cls}">${avail}/${total}</div>
        <div class="ov-label">Endpoints available</div>
      </div>`);
    } else {
        cards.push(`<div class="ov-card"><div class="ov-value" style="color:var(--grey-400)">—</div><div class="ov-label">Endpoints available</div></div>`);
    }

    // Exchange services
    if (cat) {
        const total = typeof cat.total_services === 'number' ? cat.total_services
            : Array.isArray(cat.services) ? cat.services.length : '—';
        let active = null;
        if (typeof cat.active_services === 'number') {
            active = cat.active_services;
        } else if (Array.isArray(cat.services) && cat.services.length > 0) {
            const first = cat.services[0];
            if (typeof first.status === 'string') {
                const activeVals = new Set(['active','Active','ACTIVE','available','Available','ok','OK','published','Published']);
                const n = cat.services.filter(s => activeVals.has(s.status)).length;
                if (n > 0) active = n;
            } else if (typeof first.active === 'boolean') {
                active = cat.services.filter(s => s.active === true).length;
            }
        }
        cards.push(`
      <div class="ov-card">
        <div class="ov-value orange">${active !== null ? active : total}</div>
        <div class="ov-label">${active !== null ? 'Active catalogue services' : 'Catalogue services'}</div>
      </div>`);
    } else {
        cards.push(`<div class="ov-card"><div class="ov-value" style="color:var(--grey-400)">—</div><div class="ov-label">Exchange services</div></div>`);
    }

    // ARGO uptime
    if (argo) {
        let uptime = null;
        if (typeof argo.uptime_percentage === 'number') {
            uptime = Math.round(argo.uptime_percentage * 10) / 10;
        } else {
            const items = Array.isArray(argo.endpoints) ? argo.endpoints
                : Array.isArray(argo.services)  ? argo.services : [];
            const vals = items.map(s => s.uptime_percentage ?? s.average_availability ?? s.availability ?? null).filter(v => v !== null);
            if (vals.length) uptime = Math.round((vals.reduce((a, b) => a + b, 0) / vals.length) * 10) / 10;
        }
        if (uptime !== null) {
            cards.push(`
        <div class="ov-card">
          <div class="ov-value ${uptimeColour(uptime)}">${uptime}%</div>
          <div class="ov-label">Avg ARGO uptime</div>
        </div>`);
        }
    } else {
        cards.push(`<div class="ov-card"><div class="ov-value" style="color:var(--grey-400)">—</div><div class="ov-label">Avg ARGO uptime</div></div>`);
    }

    // Compliance tier
    const compliance = ep ? computeComplianceTiers(ep) : null;
    cards.push(!compliance
        ? `<div class="ov-card"><div class="ov-value" style="color:var(--grey-400)">—</div><div class="ov-label">Compliance tier</div></div>`
        : compliance.highest
            ? `<div class="ov-card"><div class="ov-value ${compliance.highest.level === 3 ? 'green' : compliance.highest.level === 2 ? 'orange' : 'amber'}">${compliance.highest.label}</div><div class="ov-label">Compliance tier</div></div>`
            : `<div class="ov-card"><div class="ov-value red">✗</div><div class="ov-label">Compliance tier</div></div>`
    );

    return `<div class="overview-strip">${cards.join('')}</div>`;
}

// ── Endpoint Report panel ─────────────────────────────────────────────────────

function renderEndpointPanel(data, core) {
    if (!data) {
        return `<div class="panel">
      <div class="panel-header"><div class="panel-header-left"><span class="panel-icon">🔌</span> Core Services Integration Report</div>
        <span class="badge badge-missing">Not available</span></div>
      <div class="panel-missing">endpoint_report.json was not found for this node.</div>
    </div>`;
    }

    const caps = Array.isArray(data.capabilities) ? data.capabilities : [];
    if (caps.length === 0) {
        return `<div class="panel">
      <div class="panel-header"><div class="panel-header-left"><span class="panel-icon">🔌</span> Core Services Integration Report</div></div>
      <div class="panel-missing">No capabilities listed in this report.</div>
    </div>`;
    }

    const avail = caps.filter(c => c.status === 'Available').length;
    const pct   = Math.round(avail / caps.length * 100);
    const bCls  = pct === 100 ? 'badge-ok' : pct >= 50 ? 'badge-warn' : 'badge-error';

    const coreMatches = core ? caps.map(c => findCoreStatus(core, c)).filter(Boolean) : [];
    const coreOk = coreMatches.filter(m => m.status === 'OK').length;
    const coreHeader = coreMatches.length
        ? `<span class="badge ${coreOk === coreMatches.length ? 'badge-ok' : coreOk > 0 ? 'badge-warn' : 'badge-error'}" title="Status of each endpoint from the ARGO federation tenant monitoring">ARGO ${coreOk}/${coreMatches.length} OK</span>`
        : '';


    const byType = new Map(caps.map(c => [c.capability_type, c]));
    const { tiers } = computeComplianceTiers(data);

    const renderCard = (cap) => {
        const sc = cap.status === 'Available' ? 'status-available' : 'status-error';
        return `
      <div class="ep-card">
        <div class="ep-cap-type">${cap.capability_type || '(unknown type)'}</div>
        <div class="ep-url">${cap.endpoint || ''}</div>
        <span class="status-tag ${sc}">${cap.status} (${cap.http_code ?? '?'})</span>
        ${cap.version ? `<div class="ep-version">Version: ${cap.version}</div>` : ''}
        ${coreStatusBadge(findCoreStatus(core, cap))}
      </div>`;
    };

    const renderMissingCard = (type) => `
      <div class="ep-card ep-card-missing">
        <div class="ep-cap-type">${type}</div>
        <div class="ep-url">&mdash;</div>
        <span class="status-tag status-error">Not reported</span>
      </div>`;

    const tierSections = tiers.map(tier => {
        const tCls  = tier.met ? 'badge-ok' : tier.avail > 0 ? 'badge-warn' : 'badge-error';
        const cards = tier.types
            .map(type => byType.has(type) ? renderCard(byType.get(type)) : renderMissingCard(type))
            .join('');
        return `
      <div class="ep-tier">
        <div class="ep-tier-header">
          <span class="ep-tier-label">Tier ${tier.level} &middot; ${tier.label}</span>
          <span class="badge ${tCls}">${tier.avail}/${tier.total} available</span>
        </div>
        <div class="ep-grid">${cards}</div>
      </div>`;
    }).join('');

    // Capabilities present in the report that don't map to a known tier
    const knownTypes   = new Set(tiers.flatMap(t => t.types));
    const otherCaps     = caps.filter(c => !knownTypes.has(c.capability_type));
    const otherSection  = otherCaps.length
        ? `<div class="ep-tier">
         <div class="ep-tier-header"><span class="ep-tier-label">Other capabilities</span></div>
         <div class="ep-grid">${otherCaps.map(renderCard).join('')}</div>
       </div>`
        : '';

    return `
    <div class="panel">
      <div class="panel-header">
        <div class="panel-header-left"><span class="panel-icon">🔌</span> Core Services Integration Report</div>
        <span class="badge ${bCls}">${avail}/${caps.length} available</span>
      </div>
      <div class="panel-body">${tierSections}${otherSection}</div>
    </div>`;
}

// ── Exchange Services panel ──────────────────────────────────────────────────

function renderCataloguePanel(data) {
    if (!data) {
        return `<div class="panel">
      <div class="panel-header"><div class="panel-header-left"><span class="panel-icon">📚</span> Exchange Services Report</div>
        <span class="badge badge-missing">Not available</span></div>
      <div class="panel-missing">catalogue_services_report.json was not found for this node.</div>
    </div>`;
    }

    let services = [];
    if (Array.isArray(data.services)) {
        services = data.services;
    } else if (typeof data.total_services === 'number') {
        return `
      <div class="panel">
        <div class="panel-header">
          <div class="panel-header-left"><span class="panel-icon">📚</span> Exchange Services Report</div>
          <span class="badge badge-ok">${data.total_services} services</span>
        </div>
        <div class="panel-body">
          <details><summary style="cursor:pointer;font-family:var(--mono);font-size:0.7rem;color:var(--grey-600);">Show raw JSON</summary>
          <div class="raw-panel">${JSON.stringify(data, null, 2)}</div></details>
        </div>
      </div>`;
    }

    const catalogueNote = data.note ? `<div class="panel-missing" style="padding:0.5rem 0.9rem;">ⓘ ${data.note}</div>` : '';

    if (services.length === 0) {
        return `<div class="panel">
      <div class="panel-header"><div class="panel-header-left"><span class="panel-icon">📚</span> Exchange Services Report</div></div>
      ${catalogueNote}
      <div class="panel-missing">No services listed in this report.</div>
    </div>`;
    }

    const activeVals = new Set(['active','Active','ACTIVE','available','Available','ok','OK','published','Published']);

    // Metric 13 (Proposed Validation Metrics doc): status now distinguishes
    // a full pass ("Available") from reachable-but-degraded results
    // ("Available (content check failed)", "Available (slow: …)") rather
    // than a flat active/inactive split, so badge colour is derived from
    // the actual status text rather than a fixed set of "active" strings.
    function catalogueBadgeClass(status) {
        if (!status) return 'badge-missing';
        if (status === 'Available') return 'badge-ok';
        if (status.startsWith('Available (')) return 'badge-warn';
        if (status === 'Not found' || status === 'No webpage defined') return 'badge-missing';
        if (activeVals.has(status)) return 'badge-ok'; // legacy/alternate report sources
        return 'badge-error';
    }

    const rows = services.map(s => {
        const name   = s.name || s.title || s.id || '(unnamed)';
        const abbr   = s.abbreviation || null;
        const url    = s.url || s.webpage || s.endpoint || s.landing_page || null;
        const status = s.status ?? (s.active === true ? 'active' : s.active === false ? 'inactive' : null);
        const statusBadge = status
            ? `<span class="badge ${catalogueBadgeClass(status)}">${status}</span>`
            : '';

        // Metric 13 detail — only present once CheckCatalogueServices has been
        // re-run with the version that captures it; older reports simply omit
        // these fields, so each renders as '—' rather than a blank cell.
        const responseTime = s.response_time_ms != null
            ? `${s.response_time_ms}&nbsp;ms`
            : '—';
        const contentCell = s.content_type
            ? `${s.content_type.split(';')[0]} ${s.content_valid === true ? '✓' : s.content_valid === false ? '✕' : ''}`
            : '—';

        return `
      <tr>
        <td><div class="cat-name">${name}${abbr ? ` <span style="font-family:var(--mono);font-size:0.65rem;color:var(--grey-400);font-weight:400;">(${abbr})</span>` : ''}</div></td>
        <td><div class="cat-url">${url ? `<a href="${url}" target="_blank" rel="noreferrer">${url}</a>` : '—'}</div></td>
        <td><div class="cat-url">${responseTime}</div></td>
        <td><div class="cat-url">${contentCell}</div></td>
        <td>${statusBadge}</td>
      </tr>`;
    }).join('');

    // Header summary — only shown once healthy_services/pct_healthy are
    // present (i.e. the report was written by the Metric-13-aware version
    // of CheckCatalogueServices); older reports fall back to a plain count.
    const hasMetric13 = typeof data.pct_healthy === 'number';
    const headerBadge = hasMetric13
        ? `<span class="badge ${data.pct_healthy === 100 ? 'badge-ok' : data.pct_healthy > 0 ? 'badge-warn' : 'badge-error'}">${data.healthy_services}/${services.length} healthy</span>`
        : `<span class="badge badge-ok">${services.length} services</span>`;
    const avgResponse = hasMetric13 && data.avg_response_time_ms != null
        ? `<span style="font-family:var(--mono);font-size:0.65rem;color:var(--grey-400);">avg ${data.avg_response_time_ms}&nbsp;ms</span>`
        : '';

    return `
    <div class="panel">
      <div class="panel-header">
        <div class="panel-header-left"><span class="panel-icon">📚</span> Exchange Services Report</div>
        <div style="display:flex;align-items:center;gap:0.6rem;">${avgResponse}${headerBadge}</div>
      </div>
      ${catalogueNote}
      <div class="panel-body">
        <table class="cat-table">
          <thead><tr><th>Name</th><th>URL</th><th>Response</th><th>Content</th><th>Status</th></tr></thead>
          <tbody>${rows}</tbody>
        </table>
      </div>
    </div>`;
}

// ── ARGO Uptime panel ─────────────────────────────────────────────────────────

function renderArgoPanel(data) {
    if (!data) {
        return `<div class="panel">
      <div class="panel-header"><div class="panel-header-left"><span class="panel-icon">📡</span> ARGO Uptime Report (Metric 12)</div>
        <span class="badge badge-missing">Not available</span></div>
      <div class="panel-missing">argo_uptime_report.json was not found for this node.</div>
    </div>`;
    }

    // Normalise: 'endpoints' is the real key; 'services' kept as fallback
    let services = [];
    if (Array.isArray(data.endpoints)) {
        services = data.endpoints;
    } else if (Array.isArray(data.services)) {
        services = data.services;
    } else if (typeof data.uptime_percentage === 'number') {
        services = [{ name: data.service_name || data.name || 'Overall', uptime_percentage: data.uptime_percentage }];
    }

    let avgUptime = null;
    if (services.length > 0) {
        const vals = services.map(s => s.uptime_percentage ?? s.average_availability ?? s.availability ?? null).filter(v => v !== null);
        if (vals.length) avgUptime = Math.round((vals.reduce((a, b) => a + b, 0) / vals.length) * 10) / 10;
    }

    const headerBadge = avgUptime !== null
        ? `<span class="badge badge-${avgUptime >= 90 ? 'ok' : avgUptime >= 60 ? 'warn' : 'error'}">${avgUptime}% avg</span>`
        : `<span class="badge badge-missing">No uptime data</span>`;

    // Extract report-level date range (used in both the normal and empty-services branches)
    const reportStart = data.period?.start || data.start_date || data.start_time || data.period_start || data.from || null;
    const reportEnd   = data.period?.end   || data.end_date   || data.end_time   || data.period_end   || data.to   || null;

    // Extract only the date portion (YYYY-MM-DD) from ISO string timestamps before parsing
    function fmtDate(d) {
        if (!d) return null;

        // Extract the YYYY-MM-DD portion if it starts with an ISO date pattern
        const dateOnlyMatch = String(d).match(/^(\d{4}-\d{2}-\d{2})/);
        if (dateOnlyMatch) {
            const [year, month, day] = dateOnlyMatch[1].split('-').map(Number);
            const parsed = new Date(year, month - 1, day); // Parsed as local midnight (prevents UTC shift)
            return parsed.toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' });
        }

        const parsed = new Date(d);
        if (isNaN(parsed.valueOf())) return String(d);
        return parsed.toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' });
    }

    const dateRange = (reportStart || reportEnd)
        ? `<span class="argo-meta" style="display:inline-flex;align-items:center;gap:0.3rem;">
         🗓 ${reportStart ? fmtDate(reportStart) : '?'}&nbsp;→&nbsp;${reportEnd ? fmtDate(reportEnd) : '?'}
       </span>`
        : '';

    if (services.length === 0) {
        return `
      <div class="panel">
        <div class="panel-header">
          <div class="panel-header-left"><span class="panel-icon">📡</span> ARGO Uptime Report (Metric 12)</div>
          <div style="display:flex;align-items:center;gap:0.75rem;flex-wrap:wrap;">${dateRange}${headerBadge}</div>
        </div>
        <div class="panel-body">
          <details><summary style="cursor:pointer;font-family:var(--mono);font-size:0.7rem;color:var(--grey-600);">Show raw JSON</summary>
          <div class="raw-panel">${JSON.stringify(data, null, 2)}</div></details>
        </div>
      </div>`;
    }

    // Each service reports up to three independent metrics. Normalise a raw
    // fallback "uptime" ratio (0–1) to a percentage the same way the backend
    // does, in case an older-shaped report is being read.
    function normalisedUptime(s) {
        if (typeof s.uptime_percentage === 'number') return s.uptime_percentage;
        if (typeof s.uptime === 'number') return s.uptime <= 1 ? s.uptime * 100 : s.uptime;
        return null;
    }

    function renderMetricRow(label, value) {
        if (value === null || value === undefined) {
            return `
        <div class="argo-metric-row">
          <span class="argo-metric-label">${label}</span>
          <div class="argo-meta" style="margin-top:0;">No data</div>
        </div>`;
        }
        const cls = uptimeClass(value);
        return `
      <div class="argo-metric-row">
        <span class="argo-metric-label">${label}</span>
        <div class="argo-uptime-row">
          <div class="argo-bar-track"><div class="argo-bar-fill ${cls}" style="width:${value}%"></div></div>
          <span class="argo-pct ${cls}">${value}%</span>
        </div>
      </div>`;
    }

    // The capability-metrics API reports one point per calendar month
    // (granularity=monthly); the dashboard and legacy sources report one
    // point per day — so "days_monitored" needs a matching unit and plural.
    const monitoredUnit = data.data_source === 'capability-metrics-api' ? 'month' : 'day';

    const cards = services.map(s => {
        const name         = s.name || s.service_name || s.id || '(unnamed)';
        const availability = s.average_availability ?? s.availability ?? null;
        const reliability  = s.average_reliability  ?? s.reliability  ?? null;
        const uptime       = normalisedUptime(s);
        const period = s.period || s.time_period || null;

        // Per-service dates (fall back to report-level period)
        const sStart = s.period?.start || s.start_date || s.start_time || s.period_start || s.from || reportStart || null;
        const sEnd   = s.period?.end   || s.end_date   || s.end_time   || s.period_end   || s.to   || reportEnd   || null;
        const sDateRange = (sStart || sEnd)
            ? `<div class="argo-meta">📅 ${sStart ? fmtDate(sStart) : '?'} → ${sEnd ? fmtDate(sEnd) : '?'}</div>`
            : '';

        const monitoredLabel = s.days_monitored
            ? `${s.days_monitored} ${monitoredUnit}${s.days_monitored === 1 ? '' : 's'} monitored`
            : null;

        return `
      <div class="argo-card">
        <div class="argo-name">${name}</div>
        ${renderMetricRow('Availability', availability)}
        ${renderMetricRow('Reliability', reliability)}
        ${renderMetricRow('Uptime', uptime)}
        ${monitoredLabel ? `<div class="argo-meta">${monitoredLabel}</div>` : ''}
        ${period ? `<div class="argo-meta">Period: ${period}</div>` : ''}
        ${sDateRange}
      </div>`;
    }).join('');

    return `
    <div class="panel">
      <div class="panel-header">
        <div class="panel-header-left"><span class="panel-icon">📡</span> ARGO Uptime Report (Metric 12)</div>
        <div style="display:flex;align-items:center;gap:0.75rem;flex-wrap:wrap;">
          ${dateRange}
          ${headerBadge}
        </div>
      </div>
      <div class="panel-body"><div class="argo-grid">${cards}</div></div>
    </div>`;
}

// ── Federated Search panel ─────────────────────────────────────────
// Renders front_office_metrics_report.json (CheckOtherMetrics), covering
// Metrics 4, 5, 6, 9, 10 and 11 from the Proposed Validation Metrics doc
// — six metric cards in total. Metric 10 is reported as an alias of
// Metric 4 (see CheckOtherMetrics Javadoc) rather than a separately-run
// check, and its "visible" value simply mirrors Metric 4's, but it's
// still one of the six cards shown here, so the header badge counts it
// too — the badge's denominator should match the number of cards on
// screen.

function fomStatusTag(m) {
    const cls = m.visible ? 'status-available' : 'status-error';
    const label = m.status || 'Undefined';
    return `<span class="status-tag ${cls}">${label}${m.http_code != null ? ` (${m.http_code})` : ''}</span>`;
}

// Metrics 5 & 11 have no single m.status (only an aggregate "visible"
// flag across their peer_results), so their header badge is a peer
// visible-count instead of a status tag.
function fomHeaderBadge(m) {
    if (Array.isArray(m.peer_results)) {
        const total   = m.peer_results.length;
        const visible = m.peer_results.filter(p => p.visible).length;
        const cls = total === 0 ? 'badge-missing'
            : visible === total ? 'badge-ok'
                : visible > 0 ? 'badge-warn' : 'badge-error';
        return `<span class="badge ${cls}">${visible}/${total} peers</span>`;
    }
    return fomStatusTag(m);
}

// Tooltip text for a result: the backend's error and/or how the result was reached.
function fomDetail(p) {
    return [p.error ? 'Error: ' + p.error : '', p.note || ''].filter(Boolean).join(' — ').replace(/"/g, '&quot;');
}

function renderFomPeerTable(peers) {
    const rows = peers.map(p => `
    <tr>
      <td>${p.peer_node}</td>
      <td title="${fomDetail(p)}">${fomStatusTag(p)}${p.note || p.error ? ' <span class="fom-alias-note">ⓘ</span>' : ''}</td>
      <td>${p.result_count ?? '—'}</td>
    </tr>`).join('');
    return `
    <table class="fom-peer-table">
      <thead><tr><th>Peer Node</th><th>Status</th><th>Results</th></tr></thead>
      <tbody>${rows}</tbody>
    </table>`;
}

function renderFomCard(m) {
    const header = `
    <div class="fom-card-head">
      <span class="fom-metric-num">Metric ${m.metric}</span>
      ${fomHeaderBadge(m)}
    </div>`;

    // Metric 10: alias of Metric 4, not queried separately
    if (m.alias_of_metric) {
        return `
      <div class="fom-card">
        ${header}
        <div class="fom-desc">${m.description || ''}</div>
        <div class="fom-alias-note">${m.alias_note || `Alias of Metric ${m.alias_of_metric} — not queried separately.`}</div>
      </div>`;
    }

    // Metrics 5 & 11: peer_results array with an aggregate visible flag
    if (Array.isArray(m.peer_results)) {
        const total   = m.peer_results.length;
        const visible = m.peer_results.filter(p => p.visible).length;
        const detailsId = `fom-peers-${m.metric}`;
        return `
      <div class="fom-card">
        ${header}
        <div class="fom-desc">${m.description || ''}</div>
        <details id="${detailsId}">
          <summary class="fom-peer-summary">${visible}/${total} peer Node(s) &mdash; show detail</summary>
          ${total > 0 ? renderFomPeerTable(m.peer_results) : '<div class="fom-alias-note">No peer Nodes with a PID were available to check.</div>'}
        </details>
      </div>`;
    }

    // Metrics 4 & 6: single query result
    return `
    <div class="fom-card">
      ${header}
      <div class="fom-desc">${m.description || ''}</div>
      <div class="fom-target">
        ${m.front_office_owner ? `Front Office: ${m.front_office_owner}<br>` : ''}
        ${m.query_url ? `<a href="${m.query_url}" target="_blank" rel="noreferrer">${m.query_url}</a>` : '—'}
      </div>
      ${m.result_count != null ? `<div class="fom-alias-note">${m.result_count} result(s)</div>` : ''}
      ${m.error ? `<div class="fom-alias-note">Error: ${m.error}</div>` : ''}
      ${m.note ? `<div class="fom-alias-note">${m.note}</div>` : ''}
    </div>`;
}

function renderFrontOfficeMetricsPanel(data) {
    if (!data) {
        return `<div class="panel">
      <div class="panel-header"><div class="panel-header-left"><span class="panel-icon">🔗</span> Federated Search</div>
        <span class="badge badge-missing">Not available</span></div>
      <div class="panel-missing">front_office_metrics_report.json was not found for this node.</div>
    </div>`;
    }

    const metrics = Array.isArray(data.metrics) ? data.metrics : [];
    if (metrics.length === 0) {
        return `<div class="panel">
      <div class="panel-header"><div class="panel-header-left"><span class="panel-icon">🔗</span> Federated Search</div></div>
      <div class="panel-missing">No metrics listed in this report.</div>
    </div>`;
    }

    // Header badge counts every metric card shown below, Metric 10's alias
    // card included, so the denominator always matches what's on screen.
    const visibleCount = metrics.filter(m => m.visible).length;
    const bCls = visibleCount === metrics.length ? 'badge-ok'
        : visibleCount > 0 ? 'badge-warn' : 'badge-error';

    const cards = metrics.map(renderFomCard).join('');

    return `
    <div class="panel">
      <div class="panel-header">
        <div class="panel-header-left"><span class="panel-icon">🔗</span> Federated Search</div>
        <span class="badge ${bCls}">${visibleCount}/${metrics.length} visible</span>
      </div>
      <div class="panel-body"><div class="fom-grid">${cards}</div></div>
    </div>`;
}

// ── Header meta block ─────────────────────────────────────────────────────────

// "Registered" evidences C1's "Present in Node Registry" indicator directly:
// endpoint_report.json's node_id/node_pid are only ever written by
// CheckNodeCapabilities for a node it found in the live Node Registry
// response, so their presence is itself confirmation. Falls back to a
// cross-check against node_registry_summary.json (loaded separately by
// loadSwitcher) so a stale report directory for a since-deregistered node
// doesn't read as "Registered".
// Returns true / false / null (registry data not yet available to decide).
function isRegistered(ep, nodeName) {
    if (ep && (ep.node_id || ep.node_pid)) return true;
    if (Array.isArray(window._allNodes)) {
        return window._allNodes.some(n => (n.node_name || n.name) === nodeName);
    }
    return null;
}

function buildMeta(ep, cat, argo, nodeName) {
    const source   = ep || cat || argo || {};
    const name     = source.node_name || nodeName;
    const provider = source.legal_entity?.name || source.provider || null;
    const endpoint = source.node_endpoint || source.endpoint || null;
    const pid      = source.node_pid || source.pid || null;

    document.getElementById('display-node-name').textContent = name;
    document.title = `${name} — EOSC Beyond Node Dashboard`;

    // Cache the inputs so the Registered chip can be re-evaluated once
    // node_registry_summary.json (fetched separately by loadSwitcher) has
    // loaded — the two fetches race, and buildMeta may run first.
    window._lastMeta = { ep, cat, argo, nodeName };

    const registered  = isRegistered(ep, nodeName);
    const checkedTime = ep?.generated ? ` as of ${ep.generated}` : '';
    const regChip = registered === true
        ? `<span class="badge badge-ok" title="Present in the Node Registry${checkedTime}">✓ Registered</span>`
        : registered === false
            ? `<span class="badge badge-error" title="Not found in node_registry_summary.json">✕ Not registered</span>`
            : `<span class="badge badge-missing" title="Registry data not yet loaded">Registered — checking…</span>`;

    const lines = [];
    lines.push(`<div><strong>Registry:</strong> ${regChip}</div>`);
    if (provider) lines.push(`<div><strong>Provider:</strong> ${provider}</div>`);
    if (endpoint) lines.push(`<div><strong>Endpoint:</strong> <a href="${endpoint}" target="_blank" rel="noreferrer">${endpoint}</a></div>`);
    if (pid)      lines.push(`<div><strong>PID:</strong> ${pid}</div>`);
    document.getElementById('node-meta-info').innerHTML = lines.join('');
}

// ── Node switcher ─────────────────────────────────────────────────────────────

async function loadSwitcher() {
    const data = await tryFetchJSON('/api/data/node_registry_summary.json');
    if (!data || !Array.isArray(data.nodes)) {
        document.getElementById('ns-list').textContent = 'Could not load nodes';
        return;
    }
    window._allNodes = data.nodes;
    refreshSwitcher();

    // Re-evaluate the Registered chip now that registry data has loaded, in
    // case buildMeta() ran first — init() and loadSwitcher() race and are
    // not guaranteed to resolve in order.
    if (window._lastMeta) {
        const { ep, cat, argo, nodeName } = window._lastMeta;
        buildMeta(ep, cat, argo, nodeName);
    }
}

function refreshSwitcher() {
    if (!window._allNodes) return;
    const current = decodeURIComponent(window.location.hash.substring(1));
    document.getElementById('ns-list').innerHTML = window._allNodes.map(n => {
        const name  = n.node_name || n.name || '';
        const isCur = name === current;
        return `<a class="ns-item${isCur ? ' current' : ''}" role="menuitem"
               href="node.html#${encodeURIComponent(name)}"
               onclick="document.getElementById('node-switcher').setAttribute('aria-expanded','false')">
              <span class="ns-dot green"></span>
              ${name}
              <span class="ns-check">✓</span>
            </a>`;
    }).join('');
}

function toggleSwitcher() {
    const sw = document.getElementById('node-switcher');
    sw.setAttribute('aria-expanded', sw.getAttribute('aria-expanded') !== 'true');
}

// Close on outside click or Escape
document.addEventListener('click', e => {
    const sw = document.getElementById('node-switcher');
    if (sw && !sw.contains(e.target)) sw.setAttribute('aria-expanded', 'false');
});
document.addEventListener('keydown', e => {
    if (e.key === 'Escape') document.getElementById('node-switcher')?.setAttribute('aria-expanded', 'false');
});

// ── Run-checks menu ───────────────────────────────────────────────────────────

const NODE_CHECK_LABELS = {
    'catalogue-services': 'Exchange Services',
    'service-uptime':     'Service Uptime',
    'core-integrations':  'Core Service integrations',
    'other-metrics':      'Federated Search',
};

function toggleRunMenu() {
    const menu = document.getElementById('run-menu');
    const isOpen = menu.getAttribute('aria-expanded') === 'true';
    menu.setAttribute('aria-expanded', !isOpen);
    // Close node switcher if open
    if (!isOpen) {
        const elementById = document.getElementById('node-switcher');
        if (elementById) {
            elementById.setAttribute('aria-expanded', 'false');
        }
    }
}

document.addEventListener('click', e => {
    const menu = document.getElementById('run-menu');
    if (menu && !menu.contains(e.target)) menu.setAttribute('aria-expanded', 'false');
});
document.addEventListener('keydown', e => {
    if (e.key === 'Escape') {
        document.getElementById('run-menu')?.setAttribute('aria-expanded', 'false');
        closeAbout();
    }
});

// ── Exchange Services check (no API key needed) ─────────────────────────────
// Reads the Resource Catalogue endpoint from this node's endpoint_report.json,
// then POSTs { node, catalogueUrl } to the backend. No credentials required.

// Runs this node's four checks one after another (same order as the menu).
// Each check reports its own progress/failure toast, and a failed or skipped
// check (e.g. no Resource Catalogue endpoint) does not stop the others.
async function runAllNodeChecks() {
    document.getElementById('run-menu').setAttribute('aria-expanded', 'false');
    const btn      = document.getElementById('btn-run-all-node');
    const nodeName = decodeURIComponent(window.location.hash.substring(1));
    if (btn) { btn.disabled = true; btn.textContent = '…'; }
    showToast('info', 'Run All Node Checks', `Starting all checks for ${nodeName}…`);

    for (const check of [runCatalogueCheck, runServiceUptimeCheck, runCoreIntegrationsCheck, runOtherMetricsCheck]) {
        try { await check(); } catch (e) { showToast('err', 'Run All Node Checks', 'Check failed: ' + e.message); }
    }

    showToast('ok', 'Run All Node Checks', `All checks finished for ${nodeName}`);
    if (btn) { btn.disabled = false; btn.textContent = 'Run all'; }
}

async function runCatalogueCheck() {
    document.getElementById('run-menu').setAttribute('aria-expanded', 'false');
    const btn      = document.getElementById('btn-catalogue-services');
    const nodeName = decodeURIComponent(window.location.hash.substring(1));
    if (btn) { btn.disabled = true; btn.textContent = '…'; }
    showToast('info', 'Exchange Services', `Starting for ${nodeName}…`);

    // Fetch the endpoint report for the current node and find the
    // Resource Catalogue URL. Pass the raw endpoint value; the Java
    // class strips any trailing /api or /api/ and appends
    // /api/service/all itself.
    const epData = await tryFetchJSON(`/api/data/${encodeURIComponent(nodeName)}/endpoint_report.json`);
    const catalogueUrl = epData?.capabilities
        ?.find(c => c.capability_type === 'Resource Catalogue')
        ?.endpoint || null;
    const nodePid = epData?.node_pid || null;

    if (!catalogueUrl) {
        showToast('err', 'Exchange Services',
            'No Resource Catalogue endpoint found in endpoint_report.json for this node.');
        resetBtn(btn);
        return;
    }

    let jobId;
    try {
        const res  = await fetch('/api/run/catalogue-services', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ node: nodeName, catalogueUrl, nodePid }),
        });
        const data = await res.json();
        if (!res.ok) { showToast('err', 'Exchange Services', data.error || 'HTTP ' + res.status); resetBtn(btn); return; }
        jobId = data.jobId;
    } catch (e) {
        showToast('err', 'Exchange Services', 'Request failed: ' + e.message);
        resetBtn(btn); return;
    }

    showToast('info', 'Exchange Services', 'Running… (job ' + jobId + ')');
    const result = await pollJobStatus(jobId);
    if (result.status === 'DONE') { showToast('ok', 'Exchange Services', result.message || 'Completed'); init(); }
    else                          { showToast('err', 'Exchange Services', result.message || 'Check failed'); }
    resetBtn(btn);
}

async function runCoreIntegrationsCheck() {
    document.getElementById('run-menu').setAttribute('aria-expanded', 'false');
    const btn      = document.getElementById('btn-core-integrations');
    const nodeName = decodeURIComponent(window.location.hash.substring(1));
    if (btn) { btn.disabled = true; btn.textContent = '…'; }
    showToast('info', 'Core Service integrations', `Starting for ${nodeName}…`);

    let jobId;
    try {
        const res  = await fetch('/api/run/core-integrations', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ node: nodeName }),
        });
        const data = await res.json();
        if (!res.ok) { showToast('err', 'Core Service integrations', data.error || 'HTTP ' + res.status); resetBtn(btn); return; }
        jobId = data.jobId;
    } catch (e) {
        showToast('err', 'Core Service integrations', 'Request failed: ' + e.message);
        resetBtn(btn); return;
    }

    showToast('info', 'Core Service integrations', 'Running… (job ' + jobId + ')');
    const result = await pollJobStatus(jobId);
    if (result.status === 'DONE') { showToast('ok', 'Core Service integrations', result.message || 'Completed'); init(); }
    else                          { showToast('err', 'Core Service integrations', result.message || 'Check failed'); }
    resetBtn(btn);
}

// ── Federated Search check (no API key needed) ────────────────────
// Runs CheckOtherMetrics. Resolves everything it needs (PIDs, Front Office
// endpoints, including the Sandbox's) server-side from
// node_registry_summary.json and each node's endpoint_report.json — the
// caller only needs to name the node.

async function runOtherMetricsCheck() {
    document.getElementById('run-menu').setAttribute('aria-expanded', 'false');
    const btn      = document.getElementById('btn-other-metrics');
    const nodeName = decodeURIComponent(window.location.hash.substring(1));
    if (btn) { btn.disabled = true; btn.textContent = '…'; }
    showToast('info', 'Federated Search', `Starting for ${nodeName}…`);

    let jobId;
    try {
        const res  = await fetch('/api/run/other-metrics', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ node: nodeName }),
        });
        const data = await res.json();
        if (!res.ok) { showToast('err', 'Federated Search', data.error || 'HTTP ' + res.status); resetBtn(btn); return; }
        jobId = data.jobId;
    } catch (e) {
        showToast('err', 'Federated Search', 'Request failed: ' + e.message);
        resetBtn(btn); return;
    }

    showToast('info', 'Federated Search', 'Running… (job ' + jobId + ')');
    const result = await pollJobStatus(jobId);
    if (result.status === 'DONE') { showToast('ok', 'Federated Search', result.message || 'Completed'); init(); }
    else                          { showToast('err', 'Federated Search', result.message || 'Check failed'); }
    resetBtn(btn);
}

// ── Service Uptime check (no API key needed — capability-metrics API is public) ─

async function runServiceUptimeCheck() {
    document.getElementById('run-menu').setAttribute('aria-expanded', 'false');
    const btn      = document.getElementById('btn-service-uptime');
    const nodeName = decodeURIComponent(window.location.hash.substring(1));
    if (btn) { btn.disabled = true; btn.textContent = '…'; }
    showToast('info', 'Service Uptime', `Starting for ${nodeName}…`);

    let jobId;
    try {
        const res  = await fetch('/api/run/service-uptime', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ node: nodeName }),
        });
        const data = await res.json();
        if (!res.ok) { showToast('err', 'Service Uptime', data.error || 'HTTP ' + res.status); resetBtn(btn); return; }
        jobId = data.jobId;
    } catch (e) {
        showToast('err', 'Service Uptime', 'Request failed: ' + e.message);
        resetBtn(btn); return;
    }

    showToast('info', 'Service Uptime', 'Running… (job ' + jobId + ')');
    const result = await pollJobStatus(jobId);
    if (result.status === 'DONE') { showToast('ok', 'Service Uptime', result.message || 'Completed'); init(); }
    else                          { showToast('err', 'Service Uptime', result.message || 'Check failed'); }
    resetBtn(btn);
}

// ARGO federation-tenant status (core_integrations_report.json) for one
// capability: matched on capability type, preferring the same endpoint URL.
function findCoreStatus(core, cap) {
    if (!core || !Array.isArray(core.endpoints)) return null;
    const norm = u => (u || '').replace(/\/+$/, '').toLowerCase();
    const sameType = core.endpoints.filter(e => e.capability_type === cap.capability_type);
    const match = sameType.find(e => norm(e.url) === norm(cap.endpoint)) || sameType[0] || null;
    return match ? { ...match, argo_ui_url: core.argo_ui_url || null } : null;
}

// Friendly names for the ARGO probes seen in the federation tenant; any other
// probe is shown under its raw ARGO metric name.
const ARGO_PROBE_LABELS = {
    'generic.http.connect':        'HTTP connection',
    'generic.certificate.validity': 'TLS certificate validity',
    'generic.tcp.connect':         'TCP connection',
};

function argoBadgeClass(v) {
    return v === 'OK' ? 'badge-ok' : v === 'WARNING' ? 'badge-warn'
        : v === 'CRITICAL' ? 'badge-error' : 'badge-missing';
}

function argoUiLink(cs) {
    if (!cs.argo_ui_url) return '';
    return `<div class="ep-probe-note" style="font-style:normal;"><a href="${cs.argo_ui_url}" target="_blank" rel="noreferrer">Open in the ARGO status UI &#8599;</a></div>`;
}

// The card's "ARGO monitoring" line. A non-OK status expands (click) to the
// probes behind it, from core_integrations_report.json.
function coreStatusBadge(cs) {
    if (!cs) return '';
    const worst = cs.worst_status && cs.worst_status !== cs.status ? ` (worst today: ${cs.worst_status})` : '';
    const badge = `<span class="badge ${argoBadgeClass(cs.status)}">${cs.status}</span>`;
    const probes = Array.isArray(cs.probes) ? cs.probes : [];
    if (cs.status === 'OK' && cs.worst_status === 'OK') {
        return `<div class="ep-version" style="margin-top:0.4rem;">ARGO monitoring: ${badge}</div>`;
    }
    if (probes.length === 0) {
        return `<div class="ep-version" style="margin-top:0.4rem;">ARGO monitoring: ${badge}${worst}
      <span title="ARGO probe detail was not available when this report was generated. Re-run Core Service integrations.">(no detail)</span>
      ${argoUiLink(cs)}</div>`;
    }
    const time = ts => ts ? ts.slice(11, 16) + 'Z' : '';
    const stamp = ts => ts ? `${ts.slice(0, 10)} ${time(ts)}` : '';
    const rows = probes.map(pr => {
        const label = ARGO_PROBE_LABELS[pr.name] || pr.name;
        const since = pr.first_non_ok ? `, first at ${stamp(pr.first_non_ok)}` : '';
        const detail = pr.non_ok_checks > 0
            ? `${pr.non_ok_checks} of ${pr.total_checks} checks not OK${since}`
            : `all ${pr.total_checks} checks OK`;
        return `<li><span class="badge ${argoBadgeClass(pr.status)}">${pr.status}</span>
      <strong>${label}</strong>${ARGO_PROBE_LABELS[pr.name] ? ` <code>${pr.name}</code>` : ''}<br>
      <span class="ep-probe-detail">${detail}</span></li>`;
    }).join('');
    return `
    <details class="ep-argo-details">
      <summary>ARGO monitoring: ${badge}${worst} <span class="ep-argo-more">why?</span></summary>
      <ul class="ep-probes">${rows}</ul>
      <div class="ep-probe-note">Probe results from the ARGO federation tenant, last checked ${stamp(cs.last_checked)} (period: the current UTC day). ARGO reports which probe is failing, not the error message.</div>
      ${argoUiLink(cs)}
    </details>`;
}