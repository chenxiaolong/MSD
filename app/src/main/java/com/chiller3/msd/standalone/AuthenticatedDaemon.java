/* SPDX-License-Identifier: GPL-3.0-only */
package com.chiller3.msd.standalone;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Process;
import android.util.Log;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;

/** Root supervisor. Only the private child pipes carry authentication queries. */
public final class AuthenticatedDaemon {
    private static final String TAG = "MSDAuth";
    private static final String PACKAGE = "com.chiller3.msd";
    private static final int PER_USER_RANGE = 100000;
    private final Method checkService;
    private final Method asPackageManager;
    private final Method packagesForUid;
    private final Method packageInfo;
    private final boolean longFlags;
    private final String certificate;

    AuthenticatedDaemon(String certificate) throws Exception {
        if (!certificate.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid SHA-256 certificate pin");
        }
        this.certificate = certificate;
        checkService = Class.forName("android.os.ServiceManager")
                .getMethod("checkService", String.class);
        asPackageManager = Class.forName("android.content.pm.IPackageManager$Stub")
                .getMethod("asInterface", android.os.IBinder.class);
        Class<?> ipm = Class.forName("android.content.pm.IPackageManager");
        packagesForUid = ipm.getMethod("getPackagesForUid", int.class);
        Method info;
        boolean wide;
        try {
            info = ipm.getMethod("getPackageInfo", String.class, long.class, int.class);
            wide = true;
        } catch (NoSuchMethodException e) {
            info = ipm.getMethod("getPackageInfo", String.class, int.class, int.class);
            wide = false;
        }
        packageInfo = info;
        longFlags = wide;
    }

    boolean authorize(int uid) throws Exception {
        // Reject system/isolated UIDs and shared-UID installations. Match the
        // complete UID, not just its appId; query the actual Android user.
        int appId = uid % PER_USER_RANGE;
        if (uid < 0 || appId < 10000 || appId > 19999) return false;
        // Look up the service at query time: late-start can precede Package
        // Manager readiness, and a dead Binder must never become a cached allow.
        Object binder = checkService.invoke(null, "package");
        if (binder == null) return false;
        Object packageManager = asPackageManager.invoke(null, binder);
        String[] packages = (String[]) packagesForUid.invoke(packageManager, uid);
        if (packages == null || packages.length != 1 || !PACKAGE.equals(packages[0])) return false;
        Object flags;
        if (longFlags) flags = Long.valueOf(PackageManager.GET_SIGNING_CERTIFICATES);
        else flags = Integer.valueOf(PackageManager.GET_SIGNING_CERTIFICATES);
        PackageInfo info = (PackageInfo) packageInfo.invoke(
                packageManager, PACKAGE, flags, uid / PER_USER_RANGE);
        if (info == null || !PACKAGE.equals(info.packageName) || info.applicationInfo == null
                || info.applicationInfo.uid != uid || info.sharedUserId != null
                || info.signingInfo == null || info.signingInfo.hasMultipleSigners()) return false;
        Signature[] signers = info.signingInfo.getApkContentsSigners();
        if (signers == null || signers.length != 1) return false;
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(signers[0].toByteArray());
        StringBuilder hex = new StringBuilder(64);
        for (byte b : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
        return certificate.contentEquals(hex);
    }

    public static void main(String[] args) throws Exception {
        if (Process.myUid() != 0) throw new SecurityException("Supervisor must start as root");
        if (args.length != 2) throw new IllegalArgumentException("Expected daemon path and certificate file");
        String pin = new String(Files.readAllBytes(Paths.get(args[1])), StandardCharsets.US_ASCII).trim();
        AuthenticatedDaemon auth = new AuthenticatedDaemon(pin);
        // A check-only invocation is useful for device-side negative tests; it
        // exercises precisely the same verification used for daemon requests.
        if (args[0].startsWith("check:")) {
            boolean allowed = auth.authorize(Integer.parseInt(args[0].substring(6)));
            System.out.println(allowed ? "ALLOW" : "DENY");
            System.exit(allowed ? 0 : 1);
        }
        java.lang.Process child = new ProcessBuilder(
                "/system/bin/runcon", "u:r:msd_daemon:s0", args[0], "daemon",
                "--log-target", "logcat", "--log-level", "debug")
                .redirectError(java.lang.ProcessBuilder.Redirect.INHERIT).start();
        Runtime.getRuntime().addShutdownHook(new Thread(child::destroy));
        try (DataInputStream requests = new DataInputStream(child.getInputStream());
             DataOutputStream responses = new DataOutputStream(child.getOutputStream())) {
            while (true) {
                int uid;
                try { uid = requests.readInt(); } catch (EOFException e) { break; }
                boolean allowed = false;
                try { allowed = auth.authorize(uid); }
                catch (Exception e) { Log.e(TAG, "Package verification failed for UID " + uid, e); }
                if (!allowed) Log.w(TAG, "Rejected UID " + uid);
                responses.writeByte(allowed ? 1 : 0);
                responses.flush();
            }
        } finally {
            child.destroy();
        }
        throw new IllegalStateException("MSD daemon exited");
    }
}
