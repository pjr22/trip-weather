package com.pjr22.tripweather.service.ai;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * SSRF guard for the only user-supplied outbound URL in the AI feature — the
 * {@code CUSTOM} provider's base URL. AI_ASSIST_PLAN.md, Phase 1b.
 *
 * <p>Applied to {@code CUSTOM} base URLs by both model discovery and (Phase 2)
 * the chat call. Operator-set URLs (OpenAI / Anthropic defaults, the Ollama
 * endpoint) are trusted and never pass through here. The guard:
 *
 * <ol>
 *   <li>requires an {@code http}/{@code https} scheme;</li>
 *   <li>rejects single-label hosts — a host with neither a dot nor a colon,
 *       e.g. {@code localhost} or a bare container / k8s service name like
 *       {@code trip-ors};</li>
 *   <li>resolves the host via DNS and requires <b>every</b> returned address to
 *       be a public IP — rejecting loopback, link-local (incl. the
 *       {@code 169.254.169.254} metadata IP), private, IPv6 unique-local, any-
 *       local, and multicast ranges. All A/AAAA records are checked.</li>
 * </ol>
 *
 * <p><b>Known residual risk (DNS rebinding / TOCTOU):</b> this resolves and
 * checks, but the actual outbound connection re-resolves, leaving a gap where a
 * host could answer public here and private at connect time. Full hardening
 * would pin the validated IP for the connection; tracked as a follow-up in
 * AI_ASSIST_PLAN.md.
 *
 * <p>DNS resolution is injected via {@link HostResolver} so the guard is
 * unit-testable without real network lookups.
 */
@Component
public class OutboundUrlGuard {

    /** Seam over {@link InetAddress#getAllByName(String)} for testability. */
    @FunctionalInterface
    public interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final HostResolver resolver;

    public OutboundUrlGuard() {
        this(InetAddress::getAllByName);
    }

    /** Test seam — inject a fake resolver to avoid real DNS. */
    OutboundUrlGuard(HostResolver resolver) {
        this.resolver = resolver;
    }

    /**
     * Validate a user-supplied URL. Returns normally if allowed; throws
     * {@link OutboundUrlNotAllowedException} (→ 400) otherwise.
     */
    public void validate(String url) {
        if (url == null || url.isBlank()) {
            throw reject("a base URL is required");
        }

        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            throw reject("is not a valid URL");
        }

        String scheme = uri.getScheme();
        if (scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw reject("must use http or https");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw reject("has no resolvable host");
        }
        // URI.getHost() returns IPv6 literals in bracketed form (e.g. "[::1]").
        if (host.startsWith("[") && host.endsWith("]") && host.length() > 2) {
            host = host.substring(1, host.length() - 1);
        }

        // Single-label hosts (no dot AND no colon) are bare names — localhost,
        // container/service names. IPv4 literals contain dots; IPv6 literals
        // contain colons; real public hostnames are FQDNs with a dot.
        if (!host.contains(".") && !host.contains(":")) {
            throw reject("points at a single-label host (" + host
                    + ") — not an allowed public endpoint");
        }

        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException e) {
            throw reject("could not be resolved (" + host + ")");
        }
        if (addresses == null || addresses.length == 0) {
            throw reject("could not be resolved (" + host + ")");
        }
        for (InetAddress address : addresses) {
            if (!isPublic(address)) {
                throw reject("resolves to a non-public address (" + address.getHostAddress()
                        + ") — internal/private destinations are not allowed");
            }
        }
    }

    /**
     * Whether an address is a routable public IP. Rejects any-local, loopback,
     * link-local (covers IPv4 {@code 169.254/16} incl. the metadata IP and IPv6
     * {@code fe80::/10}), site-local/private (IPv4 {@code 10/8},
     * {@code 172.16/12}, {@code 192.168/16}), multicast, and IPv6 unique-local
     * ({@code fc00::/7}, which {@link InetAddress#isSiteLocalAddress()} does not
     * cover).
     */
    private static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        if (address instanceof Inet6Address) {
            byte[] bytes = address.getAddress();
            // Unique-local addresses: fc00::/7 (first 7 bits are 1111110).
            if ((bytes[0] & 0xFE) == 0xFC) {
                return false;
            }
        }
        return true;
    }

    private static OutboundUrlNotAllowedException reject(String why) {
        return new OutboundUrlNotAllowedException("The provided URL " + why + ".");
    }

    /** 400 — a user-supplied outbound URL failed the SSRF guard. */
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public static class OutboundUrlNotAllowedException extends RuntimeException {
        public OutboundUrlNotAllowedException(String message) {
            super(message);
        }
    }
}
