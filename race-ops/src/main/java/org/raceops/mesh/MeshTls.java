package org.raceops.mesh;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.raceops.RaceOpsConfig;

import javax.net.ssl.SSLContext;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Makes the A2A clients speak mTLS to the specialists. The A2A SDK builds its own JDK
 * {@code HttpClient} and takes no TLS settings: a POST goes through a client the SDK creates
 * itself, which reads {@link SSLContext#getDefault()} when it is built. So when
 * {@code race-ops.mesh-tls.directory} names the directory of the SVID files, this installs the
 * {@link SvidSslContext} as the JVM default before the first A2A client exists. Unset (dev, the
 * default cluster profile), nothing changes.
 */
@ApplicationScoped
public class MeshTls {

    private static final Logger LOG = Logger.getLogger(MeshTls.class);

    private final RaceOpsConfig config;

    @Inject
    MeshTls(RaceOpsConfig config) {
        this.config = config;
    }

    void install(@Observes StartupEvent event) throws Exception {
        Optional<String> directory = config.meshTls().directory().filter(dir -> !dir.isBlank());
        if (directory.isEmpty()) {
            return;
        }
        SSLContext.setDefault(SvidSslContext.create(Path.of(directory.get())));
        LOG.infof("A2A calls present the SPIFFE SVID from %s", directory.get());
    }
}
