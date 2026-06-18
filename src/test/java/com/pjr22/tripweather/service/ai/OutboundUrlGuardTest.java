package com.pjr22.tripweather.service.ai;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link OutboundUrlGuard}. AI_ASSIST_PLAN.md, Phase 1b. Uses a
 * fake {@link OutboundUrlGuard.HostResolver} so no real DNS lookups happen —
 * each test maps the hostname to the IP(s) it should "resolve" to. IP literals
 * are parsed by {@link InetAddress#getByName} without hitting the network.
 */
class OutboundUrlGuardTest {

    /** Map-backed resolver; unmapped hosts throw {@link UnknownHostException}. */
    private static OutboundUrlGuard.HostResolver resolver(Map<String, String[]> map) {
        return host -> {
            String[] ips = map.get(host);
            if (ips == null) {
                throw new UnknownHostException(host);
            }
            InetAddress[] out = new InetAddress[ips.length];
            for (int i = 0; i < ips.length; i++) {
                out[i] = InetAddress.getByName(ips[i]);
            }
            return out;
        };
    }

    private static OutboundUrlGuard guard(Map<String, String[]> map) {
        return new OutboundUrlGuard(resolver(map));
    }

    private static Map<String, String[]> map(String host, String... ips) {
        Map<String, String[]> m = new HashMap<>();
        m.put(host, ips);
        return m;
    }

    private static Class<OutboundUrlGuard.OutboundUrlNotAllowedException> rejected() {
        return OutboundUrlGuard.OutboundUrlNotAllowedException.class;
    }

    // ------------------------------------------------------------------------
    // Accept
    // ------------------------------------------------------------------------

    @Test
    void publicHost_isAllowed() {
        OutboundUrlGuard g = guard(map("api.example.com", "93.184.216.34"));
        assertThatCode(() -> g.validate("https://api.example.com/v1")).doesNotThrowAnyException();
    }

    @Test
    void publicIpv4Literal_isAllowed() {
        OutboundUrlGuard g = guard(map("8.8.8.8", "8.8.8.8"));
        assertThatCode(() -> g.validate("http://8.8.8.8/v1")).doesNotThrowAnyException();
    }

    // ------------------------------------------------------------------------
    // Scheme / format
    // ------------------------------------------------------------------------

    @Test
    void nonHttpScheme_isRejected() {
        OutboundUrlGuard g = guard(map("example.com", "93.184.216.34"));
        assertThatThrownBy(() -> g.validate("ftp://example.com")).isInstanceOf(rejected());
    }

    @Test
    void unparseableUrl_isRejected() {
        assertThatThrownBy(() -> guard(Map.of()).validate("not a url")).isInstanceOf(rejected());
    }

    @Test
    void blankUrl_isRejected() {
        assertThatThrownBy(() -> guard(Map.of()).validate("  ")).isInstanceOf(rejected());
    }

    // ------------------------------------------------------------------------
    // Single-label hosts (no DNS consulted)
    // ------------------------------------------------------------------------

    @Test
    void localhost_isRejected() {
        assertThatThrownBy(() -> guard(Map.of()).validate("http://localhost/v1")).isInstanceOf(rejected());
    }

    @Test
    void bareContainerName_isRejected() {
        assertThatThrownBy(() -> guard(Map.of()).validate("http://trip-ors:8082/ors")).isInstanceOf(rejected());
    }

    // ------------------------------------------------------------------------
    // Non-public resolution
    // ------------------------------------------------------------------------

    @Test
    void hostResolvingToLoopback_isRejected() {
        OutboundUrlGuard g = guard(map("evil.example.com", "127.0.0.1"));
        assertThatThrownBy(() -> g.validate("https://evil.example.com/")).isInstanceOf(rejected());
    }

    @Test
    void hostResolvingToPrivate10_isRejected() {
        OutboundUrlGuard g = guard(map("x.example.com", "10.0.0.5"));
        assertThatThrownBy(() -> g.validate("https://x.example.com/")).isInstanceOf(rejected());
    }

    @Test
    void hostResolvingToPrivate192_isRejected() {
        OutboundUrlGuard g = guard(map("x.example.com", "192.168.1.10"));
        assertThatThrownBy(() -> g.validate("https://x.example.com/")).isInstanceOf(rejected());
    }

    @Test
    void hostResolvingToMetadataIp_isRejected() {
        OutboundUrlGuard g = guard(map("meta.example.com", "169.254.169.254"));
        assertThatThrownBy(() -> g.validate("https://meta.example.com/")).isInstanceOf(rejected());
    }

    @Test
    void hostResolvingToMulticast_isRejected() {
        OutboundUrlGuard g = guard(map("mc.example.com", "224.0.0.1"));
        assertThatThrownBy(() -> g.validate("https://mc.example.com/")).isInstanceOf(rejected());
    }

    @Test
    void ipv4LiteralPrivate_isRejected() {
        OutboundUrlGuard g = guard(map("10.0.0.5", "10.0.0.5"));
        assertThatThrownBy(() -> g.validate("http://10.0.0.5/v1")).isInstanceOf(rejected());
    }

    @Test
    void ipv6Loopback_isRejected() {
        OutboundUrlGuard g = guard(map("v6.example.com", "::1"));
        assertThatThrownBy(() -> g.validate("https://v6.example.com/")).isInstanceOf(rejected());
    }

    @Test
    void ipv6UniqueLocal_isRejected() {
        // fd00::/8 is within the fc00::/7 ULA block.
        OutboundUrlGuard g = guard(map("ula.example.com", "fd00::1"));
        assertThatThrownBy(() -> g.validate("https://ula.example.com/")).isInstanceOf(rejected());
    }

    @Test
    void mixedPublicAndPrivateRecords_isRejected() {
        // One private record among public ones must fail the whole host.
        OutboundUrlGuard g = guard(map("mix.example.com", "93.184.216.34", "10.0.0.1"));
        assertThatThrownBy(() -> g.validate("https://mix.example.com/")).isInstanceOf(rejected());
    }

    @Test
    void unresolvableHost_isRejected() {
        assertThatThrownBy(() -> guard(Map.of()).validate("https://nope.example.com/")).isInstanceOf(rejected());
    }
}
