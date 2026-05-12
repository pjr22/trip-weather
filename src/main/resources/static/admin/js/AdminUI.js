/* Admin-console UI primitives. Two flavours of modal:
 *
 *   AdminUI.confirm({ title, message, confirmLabel, danger }) → Promise<boolean>
 *   AdminUI.openFormModal({ title, bodyHtml, submitLabel, danger,
 *                           cancelLabel, onShown, onSubmit })
 *
 * The confirm modal is the legacy yes/no path. The form modal is built
 * dynamically so callers can drop in arbitrary form markup (with their own
 * inputs / layout) and wire the submit handler. Both share the admin-modal
 * CSS so the visual shell stays consistent.
 *
 * confirm() usage:
 *
 *   AdminUI.confirm({
 *       title: 'Soft-delete route',
 *       message: 'Soft-delete this route? It can be restored from the\n'
 *              + '"Deleted only" filter until the cleanup job hard-deletes\n'
 *              + 'it past the grace window.',
 *       confirmLabel: 'Delete',
 *       danger: true
 *   }).then(ok => {
 *       if (!ok) return;
 *       // ... destructive action
 *   });
 *
 * The message is rendered via textContent (XSS-safe even for user-supplied
 * strings) and the modal CSS uses {@code white-space: pre-line} so {@code
 * \n} characters in the message show as line breaks without needing HTML.
 *
 * Only one confirm prompt can be open at a time; opening a second one
 * resolves any outstanding promise as cancelled before showing the new
 * prompt.
 *
 * openFormModal() usage:
 *
 *   AdminUI.openFormModal({
 *       title: 'Add pbf',
 *       bodyHtml: '<input id="my-field"> ...',
 *       submitLabel: 'Create',
 *       onShown: function (modalEl) {
 *           // modalEl is the .admin-modal-card root; query for fields here.
 *           // Optionally focus an input, wire keypress shortcuts, etc.
 *       },
 *       onSubmit: function (modalEl) {
 *           // Read field values, validate, call API. Return a Promise.
 *           // Resolving keeps the modal open until the work finishes; the
 *           // modal is closed automatically when the Promise resolves
 *           // (caller can return Promise.reject() to leave the modal open
 *           // with an error message).
 *           return apiCall(...);
 *       }
 *   });
 *
 * The form modal is not a Promise-resolving helper because submissions go
 * through async API calls that may fail and want to keep the modal open
 * for retry. The caller's onSubmit returns a Promise that gates the close.
 */
