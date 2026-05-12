/* Phase 3 of ADMIN_CONSOLE.md — Metrics dashboard.
 *
 * Five panels read live from GET /api/admin/metrics (which itself reads
 * directly from MeterRegistry — no persistence). The same dispatch pattern
 * RoutesView.js / DataView.js use: vanilla JS, closure-scoped state,
 * AdminApi.get for the XHR, window.Toast for errors.
 *
 * Refresh model: every REFRESH_MS the view re-fetches and re-renders. A
 * Pause toggle stops the timer (the next fetch will fire on Resume or on
 * manual Refresh). Switching to a different admin tab cancels the timer
 * via the module's render() being replaced by the next view's render() —
 * we also cancel explicitly on hashchange just in case.
 */
(function () {
    'use strict';

    var REFRESH_MS = 60_000;

    var state = {
        paused: false,
        snapshot: null,
        lastUpdated: null,
        timer: null,
        lastHash: '#metrics'
    };

    var rootEl = null;

    function render(root) {
        rootEl = root;
        rootEl.innerHTML = shellHtml();
        wireControls();
        // Cancel polling when the operator navigates away from this view.
        // hashchange fires before AdminApp.renderActiveView dispatches the
        // new renderer, so we get a clean handoff.
        window.addEventListener('hashchange', onHashChange);
        loadAndRender();
        startTimer();
    }

    function shellHtml() {
        return ''
            + '<h2>Metrics</h2>'
            + '<div class="metrics-toolbar">'
            +   '<button id="metrics-refresh" class="primary">Refresh now</button>'
            +   '<button id="metrics-pause">Pause auto-refresh</button>'
            +   '<span id="metrics-paused-flag" class="metrics-paused-flag" hidden>Paused</span>'
            +   '<span id="metrics-last-updated" class="metrics-last-updated"></span>'
            + '</div>'
            + '<div id="metrics-panels" class="metrics-panels"></div>'
            + '<div class="metrics-footer">'
            +   'View raw <a href="/actuator/prometheus" target="_blank" rel="noopener">/actuator/prometheus</a> '
            +   '(everything Micrometer reports, not just these panels). '
            +   'Auto-refresh every ' + Math.round(REFRESH_MS / 1000) + ' s.'
            + '</div>';
    }

    function wireControls() {
        document.getElementById('metrics-refresh').addEventListener('click', function () {
            loadAndRender();
        });
        var pauseBtn = document.getElementById('metrics-pause');
        pauseBtn.addEventListener('click', function () {
            state.paused = !state.paused;
            updatePauseUi();
            if (state.paused) {
                stopTimer();
            } else {
                // Fire an immediate fetch on resume so the operator sees
                // fresh numbers without waiting up to a full refresh tick.
                loadAndRender();
                startTimer();
            }
        });
        updatePauseUi();
    }

    function updatePauseUi() {
        var btn = document.getElementById('metrics-pause');
        var flag = document.getElementById('metrics-paused-flag');
        btn.textContent = state.paused ? 'Resume auto-refresh' : 'Pause auto-refresh';
        flag.hidden = !state.paused;
    }

    function onHashChange() {
        if (window.location.hash !== state.lastHash) {
            stopTimer();
            window.removeEventListener('hashchange', onHashChange);
        }
    }

    function startTimer() {
        stopTimer();
        if (state.paused) return;
        state.timer = setInterval(loadAndRender, REFRESH_MS);
    }

    function stopTimer() {
        if (state.timer != null) {
            clearInterval(state.timer);
            state.timer = null;
        }
    }

    function loadAndRender() {
        AdminApi.get('/api/admin/metrics').then(function (snap) {
            state.snapshot = snap;
            state.lastUpdated = new Date();
            renderPanels();
            renderLastUpdated();
        }).catch(function (err) {
            if (window.Toast) {
                window.Toast.show('Metrics load failed: ' + (err && err.message), 'error');
            }
        });
    }

    function renderLastUpdated() {
        var el = document.getElementById('metrics-last-updated');
        if (!el) return;
        if (!state.lastUpdated) {
            el.textContent = '';
            return;
        }
        var d = state.lastUpdated;
        var pad = function (n) { return n < 10 ? '0' + n : String(n); };
        el.textContent = 'Last updated ' + pad(d.getHours()) + ':' + pad(d.getMinutes())
            + ':' + pad(d.getSeconds());
    }

    function renderPanels() {
        var snap = state.snapshot;
        var wrap = document.getElementById('metrics-panels');
        if (!wrap || !snap) return;
        wrap.innerHTML = ''
            + panelHttpHtml(snap.http)
            + panelRoutingHtml(snap.routing)
            + panelHeapHtml(snap.jvmHeap)
            + panelTopUrisHtml(snap.topUris)
            + panelCachesHtml(snap.caches);
    }

    // ---------------------------------------------------------- Panel: HTTP

    function panelHttpHtml(http) {
        if (!http) return panelWrapper('HTTP latency', '<div class="metrics-empty">No data.</div>');
        if (http.count === 0) {
            return panelWrapper('HTTP latency',
                '<div class="metrics-empty">No HTTP requests recorded yet.</div>');
        }
        // Request count moves to the title; rows below carry only latency
        // statistics. Row 1: Mean, Max (the Min slot is intentionally empty
        // — Micrometer's Timer.takeSnapshot() exposes count/mean/max/
        // percentiles but not min, so MetricsSnapshotDto has no minMs).
        // Row 2: percentiles. The empty <div></div> consumes col 3 row 1
        // so P50 starts at col 1 row 2, keeping Mean above P50 and Max
        // above P95.
        var requestsLabel = formatInt(http.count)
                + (http.count === 1 ? ' request' : ' requests');
        var stats = ''
            + statCell('Mean', formatMs(http.meanMs))
            + statCell('Max', formatMs(http.maxMs))
            + '<div></div>'
            + statCell('p50', formatMs(http.p50Ms))
            + statCell('p95', formatMs(http.p95Ms))
            + statCell('p99', formatMs(http.p99Ms));
        return panelWrapper('HTTP latency (' + requestsLabel + ')',
            '<div class="metrics-stat-grid metrics-stat-grid-three-cols">' + stats + '</div>');
    }

    // ----------------------------------------------- Panel: Routing dispatch

    function panelRoutingHtml(routing) {
        if (!routing || !routing.endpoints || routing.endpoints.length === 0) {
            return panelWrapper('Routing dispatch',
                '<div class="metrics-empty">No routing calls recorded yet.</div>');
        }
        var rows = routing.endpoints.map(function (row) {
            return ''
                + '<tr>'
                +   '<td>' + esc(row.endpoint) + '</td>'
                +   '<td class="num">' + formatInt(row.localSuccess) + '</td>'
                +   '<td class="num">' + formatInt(row.publicCalls) + '</td>'
                +   '<td class="num">' + formatInt(row.fallbackTotal) + '</td>'
                +   '<td>' + esc(formatFallbackReasons(row.fallbackByReason)) + '</td>'
                + '</tr>';
        }).join('');
        var table = ''
            + '<table class="metrics-table">'
            +   '<thead><tr>'
            +     '<th>Endpoint</th>'
            +     '<th class="num">Local</th>'
            +     '<th class="num">Public</th>'
            +     '<th class="num">Fallback</th>'
            +     '<th>Fallback reasons</th>'
            +   '</tr></thead>'
            +   '<tbody>' + rows + '</tbody>'
            + '</table>';
        return panelWrapper('Routing dispatch', table);
    }

    function formatFallbackReasons(reasons) {
        if (!reasons) return '—';
        var keys = Object.keys(reasons).filter(function (k) { return reasons[k] > 0; });
        if (keys.length === 0) return '—';
        // Sort by descending count so the loudest reason wins the row.
        keys.sort(function (a, b) { return reasons[b] - reasons[a]; });
        return keys.map(function (k) { return k + '=' + reasons[k]; }).join(', ');
    }

    // ------------------------------------------------------- Panel: JVM heap

    function panelHeapHtml(heap) {
        if (!heap || heap.usedBytes === 0) {
            return panelWrapper('JVM heap',
                '<div class="metrics-empty">No heap usage reported.</div>');
        }
        var stats = ''
            + statCell('Used', formatMb(heap.usedBytes))
            + statCell('Max',
                heap.maxBytes === 0 ? '<span class="metrics-stat-value-unit">—</span>'
                                    : formatMb(heap.maxBytes))
            + statCell('Used %',
                heap.usedPct == null
                    ? '<span class="metrics-stat-value-unit">—</span>'
                    : heap.usedPct.toFixed(1) + '<span class="metrics-stat-value-unit">%</span>');
        return panelWrapper('JVM heap',
            '<div class="metrics-stat-grid">' + stats + '</div>');
    }

    // ----------------------------------------------------- Panel: Top URIs

    function panelTopUrisHtml(rows) {
        if (!rows || rows.length === 0) {
            return panelWrapper('Top URIs (by count)',
                '<div class="metrics-empty">No HTTP requests recorded yet.</div>');
        }
        var body = rows.map(function (r) {
            return ''
                + '<tr>'
                +   '<td>' + esc(r.uri || '—') + '</td>'
                +   '<td>' + esc(r.method) + '</td>'
                +   '<td>' + esc(r.status) + '</td>'
                +   '<td class="num">' + formatInt(r.count) + '</td>'
                +   '<td class="num">' + formatMs(r.meanMs) + '</td>'
                +   '<td class="num">' + formatMs(r.maxMs) + '</td>'
                + '</tr>';
        }).join('');
        var table = ''
            + '<table class="metrics-table">'
            +   '<thead><tr>'
            +     '<th>URI</th>'
            +     '<th>Method</th>'
            +     '<th>Status</th>'
            +     '<th class="num">Count</th>'
            +     '<th class="num">Mean</th>'
            +     '<th class="num">Max</th>'
            +   '</tr></thead>'
            +   '<tbody>' + body + '</tbody>'
            + '</table>';
        return panelWrapper('Top URIs (by count)', table);
    }

    // --------------------------------------- Panel: Cache hit ratios

    function panelCachesHtml(caches) {
        if (!caches || caches.length === 0) {
            return panelWrapper('Cache hit ratios',
                '<div class="metrics-empty">No cache meters registered.</div>');
        }
        var body = caches.map(function (c) {
            var ratio = c.hitRatio == null
                ? '<span class="metrics-stat-value-unit">—</span>'
                : c.hitRatio.toFixed(1) + '%';
            return ''
                + '<tr>'
                +   '<td>' + esc(c.name)
                +     '<span class="metrics-cache-kind">' + esc(c.kind || '') + '</span></td>'
                +   '<td class="num">' + formatInt(c.hits) + '</td>'
                +   '<td class="num">' + formatInt(c.misses) + '</td>'
                +   '<td class="num">' + ratio + '</td>'
                + '</tr>';
        }).join('');
        var table = ''
            + '<table class="metrics-table">'
            +   '<thead><tr>'
            +     '<th>Cache</th>'
            +     '<th class="num">Hits</th>'
            +     '<th class="num">Misses</th>'
            +     '<th class="num">Ratio</th>'
            +   '</tr></thead>'
            +   '<tbody>' + body + '</tbody>'
            + '</table>';
        return panelWrapper('Cache hit ratios', table);
    }

    // ------------------------------------------------- Rendering primitives

    function panelWrapper(title, bodyHtml) {
        return ''
            + '<div class="metrics-panel">'
            +   '<div class="metrics-panel-header"><h3>' + esc(title) + '</h3></div>'
            +   '<div class="metrics-panel-body">' + bodyHtml + '</div>'
            + '</div>';
    }

    function statCell(label, valueHtml) {
        return ''
            + '<div>'
            +   '<div class="metrics-stat-label">' + esc(label) + '</div>'
            +   '<div class="metrics-stat-value">' + valueHtml + '</div>'
            + '</div>';
    }

    // ------------------------------------------------------------ Formatters

    function formatInt(n) {
        if (n == null) return '0';
        // Locale-aware thousands separator; falls back to default.
        try { return Number(n).toLocaleString(); } catch (e) { return String(n); }
    }

    function formatMs(ms) {
        if (ms == null || isNaN(ms)) {
            return '<span class="metrics-stat-value-unit">—</span>';
        }
        if (ms >= 1000) {
            return (ms / 1000).toFixed(2) + '<span class="metrics-stat-value-unit"> s</span>';
        }
        return ms.toFixed(1) + '<span class="metrics-stat-value-unit"> ms</span>';
    }

    function formatMb(bytes) {
        if (bytes == null || bytes === 0) {
            return '<span class="metrics-stat-value-unit">—</span>';
        }
        var mb = bytes / (1024 * 1024);
        if (mb >= 1024) {
            return (mb / 1024).toFixed(2) + '<span class="metrics-stat-value-unit"> GB</span>';
        }
        return mb.toFixed(0) + '<span class="metrics-stat-value-unit"> MB</span>';
    }

    function esc(s) {
        if (s == null) return '';
        return String(s)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    window.AdminViews = window.AdminViews || {};
    window.AdminViews.metrics = render;
})();
