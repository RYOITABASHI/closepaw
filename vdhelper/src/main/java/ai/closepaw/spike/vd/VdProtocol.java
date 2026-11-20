package ai.closepaw.spike.vd;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Shared by the app and the standalone dex. Keep this file JVM-only. */
public final class VdProtocol {
    public static final int SHELL_UID = 2000;
    public static final String AUTHORITY_SUFFIX = ".spike.vd";
    public static final String REGISTER = "register";
    public static final String HELPER = "helper";
    public static final String PID = "pid";
    public static final String SESSION_SECRET = "sessionSecret";
    public static final int REQUIRED_FLAGS = 0xE49;
    private static final int FOCUS_FLAGS = (1 << 14) | (1 << 16);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern CHALLENGE = Pattern.compile("[0-9a-f]{64}");
    private static final String REGISTRATION_PREFIX = "v1:";

    private VdProtocol() {}

    public static String newChallenge() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        char[] hex = "0123456789abcdef".toCharArray();
        StringBuilder result = new StringBuilder(64);
        for (byte value : bytes) {
            result.append(hex[(value & 0xff) >>> 4]);
            result.append(hex[value & 0xf]);
        }
        return result.toString();
    }

    public static String encodeRegistration(String challenge) {
        if (!CHALLENGE.matcher(challenge).matches()) {
            throw new IllegalArgumentException("Invalid VD challenge");
        }
        return REGISTRATION_PREFIX + challenge;
    }

    public static String decodeRegistration(String encoded) {
        if (encoded == null || encoded.length() != REGISTRATION_PREFIX.length() + 64
                || !encoded.startsWith(REGISTRATION_PREFIX)) {
            return null;
        }
        String challenge = encoded.substring(REGISTRATION_PREFIX.length());
        return CHALLENGE.matcher(challenge).matches() ? challenge : null;
    }

    public static final class Registration {
        private final byte[] expected;
        private boolean consumed;

        public Registration(String challenge) {
            expected = encodeRegistration(challenge).getBytes(StandardCharsets.US_ASCII);
        }

        public synchronized boolean claim(int uid, int launchPid, int claimedPid, int callerPid,
                                          String encoded, String secret) {
            if (uid != SHELL_UID || launchPid <= 0 || claimedPid != launchPid
                    || callerPid != launchPid || consumed || decodeRegistration(encoded) == null
                    || secret == null || !CHALLENGE.matcher(secret).matches()
                    || encodeRegistration(secret).equals(encoded)
                    || !MessageDigest.isEqual(expected, encoded.getBytes(StandardCharsets.US_ASCII))) {
                return false;
            }
            consumed = true;
            return true;
        }
    }

    public static final class Peer {
        private final int uid;
        private final int pid;
        private final byte[] secret;

        public Peer(int uid, int pid, String secret) {
            this.uid = uid;
            this.pid = pid;
            this.secret = secret.getBytes(StandardCharsets.US_ASCII);
        }

        public boolean accepts(int callerUid, int callerPid, String presented) {
            return callerUid == uid && (callerPid == 0 || callerPid == pid)
                    && presented != null && CHALLENGE.matcher(presented).matches()
                    && MessageDigest.isEqual(secret, presented.getBytes(StandardCharsets.US_ASCII));
        }
    }

    // The staging shell can only exec the helper after the app has recorded its PID.
    public static final class LaunchGuard {
        private int pid;
        private boolean enabled;

        public void recordPid(int pid) {
            if (pid <= 0 || this.pid != 0) throw new IllegalStateException("Invalid VD launch PID");
            this.pid = pid;
        }

        public void enable() {
            if (pid <= 0) throw new IllegalStateException("VD launch PID is unknown");
            enabled = true;
        }

        public int pidToVerify() {
            return enabled ? pid : 0;
        }

        public boolean deathConfirmed(boolean pidAbsent) {
            return !enabled || pidAbsent;
        }
    }

    public static int preferredFlags(int sdk) {
        return sdk >= 34 ? REQUIRED_FLAGS | FOCUS_FLAGS : REQUIRED_FLAGS;
    }

    public static List<String> flagNames(int flags) {
        if (flags != REQUIRED_FLAGS && flags != (REQUIRED_FLAGS | FOCUS_FLAGS)) {
            throw new IllegalArgumentException("Unexpected VD flag mask: " + flags);
        }
        List<String> names = new ArrayList<>();
        names.add("PUBLIC");
        names.add("SECURE");
        names.add("SUPPORTS_TOUCH");
        names.add("SHOULD_SHOW_SYSTEM_DECORATIONS");
        names.add("TRUSTED");
        names.add("OWN_DISPLAY_GROUP");
        if ((flags & FOCUS_FLAGS) != 0) {
            names.add("OWN_FOCUS");
            names.add("STEAL_TOP_FOCUS_DISABLED");
        }
        return names;
    }
}
