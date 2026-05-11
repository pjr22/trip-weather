/* Data tab — Loaders + Pbfs.
 *
 * Phase 2 of ADMIN_CONSOLE.md introduced this view with three loader-shaped
 * cards (cleanup, data, ORS coverage). Phase 2b reshaped it: the ORS
 * coverage card is replaced by a Pbfs card driven by GET /api/admin/pbfs
 * (the new pbf_files table). Polygon refresh moved entirely backend-side
 * (post-apply step in the host cron) and no longer has a UI affordance.
 *
 * Loader cards (cleanup, data) keep polling every 5 s while at least one
 * loader is RUNNING. The Pbfs card has no equivalent "in flight" state in
 * the same sense — the cron writes to last_apply_started_at when an apply
 * is mid-flight, and we render a banner + "Retry stuck apply" button when
 * that's been stuck for >4 h, but we don't continuously poll; admin clicks
 * "Check upstream now" or refreshes to see updates.
 */
(function () {
    'use strict';

    var POLL_INTERVAL_MS = 5000;
    var HISTORY_LIMIT = 20;

    var rootEl = null;
    var pollTimer = null;
    var loaders = [];
    var pbfs = [];
    var openHistory = {};         // loaderName -> bool, persists across re-renders within view
    var historyByLoader = {};     // loaderName -> [LoaderRunDto]
    var pbfEditing = null;        // pbf_name currently in inline-edit mode (or 'new' for the add form)

    function render(root) {
        rootEl = root;
        rootEl.innerHTML = templateShell();
        loadAndRender();
    }

    function templateShell() {
        return ''
            + '<h2>Data / Loaders</h2>'
            + '<div id="data-message" class="routes-message"></div>'
            + '<div id="data-cards" class="data-cards"></div>';
    }

    function loadAndRender() {
        // Fetch loaders and pbfs in parallel — they feed different cards and
        // either can render before the other returns. Errors on one don't
        // block rendering of the other.
        AdminApi.get('/api/admin/loaders').then(function (list) {
            loaders = Array.isArray(list) ? list : [];
            renderCards();
            schedulePollIfRunning();
        }).catch(function (err) {
            showMessage('Failed to load loaders: ' + (err && err.message), 'err');
        });
        AdminApi.get('/api/admin/pbfs').then(function (list) {
            pbfs = Array.isArray(list) ? list : [];
            renderCards();
        }).catch(function (err) {
            showMessage('Failed to load pbfs: ' + (err && err.message), 'err');
        });
    }

    function renderCards() {
        var cardsEl = document.getElementById('data-cards');
        if (!cardsEl) return;

        var byCategory = { cleanup: [], data: [], coverage: [] };
        for (var i = 0; i < loaders.length; i++) {
            var l = loaders[i];
            var cat = l.category || 'cleanup';
            (byCategory[cat] || (byCategory[cat] = [])).push(l);
        }

        var html = ''
            + cleanupCardHtml(byCategory.cleanup)
            + dataCardHtml(byCategory.data)
            + pbfsCardHtml(pbfs);
        cardsEl.innerHTML = html;

        wireTriggerButtons();
        wireHistoryToggles();
        wirePbfButtons();
    }

    function cleanupCardHtml(list) {
        var rowsHtml = (list || []).map(loaderRowHtml).join('');
        return ''
            + '<section class="data-card">'
            +   '<header class="data-card-header">'
            +     '<h3>Cleanup</h3>'
            +     '<span class="data-card-subtitle">Soft-delete + email-token sweeps. Cron: daily 03:00.</span>'
            +   '</header>'
            +   '<div class="data-card-body">'
            +     (rowsHtml || '<div class="data-empty">No cleanup loaders registered.</div>')
            +   '</div>'
            + '</section>';
    }

    function dataCardHtml(list) {
        var rowsHtml = (list || []).map(loaderRowHtml).join('');
        return ''
            + '<section class="data-card">'
            +   '<header class="data-card-header">'
            +     '<h3>Data</h3>'
            +     '<span class="data-card-subtitle">NREL EV station mirror. Cron: weekly Mon 04:00.</span>'
            +   '</header>'
            +   '<div class="data-card-body">'
            +     (rowsHtml || '<div class="data-empty">No data loaders registered.</div>')
            +   '</div>'
            + '</section>';
    }

    function pbfsCardHtml(list) {
        list = list || [];
        var rowsHtml = list.map(pbfRowHtml).join('');
        var subtitle = 'OSM extracts managed by docker/refreshOrsGraph.sh.';
        var addingNew = pbfEditing === 'new';
        var addBtnOrForm = addingNew
            ? ''   // form rendered inline below
            : '<span class="data-card-actions"><button id="data-pbf-add" class="primary">Add pbf</button></span>';
        var addForm = addingNew
            ? '<div class="data-pbf-row data-pbf-editing">' + pbfFormHtml(null) + '</div>'
            : '';
        var body = (rowsHtml || '<div class="data-empty">No pbf rows configured. Add one to start the cron-driven flow.</div>')
                 + addForm;
        return ''
            + '<section class="data-card">'
            +   '<header class="data-card-header">'
            +     '<h3>Pbf files</h3>'
            +     '<span class="data-card-subtitle">' + esc(subtitle) + '</span>'
            +     addBtnOrForm
            +   '</header>'
            +   '<div class="data-card-body">'
            +     body
            +   '</div>'
            + '</section>';
    }

    /** Render one pbf row — either in display mode (state + actions) or
     *  edit mode (inline form). Edit mode replaces only the row's body so
     *  the surrounding card layout is stable. */
    function pbfRowHtml(pbf) {
        if (pbfEditing === pbf.pbfName) {
            return '<div class="data-pbf-row data-pbf-editing" data-pbf-name="'
                 + escAttr(pbf.pbfName) + '">' + pbfFormHtml(pbf) + '</div>';
        }
        var stateClass = 'data-pbf-state';
        var stateText;
        if (pbf.applyStuck) {
            stateClass += ' data-pbf-state-stuck';
            stateText = 'apply STUCK (in flight > 4 h)';
        } else if (pbf.applyInFlight) {
            stateClass += ' data-pbf-state-inflight';
            stateText = 'apply in flight (' + formatTimestamp(pbf.lastApplyStartedAt) + ')';
        } else if (pbf.stale) {
            stateClass += ' data-pbf-state-stale';
            stateText = 'STALE — newer pbf available upstream';
        } else if (pbf.lastApplyMd5) {
            stateClass += ' data-pbf-state-uptodate';
            stateText = 'up to date';
        } else {
            stateClass += ' data-pbf-state-unknown';
            stateText = 'never applied';
        }
        if (!pbf.active) {
            stateClass += ' data-pbf-state-inactive';
            stateText = 'INACTIVE · ' + stateText;
        }
        var deployedLine = pbf.lastApplyMd5
            ? 'Deployed md5 ' + shortMd5(pbf.lastApplyMd5)
              + (pbf.lastApplyFinishedAt ? ' on ' + formatTimestamp(pbf.lastApplyFinishedAt) : '')
            : 'Deployed: never applied';
        var upstreamLine = pbf.lastRemoteMd5
            ? 'Upstream md5 ' + shortMd5(pbf.lastRemoteMd5)
              + (pbf.lastRemoteModified ? ' modified ' + formatTimestamp(pbf.lastRemoteModified) : '')
              + (pbf.lastCheckAt ? ' (checked ' + formatTimestamp(pbf.lastCheckAt) + ')' : '')
            : 'Upstream: never checked';
        var scheduleLine = ''
            + 'next check: ' + (pbf.nextCheckAt ? formatTimestamp(pbf.nextCheckAt) : 'ASAP')
            + ' · next apply: ' + (pbf.nextUpdateAt ? formatTimestamp(pbf.nextUpdateAt) : 'paused')
            + (pbf.updateIntervalDays != null
                ? ' · auto-rebuild every ' + pbf.updateIntervalDays + ' days'
                : ' · no auto-rebuild');
        var errorLine = pbf.lastApplyError
            ? '<div class="data-error">' + esc(pbf.lastApplyStatus || 'FAIL') + ': '
              + esc(pbf.lastApplyError) + '</div>'
            : '';

        var retryBtn = pbf.applyStuck
            ? '<button data-pbf-retry="' + escAttr(pbf.pbfName) + '" class="danger">Retry stuck apply</button>'
            : '';
        var scheduleNowBtn = '<button data-pbf-schedule="' + escAttr(pbf.pbfName) + '" class="primary">Schedule now</button>';
        var checkBtn = '<button data-pbf-check="' + escAttr(pbf.pbfName) + '">Check upstream</button>';
        var editBtn = '<button data-pbf-edit="' + escAttr(pbf.pbfName) + '">Edit</button>';
        var deleteBtn = '<button data-pbf-delete="' + escAttr(pbf.pbfName) + '">Delete</button>';

        return ''
            + '<div class="data-pbf-row" data-pbf-name="' + escAttr(pbf.pbfName) + '">'
            +   '<div class="data-pbf-main">'
            +     '<div class="data-pbf-name">' + esc(pbf.pbfName) + '</div>'
            +     '<div class="' + stateClass + '">' + esc(stateText) + '</div>'
            +     '<div class="data-pbf-meta">' + esc(deployedLine) + '</div>'
            +     '<div class="data-pbf-meta">' + esc(upstreamLine) + '</div>'
            +     '<div class="data-pbf-meta">' + esc(scheduleLine) + '</div>'
            +     '<div class="data-pbf-meta data-pbf-url">' + esc(pbf.geofabrikUrl) + '</div>'
            +   '</div>'
            +   '<div class="data-pbf-actions">'
            +     retryBtn + scheduleNowBtn + checkBtn + editBtn + deleteBtn
            +   '</div>'
            +   errorLine
            + '</div>';
    }

    /** Add/edit form. pbf is null when adding; pbfName is read-only in edit mode. */
    function pbfFormHtml(pbf) {
        var isEdit = pbf != null;
        var name = pbf ? pbf.pbfName : '';
        var url = pbf ? pbf.geofabrikUrl : '';
        var active = pbf ? pbf.active : true;
        var checkInterval = pbf ? pbf.checkIntervalDays : 7;
        var updateInterval = pbf && pbf.updateIntervalDays != null ? pbf.updateIntervalDays : '';
        var nextUpdate = pbf && pbf.nextUpdateAt ? pbf.nextUpdateAt : '';
        // <input type="datetime-local"> wants 'YYYY-MM-DDTHH:MM' without seconds/TZ.
        var nextUpdateInput = nextUpdate ? toLocalInputValue(nextUpdate) : '';

        return ''
            + '<form class="data-pbf-form" data-form-mode="' + (isEdit ? 'edit' : 'create') + '"'
            +     (isEdit ? ' data-pbf-name="' + escAttr(name) + '"' : '') + '>'
            +   '<label>Name'
            +     '<input name="pbfName" required maxlength="64" '
            +       'placeholder="us-west" value="' + escAttr(name) + '"'
            +       (isEdit ? ' readonly' : '') + '>'
            +   '</label>'
            +   '<label>Geofabrik URL'
            +     '<input name="geofabrikUrl" required type="url" '
            +       'placeholder="https://download.geofabrik.de/north-america/us/colorado-latest.osm.pbf" '
            +       'value="' + escAttr(url) + '">'
            +   '</label>'
            +   '<label class="data-pbf-form-checkbox">'
            +     '<input name="active" type="checkbox"' + (active ? ' checked' : '') + '>'
            +     ' Active (cron processes this row)'
            +   '</label>'
            +   '<label>Check interval (days)'
            +     '<input name="checkIntervalDays" type="number" min="1" value="' + escAttr(String(checkInterval)) + '">'
            +   '</label>'
            +   '<label>Update interval (days, blank = manual only)'
            +     '<input name="updateIntervalDays" type="number" min="1" value="' + escAttr(String(updateInterval)) + '">'
            +   '</label>'
            +   '<label>Next update at (local time, blank = paused)'
            +     '<input name="nextUpdateAt" type="datetime-local" value="' + escAttr(nextUpdateInput) + '">'
            +   '</label>'
            +   '<div class="data-pbf-form-actions">'
            +     '<button type="submit" class="primary">' + (isEdit ? 'Save' : 'Create') + '</button>'
            +     '<button type="button" data-pbf-cancel>Cancel</button>'
            +   '</div>'
            + '</form>';
    }

    function shortMd5(md5) {
        if (!md5) return '';
        return md5.substring(0, 8) + '…';
    }

    /** Convert ISO timestamp string to YYYY-MM-DDTHH:MM in local time. */
    function toLocalInputValue(iso) {
        var d = new Date(iso);
        if (isNaN(d.getTime())) return '';
        var pad = function (n) { return n < 10 ? '0' + n : String(n); };
        return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate())
             + 'T' + pad(d.getHours()) + ':' + pad(d.getMinutes());
    }

    function loaderRowHtml(loader) {
        var lastRun = loader.lastRun;
        var status = lastRun ? lastRun.status : 'NEVER';
        var statusClass = 'data-status data-status-' + status.toLowerCase();
        var statusText = lastRun
            ? status + ' · ' + (lastRun.triggerType || '?').toLowerCase()
            : 'Never run';
        var timing = '';
        if (lastRun) {
            timing = formatTimestamp(lastRun.startedAt);
            if (lastRun.finishedAt && status !== 'RUNNING') {
                var ms = (new Date(lastRun.finishedAt) - new Date(lastRun.startedAt));
                timing += ' · ' + formatDuration(ms);
            } else if (status === 'RUNNING') {
                timing += ' · in progress';
            }
            if (lastRun.rowsAffected != null && status === 'SUCCESS') {
                timing += ' · ' + lastRun.rowsAffected + ' row' +
                          (lastRun.rowsAffected === 1 ? '' : 's');
            }
        }
        var errorRow = (lastRun && lastRun.errorMessage)
            ? '<div class="data-error">' + esc(lastRun.errorMessage) + '</div>'
            : '';
        var disabled = (status === 'RUNNING') ? ' disabled' : '';
        var historyOpen = !!openHistory[loader.name];
        var historyToggleLabel = historyOpen ? 'Hide history' : 'Show history';
        var historyHtml = historyOpen ? historyTableHtml(loader.name) : '';

        return ''
            + '<div class="data-loader-row" data-loader-name="' + escAttr(loader.name) + '">'
            +   '<div class="data-loader-main">'
            +     '<div class="data-loader-name">' + esc(loader.name) + '</div>'
            +     '<div class="' + statusClass + '">' + esc(statusText) + '</div>'
            +     '<div class="data-loader-meta">' + esc(timing) + '</div>'
            +   '</div>'
            +   '<div class="data-loader-actions">'
            +     '<button data-trigger="' + escAttr(loader.name) + '"' + disabled + '>Trigger</button>'
            +     '<button data-history-toggle="' + escAttr(loader.name) + '">' + historyToggleLabel + '</button>'
            +   '</div>'
            +   errorRow
            +   '<div class="data-history" data-loader-history="' + escAttr(loader.name) + '">'
            +     historyHtml
            +   '</div>'
            + '</div>';
    }

    function historyTableHtml(loaderName) {
        var rows = historyByLoader[loaderName];
        if (rows == null) {
            return '<div class="data-history-loading">Loading…</div>';
        }
        if (rows.length === 0) {
            return '<div class="data-history-empty">No runs recorded.</div>';
        }
        var rowHtml = rows.map(function (r) {
            var ms = (r.finishedAt && r.startedAt)
                ? new Date(r.finishedAt) - new Date(r.startedAt) : null;
            return ''
                + '<tr>'
                +   '<td>' + esc(formatTimestamp(r.startedAt)) + '</td>'
                +   '<td>' + esc(r.triggerType || '') + '</td>'
                +   '<td class="data-status data-status-' + (r.status || '').toLowerCase() + '">' + esc(r.status || '') + '</td>'
                +   '<td>' + (ms != null ? formatDuration(ms) : '—') + '</td>'
                +   '<td>' + (r.rowsAffected != null ? r.rowsAffected : '—') + '</td>'
                +   '<td class="data-history-error">' + esc(r.errorMessage || '') + '</td>'
                + '</tr>';
        }).join('');
        return ''
            + '<table class="admin-table data-history-table">'
            +   '<thead><tr>'
            +     '<th>Started</th><th>Trigger</th><th>Status</th>'
            +     '<th>Duration</th><th>Rows</th><th>Error</th>'
            +   '</tr></thead>'
            +   '<tbody>' + rowHtml + '</tbody>'
            + '</table>';
    }

    function wireTriggerButtons() {
        var btns = rootEl.querySelectorAll('button[data-trigger]');
        Array.prototype.forEach.call(btns, function (btn) {
            btn.addEventListener('click', function () {
                onTrigger(btn.getAttribute('data-trigger'));
            });
        });
    }

    function wireHistoryToggles() {
        var btns = rootEl.querySelectorAll('button[data-history-toggle]');
        Array.prototype.forEach.call(btns, function (btn) {
            btn.addEventListener('click', function () {
                onHistoryToggle(btn.getAttribute('data-history-toggle'));
            });
        });
    }

    function wirePbfButtons() {
        // Top-of-card "Add pbf" button.
        var addBtn = document.getElementById('data-pbf-add');
        if (addBtn) addBtn.addEventListener('click', function () {
            pbfEditing = 'new';
            renderCards();
        });

        // Per-row action buttons.
        forEachAttr('data-pbf-schedule', onPbfScheduleNow);
        forEachAttr('data-pbf-check',    onPbfCheckUpstream);
        forEachAttr('data-pbf-retry',    onPbfRetryStuckApply);
        forEachAttr('data-pbf-edit',     function (name) {
            pbfEditing = name;
            renderCards();
        });
        forEachAttr('data-pbf-delete',   onPbfDelete);

        // Inline add/edit form: submit + cancel.
        var forms = rootEl.querySelectorAll('form.data-pbf-form');
        Array.prototype.forEach.call(forms, function (form) {
            form.addEventListener('submit', function (event) {
                event.preventDefault();
                onPbfFormSubmit(form);
            });
            var cancel = form.querySelector('[data-pbf-cancel]');
            if (cancel) cancel.addEventListener('click', function () {
                pbfEditing = null;
                renderCards();
            });
        });
    }

    /** Bind a click listener to every element with the given data-attribute,
     *  passing the attribute value to the handler. Trims the noisy
     *  forEach/querySelectorAll dance out of each call site. */
    function forEachAttr(attr, handler) {
        var els = rootEl.querySelectorAll('[' + attr + ']');
        Array.prototype.forEach.call(els, function (el) {
            el.addEventListener('click', function () {
                handler(el.getAttribute(attr));
            });
        });
    }

    function onTrigger(loaderName) {
        AdminUI.confirm({
            title: 'Trigger loader',
            message: 'Run "' + loaderName + '" now?',
            confirmLabel: 'Trigger'
        }).then(function (ok) {
            if (!ok) return;
            AdminApi.post('/api/admin/loaders/' + encodeURIComponent(loaderName) + '/trigger')
                .then(function () {
                    showMessage('Triggered ' + loaderName + '.', 'ok');
                    scheduleAfterTriggerPolls();
                })
                .catch(function (err) {
                    if (err && err.status === 409) {
                        showMessage('Already running — wait for the current ' + loaderName + ' run to finish.', 'err');
                    } else {
                        showMessage('Trigger failed: ' + (err && err.message), 'err');
                    }
                });
        });
    }

    /**
     * After a manual trigger, the loader runs asynchronously on a background
     * thread; the trigger endpoint returns 202 before the recorder has
     * created the RUNNING row. For fast loaders (e.g. cleanup, which is two
     * SQL statements) the entire run can complete in under a second — fast
     * enough that an immediate single reload sees the OLD last-run state and
     * misses the lifecycle entirely. Without follow-up polls, the regular
     * "every 5 s while at least one loader is RUNNING" loop never engages.
     *
     * Fire several rapid follow-up reloads to bridge the gap. Each loadAndRender
     * also re-arms schedulePollIfRunning, so once we observe a RUNNING row the
     * standard polling cadence takes over without doubling-up.
     */
    function scheduleAfterTriggerPolls() {
        loadAndRender();                           // immediate
        setTimeout(loadAndRender, 600);            // catches sub-second loaders
        setTimeout(loadAndRender, 1500);           // catches slightly slower ones
        setTimeout(loadAndRender, 3000);           // last-chance backstop
    }

    function onPbfScheduleNow(pbfName) {
        AdminUI.confirm({
            title: 'Schedule pbf rebuild',
            message:
                'Schedule "' + pbfName + '" for the next cron tick?\n\n' +
                'On the next minute, the cron will fetch the upstream .md5; ' +
                'if it matches what\'s deployed, the rebuild is skipped ' +
                '(NO_CHANGE). Otherwise the full download + graph rebuild + ' +
                'container restart runs on the host (~30 min for us-west).',
            confirmLabel: 'Schedule now'
        }).then(function (ok) {
            if (!ok) return;
            AdminApi.post('/api/admin/pbfs/' + encodeURIComponent(pbfName) + '/schedule-now')
                .then(function () {
                    showMessage('Scheduled "' + pbfName + '" for next cron tick.', 'ok');
                    loadAndRender();
                })
                .catch(function (err) {
                    showMessage('Schedule-now failed: ' + (err && err.message), 'err');
                });
        });
    }

    function onPbfCheckUpstream(pbfName) {
        // No confirm — this is a cheap, non-destructive observational call.
        AdminApi.post('/api/admin/pbfs/' + encodeURIComponent(pbfName) + '/check-md5')
            .then(function () {
                showMessage('Upstream check complete for "' + pbfName + '".', 'ok');
                loadAndRender();
            })
            .catch(function (err) {
                if (err && err.status === 502) {
                    showMessage('Upstream fetch failed (Geofabrik unreachable or returned bad data): '
                            + (err && err.message), 'err');
                } else {
                    showMessage('Check failed: ' + (err && err.message), 'err');
                }
            });
    }

    function onPbfRetryStuckApply(pbfName) {
        AdminUI.confirm({
            title: 'Retry stuck apply',
            message:
                'Clear the "apply in flight" marker on "' + pbfName + '" and ' +
                'schedule a new apply for the next cron tick?\n\n' +
                'Only confirm if you\'re sure the previous apply is no longer ' +
                'actually running on the host (e.g. cron crashed, host rebooted, ' +
                'or you killed the script). The 4 h stale-detection window will ' +
                'eventually do this automatically; this lets you skip the wait.',
            confirmLabel: 'Clear and retry',
            danger: true
        }).then(function (ok) {
            if (!ok) return;
            AdminApi.post('/api/admin/pbfs/' + encodeURIComponent(pbfName) + '/retry-apply')
                .then(function () {
                    showMessage('Cleared stuck marker on "' + pbfName + '"; rescheduled.', 'ok');
                    loadAndRender();
                })
                .catch(function (err) {
                    showMessage('Retry-apply failed: ' + (err && err.message), 'err');
                });
        });
    }

    function onPbfDelete(pbfName) {
        AdminUI.confirm({
            title: 'Delete pbf row',
            message:
                'Remove "' + pbfName + '" from the pbf_files table?\n\n' +
                'Coverage polygons linked to this pbf will be unlinked but kept ' +
                '(their pbf_name column becomes NULL). The actual .osm.pbf file ' +
                'and the running trip-ors container are not touched — you handle ' +
                'those on the host if you want to clean up.',
            confirmLabel: 'Delete',
            danger: true
        }).then(function (ok) {
            if (!ok) return;
            AdminApi.del('/api/admin/pbfs/' + encodeURIComponent(pbfName))
                .then(function () {
                    showMessage('Deleted "' + pbfName + '".', 'ok');
                    loadAndRender();
                })
                .catch(function (err) {
                    showMessage('Delete failed: ' + (err && err.message), 'err');
                });
        });
    }

    function onPbfFormSubmit(form) {
        var mode = form.getAttribute('data-form-mode');   // 'edit' or 'create'
        var nameAttr = form.getAttribute('data-pbf-name');
        var body = collectPbfFormBody(form);

        var promise;
        if (mode === 'create') {
            promise = AdminApi.post('/api/admin/pbfs', body);
        } else {
            promise = AdminApi.patch
                ? AdminApi.patch('/api/admin/pbfs/' + encodeURIComponent(nameAttr), body)
                : adminPatch('/api/admin/pbfs/' + encodeURIComponent(nameAttr), body);
        }
        promise.then(function () {
            pbfEditing = null;
            showMessage((mode === 'create' ? 'Created' : 'Saved') + ' pbf "'
                    + (mode === 'create' ? body.pbfName : nameAttr) + '".', 'ok');
            loadAndRender();
        }).catch(function (err) {
            if (err && err.status === 409) {
                showMessage('A pbf with that name already exists.', 'err');
            } else {
                showMessage((mode === 'create' ? 'Create' : 'Save')
                        + ' failed: ' + (err && err.message), 'err');
            }
        });
    }

    function collectPbfFormBody(form) {
        var fd = new FormData(form);
        var body = {};
        var pbfName = fd.get('pbfName');
        if (pbfName) body.pbfName = String(pbfName).trim();
        var url = fd.get('geofabrikUrl');
        if (url) body.geofabrikUrl = String(url).trim();
        body.active = fd.get('active') === 'on';
        var checkInterval = fd.get('checkIntervalDays');
        if (checkInterval) body.checkIntervalDays = parseInt(checkInterval, 10);
        var updateInterval = fd.get('updateIntervalDays');
        if (updateInterval && String(updateInterval).trim() !== '') {
            body.updateIntervalDays = parseInt(updateInterval, 10);
        }
        var nextUpdateAt = fd.get('nextUpdateAt');
        if (nextUpdateAt && String(nextUpdateAt).trim() !== '') {
            // <input type="datetime-local"> gives YYYY-MM-DDTHH:MM in local
            // time, no timezone. Convert to ISO with the browser's offset so
            // the server stores the intended instant.
            body.nextUpdateAt = new Date(String(nextUpdateAt)).toISOString();
        }
        return body;
    }

    /** AdminApi doesn't expose a PATCH helper today; this fills the gap
     *  with a minimal direct fetch using the same redirect-on-401 wrapper. */
    function adminPatch(path, body) {
        return fetch(path, {
            method: 'PATCH',
            credentials: 'same-origin',
            headers: {
                'Accept': 'application/json',
                'Content-Type': 'application/json'
            },
            body: JSON.stringify(body)
        }).then(function (response) {
            if (response.status === 401) {
                window.location.href = '/admin/login.html';
                return Promise.reject(new Error('Unauthenticated'));
            }
            if (!response.ok) {
                return response.text().then(function (text) {
                    var err = new Error('HTTP ' + response.status);
                    err.status = response.status;
                    err.body = text;
                    throw err;
                });
            }
            if (response.status === 204) return null;
            return response.json();
        });
    }

    function onHistoryToggle(loaderName) {
        if (openHistory[loaderName]) {
            // Collapse: drop cached history so the next open re-fetches.
            openHistory[loaderName] = false;
            delete historyByLoader[loaderName];
            renderCards();
            return;
        }
        openHistory[loaderName] = true;
        renderCards();
        AdminApi.get('/api/admin/loaders/' + encodeURIComponent(loaderName) +
                     '/runs?limit=' + HISTORY_LIMIT)
            .then(function (rows) {
                historyByLoader[loaderName] = Array.isArray(rows) ? rows : [];
                renderCards();
            })
            .catch(function (err) {
                historyByLoader[loaderName] = [];
                renderCards();
                showMessage('History fetch failed for ' + loaderName + ': ' + (err && err.message), 'err');
            });
    }

    function schedulePollIfRunning() {
        clearTimeout(pollTimer);
        var anyRunning = loaders.some(function (l) {
            return l.lastRun && l.lastRun.status === 'RUNNING';
        });
        if (!anyRunning) return;
        pollTimer = setTimeout(function () {
            // Only poll while we're still on the data view; rendering
            // into a different rootEl means the user navigated away.
            if (!rootEl || !document.getElementById('data-cards')) return;
            loadAndRender();
        }, POLL_INTERVAL_MS);
    }

    function showMessage(text, kind) {
        var el = document.getElementById('data-message');
        if (!el) return;
        el.textContent = text || '';
        el.className = 'routes-message' + (kind ? ' routes-message-' + kind : '');
    }

    function formatTimestamp(value) {
        if (value == null) return '';
        var d = new Date(value);
        if (isNaN(d.getTime())) return esc(String(value));
        var pad = function (n) { return n < 10 ? '0' + n : String(n); };
        return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate())
             + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds());
    }

    function formatDuration(ms) {
        if (ms == null || isNaN(ms)) return '';
        if (ms < 1000) return ms + 'ms';
        var s = Math.round(ms / 1000);
        if (s < 60) return s + 's';
        var m = Math.floor(s / 60);
        var rem = s % 60;
        return m + 'm ' + rem + 's';
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

    function escAttr(s) { return esc(s); }

    window.AdminViews = window.AdminViews || {};
    window.AdminViews.data = render;
})();
