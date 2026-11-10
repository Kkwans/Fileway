import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Properties;

/** Signs an update manifest with the existing TV APK release identity. */
public final class SignTvUpdateManifest {
    private static final String CERT_SHA256 = "ad35201bcbbcba94754447da9f6e7f369c2a4264bfd5e179194a3cd0b0a0c4a9";
    private static final byte[] DOMAIN = "Fileway-TV-Update/v1".getBytes(StandardCharsets.US_ASCII);

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Expected signing-properties, manifest, signature-output");
        Path propertiesPath = Path.of(args[0]);
        if (!propertiesPath.isAbsolute()) throw new IllegalArgumentException("Signing properties path must be absolute");
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(propertiesPath)) {
            properties.load(input);
        }
        String storeFile = properties.getProperty("storeFile", "");
        String alias = properties.getProperty("keyAlias", "");
        if (!Path.of(storeFile).isAbsolute() || alias.isBlank()) throw new IllegalArgumentException("Invalid TV signing configuration");
        char[] storePassword = properties.getProperty("storePassword", "").toCharArray();
        char[] keyPassword = properties.getProperty("keyPassword", "").toCharArray();
        if (storePassword.length == 0 || keyPassword.length == 0) throw new IllegalArgumentException("Incomplete TV signing configuration");
        try {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            try (InputStream input = Files.newInputStream(Path.of(storeFile))) {
                keyStore.load(input, storePassword);
            }
            if (!(keyStore.getCertificate(alias) instanceof X509Certificate certificate)) {
                throw new IllegalArgumentException("TV signing certificate is unavailable");
            }
            String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
            if (!CERT_SHA256.equals(fingerprint) || !"RSA".equalsIgnoreCase(certificate.getPublicKey().getAlgorithm())) {
                throw new IllegalArgumentException("TV signing certificate does not match the published identity");
            }
            if (!(keyStore.getKey(alias, keyPassword) instanceof PrivateKey privateKey)) {
                throw new IllegalArgumentException("TV signing key is unavailable");
            }
            Path manifestPath = Path.of(args[1]);
            long manifestSize = Files.size(manifestPath);
            if (manifestSize == 0 || manifestSize > 64 * 1024) throw new IllegalArgumentException("Invalid manifest size");
            byte[] manifest = Files.readAllBytes(manifestPath);
            if (manifest.length != manifestSize) throw new IllegalArgumentException("Manifest changed while signing");
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(privateKey);
            signer.update(DOMAIN);
            signer.update((byte) 0);
            signer.update(manifest);
            byte[] signature = signer.sign();
            if (signature.length == 0 || signature.length > 1024) throw new IllegalArgumentException("Invalid signature size");
            signer.initVerify(certificate.getPublicKey());
            signer.update(DOMAIN);
            signer.update((byte) 0);
            signer.update(manifest);
            if (!signer.verify(signature)) throw new IllegalStateException("TV manifest signature verification failed");
            Files.write(Path.of(args[2]), signature, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } finally {
            Arrays.fill(storePassword, '\0');
            Arrays.fill(keyPassword, '\0');
        }
    }
}
