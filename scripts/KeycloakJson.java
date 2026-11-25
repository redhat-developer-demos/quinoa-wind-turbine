///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.22.4

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;

/** JSON transformations used by deploy-openshift.sh. Reads stdin and writes the result to stdout. */
public class KeycloakJson {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final String USAGE = "Usage: jbang scripts/KeycloakJson.java "
            + "<realm|issuer|client-flow|federated-execution|bind-client-flow>";

    public static void main(String[] args) {
        if (args.length == 1 && "--help".equals(args[0])) {
            System.out.println(USAGE);
            return;
        }
        if (args.length != 1) {
            System.err.println(USAGE);
            System.exit(1);
        }
        try {
            JsonNode input = MAPPER.readTree(System.in);
            if (input == null) {
                throw new IllegalArgumentException("JSON input is empty");
            }
            String result = switch (args[0]) {
                case "realm" -> realm(input, System.getenv("GAME_ADMIN_PASSWORD"));
                case "issuer" -> requiredText(input, "/issuer");
                case "client-flow" -> requiredText(input, "/clientAuthenticationFlow");
                case "federated-execution" -> federatedExecution(input);
                case "bind-client-flow" -> bindClientFlow(input);
                default -> throw new IllegalArgumentException(USAGE);
            };
            System.out.println(result);
        } catch (JsonProcessingException e) {
            // Parser messages can contain credentials from the input document.
            System.err.println("Keycloak JSON input could not be processed");
            System.exit(1);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        } catch (IOException e) {
            System.err.println("Keycloak JSON input could not be read");
            System.exit(1);
        }
    }

    private static String realm(JsonNode input, String password) throws JsonProcessingException {
        JsonNode realm = input.at("/items/0/spec/realm");
        if (!realm.isObject()) {
            throw new IllegalArgumentException("Expected a realm object at /items/0/spec/realm");
        }
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("GAME_ADMIN_PASSWORD is required");
        }
        // Escape before replacing inside the serialized JSON, including quotes and backslashes.
        String quotedPassword = MAPPER.writeValueAsString(password);
        String escapedPassword = quotedPassword.substring(1, quotedPassword.length() - 1);
        return MAPPER.writeValueAsString(realm).replace("${GAME_ADMIN_PASSWORD}", escapedPassword);
    }

    private static String requiredText(JsonNode input, String path) {
        JsonNode value = input.at(path);
        if (!value.isTextual() || value.textValue().isEmpty()) {
            throw new IllegalArgumentException("Expected a nonempty string at " + path);
        }
        return value.textValue();
    }

    private static String federatedExecution(JsonNode input) {
        if (!input.isArray()) {
            throw new IllegalArgumentException("Expected an array of authentication executions");
        }
        for (JsonNode execution : input) {
            if ("federated-jwt".equals(execution.path("providerId").asText())) {
                return requiredText(execution, "/id");
            }
        }
        throw new IllegalArgumentException("No federated-jwt authentication execution found");
    }

    private static String bindClientFlow(JsonNode input) throws JsonProcessingException {
        if (!(input instanceof ObjectNode realm)) {
            throw new IllegalArgumentException("Expected a realm object");
        }
        realm.put("clientAuthenticationFlow", "clients-federated");
        return MAPPER.writeValueAsString(realm);
    }
}
