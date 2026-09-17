// Pipeline Metrics dashboard.
//
// Loaded through <st:adjunct> from the dashboard's <l:header>, so this runs in <head> before the
// page body exists. Everything therefore waits for DOMContentLoaded.
//
// The whole file is wrapped in a function. Rendered through <l:layout>, it shares global scope with
// Jenkins core's own scripts, and core already defines a global `qs`; a top-level declaration of
// the same name threw and stopped the dashboard from loading. Keeping declarations function-scoped
// avoids that for any name core adds later.
(function () {
'use strict';

document.addEventListener('DOMContentLoaded', function () {

const cfg = document.getElementById('pm-config');
const BASE = cfg.getAttribute('data-base');

// ─── Requests ────────────────────────────────────────────────────────────────

// POST with Jenkins' own CSRF crumb (the global `crumb`, set on every page). The dashboard refreshes
// itself and can stay open for hours, so a request can outlive the session the crumb belongs to.
// Jenkins answers that with 403, which is reported as a prompt to reload rather than failing silently.
async function post(path, body) {
    const headers = {};
    if (body) {
        headers['Content-Type'] = 'application/x-www-form-urlencoded';
    }
    const res = await fetch(BASE + path, {method: 'POST', headers: crumb.wrap(headers), body: body});
    if (res.status === 403) {
        dialog.alert('Session expired', {message: 'Reload the page and try again.'});
        throw new Error('forbidden');
    }
    return res;
}

// dialog.confirm and dialog.prompt reject when the user cancels. Turn that into a plain value so
// callers can simply check it instead of wrapping every call in try/catch.
function confirmed(title, message) {
    return dialog.confirm(title, {message: message}).then(function () { return true; }, function () { return false; });
}

function prompted(title, message) {
    return dialog.prompt(title, {message: message}).then(function (value) { return value; }, function () { return null; });
}

// ─── Formatting ──────────────────────────────────────────────────────────────

const F = () => ({
    folder: document.getElementById('f-folder').value,
    agent: document.getElementById('f-agent').value,
    user: document.getElementById('f-user').value,
    days: document.getElementById('f-days').value,
    group_by: document.getElementById('f-group').value,
});

const query = p => Object.entries(p).filter(([, v]) => v).map(([k, v]) => `${k}=${encodeURIComponent(v)}`).join('&');

function dur(ms) {
    if (!ms) return '—';
    const s = Math.round(ms / 1000);
    if (s < 60) return s + 's';
    const m = Math.floor(s / 60), r = s % 60;
    if (m < 60) return m + 'm ' + r + 's';
    return Math.floor(m / 60) + 'h ' + m % 60 + 'm';
}

function num(n) {
    if (n == null) return '—';
    if (n >= 1e6) return (n / 1e6).toFixed(1) + 'M';
    if (n >= 1e3) return (n / 1e3).toFixed(1) + 'K';
    return n.toLocaleString();
}

// Job names, stage names, agent/node labels and usernames all come from data any user who can
// create or configure a job, or trigger a build, can influence. Never trust them as HTML.
const ESCAPE_MAP = {'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'};
function esc(s) {
    if (s == null) return '';
    return String(s).replace(/[&<>"']/g, c => ESCAPE_MAP[c]);
}

function rate(r) {
    const level = r < 20 ? 'ok' : r < 40 ? 'warn' : 'bad';
    const badge = r < 20 ? 'green' : r < 40 ? 'yellow' : 'red';
    return `<div class="pm-rate">
        <div class="pm-rate-bar"><div class="pm-rate-fill pm-${level}" style="width:${Math.min(r, 100)}%"></div></div>
        <span class="pm-badge pm-badge-${badge}">${r.toFixed(1)}%</span>
    </div>`;
}

function empty(cols, text) {
    return `<tr><td colspan="${cols}"><div class="pm-empty">${text}</div></td></tr>`;
}

// ─── Charts ──────────────────────────────────────────────────────────────────

// Resolve a theme token to rgba(). Jenkins' tokens are often oklch(), which the browser can paint
// but ECharts' own colour parsing does not understand. Painting the colour onto a 1x1 canvas and
// reading the pixel back normalises any colour syntax the browser supports.
const swatch = document.createElement('canvas').getContext('2d', {willReadFrequently: true});
function themeColor(token) {
    const value = getComputedStyle(document.querySelector('.pm-app')).getPropertyValue(token).trim();
    swatch.clearRect(0, 0, 1, 1);
    swatch.fillStyle = '#000';
    swatch.fillStyle = value || '#000';
    swatch.fillRect(0, 0, 1, 1);
    const [r, g, b, a] = swatch.getImageData(0, 0, 1, 1).data;
    return `rgba(${r}, ${g}, ${b}, ${(a / 255).toFixed(2)})`;
}

function chart(id) {
    const el = document.getElementById(id);
    return echarts.getInstanceByDom(el) || echarts.init(el);
}

function baseOption(labels) {
    const text = themeColor('--pm-text-dim');
    const grid = themeColor('--pm-border');
    return {
        textStyle: {color: text},
        grid: {left: 40, right: 16, top: 36, bottom: 28},
        legend: {top: 0, right: 0, textStyle: {color: text}},
        tooltip: {trigger: 'axis'},
        xAxis: {type: 'category', data: labels, axisLine: {lineStyle: {color: grid}}, axisLabel: {color: text}},
        yAxis: {type: 'value', splitLine: {lineStyle: {color: grid}}, axisLabel: {color: text}},
    };
}

async function loadTrends(f) {
    const data = await fetch(`${BASE}/trends?${query(f)}`).then(r => r.json());
    const labels = data.map(d => d.period.length === 10 ? d.period.slice(5) : d.period);
    const green = themeColor('--pm-green');
    const red = themeColor('--pm-red');
    const blue = themeColor('--pm-blue');

    chart('chart-volume').setOption(Object.assign(baseOption(labels), {
        series: [
            {name: 'Success', type: 'bar', stack: 'builds', data: data.map(d => d.success), itemStyle: {color: green}},
            {name: 'Failed', type: 'bar', stack: 'builds', data: data.map(d => d.failures), itemStyle: {color: red}},
        ],
    }), true);

    chart('chart-duration').setOption(Object.assign(baseOption(labels), {
        series: [{
            name: 'Average (seconds)', type: 'line', smooth: true,
            data: data.map(d => Math.round((d.avg_duration_ms || 0) / 1000)),
            itemStyle: {color: blue}, lineStyle: {color: blue}, areaStyle: {color: blue, opacity: 0.08},
        }],
    }), true);
}

window.addEventListener('resize', function () {
    ['chart-volume', 'chart-duration'].forEach(id => {
        const instance = echarts.getInstanceByDom(document.getElementById(id));
        if (instance) instance.resize();
    });
});

// ─── Panels ──────────────────────────────────────────────────────────────────

async function loadFilters() {
    const d = await fetch(BASE + '/filters').then(r => r.json());
    const add = (sel, items, emptyLabel) => {
        items.forEach(i => {
            const o = document.createElement('option');
            o.value = i || emptyLabel;
            o.textContent = i || emptyLabel;
            document.getElementById(sel).appendChild(o);
        });
    };
    add('f-folder', d.folders, '(root)');
    add('f-agent', d.agents, 'built-in');
    add('f-user', d.users, 'unknown');
}

function deltaHTML(curr, prev, opts = {}) {
    // opts.invert: true when a lower value is the improvement (failure rate, duration).
    if (curr == null || prev == null || !isFinite(curr) || !isFinite(prev) || prev === 0) return '';
    const pct = ((curr - prev) / prev) * 100;
    if (Math.abs(pct) < 0.5) return 'flat vs previous period';
    const up = pct > 0;
    const good = opts.invert ? !up : up;
    return `<span class="${good ? 'pm-delta-good' : 'pm-delta-bad'}">${up ? '▲' : '▼'} ${Math.abs(pct).toFixed(0)}% vs previous period</span>`;
}

async function loadKPIs(f) {
    const d = await fetch(`${BASE}/overview?${query(f)}`).then(r => r.json());
    const t = d.total_builds || 0;
    const sr = t ? (d.successful / t * 100) : 0;
    const fr = t ? (d.failed / t * 100) : 0;
    const set = (id, text) => { document.getElementById(id).textContent = text; };

    set('k-total', num(t));
    set('k-total-sub', t ? `${d.successful || 0} passed · ${d.failed || 0} failed` : '');
    set('k-success', sr ? sr.toFixed(1) + '%' : '—');
    set('k-success-sub', sr ? `${d.successful} builds` : '');
    set('k-fail', fr ? fr.toFixed(1) + '%' : '—');
    set('k-fail-sub', fr ? `${d.failed} builds` : '');
    set('k-dur', dur(d.avg_duration_ms));
    set('k-queue', dur(d.avg_queue_time_ms));
    set('k-max', dur(d.max_duration_ms));

    const pt = d.prev_total_builds || 0;
    const psr = pt ? (d.prev_successful / pt * 100) : null;
    const pfr = pt ? (d.prev_failed / pt * 100) : null;
    document.getElementById('k-total-delta').innerHTML = deltaHTML(t, pt);
    // Gate on `t`, not on the rates: a real 0% rate is falsy and would otherwise hide the delta
    // exactly when it matters most, for example a drop from 95% success to 0%.
    document.getElementById('k-success-delta').innerHTML = t && psr != null ? deltaHTML(sr, psr) : '';
    document.getElementById('k-fail-delta').innerHTML = t && pfr != null ? deltaHTML(fr, pfr, {invert: true}) : '';
    document.getElementById('k-dur-delta').innerHTML = deltaHTML(d.avg_duration_ms, d.prev_avg_duration_ms, {invert: true});
}

async function loadHeatmap(f) {
    const data = await fetch(`${BASE}/heatmap?${query(f)}`).then(r => r.json());
    const days = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
    const max = Math.max(...data.map(d => d.count), 1);

    let html = '<div class="pm-heatmap-label"></div>';
    for (let h = 0; h < 24; h++) html += `<div class="pm-heatmap-hour">${h % 6 === 0 ? h : ''}</div>`;
    for (let d = 0; d < 7; d++) {
        html += `<div class="pm-heatmap-label">${days[d]}</div>`;
        for (let h = 0; h < 24; h++) {
            const cell = data.find(x => x.day_of_week === d && x.hour === h);
            let level = '0';
            if (cell) {
                if (cell.failures > cell.count / 2) {
                    level = 'fail';
                } else {
                    const pct = cell.count / max;
                    level = pct > 0.75 ? '4' : pct > 0.5 ? '3' : pct > 0.25 ? '2' : '1';
                }
            }
            const title = cell ? `${cell.count} builds, ${cell.failures} failed` : 'No builds';
            html += `<div class="pm-heatmap-cell" data-level="${level}" title="${days[d]} ${h}:00, ${title}"></div>`;
        }
    }
    document.getElementById('heatmap').innerHTML = html;
}

async function loadUsers(f) {
    const data = await fetch(`${BASE}/users?${query({days: f.days, folder: f.folder})}`).then(r => r.json());
    const el = document.getElementById('users-list');
    if (!data.length) {
        el.innerHTML = '<div class="pm-empty">No user data</div>';
        return;
    }
    el.innerHTML = data.slice(0, 6).map(u => {
        const badge = u.failure_rate > 30 ? 'red' : u.failure_rate > 10 ? 'yellow' : 'green';
        return `<div class="pm-user">
            <div>
                <div>${esc(u.user)}</div>
                <div class="pm-user-meta">${u.total_builds} builds</div>
            </div>
            <span class="pm-badge pm-badge-${badge}">${u.failure_rate.toFixed(0)}% fail</span>
        </div>`;
    }).join('');
}

async function loadPipelines(f) {
    const data = await fetch(`${BASE}/pipelines?${query(f)}`).then(r => r.json());
    const tb = document.getElementById('tbl-pipelines');
    if (!data.length) { tb.innerHTML = empty(7, 'No data yet'); return; }
    tb.innerHTML = data.map(p => `<tr>
        <td>${esc(p.job_name)}</td>
        <td><span class="pm-badge pm-badge-dim">${esc(p.job_folder || 'root')}</span></td>
        <td>${p.total_builds}</td>
        <td class="pm-mono">${dur(p.avg_duration_ms)}</td>
        <td class="pm-mono">${dur(p.max_duration_ms)}</td>
        <td class="pm-mono">${dur(p.avg_queue_ms)}</td>
        <td>${rate(p.failure_rate)}</td>
    </tr>`).join('');
}

async function loadAgents(f) {
    const data = await fetch(`${BASE}/agents?${query({days: f.days, folder: f.folder, user: f.user})}`).then(r => r.json());
    const tb = document.getElementById('tbl-agents');
    if (!data.length) { tb.innerHTML = empty(6, 'No data'); return; }
    tb.innerHTML = data.map(a => `<tr>
        <td>${esc(a.agent)}</td>
        <td><span class="pm-badge pm-badge-dim">${esc(a.node_labels || '—')}</span></td>
        <td>${a.total_builds}</td>
        <td class="pm-mono">${dur(a.avg_duration_ms)}</td>
        <td>${rate(a.failure_rate)}</td>
        <td class="pm-mono">${dur(a.total_busy_ms)}</td>
    </tr>`).join('');
}

async function loadStages(f) {
    const data = await fetch(`${BASE}/stages?${query({days: f.days, folder: f.folder, agent: f.agent, user: f.user})}`).then(r => r.json());
    const tb = document.getElementById('tbl-stages');
    if (!data.length) { tb.innerHTML = empty(5, 'No data'); return; }
    tb.innerHTML = data.map(s => `<tr>
        <td>${esc(s.stage_name)}</td>
        <td>${s.run_count}</td>
        <td class="pm-mono">${dur(s.avg_duration_ms)}</td>
        <td class="pm-mono">${dur(s.max_duration_ms)}</td>
        <td>${rate(s.failure_rate)}</td>
    </tr>`).join('');
}

let lastUpdated = null;
function tickLastUpdated() {
    const el = document.getElementById('last-updated');
    if (!lastUpdated) { el.textContent = ''; return; }
    const secs = Math.round((Date.now() - lastUpdated) / 1000);
    el.textContent = secs < 5 ? 'updated just now' : `updated ${secs}s ago`;
}

async function refresh() {
    const f = F();
    await Promise.all([loadKPIs(f), loadTrends(f), loadHeatmap(f), loadUsers(f),
        loadPipelines(f), loadAgents(f), loadStages(f)]);
    lastUpdated = Date.now();
    tickLastUpdated();
}

// ─── Tabs ────────────────────────────────────────────────────────────────────

// The tab bar is core's <l:tabBar>/<l:tab>. Core renders each tab's radio input with an id starting
// with "tab-", so the panels use a "pm-panel-" prefix and every lookup is scoped to #pm-tabs;
// otherwise the panel toggling would also hide core's own inputs.
const tabs = document.getElementById('pm-tabs');

function activePanel() {
    const link = tabs.querySelector('.tab.active a');
    return link ? link.getAttribute('href').slice(1) : 'pipelines';
}

tabs.addEventListener('click', function (e) {
    const link = e.target.closest('.tab a');
    if (!link) return;
    e.preventDefault();
    tabs.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
    link.closest('.tab').classList.add('active');
    const target = link.getAttribute('href').slice(1);
    document.querySelectorAll('.pm-panel').forEach(p => { p.hidden = p.id !== 'pm-panel-' + target; });
    document.getElementById('tbl-search').value = '';
    filterActiveTable();
});

function filterActiveTable() {
    const q = document.getElementById('tbl-search').value.trim().toLowerCase();
    document.querySelectorAll(`#pm-panel-${activePanel()} tbody tr`).forEach(row => {
        row.hidden = Boolean(q) && !row.textContent.toLowerCase().includes(q);
    });
}

// ─── Actions ─────────────────────────────────────────────────────────────────

function exportCSV() {
    window.location = `${BASE}/report.csv?${query(F())}`;
}

async function triggerSync() {
    const label = document.getElementById('sync-label');
    label.textContent = 'Syncing…';
    try {
        await post('/collect');
        setTimeout(async function () { await refresh(); label.textContent = 'Live'; }, 3000);
    } catch (e) {
        label.textContent = 'Live';
    }
}

async function triggerBackfill() {
    const btn = document.getElementById('btn-backfill');
    const reset = () => { btn.disabled = false; btn.textContent = 'Backfill'; };
    btn.disabled = true;
    btn.textContent = 'Backfilling…';
    try {
        await post('/backfill');
    } catch (e) {
        reset();
        return;
    }
    const poll = setInterval(async function () {
        const st = await fetch(BASE + '/backfillStatus').then(r => r.json());
        btn.textContent = `${st.builds_processed || 0} builds`;
        if (!st.running) {
            clearInterval(poll);
            reset();
            await refresh();
        }
    }, 1500);
}

function report(action, res) {
    if (res && res.status === 'ok') {
        dialog.alert(`${action} complete`, {
            message: `${res.inserted} new, ${res.updated} updated, ${res.skipped} skipped (of ${res.read} read).`,
        });
        return true;
    }
    dialog.alert(`${action} failed`, {message: res && res.message ? res.message : 'Unknown error'});
    return false;
}

async function triggerImport() {
    const path = await prompted('Import sidecar data', 'Absolute path to the sidecar metrics.db on the Jenkins controller');
    if (!path) return;
    let res = null;
    try {
        res = await post('/import', new URLSearchParams({path: path})).then(r => r.json());
    } catch (e) {
        return;
    }
    if (report('Import', res)) await refresh();
}

async function triggerStorageMigration() {
    if (!await confirmed('Migrate storage', 'Copy all history from the local SQLite store into the currently configured storage backend?')) {
        return;
    }
    let res = null;
    try {
        res = await post('/migrateStorage').then(r => r.json());
    } catch (e) {
        return;
    }
    if (report('Migration', res)) await refresh();
}

// ─── Wiring ──────────────────────────────────────────────────────────────────

// Event handlers are attached here rather than inline, since Jenkins' Content-Security-Policy
// blocks inline event handler attributes.
document.getElementById('btn-export').addEventListener('click', exportCSV);
const sync = document.getElementById('btn-sync');
if (sync) sync.addEventListener('click', triggerSync);
const backfill = document.getElementById('btn-backfill');
if (backfill) backfill.addEventListener('click', triggerBackfill);
const importBtn = document.getElementById('btn-import');
if (importBtn) importBtn.addEventListener('click', triggerImport);
const migrate = document.getElementById('btn-migrate');
if (migrate) migrate.addEventListener('click', triggerStorageMigration);

['f-folder', 'f-agent', 'f-user', 'f-days', 'f-group'].forEach(id => {
    document.getElementById(id).addEventListener('change', refresh);
});
document.getElementById('tbl-search').addEventListener('input', filterActiveTable);

loadFilters().then(refresh);
setInterval(refresh, 60000);

});
})();
