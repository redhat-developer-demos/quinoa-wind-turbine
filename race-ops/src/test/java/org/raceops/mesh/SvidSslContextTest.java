package org.raceops.mesh;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Mutual TLS between two {@link SvidSslContext}s, the way the director and a specialist meet:
 * each side has the SVID files in its own directory, both trust the same CA. A caller whose
 * certificate comes from another CA never gets through the handshake. The fixtures in
 * {@code src/test/resources/svid} are ECDSA certificates valid for a century, not secrets.
 */
class SvidSslContextTest {

    private HttpsServer server;
    private Path serverDir;
    @TempDir
    Path tempDir;

    @BeforeEach
    void startServer() throws Exception {
        serverDir = copy("server");
        SSLContext context = SvidSslContext.create(serverDir);
        server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(context) {
            @Override
            public void configure(HttpsParameters params) {
                SSLParameters parameters = getSSLContext().getDefaultSSLParameters();
                parameters.setNeedClientAuth(true);
                params.setSSLParameters(parameters);
            }
        });
        server.createContext("/", exchange -> {
            byte[] body = "ok".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void aCallerWithAnSvidFromTheSameCaGetsThrough() throws Exception {
        HttpResponse<String> response = call("client");
        assertEquals(200, response.statusCode());
        assertEquals("ok", response.body());
    }

    @Test
    void aCallerWithAnSvidFromAnotherCaIsRejectedAtTheHandshake() throws Exception {
        assertThrows(IOException.class, () -> call("stranger"));
    }

    private HttpResponse<String> call(String clientFixture) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .sslContext(SvidSslContext.create(copy(clientFixture))).build();
        URI uri = URI.create("https://localhost:" + server.getAddress().getPort() + "/");
        return client.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString());
    }

    private Path copy(String fixture) throws IOException {
        Path dir = Files.createDirectory(tempDir.resolve(fixture));
        for (String file : new String[] {"svid.pem", "svid.key", "bundle.pem"}) {
            try (var in = SvidSslContextTest.class.getResourceAsStream("/svid/" + fixture + "/" + file)) {
                Files.copy(in, dir.resolve(file), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return dir;
    }
}
