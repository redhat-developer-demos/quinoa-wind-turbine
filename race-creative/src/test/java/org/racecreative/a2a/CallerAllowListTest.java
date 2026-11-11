package org.racecreative.a2a;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The allowlist decides on the workload path of the leaf certificate's SPIFFE ID (the part
 * after the trust domain): the trust store already pins the trust domain, so the path is what
 * identifies the caller, and no configuration hardcodes the cluster's URL. The fixtures are
 * certificates with a SPIFFE ID as URI SAN and a DNS SAN that must not count.
 */
class CallerAllowListTest {

    /** race-ops' workload path in the wind-turbine namespace. */
    private static final String RACE_OPS = "ns/wind-turbine/sa/race-ops";

    @Test
    void acceptsTheAllowedWorkloadPath() throws Exception {
        assertTrue(CallerAllowList.allows(chain("race-ops"), RACE_OPS));
    }

    @Test
    void acceptsTheSamePathFromAnotherTrustDomain() throws Exception {
        // The trust domain is pinned by the trust store, not by the configured value.
        assertTrue(CallerAllowList.allows(chain("race-ops-other-domain"), RACE_OPS));
    }

    @Test
    void rejectsAnotherWorkloadPath() throws Exception {
        assertFalse(CallerAllowList.allows(chain("other"), RACE_OPS));
    }

    @Test
    void ignoresDnsNames() throws Exception {
        assertFalse(CallerAllowList.allows(chain("race-ops"), "race-ops"));
    }

    @Test
    void rejectsACallerWithoutACertificateOrAnEmptySetting() throws Exception {
        assertFalse(CallerAllowList.allows(new Certificate[0], RACE_OPS));
        assertFalse(CallerAllowList.allows((Certificate[]) null, RACE_OPS));
        assertFalse(CallerAllowList.allows((javax.net.ssl.SSLSession) null, RACE_OPS));
        assertFalse(CallerAllowList.allows(chain("race-ops"), null));
        assertFalse(CallerAllowList.allows(chain("race-ops"), "  "));
    }

    private static Certificate[] chain(String name) throws Exception {
        try (InputStream in = CallerAllowListTest.class.getResourceAsStream("/spiffe/" + name + ".pem")) {
            return new Certificate[] {CertificateFactory.getInstance("X.509").generateCertificate(in)};
        }
    }
}
