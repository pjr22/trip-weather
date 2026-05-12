/**
 * Floating toast notifications. Loaded by both the main SPA and the admin
 * SPA so they share one toast implementation (and CSS).
 *
 * window.Toast.show(message, type, duration, options) appends a toast to
 * a container element it lazy-creates on <body>. Toasts auto-dismiss
 * after `duration` ms (default 5000; pass 0 to keep until clicked).
 *
 * The CSS lives in /static/css/toast.css. Both index.html files load
 * both that stylesheet and this script.
 */
window.Toast = (function () {
    'use strict';

    var defaults = {
        duration: 5000,
        position: 'upper-center'
    };

    var validPositions = [
        'upper-left',
        'upper-center',
        'upper-right',
        'lower-left',
        'lower-center',
        'lower-right'
    ];

    var containerEl = null;

    function isValidPosition(position) {
        return validPositions.indexOf(position) !== -1;
    }

    function applyPosition(position) {
        if (!containerEl) return;
        var prefix = 'toast-container--';
        validPositions.forEach(function (pos) {
            containerEl.classList.remove(prefix + pos);
        });
        var resolved = isValidPosition(position) ? position : defaults.position;
        containerEl.classList.add(prefix + resolved);
        containerEl.setAttribute('data-toast-position', resolved);
    }

    function getContainer(positionOverride) {
        if (!containerEl) {
            var c = document.createElement('div');
            c.className = 'toast-container';
            document.body.appendChild(c);
            containerEl = c;
        }
        var target = isValidPosition(positionOverride)
            ? positionOverride
            : defaults.position;
        applyPosition(target);
        return containerEl;
    }

    /**
     * Show a toast.
     * @param {string} message — body text
     * @param {string} [type] — 'success' | 'warning' | 'error' | 'info' (default 'info')
     * @param {number|object} [duration] — ms (0 = no auto-dismiss) or options
     * @param {object} [options] — { duration, position }
     */
    function show(message, type, duration, options) {
        var toastType = type || 'info';
        var resolvedOptions = options;
        var durationMs = defaults.duration;

        if (typeof duration === 'number') {
            durationMs = duration;
        } else if (duration && typeof duration === 'object') {
            resolvedOptions = duration;
        }

        if (resolvedOptions && typeof resolvedOptions.duration === 'number'
                && typeof duration !== 'number') {
            durationMs = resolvedOptions.duration;
        }

        var overridePosition = resolvedOptions && resolvedOptions.position;
        var container = getContainer(overridePosition);

        var toast = document.createElement('div');
        toast.className = 'toast toast-' + toastType;

        var messageSpan = document.createElement('span');
        messageSpan.className = 'toast-message-text';
        messageSpan.textContent = message;

        var closeBtn = document.createElement('button');
        closeBtn.type = 'button';
        closeBtn.className = 'toast-close';
        closeBtn.setAttribute('aria-label', 'Dismiss notification');
        closeBtn.textContent = '×';

        var removeToast = function () {
            toast.classList.remove('show');
            setTimeout(function () {
                if (toast.parentNode === container) {
                    container.removeChild(toast);
                }
            }, 250);
        };

        closeBtn.addEventListener('click', removeToast);

        toast.appendChild(messageSpan);
        toast.appendChild(closeBtn);
        container.appendChild(toast);

        requestAnimationFrame(function () {
            toast.classList.add('show');
        });

        if (durationMs > 0) {
            setTimeout(removeToast, durationMs);
        }

        return toast;
    }

    function configure(config) {
        if (!config || typeof config !== 'object') return;
        if (typeof config.duration === 'number') {
            defaults.duration = config.duration;
        }
        if (typeof config.position === 'string') {
            setPosition(config.position);
        }
    }

    function setPosition(position) {
        if (!isValidPosition(position)) {
            console.warn('Invalid toast position "' + position + '"');
            return;
        }
        defaults.position = position;
        applyPosition(position);
    }

    return {
        show: show,
        configure: configure,
        setPosition: setPosition
    };
})();
