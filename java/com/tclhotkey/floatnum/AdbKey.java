package com.tclhotkey.floatnum;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;

/**
 * 本机 adb 私钥 —— 每台设备第一次运行时现生成一把，存在应用私有目录里。
 *
 * ★ 为什么不在代码里写死一把固定私钥：
 *
 *   这把私钥是用来向电视自己的 adbd（127.0.0.1:5555）做认证的。
 *   而 adbd 一旦开了 TCP 监听（本机就是 service.adb.tcp.port=5555），
 *   它是**监听整个局域网**的，不只听 127.0.0.1。
 *
 *   如果私钥被写死在源码里、源码又公开了，那么任何能连上这台电视
 *   5555 端口的人，只要拿着这把公开的钥匙，就能直接拿到 shell —
 *   等于把用户的电视交出去。
 *
 *   所以：每台设备用自己独立的一把，私钥只落在 /data/data/本应用/files/ 下，
 *   别的应用读不到（Android 的沙箱机制），也不会跟着源码一起发出去。
 *
 * ★ 权限提示
 *   本文件写入的是应用私有目录，不需要任何存储权限。
 *
 * ★ 想重新配对（换了电视、或者怀疑钥匙泄露了）
 *   把 files/adbkey.pk8 删掉即可 —— 下次会自动生成新的一把，
 *   电视上会重新弹一次「允许 USB 调试」，点允许就行。
 *   旧的那把还留在电视的 adb_keys 里，不影响使用，也可以自行清理。
 */
public final class AdbKey {

    private static final String FILE_NAME = "adbkey.pk8";
    private static final int KEY_BITS = 2048;

    /**
     * 2048 位 RSA 私钥的 PKCS#8 DER 大约 1.2KB。
     * 给一个上下限，写坏的文件（截断的、被塞进垃圾的）当场判死重新生成，
     * 而不是拿半截 DER 去签名然后报一堆看不懂的异常。
     */
    private static final int MIN_DER = 512;
    private static final int MAX_DER = 4096;

    /** 进程内缓存，避免每次都读盘 / 重新生成 */
    private static byte[] sCached;

    private AdbKey() {
    }

    /**
     * 拿到本机的 adb 私钥（PKCS#8 DER 格式）。
     * 已有就用已有的，没有就现生成一把并落盘。
     */
    public static synchronized byte[] get(Context c) throws IOException {
        if (sCached != null) {
            return sCached;
        }
        File f = new File(c.getFilesDir(), FILE_NAME);
        if (f.exists()) {
            byte[] der = (f.length() >= MIN_DER && f.length() <= MAX_DER) ? readAll(f) : null;
            if (der != null && der.length >= MIN_DER) {
                sCached = der;
                return der;
            }
            // 文件损坏或被塞了别的东西，当没有处理，下面重新生成
            f.delete();
        }
        byte[] der = generate();
        writeAll(f, der);
        sCached = der;
        return der;
    }

    // ---------------- 内部 ----------------

    private static byte[] generate() throws IOException {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(KEY_BITS);
            KeyPair kp = g.generateKeyPair();
            // RSA 私钥的 getEncoded() 返回的就是 PKCS#8 DER，正是 adbd 要的格式
            return kp.getPrivate().getEncoded();
        } catch (Exception e) {
            throw new IOException("生成 RSA 私钥失败：" + e, e);
        }
    }

    private static byte[] readAll(File f) throws IOException {
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream((int) f.length());
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignore) {
                }
            }
        }
    }

    /**
     * 先写临时文件再改名。
     *
     * 直接往目标文件写的话，写到一半掉电/被杀会留下半截 DER ——
     * 而半截文件长度不为 0，看起来是有效的，下次启动就会拿它去签名然后莫名失败。
     * rename 在同一目录下是原子的，要么整个换过去，要么还是旧文件。
     */
    private static void writeAll(File f, byte[] data) throws IOException {
        File tmp = new File(f.getParentFile(), FILE_NAME + ".tmp");
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(tmp);
            out.write(data);
            out.flush();
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignore) {
                }
            }
        }
        if (!tmp.renameTo(f)) {
            // 极端情况下 rename 会失败，退回直接写
            FileOutputStream out2 = new FileOutputStream(f);
            try {
                out2.write(data);
                out2.flush();
            } finally {
                out2.close();
            }
            tmp.delete();
        }
    }
}
