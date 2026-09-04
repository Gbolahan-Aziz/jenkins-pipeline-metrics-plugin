// Bootstrap: resolve API base + CSRF crumb from the page config element.
window.PM = window.PM || {};
(function () {
    var cfg = document.getElementById('pm-config');
    window.PM.base = cfg ? cfg.getAttribute('data-base') : 'api';
    window.PM.root = cfg ? cfg.getAttribute('data-root') : '/';
    var field = cfg ? cfg.getAttribute('data-crumb-field') : '';
    var value = cfg ? cfg.getAttribute('data-crumb') : '';
    window.PM.crumbHeader = {};
    if (field) { window.PM.crumbHeader[field] = value; }
})();

// Fetch a fresh CSRF crumb for state-changing POSTs (layout-independent).
window.PM.postHeaders = async function (extra) {
    var headers = extra || {};
    try {
        var c = await fetch(window.PM.root + 'crumbIssuer/api/json').then(function (r) { return r.json(); });
        if (c && c.crumbRequestField) { headers[c.crumbRequestField] = c.crumb; }
    } catch (e) { /* crumb issuer disabled: proceed without */ }
    return headers;
};

let volChart = null, durChart = null;

const F = () => ({
    folder: document.getElementById('f-folder').value,
    agent: document.getElementById('f-agent').value,
    user: document.getElementById('f-user').value,
    days: document.getElementById('f-days').value,
    group_by: document.getElementById('f-group').value,
});

const qs = p => Object.entries(p).filter(([,v])=>v).map(([k,v])=>`${k}=${encodeURIComponent(v)}`).join('&');

function dur(ms) {
    if (!ms) return '—';
    const s = Math.round(ms/1000);
    if (s < 60) return s+'s';
    const m = Math.floor(s/60), r = s%60;
    if (m < 60) return m+'m '+r+'s';
    return Math.floor(m/60)+'h '+m%60+'m';
}

function num(n) {
    if (n==null) return '—';
    if (n>=1e6) return (n/1e6).toFixed(1)+'M';
    if (n>=1e3) return (n/1e3).toFixed(1)+'K';
    return n.toLocaleString();
}

