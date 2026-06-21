/**
 * AiProvidersModal — the "AI Providers" manager modal (AI_ASSIST_PLAN.md
 * Phase 3). Opened from the profile menu (dispatched by UIManager). Lists the
 * user's saved AI provider configs and provides an add/edit form whose model
 * field is a dropdown populated by server-side discovery (no free-text model
 * names). Mirrors FavoritesManagerModal's structure and conventions.
 *
 * Also owns the profile-menu visibility probe: on auth change it asks the
 * server whether the assist feature is enabled and re-renders the menu so the
 * "AI Providers" entry is hidden when trip.ai.assist.enabled=false.
 */
window.TripWeather = window.TripWeather || {};
window.TripWeather.Managers = window.TripWeather.Managers || {};

window.TripWeather.Managers.AiProvidersModal = {

    initialized: false,
    rows: [],            // list snapshot for the modal's lifetime
    providers: null,     // available provider types (cached per modal session)
    editingId: null,     // config id being edited, or null when adding
    editingConfig: null, // the summary being edited (for apiKeySet / current model)
    models: [],          // full sorted model list for the current form (filter source)

    PROVIDER_LABELS: {
        OPENAI: 'OpenAI',
        ANTHROPIC: 'Anthropic',
        CUSTOM: 'Custom (OpenAI-compatible)',
        OLLAMA: 'Ollama'
    },

    // Public pricing pages — only the hosted providers have a canonical one.
    PRICING_URLS: {
        OPENAI: 'https://openai.com/api/pricing/',
        ANTHROPIC: 'https://www.anthropic.com/pricing'
    },

    initialize: function() {
        if (this.initialized) return;
        this.initialized = true;

        this.setupModalCloseAffordances();
        this.setupFormControls();
        this.subscribeAuthChanges();
    },

    // ---------------- auth gating + menu visibility probe ----------------

    subscribeAuthChanges: function() {
        const auth = window.TripWeather.Services.Auth;
        if (auth && typeof auth.onChange === 'function') {
            auth.onChange(function(user) {
                if (!user) {
                    this.close();
                } else {
                    this.probeAndRefreshMenu();
                }
            }.bind(this));
        }
        // In case auth resolved before we subscribed.
        if (auth && auth.getCurrentUser()) {
            this.probeAndRefreshMenu();
        }
    },

    /**
     * Probe whether assist is enabled, then re-render the profile menu so the
     * "AI Providers" entry appears/disappears to match.
     */
    probeAndRefreshMenu: function() {
        const svc = window.TripWeather.Services.AiProvider;
        if (!svc) return;
        svc.refreshAssistEnabled().then(function() {
            const ui = window.TripWeather.Managers.UI;
            const auth = window.TripWeather.Services.Auth;
            if (ui && typeof ui.renderProfileMenu === 'function') {
                ui.renderProfileMenu(auth ? auth.getCurrentUser() : null);
            }
        });
    },

    // ---------------- modal lifecycle ----------------

    setupModalCloseAffordances: function() {
        const modal = document.getElementById('ai-providers-modal');
        if (!modal) return;

        const closeBtn = modal.querySelector('.modal-header .close');
        if (closeBtn) closeBtn.addEventListener('click', this.close.bind(this));

        modal.addEventListener('click', function(event) {
            if (event.target === modal) this.close();
        }.bind(this));
        document.addEventListener('keydown', function(event) {
            if (event.key === 'Escape' && modal.style.display === 'block') this.close();
        }.bind(this));
    },

    setupFormControls: function() {
        const addBtn = document.getElementById('ai-providers-add-btn');
        if (addBtn) addBtn.addEventListener('click', function() { this.openForm(null); }.bind(this));

        const providerSel = document.getElementById('ai-provider-field');
        if (providerSel) {
            providerSel.addEventListener('change', function() {
                this.updateFieldVisibility();
                this.resetModelOptions();
                this.setFormStatus('');
            }.bind(this));
        }

        const loadBtn = document.getElementById('ai-load-models-btn');
        if (loadBtn) loadBtn.addEventListener('click', this.loadModels.bind(this));

        const modelFilter = document.getElementById('ai-model-filter');
        if (modelFilter) {
            modelFilter.addEventListener('input', function() {
                this.renderModelOptions(modelFilter.value);
            }.bind(this));
        }

        const saveBtn = document.getElementById('ai-provider-save-btn');
        if (saveBtn) saveBtn.addEventListener('click', this.save.bind(this));

        const cancelBtn = document.getElementById('ai-provider-cancel-btn');
        if (cancelBtn) cancelBtn.addEventListener('click', this.hideForm.bind(this));
    },

    open: function() {
        const auth = window.TripWeather.Services.Auth;
        if (!auth || !auth.getCurrentUser()) return;

        const modal = document.getElementById('ai-providers-modal');
        if (!modal) return;
        modal.style.display = 'block';

        this.hideForm();
        this.editingId = null;
        this.setStatus('Loading…');

        this.ensureProviders();
        this.refreshList();
    },

    close: function() {
        const modal = document.getElementById('ai-providers-modal');
        if (modal) modal.style.display = 'none';
        this.hideForm();
        this.editingId = null;
    },

    setStatus: function(text) {
        const el = document.getElementById('ai-providers-modal-status');
        if (el) el.textContent = text || '';
    },

    setFormStatus: function(text) {
        const el = document.getElementById('ai-provider-form-status');
        if (el) el.textContent = text || '';
    },

    // ---------------- list ----------------

    refreshList: function() {
        window.TripWeather.Services.AiProvider.list()
            .then(function(configs) {
                this.rows = configs || [];
                this.setStatus('');
                this.renderRows();
            }.bind(this))
            .catch(function(err) {
                this.rows = [];
                const msg = err.status === 403
                    ? 'The AI assistant is not enabled on this server.'
                    : ('Could not load providers: ' + err.message);
                this.setStatus(msg);
                this.renderRows();
            }.bind(this));
    },

    renderRows: function() {
        const tbody = document.getElementById('ai-providers-modal-tbody');
        const emptyEl = document.getElementById('ai-providers-modal-empty');
        if (!tbody) return;
        tbody.innerHTML = '';

        if (this.rows.length === 0) {
            if (emptyEl) {
                emptyEl.style.display = '';
                emptyEl.textContent = 'No AI providers yet — click "Add provider" to create one.';
            }
            return;
        }
        if (emptyEl) emptyEl.style.display = 'none';

        this.rows.forEach(function(cfg) {
            tbody.appendChild(this.buildRow(cfg));
        }.bind(this));
    },

    buildRow: function(cfg) {
        const tr = document.createElement('tr');
        tr.dataset.configId = cfg.id;

        tr.appendChild(this.cell(cfg.nickname));
        tr.appendChild(this.cell(this.PROVIDER_LABELS[cfg.provider] || cfg.provider));
        tr.appendChild(this.cell(cfg.model));
        tr.appendChild(this.cell(cfg.apiKeySet ? '✓' : '—'));

        const actionsTd = document.createElement('td');
        actionsTd.className = 'favorites-cell-actions';
        actionsTd.appendChild(this.makeActionButton('Edit', 'edit',
            function() { this.openForm(cfg); }.bind(this)));
        actionsTd.appendChild(this.makeActionButton('Delete', 'delete',
            this.handleDelete.bind(this, cfg)));
        tr.appendChild(actionsTd);
        return tr;
    },

    cell: function(text) {
        const td = document.createElement('td');
        td.textContent = text == null ? '' : String(text);
        return td;
    },

    makeActionButton: function(label, kind, handler) {
        const btn = document.createElement('button');
        btn.className = 'favorites-row-action favorites-row-action-' + kind;
        btn.textContent = label;
        btn.addEventListener('click', handler);
        return btn;
    },

    // ---------------- add / edit form ----------------

    /** Ensure the available-providers list is loaded; resolves to the array. */
    ensureProviders: function() {
        if (this.providers) return Promise.resolve(this.providers);
        return window.TripWeather.Services.AiProvider.available()
            .then(function(providers) {
                this.providers = providers || [];
                return this.providers;
            }.bind(this))
            .catch(function() {
                this.providers = [];
                return this.providers;
            }.bind(this));
    },

    openForm: function(cfg) {
        this.ensureProviders().then(function(providers) {
            if (!providers || providers.length === 0) {
                this.setStatus('No AI providers are available on this server.');
                return;
            }
            this.editingId = cfg ? cfg.id : null;
            this.editingConfig = cfg || null;

            const titleEl = document.getElementById('ai-provider-form-title');
            if (titleEl) titleEl.textContent = cfg ? 'Edit provider' : 'Add provider';

            // Provider select
            const providerSel = document.getElementById('ai-provider-field');
            providerSel.innerHTML = '';
            providers.forEach(function(p) {
                const opt = document.createElement('option');
                opt.value = p;
                opt.textContent = this.PROVIDER_LABELS[p] || p;
                providerSel.appendChild(opt);
            }.bind(this));
            if (cfg && providers.indexOf(cfg.provider) !== -1) {
                providerSel.value = cfg.provider;
            }

            // Nickname
            document.getElementById('ai-nickname-field').value = cfg ? cfg.nickname : '';

            // API key (write-only): blank on edit means keep the stored value.
            const keyField = document.getElementById('ai-apikey-field');
            keyField.value = '';
            keyField.placeholder = (cfg && cfg.apiKeySet)
                ? '•••• stored — leave blank to keep'
                : 'sk-…';

            // Base URL (Custom only)
            document.getElementById('ai-baseurl-field').value = (cfg && cfg.baseUrl) ? cfg.baseUrl : '';

            // Optional per-million-token costs.
            document.getElementById('ai-input-cost-field').value =
                (cfg && cfg.inputCostPerMtok != null) ? cfg.inputCostPerMtok : '';
            document.getElementById('ai-output-cost-field').value =
                (cfg && cfg.outputCostPerMtok != null) ? cfg.outputCostPerMtok : '';

            this.updateFieldVisibility();

            // Model dropdown — seed with the stored model so it shows before a
            // discovery run; "Load models" repopulates (sorted) from the provider.
            this.resetModelOptions();
            if (cfg && cfg.model) {
                this.models = [cfg.model];
                this.renderModelOptions('');
                const modelSel = document.getElementById('ai-model-field');
                if (modelSel) modelSel.value = cfg.model;
            }

            this.setFormStatus('');
            const form = document.getElementById('ai-provider-form');
            if (form) form.style.display = '';
        }.bind(this));
    },

    hideForm: function() {
        const form = document.getElementById('ai-provider-form');
        if (form) form.style.display = 'none';
        this.editingId = null;
        this.editingConfig = null;
        this.setFormStatus('');
    },

    currentProvider: function() {
        const sel = document.getElementById('ai-provider-field');
        return sel ? sel.value : null;
    },

    needsKey: function(provider) {
        return provider === 'OPENAI' || provider === 'ANTHROPIC';
    },

    showsKey: function(provider) {
        // Ollama is keyless (operator URL); the others may carry a key.
        return provider !== 'OLLAMA';
    },

    needsBaseUrl: function(provider) {
        return provider === 'CUSTOM';
    },

    updateFieldVisibility: function() {
        const provider = this.currentProvider();
        const keyRow = document.getElementById('ai-apikey-row');
        const keyLabel = document.getElementById('ai-apikey-label');
        const baseRow = document.getElementById('ai-baseurl-row');

        if (keyRow) keyRow.style.display = this.showsKey(provider) ? '' : 'none';
        if (keyLabel) keyLabel.textContent = this.needsKey(provider) ? 'API key' : 'API key (optional)';
        if (baseRow) baseRow.style.display = this.needsBaseUrl(provider) ? '' : 'none';

        // Pricing link only for the hosted providers that publish one.
        const pricingUrl = this.PRICING_URLS[provider];
        const pricingRow = document.getElementById('ai-pricing-link-row');
        const pricingLink = document.getElementById('ai-pricing-link');
        if (pricingRow) pricingRow.style.display = pricingUrl ? '' : 'none';
        if (pricingLink && pricingUrl) pricingLink.href = pricingUrl;
    },

    resetModelOptions: function() {
        this.models = [];
        const filter = document.getElementById('ai-model-filter');
        if (filter) filter.value = '';
        const modelSel = document.getElementById('ai-model-field');
        if (!modelSel) return;
        modelSel.innerHTML = '';
        const placeholder = document.createElement('option');
        placeholder.value = '';
        placeholder.textContent = '— load models —';
        modelSel.appendChild(placeholder);
    },

    // ---------------- model discovery ----------------

    loadModels: function() {
        const provider = this.currentProvider();
        const apiKey = document.getElementById('ai-apikey-field').value.trim();
        const baseUrl = document.getElementById('ai-baseurl-field').value.trim();

        if (this.needsBaseUrl(provider) && !baseUrl) {
            this.setFormStatus('Enter the base URL first.');
            return;
        }
        // On the edit form, a blank key + unchanged provider means "use the
        // stored key" — discover via the stored-config endpoint.
        const useStored = this.editingId && !apiKey
            && this.editingConfig && this.editingConfig.provider === provider;
        if (!useStored && this.needsKey(provider) && !apiKey) {
            this.setFormStatus('Enter the API key first, then load models.');
            return;
        }

        this.setFormStatus('Loading models…');
        const svc = window.TripWeather.Services.AiProvider;
        const promise = useStored
            ? svc.discoverModelsForConfig(this.editingId)
            : svc.discoverModels({ provider: provider, apiKey: apiKey || null, baseUrl: baseUrl || null });

        promise
            .then(function(models) { this.populateModels(models); }.bind(this))
            .catch(function(err) { this.setFormStatus('Could not load models: ' + err.message); }.bind(this));
    },

    populateModels: function(models) {
        const modelSel = document.getElementById('ai-model-field');
        if (!modelSel) return;

        if (!models || models.length === 0) {
            this.models = [];
            modelSel.innerHTML = '';
            const opt = document.createElement('option');
            opt.value = '';
            opt.textContent = '— no models returned —';
            modelSel.appendChild(opt);
            this.setFormStatus('The provider returned no models.');
            return;
        }

        // Sort alphabetically (case-insensitive) and stash the full list so the
        // filter box can narrow it without re-fetching.
        this.models = models.slice().sort(function(a, b) {
            return String(a).toLowerCase().localeCompare(String(b).toLowerCase());
        });
        const filter = document.getElementById('ai-model-filter');
        if (filter) filter.value = '';
        this.renderModelOptions('');
        this.setFormStatus(this.models.length + ' model' + (this.models.length === 1 ? '' : 's')
            + ' loaded. Type to filter.');
    },

    /**
     * Rebuild the model dropdown from {@link models}, narrowed to the filter
     * text (case-insensitive substring). Preserves the current selection when it
     * still matches; pins it as an option even when filtered out so typing can't
     * silently drop a chosen model.
     */
    renderModelOptions: function(filterText) {
        const modelSel = document.getElementById('ai-model-field');
        if (!modelSel) return;
        const selected = modelSel.value;
        const needle = (filterText || '').trim().toLowerCase();

        const matches = needle
            ? this.models.filter(function(m) { return m.toLowerCase().indexOf(needle) !== -1; })
            : this.models.slice();

        // Keep the current selection reachable even if it doesn't match the filter.
        if (selected && this.models.indexOf(selected) !== -1 && matches.indexOf(selected) === -1) {
            matches.unshift(selected);
        }

        modelSel.innerHTML = '';
        if (matches.length === 0) {
            const opt = document.createElement('option');
            opt.value = '';
            opt.textContent = '— no models match —';
            modelSel.appendChild(opt);
            return;
        }
        matches.forEach(function(m) {
            const opt = document.createElement('option');
            opt.value = m;
            opt.textContent = m;
            modelSel.appendChild(opt);
        });
        if (selected && matches.indexOf(selected) !== -1) {
            modelSel.value = selected;
        }
    },

    // ---------------- save / delete ----------------

    save: function() {
        const provider = this.currentProvider();
        const nickname = document.getElementById('ai-nickname-field').value.trim();
        const apiKey = document.getElementById('ai-apikey-field').value.trim();
        const baseUrl = document.getElementById('ai-baseurl-field').value.trim();
        const model = document.getElementById('ai-model-field').value;

        if (!provider) { this.setFormStatus('Choose a provider.'); return; }
        if (!nickname) { this.setFormStatus('Enter a nickname.'); return; }
        if (!model) { this.setFormStatus('Load models and pick one.'); return; }
        if (this.needsBaseUrl(provider) && !baseUrl) { this.setFormStatus('Enter the base URL.'); return; }

        // On create, OpenAI/Anthropic require a key. On edit, a blank key keeps
        // the stored one (so blank is fine when a key is already stored).
        const willHaveKey = !!apiKey || (this.editingConfig && this.editingConfig.apiKeySet);
        if (this.needsKey(provider) && !willHaveKey) {
            this.setFormStatus('Enter the API key.');
            return;
        }

        // Optional per-million-token costs. Blank → null (omitted from estimate);
        // any present value must be a non-negative number.
        const inputCost = this.parseCost(document.getElementById('ai-input-cost-field').value);
        const outputCost = this.parseCost(document.getElementById('ai-output-cost-field').value);
        if (inputCost === false || outputCost === false) {
            this.setFormStatus('Costs must be non-negative numbers (or left blank).');
            return;
        }

        const body = { provider: provider, nickname: nickname, model: model };
        if (apiKey) body.apiKey = apiKey;
        if (this.needsBaseUrl(provider)) body.baseUrl = baseUrl;
        // Always send the cost fields (null clears a previously-stored value).
        body.inputCostPerMtok = inputCost;
        body.outputCostPerMtok = outputCost;

        const svc = window.TripWeather.Services.AiProvider;
        const op = this.editingId ? svc.update(this.editingId, body) : svc.create(body);

        this.setFormStatus('Saving…');
        op
            .then(function(saved) {
                window.Toast.show('Saved "' + saved.nickname + '"', 'success');
                this.hideForm();
                this.refreshList();
            }.bind(this))
            .catch(function(err) {
                if (err.code === 'DUPLICATE_NICKNAME') {
                    this.setFormStatus('You already have a provider named "' + nickname + '".');
                } else {
                    this.setFormStatus('Save failed: ' + err.message);
                }
            }.bind(this));
    },

    /**
     * Parse an optional cost input. Returns null for blank, a non-negative
     * number for a valid value, or false to signal an invalid entry.
     */
    parseCost: function(raw) {
        const trimmed = (raw == null ? '' : String(raw)).trim();
        if (trimmed === '') return null;
        const n = Number(trimmed);
        if (!isFinite(n) || n < 0) return false;
        return n;
    },

    handleDelete: function(cfg) {
        const ui = window.TripWeather.Managers.UI;
        const performDelete = function() {
            window.TripWeather.Services.AiProvider.remove(cfg.id)
                .then(function() {
                    this.rows = this.rows.filter(function(r) { return r.id !== cfg.id; });
                    this.renderRows();
                    window.Toast.show('Deleted "' + cfg.nickname + '"', 'success');
                }.bind(this))
                .catch(function(err) {
                    window.Toast.show('Delete failed: ' + err.message, 'error');
                });
        }.bind(this);

        if (ui && typeof ui.showConfirm === 'function') {
            ui.showConfirm(
                'Delete the AI provider "' + cfg.nickname + '"?',
                performDelete,
                null,
                { title: 'Delete AI provider', confirmLabel: 'Delete', danger: true });
        } else if (window.confirm('Delete "' + cfg.nickname + '"?')) {
            performDelete();
        }
    }
};
