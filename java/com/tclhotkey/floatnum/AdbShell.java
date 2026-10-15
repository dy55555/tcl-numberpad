package com.tclhotkey.floatnum;

import android.os.SystemClock;
import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;

/**
 * 本机 adb 客户端：连电视自己的 adbd（127.0.0.1:5555），认证后执行 shell 命令。
 *
 * 这是"数字键能被任何直播 app 收到"的关键——shell 权限有 INJECT_EVENTS，
 * 可以真正注入按键，而不是走某个 app 的私有接口。
 *
 * 两个踩坑点（都是实测出来的）：
 *   1. 签名不是"对 token 做 SHA1"，而是把 20 字节 token 直接当成 SHA-1 摘要，
 *      前面拼 SHA-1 的 DigestInfo 头，再做 PKCS#1 v1.5 填充。
 *      用 Signature.getInstance("NONEwithRSA") 正好就是这个语义。
 *   2. 认证是强制的，跳过直接 OPEN 会被 adbd 断连。
 */
public final class AdbShell {

    /** 应用上下文：只在签名时用来取本机的 adb 私钥（见 AdbKey） */
    private static Context sCtx;

    /** 由 Activity 在启动时注入，之后 signToken() 才能拿到本机私钥 */
    public static void setContext(Context c) {
        sCtx = c.getApplicationContext();
    }

    private static final int CNXN = 0x4e584e43;
    private static final int AUTH = 0x48545541;
    private static final int OPEN = 0x4e45504f;
    private static final int OKAY = 0x59414b4f;
    private static final int CLSE = 0x45534c43;
    private static final int WRTE = 0x45545257;

    private static final int AUTH_TOKEN = 1;
    private static final int AUTH_SIGNATURE = 2;

    /** 单条报文载荷的上限。超过就当成畸形包断开，不给它分配内存。 */
    private static final int MAX_PAYLOAD = 1 << 20;

    /** SHA-1 的 DigestInfo 前缀：30 21 30 09 06 05 2b0e03021a 05 00 04 14 */
    private static final byte[] DER_SHA1 = new byte[] {
            (byte) 0x30, (byte) 0x21, (byte) 0x30, (byte) 0x09, (byte) 0x06, (byte) 0x05,
            (byte) 0x2b, (byte) 0x0e, (byte) 0x03, (byte) 0x02, (byte) 0x1a, (byte) 0x05,
            (byte) 0x00, (byte) 0x04, (byte) 0x14
    };

    /** 本应用包名。助手用它判断"焦点是不是还在悬浮键盘上" */
    private static final String OWN_PKG = "com.tclhotkey.floatnum";

    private static final int[] PORTS = { 5555 };

