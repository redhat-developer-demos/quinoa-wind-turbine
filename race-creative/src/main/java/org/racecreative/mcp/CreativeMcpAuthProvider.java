package org.racecreative.mcp;

import io.quarkiverse.langchain4j.mcp.auth.McpClientAuthProvider;
import io.quarkiverse.langchain4j.mcp.runtime.McpClientName;
import io.quarkus.oidc.client.OidcClient;
import io.quarkus.oidc.client.Tokens;
import io.quarkus.oidc.client.runtime.TokensHelper;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;

/**
 * Supplies the app's bearer token to the race-admin MCP client. TokensHelper caches and
 * refreshes the token shortly before expiry, so a talk-length demo does not outlive its
 * credentials. Dev uses the game's direct grant; a deployment uses the race-ops confidential
 * client, whose service account already holds the admin role.
 */
@ApplicationScoped
@McpClientName("race-admin")
public class CreativeMcpAuthProvider implements McpClientAuthProvider {

    private final OidcClient oidcClient;
    private final TokensHelper tokensHelper = new TokensHelper();

    @Inject
    public CreativeMcpAuthProvider(OidcClient oidcClient) {
        this.oidcClient = oidcClient;
    }

    @PostConstruct
    void init() {
        tokensHelper.initTokens(oidcClient);
    }

    @Override
    public String getAuthorization(Input input) {
        Uni<Tokens> tokens = tokensHelper.getTokens(oidcClient);
        return "Bearer " + tokens.await().atMost(Duration.ofSeconds(10)).getAccessToken();
    }
}
