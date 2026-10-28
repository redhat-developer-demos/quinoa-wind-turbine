package org.raceops.mcp;

import io.quarkiverse.langchain4j.mcp.auth.McpClientAuthProvider;
import io.quarkiverse.langchain4j.mcp.runtime.McpClientName;
import io.quarkus.oidc.client.OidcClient;
import io.quarkus.oidc.client.Tokens;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Supplies the director's bearer token to the race-admin MCP client. The OidcClient
 * refreshes the token when it expires, so a long demo does not outlive its credentials.
 */
@ApplicationScoped
@McpClientName("race-admin")
public class AdminMcpAuthProvider implements McpClientAuthProvider {

    private final OidcClient oidcClient;

    public AdminMcpAuthProvider(OidcClient oidcClient) {
        this.oidcClient = oidcClient;
    }

    @Override
    public String getAuthorization(Input input) {
        Uni<Tokens> tokens = oidcClient.getTokens();
        return "Bearer " + tokens.await().indefinitely().getAccessToken();
    }
}
