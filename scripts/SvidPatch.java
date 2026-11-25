///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.22.4

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Builds the strategic merge patch used by mount-spiffe-svid.sh. */
public class SvidPatch {

    private static final String FETCH_JWT = """
            fetch_jwt() {
              t=$(/spire-agent api fetch jwt -audience "$AUDIENCE" -socketPath /spiffe-workload-api/spire-agent.sock \
                | awk '/^token\\(/{getline; gsub(/^[ \\t]+/,""); print; exit}')
              [ -n "$t" ] && printf %s "$t" > /svid/.jwt_svid.tmp && mv /svid/.jwt_svid.tmp /svid/jwt_svid.token
            };
            """;
    private static final String FETCH_X509 = """
            fetch_x509() {
              mkdir -p /svid/.x509 && /spire-agent api fetch x509 -socketPath /spiffe-workload-api/spire-agent.sock -write /svid/.x509 >/dev/null \
                && mv /svid/.x509/bundle.0.pem /svid/bundle.pem && mv /svid/.x509/svid.0.key /svid/svid.key \
                && mv /svid/.x509/svid.0.pem /svid/svid.pem
            };
            """;

    public static void main(String[] args) {
        try {
            String name = requiredEnv("NAME");
            String image = requiredEnv("IMAGE");
            String audience = System.getenv().getOrDefault("AUDIENCE", "");
            boolean x509 = "1".equals(System.getenv("X509"));
            if (audience.isEmpty() && !x509) {
                throw new IllegalArgumentException("AUDIENCE or X509=1 is required");
            }

            List<String> steps = new ArrayList<>();
            String fetch = "";
            if (!audience.isEmpty()) {
                fetch += FETCH_JWT;
                steps.add("fetch_jwt");
            }
            if (x509) {
                fetch += FETCH_X509;
                steps.add("fetch_x509");
            }
            fetch += "fetch() { " + String.join(" && ", steps) + "; };\n";
            String initScript = fetch
                    + "for i in $(seq 1 60); do fetch && exit 0; sleep 2; done; echo no SVID >&2; exit 1";
            String refreshScript = fetch + "while true; do fetch && sleep 60 || sleep 5; done";

            Map<String, Object> pod = Map.of(
                    "volumes", List.of(
                            Map.of("name", "spiffe-workload-api", "csi",
                                    Map.of("driver", "csi.spiffe.io", "readOnly", true)),
                            Map.of("name", "svid", "emptyDir", Map.of("medium", "Memory"))),
                    "initContainers", List.of(container("svid-init", image, audience, initScript)),
                    "containers", List.of(
                            Map.of("name", name, "volumeMounts", List.of(Map.of(
                                    "name", "svid", "mountPath", "/var/run/secrets/svid", "readOnly", true))),
                            container("svid-refresh", image, audience, refreshScript)));
            System.out.println(new ObjectMapper().writeValueAsString(
                    Map.of("spec", Map.of("template", Map.of("spec", pod)))));
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        } catch (JsonProcessingException e) {
            System.err.println("Could not serialize the SVID pod patch");
            System.exit(1);
        }
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static Map<String, Object> container(String name, String image, String audience, String script) {
        return Map.of(
                "name", name,
                "image", image,
                "command", List.of("sh", "-c", script),
                "env", List.of(Map.of("name", "AUDIENCE", "value", audience)),
                "resources", Map.of("requests", Map.of("cpu", "10m", "memory", "16Mi"),
                        "limits", Map.of("memory", "64Mi")),
                "securityContext", Map.of("allowPrivilegeEscalation", false,
                        "capabilities", Map.of("drop", List.of("ALL"))),
                "volumeMounts", List.of(
                        Map.of("name", "spiffe-workload-api", "mountPath", "/spiffe-workload-api", "readOnly", true),
                        Map.of("name", "svid", "mountPath", "/svid")));
    }
}
