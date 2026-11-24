package ai.closepaw.taskhelper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Shared by the app and the standalone dex. Keep this file JVM-only. */
public final class TaskHelperProtocol {
    public static final int SHELL_UID = 2000;
    public static final String AUTHORITY_SUFFIX = ".taskhelper";
    public static final String REGISTER = "register";
    public static final String HELPER = "helper";
    public static final String PID = "pid";
    public static final String SESSION_SECRET = "sessionSecret";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern CHALLENGE = Pattern.compile("[0-9a-f]{64}");
    private static final String REGISTRATION_PREFIX = "v1:";

    public static String encodeTapRequest(int displayId, float x, float y) {
        if (displayId < 0 || !Float.isFinite(x) || !Float.isFinite(y)) throw new IllegalArgumentException("Invalid tap");
        return displayId + "\n" + x + "\n" + y;
    }
    public static final class TapRequest {
        public final int displayId;
        public final float x;
        public final float y;

        private TapRequest(int displayId, float x, float y) {
            this.displayId = displayId;
            this.x = x;
            this.y = y;
        }
    }

    public static TapRequest parseTapRequest(String value) {
        String[] fields = value.split("\n", -1);
        if (fields.length != 3) throw new IllegalArgumentException("Invalid tap request");
        int displayId = Integer.parseInt(fields[0]);
        float x = Float.parseFloat(fields[1]), y = Float.parseFloat(fields[2]);
        encodeTapRequest(displayId, x, y);
        return new TapRequest(displayId, x, y);
    }

    /** Primitive-only spike payload codec; nullable inject values mean not attempted. */
    public static String encodeTapResult(boolean reflectionOk, Boolean down, Boolean up, String error) {
        return reflectionOk + "\n" + down + "\n" + up + "\n" + (error == null ? "" : error);
    }
    public static String[] parseTapResult(String encoded) {
        String[] fields = encoded.split("\n", 4);
        if (fields.length != 4 || !isWireBoolean(fields[0])) throw new IllegalArgumentException("Invalid tap result");
        for (int i = 1; i < 3; i++) if (!isWireBoolean(fields[i]) && !"null".equals(fields[i]))
            throw new IllegalArgumentException("Invalid inject result");
        return fields;
    }

    private TaskHelperProtocol() {}

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
            throw new IllegalArgumentException("Invalid Task helper challenge");
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
            if (pid <= 0 || this.pid != 0) throw new IllegalStateException("Invalid Task helper launch PID");
            this.pid = pid;
        }

        public void enable() {
            if (pid <= 0) throw new IllegalStateException("Task helper launch PID is unknown");
            enabled = true;
        }

        public int pidToVerify() {
            return enabled ? pid : 0;
        }

        public boolean deathConfirmed(boolean pidAbsent) {
            return !enabled || pidAbsent;
        }
    }

    // ---- Root-task row codec (stage-2 Increment 1: QUERY_ROOT_TASKS) ----
    // A decimal row count, one line per row, then an END terminator, joined with "\n".
    // Each row is tab-separated:
    //   taskId \t topPackage \t basePackage \t packageSetCsv(sorted) \t visible \t pinned
    // "-" is the canonical token for a null package or visibility value.
    // The package set is sorted. Zero rows encode to "0\nEND".
    // An unframed or empty payload is malformed and cannot represent an empty snapshot.
    // Failed queries carry QUERY_NULL.

    public static String encodeRootTaskRow(int taskId, String topPackage, String basePackage,
                                           List<String> packages, Boolean visible,
                                           boolean pinned) {
        if (topPackage != null) requirePackageName(topPackage, "topPackage");
        if (basePackage != null) requirePackageName(basePackage, "basePackage");
        List<String> sorted = new ArrayList<>(packages);
        for (String name : sorted) requirePackageName(name, "packages");
        java.util.Collections.sort(sorted);
        StringBuilder out = new StringBuilder();
        out.append(taskId).append('\t');
        out.append(topPackage == null ? "-" : topPackage).append('\t');
        out.append(basePackage == null ? "-" : basePackage).append('\t');
        out.append(String.join(",", sorted)).append('\t');
        out.append(visible == null ? "-" : visible.toString()).append('\t');
        out.append(pinned);
        return out.toString();
    }

    public static String encodeRootTaskRows(List<String> encodedRows) {
        StringBuilder out = new StringBuilder();
        out.append(encodedRows.size());
        for (String row : encodedRows) {
            out.append('\n').append(row);
        }
        return out.append("\nEND").toString();
    }

    private static final String PACKAGE_NAME = "[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*";
    private static final Pattern REQUIRED_PACKAGE = Pattern.compile(PACKAGE_NAME);
    private static final Pattern OPTIONAL_PACKAGE =
            Pattern.compile("-|" + PACKAGE_NAME);
    private static final Pattern PACKAGE_LIST =
            Pattern.compile("(?:" + PACKAGE_NAME + "(?:," + PACKAGE_NAME + ")*)?");

    private static void requirePackageName(String name, String field) {
        if (name == null || !REQUIRED_PACKAGE.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid package name in " + field);
        }
    }

    /** Validates incoming root-task rows and returns their fields in wire order. */
    public static List<String[]> parseRootTaskRows(String encoded) {
        if (encoded == null) throw new IllegalArgumentException("Missing root-task rows");
        String[] lines = encoded.split("\n", -1);
        if (!lines[0].matches("0|[1-9][0-9]*")) {
            throw new IllegalArgumentException("Invalid root-task row count");
        }
        int count = Integer.parseInt(lines[0]);
        if (lines.length - 2 != count || !"END".equals(lines[lines.length - 1])) {
            throw new IllegalArgumentException("Malformed root-task row framing");
        }
        List<String[]> rows = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            String line = lines[i];
            String[] fields = line.split("\t", -1);
            if (fields.length != 6
                    || !OPTIONAL_PACKAGE.matcher(fields[1]).matches()
                    || !OPTIONAL_PACKAGE.matcher(fields[2]).matches()
                    || !PACKAGE_LIST.matcher(fields[3]).matches()
                    || !("-".equals(fields[4]) || isWireBoolean(fields[4]))
                    || !isWireBoolean(fields[5])) {
                throw new IllegalArgumentException("Malformed root-task row: " + line);
            }
            Integer.parseInt(fields[0]);
            rows.add(fields);
        }
        return rows;
    }

    private static boolean isWireBoolean(String value) {
        return "true".equals(value) || "false".equals(value);
    }
}