window.AdminUI = (function () {
    'use strict';

    var pendingResolve = null;
    var modal, titleEl, messageEl, okBtn, cancelBtn;

    function init() {
        modal = document.getElementById('admin-confirm-modal');
        if (!modal) return;

        titleEl = document.getElementById('admin-confirm-modal-title');
        messageEl = document.getElementById('admin-confirm-modal-message');
        okBtn = document.getElementById('admin-confirm-modal-ok');
        cancelBtn = document.getElementById('admin-confirm-modal-cancel');

        okBtn.addEventListener('click', function () { finish(true); });
        cancelBtn.addEventListener('click', function () { finish(false); });
        modal.addEventListener('click', function (event) {
            // Click on the backdrop (not the card) cancels.
            if (event.target === modal) finish(false);
        });
        document.addEventListener('keydown', function (event) {
            if (modal.hidden) return;
            if (event.key === 'Escape') {
                finish(false);
            } else if (event.key === 'Enter' && document.activeElement !== cancelBtn) {
                // Enter on the OK button (or with no button focused) confirms.
                // Cancel-focused dangerous prompts have to be explicitly OK'd.
                event.preventDefault();
                finish(true);
            }
        });
    }

    function finish(result) {
        if (!modal) return;
        modal.hidden = true;
        var resolve = pendingResolve;
        pendingResolve = null;
        if (resolve) resolve(result);
    }

    function confirm(opts) {
        opts = opts || {};

        // Markup-missing fallback. Should never fire in normal operation —
        // if it does, something has gone wrong with the shell load order.
        if (!modal || !titleEl || !messageEl || !okBtn || !cancelBtn) {
            console.warn('AdminUI.confirm: modal markup missing; falling back to window.confirm');
            return Promise.resolve(window.confirm(opts.message || ''));
        }

        // If something is already open, resolve it as cancelled so the new
        // prompt isn't fighting the old one for the same DOM nodes.
        if (pendingResolve) {
            var prior = pendingResolve;
            pendingResolve = null;
            prior(false);
        }

        titleEl.textContent = opts.title || 'Confirm';
        messageEl.textContent = opts.message || '';
        okBtn.textContent = opts.confirmLabel || 'OK';
        cancelBtn.textContent = opts.cancelLabel || 'Cancel';
        okBtn.className = opts.danger ? 'danger' : 'primary';

        modal.hidden = false;
        // Default focus: Cancel for destructive prompts (so an absent-minded
        // Enter doesn't fire the destructive action), OK otherwise.
        (opts.danger ? cancelBtn : okBtn).focus();

        return new Promise(function (resolve) {
            pendingResolve = resolve;
        });
    }

    document.addEventListener('DOMContentLoaded', init);

    // ---- Form modal --------------------------------------------------------

    var formModalEl = null;          // current backdrop element
    var formModalKeyHandler = null;  // ESC handler bound for the open modal
    var formModalSubmitting = false; // gate to prevent double-submits

    /**
     * Open a form-shaped modal with caller-supplied body HTML. Returns the
     * created modal card element so the caller can wire form behavior in
     * onShown. The modal is closed when the user clicks Cancel / backdrop /
     * ESC, or when onSubmit's returned Promise resolves.
     */
    function openFormModal(opts) {
        opts = opts || {};
        // Single form modal at a time. If one is open, close it first.
        closeFormModal();

        var backdrop = document.createElement('div');
        backdrop.className = 'admin-modal admin-form-modal-backdrop';
        backdrop.setAttribute('role', 'dialog');
        backdrop.setAttribute('aria-modal', 'true');

        var card = document.createElement('div');
        card.className = 'admin-modal-card admin-form-modal-card';

        var header = document.createElement('header');
        header.className = 'admin-modal-header';
        var titleEl = document.createElement('h2');
        titleEl.textContent = opts.title || '';
        header.appendChild(titleEl);

        var body = document.createElement('div');
        body.className = 'admin-modal-body admin-form-modal-body';
        // bodyHtml is a trusted template assembled by the caller (not user
        // input) — the calling pattern is "static template + escaped value
        // interpolation" the same as everywhere else in this SPA.
        body.innerHTML = opts.bodyHtml || '';

        var errBar = document.createElement('div');
        errBar.className = 'admin-form-modal-error';
        errBar.hidden = true;

        var actions = document.createElement('footer');
        actions.className = 'admin-modal-actions';
        var cancelBtn = document.createElement('button');
        cancelBtn.type = 'button';
        cancelBtn.textContent = opts.cancelLabel || 'Cancel';
        var submitBtn = document.createElement('button');
        submitBtn.type = 'button';
        submitBtn.textContent = opts.submitLabel || 'Save';
        submitBtn.className = opts.danger ? 'danger' : 'primary';
        actions.appendChild(cancelBtn);
        actions.appendChild(submitBtn);

        card.appendChild(header);
        card.appendChild(body);
        card.appendChild(errBar);
        card.appendChild(actions);
        backdrop.appendChild(card);
        document.body.appendChild(backdrop);

        formModalEl = backdrop;

        // Cancel paths: explicit button, backdrop click, ESC. None fire while
        // a submit is in flight (we'd lose track of the in-progress call).
        cancelBtn.addEventListener('click', function () {
            if (formModalSubmitting) return;
            closeFormModal();
        });
        backdrop.addEventListener('click', function (event) {
            if (event.target !== backdrop) return;
            if (formModalSubmitting) return;
            closeFormModal();
        });
        formModalKeyHandler = function (event) {
            if (event.key === 'Escape' && !formModalSubmitting) {
                closeFormModal();
            }
        };
        document.addEventListener('keydown', formModalKeyHandler);

        submitBtn.addEventListener('click', function () {
            if (formModalSubmitting) return;
            errBar.hidden = true;
            errBar.textContent = '';
            if (typeof opts.onSubmit !== 'function') {
                closeFormModal();
                return;
            }
            var result;
            try {
                result = opts.onSubmit(card);
            } catch (e) {
                errBar.textContent = e && e.message ? e.message : String(e);
                errBar.hidden = false;
                return;
            }
            // onSubmit may return a Promise (async API call) or a value
            // (sync validation result). Normalise to Promise so the close
            // logic is uniform.
            if (!result || typeof result.then !== 'function') {
                closeFormModal();
                return;
            }
            formModalSubmitting = true;
            submitBtn.disabled = true;
            cancelBtn.disabled = true;
            result.then(function () {
                formModalSubmitting = false;
                closeFormModal();
            }, function (err) {
                formModalSubmitting = false;
                submitBtn.disabled = false;
                cancelBtn.disabled = false;
                errBar.textContent = err && err.message ? err.message : String(err);
                errBar.hidden = false;
            });
        });

        if (typeof opts.onShown === 'function') {
            opts.onShown(card);
        }
    }

    function closeFormModal() {
        if (!formModalEl) return;
        if (formModalKeyHandler) {
            document.removeEventListener('keydown', formModalKeyHandler);
            formModalKeyHandler = null;
        }
        if (formModalEl.parentNode) {
            formModalEl.parentNode.removeChild(formModalEl);
        }
        formModalEl = null;
        formModalSubmitting = false;
    }

    return {
        confirm: confirm,
        openFormModal: openFormModal,
        closeFormModal: closeFormModal
    };
})();
