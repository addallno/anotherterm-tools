package green_green_avk.anotherterm.termtools;

import android.content.Context;
import android.content.res.AssetManager;
import android.text.TextUtils;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Termux 风格环境：
 * 目录结构（$PREFIX/$HOME/$TMPDIR）、内置工具安装、shell 探测、Dropbear SSH。
 */
public final class TermEnv {

    private static final String TAG = "TermEnv";

    public static final String[] SHELL_CANDIDATES = {
            "/bin/fish",
            "/bin/bash",
            "/bin/zsh",
            "/bin/sh",
    };

    private static File prefixDir; // $PREFIX = filesDir
    private static File usrDir;    // $PREFIX/usr
    private static File etcDir;
    private static File tmpDir;    // $TMPDIR
    private static File homeDir;   // $HOME
    private static boolean ready = false;

    private TermEnv() {
    }

    public static synchronized void init(final Context ctx) {
        if (ready) return;
        final File files = ctx.getFilesDir();
        prefixDir = files;
        usrDir = new File(files, "usr");
        etcDir = new File(usrDir, "etc");
        tmpDir = new File(usrDir, "tmp");
        homeDir = new File(files, "home");

        mkdir(usrDir);
        mkdir(etcDir);
        mkdir(new File(usrDir, "lib"));
        mkdir(tmpDir);
        mkdir(homeDir);
        mkdir(new File(homeDir, ".ssh"));

        try {
            installTools(ctx.getAssets());
            installSkeleton(ctx.getAssets());
        } catch (final Throwable t) {
            Log.e(TAG, "tool install failed", t);
        }

        ready = true;
        startDropbear(usrDir, etcDir);
        Log.i(TAG, "init done prefix=" + prefixDir);
    }

    private static void mkdir(final File d) {
        if (!d.exists() && !d.mkdirs()) {
            Log.w(TAG, "mkdir failed: " + d);
        }
        d.setReadable(true, false);
        d.setExecutable(true, false);
    }

    public static File getPrefixDir() {
        return prefixDir;
    }

    public static File getUsrDir() {
        return usrDir;
    }

    public static File getTmpDir() {
        return tmpDir;
    }

    public static File getHomeDir() {
        return homeDir;
    }

    /**
     * Termux 风格 PATH。
     */
    public static String buildPATH() {
        final StringBuilder sb = new StringBuilder();
        sb.append(new File(usrDir, "bin").getAbsolutePath());
        final String homePath = homeDir.getAbsolutePath();
        sb.append(File.pathSeparator).append(homePath).append("/bin");
        sb.append(File.pathSeparator).append(homePath).append("/tools/bin");
        final String sysPath = System.getenv("PATH");
        if (!TextUtils.isEmpty(sysPath))
            sb.append(File.pathSeparator).append(sysPath);
        return sb.toString();
    }

    /**
     * shell 优先级 fish > bash > zsh > sh > /system/bin/sh。
     * 返回可执行文件绝对路径。
     */
    public static String detectShell() {
        for (final String rel : SHELL_CANDIDATES) {
            final File f = new File(usrDir, rel);
            if (f.canExecute() && f.length() > 0) {
                return f.getAbsolutePath();
            }
        }
        return "/system/bin/sh";
    }

    /**
     * assets 安装到 $PREFIX/usr/bin。0 字节目标视为损坏重装。
     */
    public static boolean installTools(final AssetManager am) {
        final File binDir = new File(usrDir, "bin");
        mkdir(binDir);
        try {
            final String[] tools = am.list("tools/usr/bin");
            if (tools == null) return false;
            boolean allOk = true;
            for (final String tool : tools) {
                final File target = new File(binDir, tool);
                if (target.exists() && target.length() > 0) continue;
                if (target.exists()) target.delete();
                if (!copyAsset(am, "tools/usr/bin/" + tool, target)) {
                    allOk = false;
                    continue;
                }
                target.setReadable(true, false);
                target.setExecutable(true, false);
            }
            return allOk;
        } catch (final IOException e) {
            Log.e(TAG, "installTools failed", e);
        }
        return false;
    }

    /**
     * assets/skel/* -> $HOME/.<name>
     */
    private static void installSkeleton(final AssetManager am) {
        try {
            final String[] list = am.list("skel");
            if (list == null) return;
            for (final String item : list) {
                final File target = new File(homeDir, "." + item);
                if (target.exists() && target.length() > 0) continue;
                if (copyAsset(am, "skel/" + item, target)) {
                    target.setReadable(true, true);
                }
            }
        } catch (final IOException ignored) {
        }
    }

    private static boolean copyAsset(final AssetManager am, final String asset,
                                     final File target) {
        try (InputStream is = am.open(asset, AssetManager.ACCESS_STREAMING);
             OutputStream os = new FileOutputStream(target)) {
            final byte[] buf = new byte[32 * 1024];
            int len;
            while ((len = is.read(buf)) > 0) {
                os.write(buf, 0, len);
            }
            return true;
        } catch (final IOException e) {
            Log.e(TAG, "copyAsset " + asset + " failed", e);
        }
        return false;
    }

    /**
     * 后台生成 host key 并启动 Dropbear SSH（端口 8022）。
     */
    private static void startDropbear(final File usr, final File etc) {
        final ExecutorService es = Executors.newSingleThreadExecutor();
        es.execute(() -> {
            try {
                final File dropbear = new File(usr, "bin/dropbear");
                if (!dropbear.canExecute()) {
                    Log.w(TAG, "dropbear not found/executable");
                    return;
                }
                final File dropbearkey = new File(usr, "bin/dropbearkey");
                final String[] keyTypes = {"rsa", "ecdsa", "ed25519"};
                for (final String type : keyTypes) {
                    final File key = new File(etc, "dropbear_" + type + "_host_key");
                    if (key.exists()) continue;
                    if (!dropbearkey.canExecute()) break;
                    final Process p = Runtime.getRuntime().exec(new String[]{
                            dropbearkey.getAbsolutePath(), "-t", type,
                            "-f", key.getAbsolutePath()
                    });
                    p.waitFor();
                }
                Runtime.getRuntime().exec(new String[]{
                        dropbear.getAbsolutePath(),
                        "-p", "0.0.0.0:8022",
                        "-r", etc.getAbsolutePath(),
                        "-E",
                        "-F"
                });
                Log.i(TAG, "dropbear started on 8022");
            } catch (final Exception e) {
                Log.e(TAG, "dropbear start failed", e);
            }
        });
    }
}
