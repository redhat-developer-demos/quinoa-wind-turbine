package org.acme;

import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.IdentityProvider;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.TrustedAuthenticationRequest;
import io.quarkus.security.identity.request.UsernamePasswordAuthenticationRequest;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Checks the dashboard login against the game.admin config and gives that user the admin role.
 */
@ApplicationScoped
public class AdminIdentityProvider implements IdentityProvider<UsernamePasswordAuthenticationRequest> {

    static final String ADMIN_ROLE = "admin";

    private final GameConfig.Admin admin;

    public AdminIdentityProvider(GameConfig config) {
        this.admin = config.admin();
    }

    @Override
    public Class<UsernamePasswordAuthenticationRequest> getRequestType() {
        return UsernamePasswordAuthenticationRequest.class;
    }

    @Override
    public Uni<SecurityIdentity> authenticate(UsernamePasswordAuthenticationRequest request,
                                              AuthenticationRequestContext context) {
        if (!admin.accepts(request.getUsername(), new String(request.getPassword().getPassword()))) {
            return Uni.createFrom().failure(new AuthenticationFailedException());
        }
        return Uni.createFrom().item(adminIdentity(request.getUsername()));
    }

    static SecurityIdentity adminIdentity(String username) {
        return QuarkusSecurityIdentity.builder()
            .setPrincipal(new QuarkusPrincipal(username))
            .addRole(ADMIN_ROLE)
            .build();
    }

    /**
     * After login, form authentication sends the user name from its encrypted session cookie.
     */
    @ApplicationScoped
    public static class SessionIdentityProvider implements IdentityProvider<TrustedAuthenticationRequest> {

        private final GameConfig.Admin admin;

        public SessionIdentityProvider(GameConfig config) {
            this.admin = config.admin();
        }

        @Override
        public Class<TrustedAuthenticationRequest> getRequestType() {
            return TrustedAuthenticationRequest.class;
        }

        @Override
        public Uni<SecurityIdentity> authenticate(TrustedAuthenticationRequest request,
                                                  AuthenticationRequestContext context) {
            if (!admin.username().equals(request.getPrincipal())) {
                return Uni.createFrom().failure(new AuthenticationFailedException());
            }
            return Uni.createFrom().item(adminIdentity(request.getPrincipal()));
        }
    }
}
