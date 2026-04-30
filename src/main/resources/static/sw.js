/**
 * Trip Weather — service worker.
 *
 * Strategy:
 *   - HTML navigation requests: stale-while-revalidate so deploys propagate
 *     on the next load without forcing a hard refresh.
 *   - Same-origin static assets (CSS, JS, images, icons, manifest) and the
 *     Leaflet CDN bundle: cache-first; populated lazily on first fetch so
 *     no manual asset manifest needs maintaining.
 *   - Anything under /api/ on this origin: network-only — never serve a
 *     stale routing or persistence response from cache.
 *   - All other origins (map tiles, weather imagery, geocoding): network-only.
 *
 * Bump CACHE_VERSION when changing this file to force a clean activation.
 */

const CACHE_VERSION = 'v1';
const STATIC_CACHE = 'tripweather-static-' + CACHE_VERSION;
const SHELL_CACHE = 'tripweather-shell-' + CACHE_VERSION;

const LEAFLET_HOST = 'unpkg.com';

// Minimal install: pre-cache the HTML shell so the first offline visit
// after install still renders something. Everything else is cached on demand.
self.addEventListener('install', function(event) {
    event.waitUntil(
        caches.open(SHELL_CACHE).then(function(cache) {
            return cache.addAll(['./', 'manifest.webmanifest']);
        }).then(function() {
            return self.skipWaiting();
        })
    );
});

self.addEventListener('activate', function(event) {
    event.waitUntil(
        caches.keys().then(function(keys) {
            return Promise.all(keys.map(function(key) {
                if (key !== STATIC_CACHE && key !== SHELL_CACHE) {
                    return caches.delete(key);
                }
                return null;
            }));
        }).then(function() {
            return self.clients.claim();
        })
    );
});

function isApiRequest(url) {
    return url.origin === self.location.origin && url.pathname.startsWith('/api/');
}

function isLeafletAsset(url) {
    return url.host === LEAFLET_HOST;
}

function isSameOriginStatic(url, request) {
    if (url.origin !== self.location.origin) return false;
    if (request.method !== 'GET') return false;
    if (request.mode === 'navigate') return false;
    if (url.pathname.startsWith('/api/')) return false;
    return true;
}

function staleWhileRevalidate(request, cacheName) {
    return caches.open(cacheName).then(function(cache) {
        return cache.match(request).then(function(cached) {
            const networkPromise = fetch(request).then(function(response) {
                if (response && response.ok) {
                    cache.put(request, response.clone());
                }
                return response;
            }).catch(function() {
                return cached;
            });
            return cached || networkPromise;
        });
    });
}

function cacheFirst(request, cacheName) {
    return caches.open(cacheName).then(function(cache) {
        return cache.match(request).then(function(cached) {
            if (cached) return cached;
            return fetch(request).then(function(response) {
                // Only cache successful, basic/cors responses; skip opaque
                // partials or errored requests to avoid poisoning the cache.
                if (response && response.ok &&
                    (response.type === 'basic' || response.type === 'cors')) {
                    cache.put(request, response.clone());
                }
                return response;
            });
        });
    });
}

self.addEventListener('fetch', function(event) {
    const request = event.request;
    if (request.method !== 'GET') return;

    const url = new URL(request.url);

    if (isApiRequest(url)) {
        return;
    }

    if (request.mode === 'navigate') {
        event.respondWith(staleWhileRevalidate(request, SHELL_CACHE));
        return;
    }

    if (isSameOriginStatic(url, request) || isLeafletAsset(url)) {
        event.respondWith(cacheFirst(request, STATIC_CACHE));
        return;
    }
});
