/* Admin-console UI primitives — currently just a styled confirm modal that
 * replaces window.confirm() across the admin SPA. The native dialog looks
 * out of place against the rest of the console (and varies wildly between
 * browsers); this gives every confirmation the same shell-consistent shape.
 *
 * Usage:
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
 * Only one prompt can be open at a time; opening a second one resolves any
 * outstanding promise as cancelled before showing the new prompt.
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

    return { confirm: confirm };
})();
