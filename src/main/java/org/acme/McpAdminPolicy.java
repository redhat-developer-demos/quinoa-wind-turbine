package org.acme;

import io.quarkus.oidc.AccessTokenCredential;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.vertx.http.runtime.security.HttpSecurityPolicy;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Disabling OIDC must never make dashboard form credentials sufficient for admin MCP. */
@ApplicationScoped
public class McpAdminPolicy implements HttpSecurityPolicy {
    private final boolean enabled;

    public McpAdminPolicy(@ConfigProperty(name = "quarkus.oidc.tenant-enabled") boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public String name() {
        return "mcp-admin";
    }

    @Override
    public Uni<CheckResult> checkPermission(RoutingContext request, Uni<SecurityIdentity> identity,
                                           AuthorizationRequestContext context) {
        if (!enabled) {
            // DENY alone would challenge anonymous requests through the remaining form mechanism.
            return Uni.createFrom().failure(new ForbiddenException());
        }
        return identity.map(current -> current.hasRole("admin")
            && current.getCredential(AccessTokenCredential.class) != null ? CheckResult.PERMIT : CheckResult.DENY);
    }
}
