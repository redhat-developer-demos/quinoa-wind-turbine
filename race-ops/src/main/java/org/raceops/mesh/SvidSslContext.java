package org.raceops.mesh;

import org.jboss.logging.Logger;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509ExtendedTrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * An {@link SSLContext} whose client certificate and extra trust anchors are the SPIFFE SVID files
 * a sidecar keeps in a directory ({@code svid.pem}, {@code svid.key}, {@code bundle.pem}). SPIRE
 * rotates the X.509 SVID about every half hour, and a JVM-wide context built once would hold the
 * old one, so both managers reread the files when they change (checked at most every 10 s). A
 * half-written set of files keeps the previous material until the next check.
 *
 * <p>Trust is the JVM's own anchors plus the SPIFFE bundle, so the same context still reaches
 * the game and Keycloak through their public certificates.
 */
final class SvidSslContext {

    private static final Logger LOG = Logger.getLogger(SvidSslContext.class);
    private static final long CHECK_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final char[] NO_PASSWORD = new char[0];

    private final Path cert;
    private final Path key;
    private final Path bundle;
    private Material material;
    private long checkedAt = System.nanoTime();

    private SvidSslContext(Path dir) throws IOException, GeneralSecurityException {
        this.cert = dir.resolve("svid.pem");
        this.key = dir.resolve("svid.key");
        this.bundle = dir.resolve("bundle.pem");
        this.material = load();
    }

    static SSLContext create(Path dir) throws IOException, GeneralSecurityException {
        SvidSslContext svid = new SvidSslContext(dir);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(new KeyManager[] {svid.new Keys()},
            new TrustManager[] {svid.new Trust(jvmTrustManager())}, null);
        return context;
    }

    private static X509ExtendedTrustManager jvmTrustManager() throws GeneralSecurityException {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init((KeyStore) null);
        for (TrustManager manager : factory.getTrustManagers()) {
            if (manager instanceof X509ExtendedTrustManager extended) {
                return extended;
            }
        }
        throw new GeneralSecurityException("The JVM has no X509ExtendedTrustManager");
    }

    private record Material(List<FileTime> stamp, X509ExtendedKeyManager keys, X509ExtendedTrustManager trust) {
    }

    private synchronized Material current() {
        long now = System.nanoTime();
        if (now - checkedAt > CHECK_NANOS) {
            checkedAt = now;
            try {
                if (!stamp().equals(material.stamp())) {
                    material = load();
                    LOG.info("Reloaded the SPIFFE SVID");
                }
            } catch (IOException | GeneralSecurityException | RuntimeException e) {
                LOG.warnf("Keeping the previous SPIFFE SVID, the new files do not load yet: %s", e.getMessage());
            }
        }
        return material;
    }

    private List<FileTime> stamp() throws IOException {
        return List.of(Files.getLastModifiedTime(cert), Files.getLastModifiedTime(key),
            Files.getLastModifiedTime(bundle));
    }

    private Material load() throws IOException, GeneralSecurityException {
        List<FileTime> stamp = stamp();
        CertificateFactory certificates = CertificateFactory.getInstance("X.509");

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        try (InputStream in = Files.newInputStream(cert)) {
            keyStore.setKeyEntry("svid", privateKey(Files.readString(key)), NO_PASSWORD,
                certificates.generateCertificates(in).toArray(new Certificate[0]));
        }
        KeyManagerFactory keyFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyFactory.init(keyStore, NO_PASSWORD);

        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        trustStore.load(null, null);
        try (InputStream in = Files.newInputStream(bundle)) {
            int index = 0;
            for (Certificate anchor : certificates.generateCertificates(in)) {
                trustStore.setCertificateEntry("bundle-" + index++, anchor);
            }
        }
        TrustManagerFactory trustFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustFactory.init(trustStore);

        return new Material(stamp, first(keyFactory.getKeyManagers(), X509ExtendedKeyManager.class),
            first(trustFactory.getTrustManagers(), X509ExtendedTrustManager.class));
    }

    private static <T> T first(Object[] managers, Class<T> type) throws GeneralSecurityException {
        for (Object manager : managers) {
            if (type.isInstance(manager)) {
                return type.cast(manager);
            }
        }
        throw new GeneralSecurityException("No " + type.getSimpleName() + " for the SVID");
    }

    /** SPIRE writes the key as unencrypted PKCS#8 PEM; EC P-256 by default, RSA if configured. */
    private static PrivateKey privateKey(String pem) throws GeneralSecurityException {
        byte[] der = Base64.getMimeDecoder().decode(pem.replaceAll("-----[A-Z ]+-----", ""));
        for (String algorithm : List.of("EC", "RSA")) {
            try {
                return KeyFactory.getInstance(algorithm).generatePrivate(new PKCS8EncodedKeySpec(der));
            } catch (InvalidKeySpecException e) {
                // try the next algorithm
            }
        }
        throw new InvalidKeySpecException("The SVID key is neither EC nor RSA PKCS#8");
    }

    /** Presents the current SVID as the client certificate. */
    private final class Keys extends X509ExtendedKeyManager {
        @Override
        public String[] getClientAliases(String keyType, Principal[] issuers) {
            return current().keys().getClientAliases(keyType, issuers);
        }

        @Override
        public String chooseClientAlias(String[] keyTypes, Principal[] issuers, Socket socket) {
            return current().keys().chooseClientAlias(keyTypes, issuers, socket);
        }

        @Override
        public String chooseEngineClientAlias(String[] keyTypes, Principal[] issuers, SSLEngine engine) {
            return current().keys().chooseEngineClientAlias(keyTypes, issuers, engine);
        }

        @Override
        public String[] getServerAliases(String keyType, Principal[] issuers) {
            return current().keys().getServerAliases(keyType, issuers);
        }

        @Override
        public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
            return current().keys().chooseServerAlias(keyType, issuers, socket);
        }

        @Override
        public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine) {
            return current().keys().chooseEngineServerAlias(keyType, issuers, engine);
        }

        @Override
        public X509Certificate[] getCertificateChain(String alias) {
            return current().keys().getCertificateChain(alias);
        }

        @Override
        public PrivateKey getPrivateKey(String alias) {
            return current().keys().getPrivateKey(alias);
        }
    }

    /** A server is trusted if the JVM's anchors or the SPIFFE bundle vouch for it. */
    private final class Trust extends X509ExtendedTrustManager {
        private final X509ExtendedTrustManager jvm;

        private Trust(X509ExtendedTrustManager jvm) {
            this.jvm = jvm;
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            try {
                jvm.checkServerTrusted(chain, authType, socket);
            } catch (CertificateException e) {
                current().trust().checkServerTrusted(chain, authType, socket);
            }
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            try {
                jvm.checkServerTrusted(chain, authType, engine);
            } catch (CertificateException e) {
                current().trust().checkServerTrusted(chain, authType, engine);
            }
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            try {
                jvm.checkServerTrusted(chain, authType);
            } catch (CertificateException e) {
                current().trust().checkServerTrusted(chain, authType);
            }
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            current().trust().checkClientTrusted(chain, authType, socket);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            current().trust().checkClientTrusted(chain, authType, engine);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            current().trust().checkClientTrusted(chain, authType);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return Stream.concat(Arrays.stream(jvm.getAcceptedIssuers()),
                Arrays.stream(current().trust().getAcceptedIssuers())).toArray(X509Certificate[]::new);
        }
    }
}