// Job names, stage names, agent/node labels, and usernames all come from data any user who can
// create/configure a job or trigger a build can influence — never trust them as HTML. Escape
// before interpolating into any innerHTML-bound template.
const ESCAPE_MAP = {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'};
function esc(s) {
    if (s == null) return '';
    return String(s).replace(/[&<>"']/g, c => ESCAPE_MAP[c]);
}

function rate(r) {
    const cls = r===0?'ok':r<20?'ok':r<40?'warn':'bad';
    const badge = r===0?'badge-green':r<20?'badge-green':r<40?'badge-yellow':'badge-red';
    return `<div class="rate">
        <div class="rate-bar"><div class="rate-fill ${cls}" style="width:${Math.min(r,100)}%"></div></div>
        <span class="badge ${badge}">${r.toFixed(1)}%</span>
    </div>`;
}

const chartOpts = {
    responsive:true, maintainAspectRatio:true,
    plugins:{
        legend:{position:'top',align:'end',labels:{color:'#484f58',font:{size:11,family:'DM Sans'},boxWidth:8,boxHeight:8,borderRadius:2,useBorderRadius:true,padding:14}},
        tooltip:{backgroundColor:'rgba(13,17,23,0.96)',borderColor:'rgba(255,255,255,0.1)',borderWidth:1,titleFont:{family:'DM Sans',size:12},bodyFont:{family:'DM Sans',size:11},padding:10,cornerRadius:10,boxWidth:8,boxHeight:8,boxPadding:4}
    },
    scales:{
        x:{ticks:{color:'#484f58',font:{size:10,family:'DM Sans'}},grid:{color:'rgba(255,255,255,0.03)',drawBorder:false},border:{display:false}},
        y:{ticks:{color:'#484f58',font:{size:10,family:'DM Sans'}},grid:{color:'rgba(255,255,255,0.03)',drawBorder:false},border:{display:false}}
    }
};

async function loadFilters() {
    const d = await fetch(window.PM.base + '/filters').then(r=>r.json());
    const add = (sel, items, empty) => {
        items.forEach(i => {
            const o = document.createElement('option');
            o.value = i || empty; o.textContent = i || empty;
            document.getElementById(sel).appendChild(o);
        });
    };
    add('f-folder', d.folders, '(root)');
    add('f-agent', d.agents, 'built-in');
    add('f-user', d.users, 'unknown');
}

async function refresh() {
    const f = F();
    await Promise.all([loadKPIs(f), loadTrends(f), loadHeatmap(f), loadUsers(f), loadPipelines(f), loadAgents(f), loadStages(f)]);
}

function deltaHTML(curr, prev, opts={}) {
    // opts.invert: true when a lower value is the improvement (e.g. failure rate, duration)
    if (curr == null || prev == null || !isFinite(curr) || !isFinite(prev) || prev === 0) return '';
    const pct = ((curr - prev) / prev) * 100;
    if (Math.abs(pct) < 0.5) return `<span class="kpi-delta-flat">flat vs prev</span>`;
    const up = pct > 0;
    const good = opts.invert ? !up : up;
    const arrow = up ? '▲' : '▼';
    return `<span class="kpi-delta-val ${good?'good':'bad'}">${arrow} ${Math.abs(pct).toFixed(0)}% vs prev ${opts.periodLabel||'period'}</span>`;
}

async function loadKPIs(f) {
    const d = await fetch(`${window.PM.base}/overview?${qs(f)}`).then(r=>r.json());
    const t = d.total_builds||0;
    document.getElementById('k-total').textContent = num(t);
    document.getElementById('k-total-sub').textContent = t ? `${d.successful||0} passed · ${d.failed||0} failed` : '';
    const sr = t ? (d.successful/t*100) : 0;
    const fr = t ? (d.failed/t*100) : 0;
    document.getElementById('k-success').textContent = sr ? sr.toFixed(1)+'%' : '—';
    document.getElementById('k-success-sub').textContent = sr ? `${d.successful} builds` : '';
    document.getElementById('k-fail').textContent = fr ? fr.toFixed(1)+'%' : '—';
    document.getElementById('k-fail-sub').textContent = fr ? `${d.failed} builds` : '';
    document.getElementById('k-dur').textContent = dur(d.avg_duration_ms);
    document.getElementById('k-queue').textContent = dur(d.avg_queue_time_ms);
    document.getElementById('k-max').textContent = dur(d.max_duration_ms);

    const pt = d.prev_total_builds || 0;
    const psr = pt ? (d.prev_successful/pt*100) : null;
    const pfr = pt ? (d.prev_failed/pt*100) : null;
    document.getElementById('k-total-delta').innerHTML = deltaHTML(t, pt);
    document.getElementById('k-success-delta').innerHTML = sr && psr!=null ? deltaHTML(sr, psr) : '';
    document.getElementById('k-fail-delta').innerHTML = fr && pfr!=null ? deltaHTML(fr, pfr, {invert:true}) : '';
    document.getElementById('k-dur-delta').innerHTML = deltaHTML(d.avg_duration_ms, d.prev_avg_duration_ms, {invert:true});
}

async function loadTrends(f) {
    const data = await fetch(`${window.PM.base}/trends?${qs(f)}`).then(r=>r.json());
    const labels = data.map(d => { const p=d.period; return p.length===10?p.slice(5):p; });
    const success = data.map(d=>d.success);
    const fail = data.map(d=>d.failures);
    const durs = data.map(d=>Math.round((d.avg_duration_ms||0)/1000));

    if(volChart)volChart.destroy();
    volChart = new Chart(document.getElementById('chart-volume'),{
        type:'bar',data:{labels,datasets:[
            {label:'Success',data:success,backgroundColor:'rgba(63,185,80,0.65)',hoverBackgroundColor:'rgba(63,185,80,0.9)',borderRadius:5,borderSkipped:false},
            {label:'Failed',data:fail,backgroundColor:'rgba(248,81,73,0.6)',hoverBackgroundColor:'rgba(248,81,73,0.9)',borderRadius:5,borderSkipped:false},
        ]},options:{...chartOpts,scales:{...chartOpts.scales,x:{...chartOpts.scales.x,stacked:true},y:{...chartOpts.scales.y,stacked:true}}}
    });

    if(durChart)durChart.destroy();
    durChart = new Chart(document.getElementById('chart-duration'),{
        type:'line',data:{labels,datasets:[{
            label:'Avg (seconds)',data:durs,
            borderColor:'#58a6ff',backgroundColor:'rgba(88,166,255,0.06)',
            fill:true,tension:0.4,borderWidth:2.5,
            pointRadius:4,pointBackgroundColor:'#58a6ff',pointBorderColor:'#0d1117',pointBorderWidth:2,pointHoverRadius:6,
        }]},options:chartOpts
    });
}

async function loadHeatmap(f) {
    const data = await fetch(`${window.PM.base}/heatmap?${qs(f)}`).then(r=>r.json());
    const grid = document.getElementById('heatmap');
    const days = ['Sun','Mon','Tue','Wed','Thu','Fri','Sat'];
    const maxCount = Math.max(...data.map(d=>d.count), 1);

    let html = '<div class="heatmap-label"></div>';
    for(let h=0;h<24;h++) html += `<div class="heatmap-hour">${h%6===0?h:''}</div>`;

    for(let d=0;d<7;d++){
        html += `<div class="heatmap-label">${days[d]}</div>`;
        for(let h=0;h<24;h++){
            const cell = data.find(x=>x.day_of_week===d && x.hour===h);
            let level = '0';
            if(cell) {
                if(cell.failures > cell.count/2) level = 'fail';
                else {
                    const pct = cell.count/maxCount;
                    if(pct>0.75) level='4'; else if(pct>0.5) level='3'; else if(pct>0.25) level='2'; else level='1';
                }
            }
            const title = cell ? `${cell.count} builds, ${cell.failures} failed` : 'No builds';
            html += `<div class="heatmap-cell" data-level="${level}" title="${days[d]} ${h}:00 — ${title}"></div>`;
        }
    }
    grid.innerHTML = html;
}

async function loadUsers(f) {
    const data = await fetch(`${window.PM.base}/users?${qs({days:f.days,folder:f.folder})}`).then(r=>r.json());
    const el = document.getElementById('users-list');
    if(!data.length){ el.innerHTML='<div class="empty"><div class="empty-icon">👤</div><div class="empty-text">No user data</div></div>'; return; }
    el.innerHTML = data.slice(0,6).map(u=>`
        <div style="display:flex;align-items:center;justify-content:space-between;padding:10px 0;border-bottom:1px solid rgba(255,255,255,0.04)">
            <div style="display:flex;align-items:center;gap:10px">
                <div style="width:28px;height:28px;border-radius:50%;background:rgba(88,166,255,0.15);display:flex;align-items:center;justify-content:center;font-size:0.7rem;color:var(--blue)">${esc(u.user.slice(0,2).toUpperCase())}</div>
                <div>
                    <div style="font-size:0.82rem;font-weight:500;color:var(--text)">${esc(u.user)}</div>
                    <div style="font-size:0.7rem;color:var(--text-faint)">${u.total_builds} builds</div>
                </div>
            </div>
            <span class="badge ${u.failure_rate>30?'badge-red':u.failure_rate>10?'badge-yellow':'badge-green'}">${u.failure_rate.toFixed(0)}% fail</span>
        </div>
    `).join('');
}

async function loadPipelines(f) {
    const data = await fetch(`${window.PM.base}/pipelines?${qs(f)}`).then(r=>r.json());
    const tb = document.getElementById('tbl-pipelines');
    if(!data.length){tb.innerHTML='<tr><td colspan="7"><div class="empty"><div class="empty-icon">📊</div><div class="empty-text">No data yet</div></div></td></tr>';return;}
    tb.innerHTML = data.map(p=>`<tr>
        <td class="td-name">${esc(p.job_name)}</td>
        <td><span class="badge badge-dim">${esc(p.job_folder||'root')}</span></td>
        <td>${p.total_builds}</td>
        <td class="td-mono">${dur(p.avg_duration_ms)}</td>
        <td class="td-mono">${dur(p.max_duration_ms)}</td>
        <td class="td-mono">${dur(p.avg_queue_ms)}</td>
        <td>${rate(p.failure_rate)}</td>
    </tr>`).join('');
}

async function loadAgents(f) {
    const data = await fetch(`${window.PM.base}/agents?${qs({days:f.days,folder:f.folder,user:f.user})}`).then(r=>r.json());
    const tb = document.getElementById('tbl-agents');
    if(!data.length){tb.innerHTML='<tr><td colspan="6"><div class="empty"><div class="empty-icon">🖥️</div><div class="empty-text">No data</div></div></td></tr>';return;}
    tb.innerHTML = data.map(a=>`<tr>
        <td class="td-name">${esc(a.agent)}</td>
        <td><span class="badge badge-dim">${esc(a.node_labels||'—')}</span></td>
        <td>${a.total_builds}</td>
        <td class="td-mono">${dur(a.avg_duration_ms)}</td>
        <td>${rate(a.failure_rate)}</td>
        <td class="td-mono">${dur(a.total_busy_ms)}</td>
    </tr>`).join('');
}

async function loadStages(f) {
    const data = await fetch(`${window.PM.base}/stages?${qs({days:f.days,folder:f.folder,agent:f.agent,user:f.user})}`).then(r=>r.json());
    const tb = document.getElementById('tbl-stages');
    if(!data.length){tb.innerHTML='<tr><td colspan="5"><div class="empty"><div class="empty-icon">🔗</div><div class="empty-text">No data</div></div></td></tr>';return;}
    tb.innerHTML = data.map(s=>`<tr>
        <td class="td-name">${esc(s.stage_name)}</td>
        <td>${s.run_count}</td>
        <td class="td-mono">${dur(s.avg_duration_ms)}</td>
        <td class="td-mono">${dur(s.max_duration_ms)}</td>
        <td>${rate(s.failure_rate)}</td>
    </tr>`).join('');
}

function switchTab(el) {
    document.querySelectorAll('.tab').forEach(t=>t.classList.remove('active'));
    el.classList.add('active');
    document.querySelectorAll('[id^="tab-"]').forEach(t=>t.style.display='none');
    document.getElementById(`tab-${el.dataset.tab}`).style.display='block';
    document.getElementById('tbl-search').value = '';
}

function filterActiveTable() {
    const q = document.getElementById('tbl-search').value.trim().toLowerCase();
    const activeTab = document.querySelector('.tab.active').dataset.tab;
    const rows = document.querySelectorAll(`#tab-${activeTab} tbody tr`);
    rows.forEach(row => {
        const text = row.textContent.toLowerCase();
        row.style.display = !q || text.includes(q) ? '' : 'none';
    });
}

function exportCSV() {
    window.location = `${window.PM.base}/report.csv?${qs(F())}`;
}

let lastUpdated = null;
function tickLastUpdated() {
    const el = document.getElementById('last-updated');
    if (!lastUpdated) { el.textContent = ''; return; }
    const secs = Math.round((Date.now() - lastUpdated) / 1000);
    el.textContent = secs < 5 ? '· updated just now' : `· updated ${secs}s ago`;
}

async function triggerSync() {
    document.getElementById('sync-label').textContent = 'Syncing...';
    await fetch(window.PM.base + '/collect', {method:'POST', headers: await window.PM.postHeaders()});
    setTimeout(async()=>{ await refresh(); document.getElementById('sync-label').textContent='Live'; },3000);
}

async function triggerBackfill() {
    const btn = document.getElementById('btn-backfill');
    if (btn) { btn.disabled = true; btn.textContent = '⤓ Backfilling…'; }
    try {
        await fetch(window.PM.base + '/backfill', {method:'POST', headers: await window.PM.postHeaders()});
        const poll = setInterval(async () => {
            const st = await fetch(window.PM.base + '/backfillStatus').then(r=>r.json());
            if (btn) { btn.textContent = `⤓ ${st.builds_processed||0} builds`; }
            if (!st.running) {
                clearInterval(poll);
                if (btn) { btn.disabled = false; btn.textContent = '⤓ Backfill'; }
                await refresh();
            }
        }, 1500);
    } catch (e) {
        if (btn) { btn.disabled = false; btn.textContent = '⤓ Backfill'; }
    }
}

async function triggerImport() {
    const path = prompt('Absolute path to the sidecar metrics.db on the Jenkins controller:');
    if (!path) { return; }
    const body = new URLSearchParams({path});
    const headers = await window.PM.postHeaders({'Content-Type':'application/x-www-form-urlencoded'});
    const res = await fetch(window.PM.base + '/import', {method:'POST', headers, body}).then(r=>r.json()).catch(()=>null);
    if (res && res.status === 'ok') {
        alert(`Imported: ${res.inserted} new, ${res.updated} updated, ${res.skipped} skipped (of ${res.read} read).`);
        await refresh();
    } else {
        alert('Import failed: ' + (res && res.message ? res.message : 'unknown error'));
    }
}

async function triggerStorageMigration() {
    if (!confirm('Copy all history from the local SQLite store into the currently configured storage backend?')) { return; }
    const headers = await window.PM.postHeaders();
    const res = await fetch(window.PM.base + '/migrateStorage', {method:'POST', headers}).then(r=>r.json()).catch(()=>null);
    if (res && res.status === 'ok') {
        alert(`Migrated: ${res.inserted} new, ${res.updated} updated, ${res.skipped} skipped (of ${res.read} read).`);
        await refresh();
    } else {
        alert('Migration failed: ' + (res && res.message ? res.message : 'unknown error'));
    }
}

const _refresh = refresh;
refresh = async function() {
    await _refresh();
    lastUpdated = Date.now();
    tickLastUpdated();
};

loadFilters().then(refresh);
setInterval(refresh, 60000);
