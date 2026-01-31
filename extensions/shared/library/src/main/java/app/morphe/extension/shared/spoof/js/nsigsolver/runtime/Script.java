package app.morphe.extension.shared.spoof.js.nsigsolver.runtime;

import androidx.annotation.NonNull;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class Script {
    private final ScriptType type;
    private final ScriptVariant variant;
    private final ScriptSource source;
    private final String version;
    private final String code;
    private String cachedHash;

    public Script(ScriptType type, ScriptVariant variant, ScriptSource source, String version, String code) {
        this.type = type;
        this.variant = variant;
        this.source = source;
        this.version = version;
        this.code = code;
    }

    public ScriptType getType() {
        return type;
    }

    public ScriptVariant getVariant() {
        return variant;
    }

    public ScriptSource getSource() {
        return source;
    }

    public String getVersion() {
        return version;
    }

    public String getCode() {
        return code;
    }

    public String getHash() {
        if (cachedHash == null) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA3-512");
                byte[] bytes = digest.digest(code.getBytes(StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                for (byte b : bytes) {
                    sb.append(String.format("%02x", b));
                }
                cachedHash = sb.toString();
            } catch (NoSuchAlgorithmException e) {
                cachedHash = "";
            }
        }
        return cachedHash;
    }

    @NonNull
    @Override
    public String toString() {
        String hash = getHash();
        String shortHash = hash.length() > 7 ? hash.substring(0, 7) : hash;
        return "<Script " + type.getValue() + " v" + version + " (source: " + source.getValue() + ") variant=" + variant.getValue() + " size=" + code.length() + " hash=" + shortHash + "...>";
    }
}
