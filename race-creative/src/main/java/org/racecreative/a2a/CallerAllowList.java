package org.racecreative.a2a;

import io.quarkus.vertx.http.runtime.filters.Filters;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import java.net.URI;
import java.security.cert.Certificate;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Lets one workload call this app. TLS with {@code client-auth=required} only proves the caller
 * holds an SVID from the trust domain, and every workload in the cluster does. So when
 * {@code a2a.allowed-caller} is set, a request goes through only if the workload path of the
 * caller's SPIFFE ID ({@code ns/<namespace>/sa/<service account>} — the part after the trust
 * domain) equals it; anything else, including a request with no certificate, gets 403. The
 * trust domain itself needs no matching here: the server's trust store is the SPIFFE bundle
 * alone, so only this trust domain's certificates complete the handshake. With the setting
 * empty (dev, tests, a cluster without SPIRE) nothing is filtered.
 *
 * <p>Deliberately a route filter, not an {@code HttpSecurityPolicy}: that would be the more
 * idiomatic extension point, but it needs the {@code quarkus-security} extension, and with the
 * security capability present the A2A SDK's Quarkus glue starts rejecting anonymous JSON-RPC
 * calls with 403 regardless of {@code a2a.authorization.required=false} (checked against SDK
 * 1.4.0.Final, 2026-11-11). The apps hold no end-user auth on purpose; the transport check is
 * the whole authorization story.
 */
@ApplicationScoped
public class CallerAllowList {

    private static final Logger LOG = Logger.getLogger(CallerAllowList.class);
    /** The URI SAN type, the one a SPIFFE ID travels in. */
    private static final int URI_SAN = 6;
    /** Above the router's own handlers, so a rejected caller never reaches a resource. */
    private static final int FIRST = 1000;

    @ConfigProperty(name = "a2a.allowed-caller")
    Optional<String> allowedCaller;

    void register(@Observes Filters filters) {
        allowedCaller.filter(id -> !id.isBlank()).ifPresent(allowed -> filters.register(rc -> {
            if (allows(rc.request().sslSession(), allowed)) {
                rc.next();
            } else {
                LOG.warnf("Rejected a caller that is not %s", allowed);
                rc.response().setStatusCode(403).end();
            }
        }, FIRST));
    }

    static boolean allows(SSLSession session, String allowed) {
        if (session == null) {
            return false;
        }
        try {
            return allows(session.getPeerCertificates(), allowed);
        } catch (SSLPeerUnverifiedException e) {
            return false;
        }
    }

    /** The leaf certificate is the first of the peer's chain. */
    static boolean allows(Certificate[] peerChain, String allowed) {
        if (peerChain == null || peerChain.length == 0 || !(peerChain[0] instanceof X509Certificate leaf)
            || allowed == null || allowed.isBlank()) {
            return false;
        }
        try {
            Collection<List<?>> names = leaf.getSubjectAlternativeNames();
            return names != null && names.stream()
                .anyMatch(name -> isAllowedCaller(name, allowed));
        } catch (CertificateParsingException e) {
            return false;
        }
    }

    /**
     * A URI SAN is a SPIFFE ID, {@code spiffe://<trust domain>/<workload path>}: the trust
     * domain is pinned by the trust store, so the workload path is the part that identifies
     * the caller. The configured value is that path ({@code ns/<namespace>/sa/<service
     * account>}), without the trust domain, so no manifest hardcodes the cluster's URL.
     */
    private static boolean isAllowedCaller(List<?> san, String allowed) {
        if (!Integer.valueOf(URI_SAN).equals(san.get(0)) || !(san.get(1) instanceof String spiffeId)) {
            return false;
        }
        try {
            String path = URI.create(spiffeId).getPath().replaceFirst("^/", "");
            return path.equals(allowed.replaceFirst("^/", ""));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
