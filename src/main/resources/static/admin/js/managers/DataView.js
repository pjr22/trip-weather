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

    // Canonical Geofabrik US slug list — Phase 2c add-pbf modal uses this to
    // auto-fill the name + URL fields when the admin picks from the region
    // dropdown. Mirrors derive_geofabrik_url() in
    // dev_scripts/admin-console-db-migration.sh so the seed and the UI agree
    // on which slugs live under which Geofabrik path.
    //
    // {slug, name, path} where path is the directory under
    // https://download.geofabrik.de/. The four US sub-regions (us-west,
    // us-east, us-south, us-midwest) plus Canada/Mexico live at
    // /north-america/; states live at /north-america/us/.
    var GEOFABRIK_BASE = 'https://download.geofabrik.de';
    var US_REGIONS = [
        // Sub-region extracts — much larger graphs; cover multiple states.
        { slug: 'us-west',    name: 'US West (CA, NV, OR, WA, ID, UT, AZ, NM, MT, WY, CO)', path: 'north-america' },
        { slug: 'us-midwest', name: 'US Midwest (IL, IN, IA, KS, MI, MN, MO, NE, ND, OH, SD, WI)', path: 'north-america' },
        { slug: 'us-northeast', name: 'US Northeast (CT, ME, MA, NH, NJ, NY, PA, RI, VT)', path: 'north-america' },
        { slug: 'us-pacific', name: 'US Pacific (AK, HI)', path: 'north-america' },
        { slug: 'us-south',   name: 'US South (AL, AR, DE, DC, FL, GA, KY, LA, MD, MS, NC, OK, SC, TN, TX, VA, WV)', path: 'north-america' },
        // States (alphabetical), under /north-america/us/.
        { slug: 'alabama',              name: 'Alabama' },
        { slug: 'alaska',               name: 'Alaska' },
        { slug: 'arizona',              name: 'Arizona' },
        { slug: 'arkansas',             name: 'Arkansas' },
        { slug: 'california',           name: 'California' },
        { slug: 'colorado',             name: 'Colorado' },
        { slug: 'connecticut',          name: 'Connecticut' },
        { slug: 'delaware',             name: 'Delaware' },
        { slug: 'district-of-columbia', name: 'District of Columbia' },
        { slug: 'florida',              name: 'Florida' },
        { slug: 'georgia',              name: 'Georgia' },
        { slug: 'hawaii',               name: 'Hawaii' },
        { slug: 'idaho',                name: 'Idaho' },
        { slug: 'illinois',             name: 'Illinois' },
        { slug: 'indiana',              name: 'Indiana' },
        { slug: 'iowa',                 name: 'Iowa' },
        { slug: 'kansas',               name: 'Kansas' },
        { slug: 'kentucky',             name: 'Kentucky' },
        { slug: 'louisiana',            name: 'Louisiana' },
        { slug: 'maine',                name: 'Maine' },
        { slug: 'maryland',             name: 'Maryland' },
        { slug: 'massachusetts',        name: 'Massachusetts' },
        { slug: 'michigan',             name: 'Michigan' },
        { slug: 'minnesota',            name: 'Minnesota' },
        { slug: 'mississippi',          name: 'Mississippi' },
        { slug: 'missouri',             name: 'Missouri' },
        { slug: 'montana',              name: 'Montana' },
        { slug: 'nebraska',             name: 'Nebraska' },
        { slug: 'nevada',               name: 'Nevada' },
        { slug: 'new-hampshire',        name: 'New Hampshire' },
        { slug: 'new-jersey',           name: 'New Jersey' },
        { slug: 'new-mexico',           name: 'New Mexico' },
        { slug: 'new-york',             name: 'New York' },
        { slug: 'north-carolina',       name: 'North Carolina' },
        { slug: 'north-dakota',         name: 'North Dakota' },
        { slug: 'ohio',                 name: 'Ohio' },
        { slug: 'oklahoma',             name: 'Oklahoma' },
        { slug: 'oregon',               name: 'Oregon' },
        { slug: 'pennsylvania',         name: 'Pennsylvania' },
        { slug: 'puerto-rico',          name: 'Puerto Rico' },
        { slug: 'rhode-island',         name: 'Rhode Island' },
        { slug: 'south-carolina',       name: 'South Carolina' },
        { slug: 'south-dakota',         name: 'South Dakota' },
        { slug: 'tennessee',            name: 'Tennessee' },
        { slug: 'texas',                name: 'Texas' },
        { slug: 'us-virgin-islands',    name: 'US Virgin Islands' },
        { slug: 'utah',                 name: 'Utah' },
        { slug: 'vermont',              name: 'Vermont' },
        { slug: 'virginia',             name: 'Virginia' },
        { slug: 'washington',           name: 'Washington' },
        { slug: 'west-virginia',        name: 'West Virginia' },
        { slug: 'wisconsin',            name: 'Wisconsin' },
        { slug: 'wyoming',              name: 'Wyoming' }
    ];

    /** Geofabrik URL for a region descriptor (state slug → us/ path; everything
     *  else is at the /north-america/ root). */
    function urlForRegion(region) {
        var path = region.path || 'north-america/us';
        return GEOFABRIK_BASE + '/' + path + '/' + region.slug + '-latest.osm.pbf';
    }

    function render(root) {
        rootEl = root;
        rootEl.innerHTML = templateShell();
        loadAndRender();
    }

    function templateShell() {
        return ''
            + '<h2>Data / Loaders</h2>'
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
        var subtitle = 'OSM extracts and their dispatcher coverage. Adding a pbf creates the dispatcher row; the polygon is fetched after the cron\'s next apply.';

        // Single-loaded-pbf banner. The runOrs.sh / ors-config.yml stack
        // loads exactly one extract at a time, so when admin has more
        // than one pbf row, surfacing the currently-loaded one up-front
        // saves them from having to scan rows to figure out which has
        // active local coverage. Banner only renders when there are 2+
        // pbfs (in the single-row case it's redundant); the message
        // also covers the "no pbf loaded yet" edge case explicitly.
        var loadedBanner = '';
        if (list.length >= 2) {
            var loaded = list.filter(function (p) { return p.routingHasPolygon; });
            var loadedNames = loaded.map(function (p) { return p.pbfName; }).join(', ');
            if (loaded.length === 0) {
                loadedBanner = '<div class="data-pbf-banner data-pbf-banner-warn">'
                    + 'No pbf is currently loaded for local routing. '
                    + 'All routing falls back to public ORS until one is applied.'
                    + '</div>';
            } else if (loaded.length === 1) {
                loadedBanner = '<div class="data-pbf-banner">'
                    + 'Currently loaded: <strong>' + esc(loadedNames) + '</strong>. '
                    + 'Only one extract can be loaded at a time — applying another '
                    + 'pbf will replace this one. (Merging multiple extracts is a '
                    + 'follow-up; see ADMIN_CONSOLE.md.)'
                    + '</div>';
            } else {
                // More than one pbf carries a polygon. This shouldn't happen
                // under the cron's enforce_single_loaded_pbf invariant — if
                // it does, the admin needs to know so they can investigate
                // (the dispatcher will route through stale polygons whose
                // graphs aren't actually loaded).
                loadedBanner = '<div class="data-pbf-banner data-pbf-banner-warn">'
                    + 'Invariant broken: more than one pbf has a polygon ('
                    + esc(loadedNames) + '). The dispatcher may route to '
                    + 'unloaded extracts. Re-apply one pbf to restore the '
                    + 'invariant; if this persists, check the cron logs.'
                    + '</div>';
            }
        }

        var body = (loadedBanner || '')
            + (rowsHtml
                || '<div class="data-empty">No pbf rows configured. Add one to start the cron-driven flow.</div>');
        return ''
            + '<section class="data-card">'
            +   '<header class="data-card-header">'
            +     '<h3>Pbf files</h3>'
            +     '<span class="data-card-subtitle">' + esc(subtitle) + '</span>'
            +     '<span class="data-card-actions"><button id="data-pbf-add" class="primary">Add pbf</button></span>'
            +   '</header>'
            +   '<div class="data-card-body">'
            +     body
            +   '</div>'
            + '</section>';
    }

    /** Render one pbf row in display mode. Add/edit happens in a modal
     *  (AdminUI.openFormModal) rather than inline now. */
    function pbfRowHtml(pbf) {
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

        // Dispatcher state line + manual toggle.
        //
        // Phase 2c constraint: at most one pbf is loaded into trip-ors at a
        // time (runOrs.sh / ors-config.yml mount a single file). The cron's
        // enforce_single_loaded_pbf step clears geom + fetched_at on every
        // *other* pbf row after a successful apply, so routingHasPolygon
        // doubles as "this is the currently-loaded pbf". The label below
        // distinguishes the three meaningful states an unloaded row can be
        // in (never applied vs replaced-by-another vs admin-disabled) so
        // the admin doesn't have to deduce the reason from the surrounding
        // columns.
        var routingText;
        if (pbf.routingHasPolygon) {
            if (pbf.routingEnabled) {
                routingText = 'active — currently loaded'
                    + (pbf.routingFetchedAt
                        ? ' (polygon fetched ' + formatTimestamp(pbf.routingFetchedAt) + ')'
                        : '');
            } else {
                routingText = 'disabled — admin paused (currently loaded but dispatcher uses public ORS)';
            }
        } else if (pbf.lastApplyMd5) {
            // Has been applied before, but another pbf is now the loaded
            // one — the single-loaded-pbf invariant cleared this row's
            // polygon when the other pbf was applied. Re-applying this one
            // makes it the loaded extract again.
            routingText = 'not currently loaded — another pbf is the active extract. Click "Schedule now" to reload this one.';
        } else {
            routingText = 'awaiting first apply (polygon will be fetched after apply succeeds)';
        }
        var routingClass = 'data-pbf-routing'
            + (pbf.routingEnabled && pbf.routingHasPolygon ? ' data-pbf-routing-active' : '')
            + (!pbf.routingEnabled ? ' data-pbf-routing-disabled' : '');
        var routingLine = '<div class="' + routingClass + '">routing: ' + esc(routingText) + '</div>';
        var routingToggleLabel = pbf.routingEnabled ? 'Disable routing' : 'Enable routing';
        var routingToggleBtn = '<button data-pbf-routing-toggle="' + escAttr(pbf.pbfName) + '"'
            + ' data-pbf-routing-current="' + (pbf.routingEnabled ? 'true' : 'false') + '">'
            + routingToggleLabel + '</button>';

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
            +     routingLine
            +   '</div>'
            +   '<div class="data-pbf-actions">'
            +     retryBtn + scheduleNowBtn + checkBtn + routingToggleBtn + editBtn + deleteBtn
            +   '</div>'
            +   errorLine
            + '</div>';
    }

    /**
     * Modal body for add/edit pbf. pbf is null when adding. For Add mode the
     * top of the form is a region picker (datalist combobox over US_REGIONS)
     * that auto-fills the name + URL when admin picks a known slug. Either
     * field stays editable for custom/non-listed extracts.
     *
     * Edit mode hides the region picker (the pbf is already named) and makes
     * pbfName readonly — the PK can't change.
     */
    function pbfFormHtmlForModal(pbf) {
        var isEdit = pbf != null;
        var name = pbf ? pbf.pbfName : '';
        var url = pbf ? pbf.geofabrikUrl : '';
        var active = pbf ? pbf.active : true;
        var checkInterval = pbf ? pbf.checkIntervalDays : 7;
        var updateInterval = pbf && pbf.updateIntervalDays != null ? pbf.updateIntervalDays : '';
        var nextUpdate = pbf && pbf.nextUpdateAt ? pbf.nextUpdateAt : '';
        // <input type="datetime-local"> wants 'YYYY-MM-DDTHH:MM' without seconds/TZ.
        var nextUpdateInput = nextUpdate ? toLocalInputValue(nextUpdate) : '';

        // Datalist options: native filtering in the input gives prefix-match
        // autocomplete in Chrome/Edge (Firefox does substring). Plain
        // <input list="..."> stays freely typable so admin can paste a slug
        // that's not in the list and the form still works.
        var regionOptionsHtml = US_REGIONS.map(function (r) {
            return '<option value="' + escAttr(r.slug) + '">' + esc(r.name) + '</option>';
        }).join('');

        var regionPicker = isEdit ? '' : ''
            + '<label>Pick a US region'
            +   '<input id="pbf-region-picker" type="text" list="pbf-region-options" '
            +     'placeholder="Type to filter (alabama, california, us-west…)" autocomplete="off">'
            +   '<datalist id="pbf-region-options">' + regionOptionsHtml + '</datalist>'
            +   '<span class="admin-form-hint">Selecting a region auto-fills the name and URL below. '
            +     'You can paste a non-US Geofabrik URL into either field instead.</span>'
            + '</label>';

        return ''
            + regionPicker
            + '<label>Name'
            +   '<input id="pbf-form-name" type="text" required maxlength="64" '
            +     'placeholder="us-west" value="' + escAttr(name) + '"'
            +     (isEdit ? ' readonly' : '') + '>'
            + '</label>'
            + '<label>Geofabrik URL'
            +   '<input id="pbf-form-url" type="url" required '
            +     'placeholder="https://download.geofabrik.de/north-america/us/colorado-latest.osm.pbf" '
            +     'value="' + escAttr(url) + '">'
            + '</label>'
            + '<label class="admin-form-checkbox">'
            +   '<input id="pbf-form-active" type="checkbox"' + (active ? ' checked' : '') + '>'
            +   ' Active (cron processes this row)'
            + '</label>'
            + '<div class="admin-form-row">'
            +   '<label>Check interval (days)'
            +     '<input id="pbf-form-check-interval" type="number" min="1" value="' + escAttr(String(checkInterval)) + '">'
            +   '</label>'
            +   '<label>Update interval (days)'
            +     '<input id="pbf-form-update-interval" type="number" min="1" value="' + escAttr(String(updateInterval)) + '" placeholder="blank = manual">'
            +   '</label>'
            + '</div>'
            + '<label>Next update at (local time, blank = paused)'
            +   '<input id="pbf-form-next-update" type="datetime-local" value="' + escAttr(nextUpdateInput) + '">'
            + '</label>';
    }

    /**
     * Open the add/edit modal. pbf is null for Add, an existing dto for Edit.
     * Wires the region picker → name+URL auto-fill behaviour and posts the
     * collected form values to the backend on submit.
     */
    function openPbfModal(pbf) {
        var isEdit = pbf != null;
        AdminUI.openFormModal({
            title: isEdit ? ('Edit pbf · ' + pbf.pbfName) : 'Add pbf',
            submitLabel: isEdit ? 'Save' : 'Create',
            bodyHtml: pbfFormHtmlForModal(pbf),
            onShown: function (modalEl) {
                // Region picker auto-fill: when admin's choice matches one of
                // the known slugs, drop the slug into the name field and the
                // derived URL into the URL field. Custom slugs left as-is.
                var picker = modalEl.querySelector('#pbf-region-picker');
                var nameInput = modalEl.querySelector('#pbf-form-name');
                var urlInput = modalEl.querySelector('#pbf-form-url');
                if (picker) {
                    picker.addEventListener('change', function () {
                        var slug = (picker.value || '').trim().toLowerCase();
                        var region = US_REGIONS.find(function (r) { return r.slug === slug; });
                        if (!region) return;
                        nameInput.value = region.slug;
                        urlInput.value = urlForRegion(region);
                    });
                    // Focus the picker on open so admin can start typing
                    // immediately. Edit mode (no picker) focuses the URL.
                    picker.focus();
                } else if (urlInput) {
                    urlInput.focus();
                }
            },
            onSubmit: function (modalEl) {
                var body = collectPbfModalBody(modalEl);
                if (!body.pbfName) {
                    return Promise.reject(new Error('Name is required.'));
                }
                if (!body.geofabrikUrl) {
                    return Promise.reject(new Error('Geofabrik URL is required.'));
                }
                var promise;
                if (isEdit) {
                    promise = (AdminApi.patch || adminPatch)(
                            '/api/admin/pbfs/' + encodeURIComponent(pbf.pbfName), body);
                } else {
                    promise = AdminApi.post('/api/admin/pbfs', body);
                }
                return promise.then(function () {
                    showMessage((isEdit ? 'Saved' : 'Created') + ' pbf "'
                            + body.pbfName + '".', 'ok');
                    loadAndRender();
                }, function (err) {
                    // Translate 409 + bubble; openFormModal surfaces the
                    // message inline and leaves the modal open for retry.
                    if (err && err.status === 409) {
                        return Promise.reject(new Error('A pbf with that name already exists.'));
                    }
                    return Promise.reject(new Error(
                        (isEdit ? 'Save' : 'Create') + ' failed: '
                        + (err && err.message ? err.message : 'unknown error')));
                });
            }
        });
    }

    function collectPbfModalBody(modalEl) {
        var body = {};
        var name = (modalEl.querySelector('#pbf-form-name').value || '').trim();
        if (name) body.pbfName = name;
        var url = (modalEl.querySelector('#pbf-form-url').value || '').trim();
        if (url) body.geofabrikUrl = url;
        body.active = modalEl.querySelector('#pbf-form-active').checked;
        var checkInterval = modalEl.querySelector('#pbf-form-check-interval').value;
        if (checkInterval) body.checkIntervalDays = parseInt(checkInterval, 10);
        var updateInterval = modalEl.querySelector('#pbf-form-update-interval').value;
        if (updateInterval && String(updateInterval).trim() !== '') {
            body.updateIntervalDays = parseInt(updateInterval, 10);
        }
        var nextUpdateAt = modalEl.querySelector('#pbf-form-next-update').value;
        if (nextUpdateAt && String(nextUpdateAt).trim() !== '') {
            // <input type="datetime-local"> gives YYYY-MM-DDTHH:MM in local
            // time, no timezone. Convert to ISO with the browser's offset so
            // the server stores the intended instant.
            body.nextUpdateAt = new Date(String(nextUpdateAt)).toISOString();
        }
        return body;
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
        // Top-of-card "Add pbf" button — opens the form modal in add mode.
        var addBtn = document.getElementById('data-pbf-add');
        if (addBtn) addBtn.addEventListener('click', function () {
            openPbfModal(null);
        });

        // Per-row action buttons.
        forEachAttr('data-pbf-schedule', onPbfScheduleNow);
        forEachAttr('data-pbf-check',    onPbfCheckUpstream);
        forEachAttr('data-pbf-retry',    onPbfRetryStuckApply);
        forEachAttr('data-pbf-edit',     function (name) {
            // Open the edit modal pre-populated with the row's current state.
            var pbf = pbfs.find(function (p) { return p.pbfName === name; });
            if (pbf) openPbfModal(pbf);
        });
        forEachAttr('data-pbf-delete',   onPbfDelete);

        // Routing toggle reads the current state from a data-attribute on the
        // same button (set in pbfRowHtml) so the click handler knows which
        // way to flip without re-querying state from a closure.
        var toggleBtns = rootEl.querySelectorAll('button[data-pbf-routing-toggle]');
        Array.prototype.forEach.call(toggleBtns, function (btn) {
            btn.addEventListener('click', function () {
                var name = btn.getAttribute('data-pbf-routing-toggle');
                var current = btn.getAttribute('data-pbf-routing-current') === 'true';
                onPbfRoutingToggle(name, !current);
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
        // The check-md5 endpoint returns the full updated dto; we use the
        // returned remote / deployed md5 + stale flag to compose a concrete
        // message instead of just "complete" (which left admin guessing
        // whether anything actually happened).
        AdminApi.post('/api/admin/pbfs/' + encodeURIComponent(pbfName) + '/check-md5')
            .then(function (dto) {
                showMessage(formatCheckUpstreamResult(pbfName, dto), 'ok');
                loadAndRender();
            })
            .catch(function (err) {
                if (err && err.status === 502) {
                    showMessage('Upstream fetch failed for "' + pbfName + '" '
                            + '(Geofabrik unreachable or returned bad data): '
                            + (err && err.message), 'err');
                } else {
                    showMessage('Check failed for "' + pbfName + '": '
                            + (err && err.message), 'err');
                }
            });
    }

    /** Compose the toast text shown after a successful upstream check. The
     *  goal is to make the result *visible*: show the upstream md5 the
     *  server just fetched and a verdict (up-to-date vs stale vs first-fetch)
     *  so admin doesn't have to scan the row to see whether anything
     *  changed. */
    function formatCheckUpstreamResult(pbfName, dto) {
        if (!dto || !dto.lastRemoteMd5) {
            // Defensive: server should always return remote md5 on success.
            return 'Upstream check complete for "' + pbfName + '".';
        }
        var upstream = shortMd5(dto.lastRemoteMd5);
        if (!dto.lastApplyMd5) {
            return '"' + pbfName + '": upstream md5 ' + upstream
                    + ' — never applied yet. Click "Schedule now" to build the initial graph.';
        }
        var deployed = shortMd5(dto.lastApplyMd5);
        if (dto.stale) {
            return '"' + pbfName + '": upstream md5 ' + upstream
                    + ' differs from deployed ' + deployed
                    + ' — STALE. Click "Schedule now" to apply.';
        }
        return '"' + pbfName + '": upstream md5 ' + upstream
                + ' matches deployed — up to date.';
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
                'The paired routing_coverage row is also deleted (FK cascade). ' +
                'The dispatcher immediately falls back to public ORS for that ' +
                'area. The actual .osm.pbf file and the running trip-ors ' +
                'container are not touched — you handle those on the host if ' +
                'you want to clean up.',
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

    /**
     * Phase 2c: flip routing_coverage.enabled for one pbf. The PATCH endpoint
     * writes the paired row in the same transaction as any other pbf field;
     * here we only send routingEnabled so the rest of pbf_files is untouched.
     */
    function onPbfRoutingToggle(pbfName, nextEnabled) {
        var action = nextEnabled ? 'Enable' : 'Disable';
        var path = '/api/admin/pbfs/' + encodeURIComponent(pbfName);
        var body = { routingEnabled: nextEnabled };
        var promise = AdminApi.patch
            ? AdminApi.patch(path, body)
            : adminPatch(path, body);
        promise.then(function () {
            showMessage(action + 'd routing for "' + pbfName + '".', 'ok');
            loadAndRender();
        }).catch(function (err) {
            showMessage(action + ' routing failed: ' + (err && err.message), 'err');
        });
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

    /**
     * Delegate to the shared floating toast (window.Toast — same code the
     * main SPA uses). The legacy {kind: 'ok' | 'err'} convention used by
     * this view is mapped to Toast's {type: 'success' | 'error'}. Calls
     * without a kind are dropped — those were previously used for inline
     * "Loading…" indicators and don't translate to a floating toast.
     */
    function showMessage(text, kind) {
        if (!text || !window.Toast) return;
        var type;
        if (kind === 'ok')       type = 'success';
        else if (kind === 'err') type = 'error';
        else                     return;
        window.Toast.show(text, type);
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
