package com.tclhotkey.floatnum;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * 调电视自带的换台接口。
 *
 * 这不是我们发明的协议，是电视自己提供的：
 * 电视在 127.0.0.1:29191 上跑着一个「电视大全局域网播放控制系统」，
 * 里面有 playEname（按频道号换台）这个能力。
 * app 装在电视上，访问自己的 localhost 不需要任何权限，也不经过网络。
 */
final class ChannelApi {

    /** 电视本地控制服务 */
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 29191;

    private ChannelApi() {
    }

    /**
     * 切换到指定频道。
     *
     * @param channel 频道号，如 13
     * @return null 成功，否则是错误信息
     */
    static String play(final String channel) {
        return post("/playEname", "data=" + channel);
    }

    // 注：电视这个接口的 /ykq 只认 up/down/left/right/ok/menu/exit/volume_up/volume_down，
    // **没有数字键** —— 换频道只能靠上面的 playEname。所以这里不再留一个「按键」方法占位。

    private static String post(final String path, final String body) {
        HttpURLConnection c = null;
        try {
            URL u = new URL("http://" + HOST + ":" + PORT + path);
            c = (HttpURLConnection) u.openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(2500);
            c.setReadTimeout(3000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

            byte[] data = body.getBytes("UTF-8");
            c.setFixedLengthStreamingMode(data.length);
            OutputStream os = c.getOutputStream();
            os.write(data);
            os.flush();
            os.close();

            int code = c.getResponseCode();
            BufferedReader br = new BufferedReader(new InputStreamReader(
                    (code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream()),
                    "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
            br.close();

            String r = sb.toString().trim();
            if (code < 200 || code >= 300) {
                return "HTTP " + code;
            }
            if ("success".equals(r)) {
                return null;
            }
            if (r.length() == 0) {
                return null;      // 通了，只是没回包
            }
            return r;
        } catch (Exception e) {
            return String.valueOf(e.getMessage());
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }
}
