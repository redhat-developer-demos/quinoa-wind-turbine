package org.raceops.mcp;

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
 * Supplies the director's bearer token to the race-admin MCP client. TokensHelper caches
 * the tokens and refreshes them shortly before they expire, so a talk-length demo does
 * not outlive its credentials and repeated tool calls do not each run a password grant.
 */
@ApplicationScoped
@McpClientName("race-admin")
public class AdminMcpAuthProvider implements McpClientAuthProvider {

    private final OidcClient oidcClient;
    private final TokensHelper tokensHelper = new TokensHelper();

    @Inject
    public AdminMcpAuthProvider(OidcClient oidcClient) {
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