    /** 候选地址：优先本机回环，不行再试电视自己的局域网 IP */
    private static String[] candidateHosts() {
        java.util.List<String> out = new java.util.ArrayList<String>();
        out.add("127.0.0.1");
        try {
            java.util.Enumeration<java.net.NetworkInterface> it =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (it.hasMoreElements()) {
                java.util.Enumeration<java.net.InetAddress> addrs = it.nextElement().getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress a = addrs.nextElement();
                    if (a instanceof java.net.Inet4Address && !a.isLoopbackAddress()) {
                        String h = a.getHostAddress();
                        if (h != null && !out.contains(h)) {
                            out.add(h);
                        }
                    }
                }
            }
        } catch (Throwable ignore) {
        }
        return out.toArray(new String[0]);
    }

    // ---------------- 对外接口 ----------------

    /** 本应用 APK 的路径，拉起常驻助手时要把它拷给 shell 当 classpath */
    private static String sApkPath = null;

    public static void setApkPath(String p) {
        sApkPath = p;
    }

    /** 把一串数字作为真实按键注入（0-9 -> KEYCODE_0..KEYCODE_9 = 7..16） */
    public static void sendDigits(String digits) throws Exception {
        StringBuilder codes = new StringBuilder();
        int n = 0;
        for (int i = 0; i < digits.length(); i++) {
            char c = digits.charAt(i);
            if (c < '0' || c > '9') {
                continue;
            }
            codes.append(' ').append(c - '0' + 7);
            n++;
        }
        if (n == 0) {
            return;
        }

        // KEYW：让助手先确认焦点已经离开本应用（回到直播 app）再注入。
        // 悬浮键盘是拿焦点的，它还在前台时注入的数字会打进自己。
        String cmd = "KEYW " + OWN_PKG + codes.toString();

        // 1) 首选常驻助手：实测约 30ms。
        //    注意只认带了 v2 版本标记的回包——老版本助手的注入是坏的（报成功但不生效），
        //    必须当成"不可用"处理，否则会把有效的 input 兜底路径给屏蔽掉。
        if (helperAlive() && helperCmd(cmd)) {
            return;
        }
        // 2) 助手不在跑（或版本不对），用 adb（shell 权限）重启它再试
        startHelper();
        if (helperCmd(cmd)) {
            return;
        }
        // 3) 都不行，退回 input 命令（每条要启动一个虚拟机，约 1.3 秒，但一定生效）
        StringBuilder sb = new StringBuilder("input keyevent");
        for (int i = 0; i < digits.length(); i++) {
            char c = digits.charAt(i);
            if (c < '0' || c > '9') {
                continue;
            }
            sb.append(' ').append(c - '0' + 7);
        }
        exec("shell:" + sb.toString());
    }

    /**
     * 给常驻助手发一条命令，成功返回 true。
     *
     * 只认回包带 v2 版本标记的：老版本助手的注入是坏的（报成功但不生效），
     * 必须当成不可用，否则会把有效的 input 兜底路径给屏蔽掉。
     */
    private static boolean helperCmd(String cmd) {
        String reply = helperTalk(cmd);
        return reply != null && reply.startsWith("OK") && reply.contains("v2");
    }

    /** 助手在跑且是新版（回包带 v2）才返回 true */
    private static boolean helperAlive() {
        return helperCmd("PING");
    }

    /** 和常驻助手说一句话，返回它的回包；连不上返回 null */
    private static String helperTalk(String cmd) {
        Socket s = null;
        try {
            s = new Socket();
            s.connect(new InetSocketAddress("127.0.0.1", KeyInject.PORT), 500);
            s.setSoTimeout(2000);
            s.getOutputStream().write((cmd + "\n").getBytes("UTF-8"));
            s.getOutputStream().flush();
            byte[] buf = new byte[64];
            int r = s.getInputStream().read(buf);
            String reply = r > 0 ? new String(buf, 0, r, "UTF-8").trim() : "";
            android.util.Log.d("NumPad", "helper <- " + cmd + " -> " + reply);
            return reply;
        } catch (Throwable t) {
            android.util.Log.w("NumPad", "helper 不可用: " + t);
            return null;
        } finally {
            if (s != null) {
                try {
                    s.close();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    /** 上次拉起助手失败的时刻（uptimeMillis）。用来退避，避免每次按键都白等一整轮。 */
    private static long sLastStartFail = 0;
    private static final long START_BACKOFF_MS = 20000;

    /** 用 shell 权限把常驻助手拉起来 */
    private static void startHelper() {
        if (sApkPath == null) {
            return;
        }
        // 刚失败过就先别重试，直接让调用方走 input 兜底——免得每按一次键都干等一轮。
        if (sLastStartFail != 0
                && SystemClock.uptimeMillis() - sLastStartFail < START_BACKOFF_MS) {
            return;
        }
        try {
            // 先把可能占着端口的旧助手干掉。老版本的注入是坏的，
            // 不杀掉的话新助手起不来（端口被占），会一直用坏的那个。
            String kill = "for p in $(ps -A -o PID,NAME 2>/dev/null | grep -w 'ki' | awk '{print $1}');"
                    + " do kill -9 $p 2>/dev/null; done; sleep 0.3";
            exec("shell:" + kill);

            // ⚠️ 下面三个细节缺一不可。实测（各跑 5 轮，杀掉助手后冷启）：
            //     原来那条 `cp ... && nohup ... &` 成功率只有 1/5；
            //     改成现在这样是 5/5。
            //   1. cp 单独先执行，不要和 nohup 一起后台化。
            //      整条 `cp && nohup ... &` 会把"复制+启动"当成一个后台任务，
            //      而 shell 立刻退出，子进程还没跑到 nohup 就被带走了——
            //      现象是 ki.apk 被更新了、但 ki.log 根本没生成。
            //   2. `</dev/null` 必须加。否则助手的标准输入继承自 adb 会话，
            //      会话迟迟不关闭，exec() 会一直卡住等不到回包。
            //   3. 结尾 sleep 1：把会话多留一秒，让 app_process 站稳再断连。
            String sh = "cp " + sApkPath + " /data/local/tmp/ki.apk; "
                    + "nohup app_process -Djava.class.path=/data/local/tmp/ki.apk /system/bin"
                    + " --nice-name=ki com.tclhotkey.floatnum.KeyInject"
                    + " >/data/local/tmp/ki.log 2>&1 </dev/null & "
                    + "sleep 1";
            exec("shell:" + sh);
            android.util.Log.d("NumPad", "已下发拉起助手的命令");

            // exec 里已经等了 1 秒，助手一般这时已经监听上了，补几轮短轮询兜底
            for (int i = 0; i < 5; i++) {
                if (helperAlive()) {
                    sLastStartFail = 0;
                    android.util.Log.d("NumPad", "助手就绪，用时约 " + (1000 + i * 200) + "ms");
                    return;
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ignore) {
                }
            }
            sLastStartFail = SystemClock.uptimeMillis();
            android.util.Log.w("NumPad", "助手启动超时，退避 " + (START_BACKOFF_MS / 1000) + " 秒");
        } catch (Throwable t) {
            sLastStartFail = SystemClock.uptimeMillis();
            android.util.Log.w("NumPad", "拉起助手失败: " + t);
        }
    }

    /** 键盘打开时预热：助手不在（或版本不对）就换掉重拉，等用户按完数字就已经就绪 */
    public static void warmUp() {
        if (helperAlive()) {
            return;
        }
        startHelper();
    }

    /** 连 adbd 并执行一条命令，dest 形如 "shell:input keyevent 8" */
    public static String exec(String dest) throws Exception {
        IOException last = null;
        for (String host : candidateHosts()) {
            for (int port : PORTS) {
                try {
                    return execOnce(host, port, dest);
                } catch (IOException e) {
                    android.util.Log.w("NumPad", "adb " + host + ":" + port + " -> " + e.getMessage());
                    last = e;
                }
            }
        }
        throw (last != null ? last : new IOException("无法连接本机 adbd"));
    }

    private static String execOnce(String host, int port, String dest) throws Exception {
        // ⚠️ connect() 必须在 try 里面。放在外面的话，连接失败抛异常时
        //    这个 socket 永远不会走 finally 的 close()，反复重试会漏掉 fd。
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), 4000);
            s.setSoTimeout(10000);
            OutputStream os = s.getOutputStream();
            InputStream is = s.getInputStream();

            send(os, CNXN, 0x01000000, 4096, "host::\u0000");
            Msg r = recv(is);
            if (r != null && r.cmd == AUTH && r.a0 == AUTH_TOKEN) {
                send(os, AUTH, AUTH_SIGNATURE, 0, signToken(r.data));
                r = recv(is);
                if (r != null && r.cmd == AUTH) {
                    throw new IOException("adb 认证失败：电视未信任本应用的密钥");
                }
            }

            send(os, OPEN, 1, 0, dest + "\u0000");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            while (true) {
                Msg m = recv(is);
                if (m == null) {
                    break;
                }
                if (m.cmd == OKAY) {
                    continue;
                }
                if (m.cmd == WRTE) {
                    out.write(m.data);
                    send(os, OKAY, m.a1, m.a0, "");
                    continue;
                }
                if (m.cmd == CLSE) {
                    break;
                }
            }
            return new String(out.toByteArray(), "UTF-8");
        } finally {
            try {
                s.close();
            } catch (Throwable ignore) {
            }
        }
    }

    // ---------------- 签名 ----------------

    private static byte[] signToken(byte[] token) throws Exception {
        if (sCtx == null) {
            throw new IllegalStateException("AdbShell.setContext() 还没调用");
        }
        byte[] der = AdbKey.get(sCtx);
        RSAPrivateKey key = (RSAPrivateKey) KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(der));

        byte[] t = new byte[DER_SHA1.length + token.length];
        System.arraycopy(DER_SHA1, 0, t, 0, DER_SHA1.length);
        System.arraycopy(token, 0, t, DER_SHA1.length, token.length);

        // NONEwithRSA：不做摘要，直接对传入数据做 PKCS#1 v1.5 填充后私钥运算
        Signature sg = Signature.getInstance("NONEwithRSA");
        sg.initSign(key);
        sg.update(t);
        return sg.sign();
    }

    // ---------------- 协议收发 ----------------

    private static void send(OutputStream os, int cmd, int a0, int a1, String data) throws Exception {
        send(os, cmd, a0, a1, data.getBytes("UTF-8"));
    }

    private static void send(OutputStream os, int cmd, int a0, int a1, byte[] d) throws Exception {
        ByteArrayOutputStream m = new ByteArrayOutputStream();
        le32(m, cmd);
        le32(m, a0);
        le32(m, a1);
        le32(m, d.length);
        int sum = 0;
        for (byte b : d) {
            sum += (b & 0xff);
        }
        le32(m, sum);
        le32(m, cmd ^ 0xffffffff);
        m.write(d);
        os.write(m.toByteArray());
        os.flush();
    }

    private static final class Msg {
        int cmd;
        int a0;
        int a1;
        byte[] data = new byte[0];
    }

    private static Msg recv(InputStream is) throws Exception {
        byte[] h = readFully(is, 24);
        if (h == null) {
            return null;
        }
        Msg m = new Msg();
        m.cmd = le32(h, 0);
        m.a0 = le32(h, 4);
        m.a1 = le32(h, 8);
        int len = le32(h, 12);
        // 对端声明的载荷长度直接拿去 new byte[]，一个畸形的长度就能把进程撑爆。
        // 本机 adbd 是可信的，但这里还是加个上限兜底。
        if (len < 0 || len > MAX_PAYLOAD) {
            throw new IOException("adb 报文长度异常: " + len);
        }
        if (len > 0) {
            m.data = readFully(is, len);
            if (m.data == null) {
                return null;
            }
        }
        return m;
    }

    private static byte[] readFully(InputStream is, int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = is.read(buf, off, n - off);
            if (r < 0) {
                return null;
            }
            off += r;
        }
        return buf;
    }

    private static void le32(ByteArrayOutputStream o, int v) {
        o.write(v & 0xff);
        o.write((v >>> 8) & 0xff);
        o.write((v >>> 16) & 0xff);
        o.write((v >>> 24) & 0xff);
    }

    private static int le32(byte[] b, int o) {
        return (b[o] & 0xff)
                | ((b[o + 1] & 0xff) << 8)
                | ((b[o + 2] & 0xff) << 16)
                | ((b[o + 3] & 0xff) << 24);
    }
}
