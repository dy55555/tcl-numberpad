package com.tclhotkey.floatnum;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * 常驻按键注入助手。
 *
 * 由 app 通过本机 adbd 以「shell 权限」启动（app_process），跑起来后监听本地端口，
 * 收到键码就直接注入真实的按键事件。
 *
 * 为什么需要它：/system/bin/input 每执行一次都要启动一个 Java 虚拟机，实测约 1.3 秒，
 * 而且没有缓存。这个进程是常驻的，注入一次只要几毫秒。
 *
 * 为什么它能注入：shell uid 持有 INJECT_EVENTS 权限，普通 app 拿不到。
 *
 * ★ 关键坑（实测血泪）：
 *   不能用 new KeyEvent(action, code) 这个两参构造。它造出来的事件
 *     eventTime=0、downTime=0、source=0
 *   系统会把这种事件当无效丢掉，但 ASYNC 模式的 injectInputEvent 照样返回 true，
 *   于是「报成功、按键不生效」，极难排查。
 *   必须自己填 downTime/eventTime=SystemClock.uptimeMillis()，
 *   deviceId=KeyCharacterMap.VIRTUAL_KEYBOARD(-1)，source=InputDevice.SOURCE_KEYBOARD(0x101)。
 *   实测矩阵：两参构造 UI 树 2438 不动；完整构造 2438 -> 27360，按键真正送达。
 */
public class KeyInject {

    public static final int PORT = 38300;

    /** 协议版本号。app 用 PING 的回包里的 v2 判断电视上跑的是不是最新版助手 */
    private static final String VERSION = "OK v2";

    private static Class<?> imCls;
    private static Object imObj;
    private static Method injectMethod;

    public static void main(String[] args) {
        log("KeyInject v2 start, port=" + PORT);
        try {
            imCls = Class.forName("android.hardware.input.InputManager");
            imObj = imCls.getDeclaredMethod("getInstance").invoke(null);
            injectMethod = imCls.getMethod("injectInputEvent", InputEvent.class, int.class);
            log("InputManager ready: " + injectMethod);
        } catch (Throwable t) {
            log("init failed: " + t);
            return;
        }
        try {
            ServerSocket server = new ServerSocket(PORT);
            log("listening");
            while (true) {
                Socket s = server.accept();
                try {
                    serve(s);
                } catch (Throwable t) {
                    log("serve err: " + t);
                } finally {
                    try {
                        s.close();
                    } catch (Throwable ignore) {
                    }
                }
            }
        } catch (Throwable t) {
            log("fatal: " + t);
        }
    }

    private static void serve(Socket s) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream()));
        OutputStream os = s.getOutputStream();
        String line;
        while ((line = r.readLine()) != null) {
            line = line.trim();
            if (line.length() == 0) {
                continue;
            }
            if ("PING".equalsIgnoreCase(line)) {
                os.write((VERSION + "\n").getBytes("UTF-8"));
                os.flush();
                continue;
            }
            // KEYW <排除包名> <键码...>：先等焦点离开这个包（也就是离开悬浮键盘），再注入。
            // 用它是因为：焦点归还是异步的，靠 sleep 猜时间不稳，
            // 键盘还在前面时注入，数字就全打进键盘自己了。
            if (line.startsWith("KEYW")) {
                String[] p = line.split("\\s+");
                if (p.length < 3) {
                    os.write("ERR\n".getBytes("UTF-8"));
                    os.flush();
                    continue;
                }
                String exclude = p[1];
                long waited = waitFocusAwayFrom(exclude, 2500);
                int[] codes = new int[p.length - 2];
                for (int i = 2; i < p.length; i++) {
                    codes[i - 2] = Integer.parseInt(p[i]);
                }
                long t0 = System.currentTimeMillis();
                boolean ok = true;
                for (int c : codes) {
                    try {
                        inject(c);
                    } catch (Exception e) {
                        ok = false;
                        log("inject " + c + " failed: " + e);
                    }
                }
                log("KEYW 等焦点 " + waited + "ms，注入 " + codes.length + " 键耗时 "
                        + (System.currentTimeMillis() - t0) + "ms，焦点=" + focusLine());
                os.write((ok ? VERSION + "\n" : "ERR\n").getBytes("UTF-8"));
                os.flush();
                continue;
            }
            // 只剩 PING 和 KEYW 两种命令。
            // （曾经还有个不带 W 的 KEY —— 它不做「等焦点离开」，
            //   键盘还挂在前面时数字会全打进键盘自己。早没人发了，删掉。）
            os.write("ERR\n".getBytes("UTF-8"));
            os.flush();
        }
    }

    /** 注入一次按下+抬起。任一环节系统不接收就抛异常 */
    private static void inject(int code) throws Exception {
        long now = SystemClock.uptimeMillis();
        KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code,
                0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD);
        KeyEvent up = new KeyEvent(now, now, KeyEvent.ACTION_UP, code,
                0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD);

        // 0 = ASYNC：不等待目标消费，永远几毫秒就返回，不会卡住。
        //    实测事件构造正确 + 焦点已确认时，ASYNC 能正常送达。
        // 2 = WAIT_FOR_FINISH：能立刻发现"没送达"，但目标不消费时会整整卡 5 秒，
        //    所以只当兜底，不作为首选。
        Object r1 = injectMethod.invoke(imObj, down, 0);
        Object r2 = injectMethod.invoke(imObj, up, 0);
        if (Boolean.TRUE.equals(r1) && Boolean.TRUE.equals(r2)) {
            return;
        }
        log("mode=0 down=" + r1 + " up=" + r2 + "，退回 WAIT_FOR_FINISH");
        Object a1 = injectMethod.invoke(imObj, down, 0);
        Object a2 = injectMethod.invoke(imObj, up, 0);
        if (Boolean.TRUE.equals(a1) && Boolean.TRUE.equals(a2)) {
            return;
        }
        throw new Exception("injectInputEvent 被拒 down=" + a1 + " up=" + a2);
    }

    /**
     * 等焦点离开某个包。返回等了多少毫秒。
     * 超时也放行——宁可试一次，也不要卡住不动。
     */
    private static long waitFocusAwayFrom(String exclude, long timeoutMs) {
        long t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < timeoutMs) {
            String f = focusLine();
            if (focusReady(f, exclude)) {
                return System.currentTimeMillis() - t0;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignore) {
            }
        }
        return System.currentTimeMillis() - t0;
    }

    /** 焦点行里没有"排除的包"，且确实指向一个真实窗口，才算就绪 */
    private static boolean focusReady(String focus, String exclude) {
        if (focus == null || focus.length() == 0) {
            return false;
        }
        if (focus.contains("null")) {
            return false; // mCurrentFocus=null，焦点还没落定
        }
        if (exclude != null && focus.contains(exclude)) {
            return false; // 还在悬浮键盘上
        }
        return true;
    }

    /** 读一次当前焦点窗口。grep -m1 命中就断，实测 80~140ms */
    private static String focusLine() {
        Process pr = null;
        try {
            pr = Runtime.getRuntime().exec(new String[] {
                    "/system/bin/sh", "-c",
                    "dumpsys window 2>/dev/null | grep -m1 mCurrentFocus" });
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(pr.getInputStream(), "UTF-8"));
            String line = r.readLine();
            try {
                pr.waitFor();
            } catch (InterruptedException ignore) {
            }
            return line == null ? "" : line.trim();
        } catch (Throwable t) {
            return "";
        } finally {
            if (pr != null) {
                try {
                    pr.destroy();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    private static void log(String msg) {
        System.out.println("[KeyInject] " + msg);
        System.out.flush();
    }
}
