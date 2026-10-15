package com.tclhotkey.floatnum;

import android.app.Activity;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 悬浮数字键盘。
 *
 * 遥控器没有数字键，看直播时没法输频道号。这个应用在屏幕上弹一个数字键盘，
 * 用方向键选数字，选完调电视本机的换台接口切过去。
 *
 * 两种用法：
 *   · 从应用列表打开
 *   · 用 TVKeyMapper 把某个键映射到本应用，按那个键就弹出来
 *
 * 两个关键点（都是踩过的坑）：
 *   1. 窗口绝对不能加 FLAG_NOT_FOCUSABLE，加了遥控器按键全部收不到
 *   2. 确定键要读「当前焦点按钮的文字」，不要自己维护光标索引，
 *      否则焦点由系统移动、索引没同步，就会出现「按哪个键都输入同一个数字」
 *
 * 界面约定：所有按键宽高完全一致（曾经按字数给宽度，「退/0/换台」那行比上面宽，
 * 四行又是左对齐，右边就成了锯齿）。四行 + 顶部显示条宽度统一由 KEY_W_DP 推出来。
 */
public class KeyboardActivity extends Activity {

    // ——— 尺寸：按键一律同样大小 ———
    private static final int KEY_W_DP = 80;
    private static final int KEY_H_DP = 58;
    private static final int KEY_GAP_DP = 5;

    // ——— 配色：跟原来一致，只是抽成常量 ———
    private static final int C_PANEL = 0xE60D1017;
    private static final int C_KEY = 0x1FFFFFFF;
    private static final int C_KEY_FOCUS = 0xFF2F5FA8;
    private static final int C_TEXT = 0xFFFFFFFF;
    private static final int C_AMBER = 0xFFFFC24D;
    private static final int C_DISPLAY_BG = 0x1FFFFFFF;
    private static final int C_STATUS = 0xFFFFB4A2;
    // 声明正文的灰：与按键改键器 DisclaimerDialog.C_SOFT 取同一个值，两处观感一致
    private static final int C_SOFT = 0xFF9AA0B4;
    private static final int C_SOFT_DIM = 0xFF6E7488;
    private static final int C_LINK = 0xFF7FC4FF;       // 声明里 github 地址的浅蓝（同改键器）
    private static final int C_DISC_BG = 0x33FFC24D;

    // 勾选框用的一套色（跟按钮区分开，一眼能看出「没生效」）
    private static final int C_CHK_OFF = 0x1AFFFFFF;
    private static final int C_DISC_OFF = 0x33FFFFFF;   // 免责声明按钮失效态底色
    private static final int C_CHK_MARK = 0xFF10131A;  // 琥珀/蓝色底上那个对勾用深色才看得清

    private LinearLayout root;
    private TextView display;
    private TextView status;
    private View.OnFocusChangeListener focusBg;
    private boolean inPanel = false;
    private final StringBuilder value = new StringBuilder();

    // 免责声明开关：默认关闭。快速输频道号时最容易误触的就是这个按钮，
    // 所以把它做成要先手动勾上才生效，而不是点了才生效。
    private static final String PREF = "np";
    private static final String K_DISC_ON = "disc_on";
    private boolean discOn = false;
    private TextView discBtn;
    private TextView discChk;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setFormat(PixelFormat.TRANSLUCENT);
        getWindow().setBackgroundDrawable(null);

        focusBg = new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                v.setBackgroundColor(hasFocus ? C_KEY_FOCUS : C_KEY);
            }
        };

        // 免责声明开关要跨次启动记住：用户勾一次是为了以后一直不被误触，
        // 每次开键盘都要重勾一遍就失去意义了。默认 false = 按钮失效（防误触优先）。
        discOn = getSharedPreferences(PREF, MODE_PRIVATE).getBoolean(K_DISC_ON, false);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(12), dp(14), dp(12));
        root.setBackgroundColor(C_PANEL);
        setContentView(root);

        buildPad();
        setupWindow();

        // 把上下文交给 AdbShell，它签名时要用它去取本机的 adb 私钥（见 AdbKey）
        AdbShell.setContext(this);

        // 预热：常驻注入助手没在跑就先拉起来，等用户按完数字它已经就绪，换台就不用等
        AdbShell.setApkPath(getApplicationInfo().sourceDir);
        new Thread(new Runnable() {
            @Override
            public void run() {
                AdbShell.warmUp();
            }
        }).start();
    }

    private void setupWindow() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // ⚠️ 不能加 FLAG_NOT_FOCUSABLE
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.y = dp(30);
        getWindow().setAttributes(lp);
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    /** 加一个横向居中、指定宽高的子视图 */
    private void addCentered(View v, int w, int h) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(v, lp);
    }

    // ---------------- 界面：数字键盘 ----------------
    private void buildPad() {
        inPanel = false;
        root.removeAllViews();
        // 声明页会把 padding 收紧到 8dp，这里还原键盘页的 14/12
        root.setPadding(dp(14), dp(12), dp(14), dp(12));

        // 显示条宽度 = 最外侧两个键的外边缘之间的距离，
        // 也就是「3 个键宽 + 2 个内部间隙」。
        // ⚠️ 别写成 KEY_GAP_DP * 6 —— 那是把每个键左右两侧的 margin 全算上了，
        //    连最外侧那 2 个（键外面根本没有键，是空白）也算进去，
        //    结果显示条左右各多探出 5dp，实测在 1080p 上肉眼明显宽出一圈。
        final int gridW = KEY_W_DP * 3 + KEY_GAP_DP * 4;

        // 已输入的频道号：窄条显示，不是大输入框
        display = new TextView(this);
        display.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        display.setTextColor(C_AMBER);
        display.setGravity(Gravity.CENTER);
        display.setBackgroundColor(C_DISPLAY_BG);
        display.setPadding(dp(8), dp(5), dp(8), dp(5));
        display.setText(value.length() == 0 ? "—" : value.toString());
        addCentered(display, dp(gridW), ViewGroup.LayoutParams.WRAP_CONTENT);

        String[][] rows = {
                {"1", "2", "3"},
                {"4", "5", "6"},
                {"7", "8", "9"},
                {"退", "0", "换台"},
        };
        int idx = 0;
        for (String[] row : rows) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER);
            for (String label : row) {
                final String text = label;
                TextView k = new TextView(this);
                k.setText(text);
                k.setTextSize(TypedValue.COMPLEX_UNIT_SP, text.length() > 1 ? 20 : 25);
                k.setTextColor(C_TEXT);
                k.setGravity(Gravity.CENTER);
                k.setFocusable(true);
                k.setFocusableInTouchMode(true);
                k.setBackgroundColor(C_KEY);
                if (idx == 0) {
                    k.setTag("key0");
                }
                k.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        press(text);
                    }
                });
                k.setOnFocusChangeListener(focusBg);
                // ⚠️ 这里曾经是 label.length() > 1 ? dp(84) : dp(66)，
                //    那正是「按键大小不一」的来源。现在一律 dp(KEY_W_DP)。
                LinearLayout.LayoutParams lp =
                        new LinearLayout.LayoutParams(dp(KEY_W_DP), dp(KEY_H_DP));
                lp.setMargins(dp(KEY_GAP_DP), dp(KEY_GAP_DP), dp(KEY_GAP_DP), dp(KEY_GAP_DP));
                line.addView(k, lp);
                idx++;
            }
            addCentered(line, ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        status.setTextColor(C_STATUS);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(4), 0, 0);
        addCentered(status, ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);

        root.addView(buildFooter());

        root.requestLayout();
        root.post(new Runnable() {
            @Override
            public void run() {
                // 焦点交给第一个数字键，方向键才有起点
                View first = root.findViewWithTag("key0");
                if (first != null) {
                    first.requestFocus();
                }
            }
        });
    }

    /** 底部一行：版权作者信息 + 「免责声明」按钮 + 它右边的勾选框 */
    private View buildFooter() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, dp(8), 0, 0);

        TextView author = new TextView(this);
        author.setText(R.string.footer_author);
        author.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        author.setTextColor(C_SOFT_DIM);
        row.addView(author);

        discBtn = new TextView(this);
        discBtn.setText(R.string.disc_btn);
        discBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        discBtn.setGravity(Gravity.CENTER);
        discBtn.setPadding(dp(12), dp(4), dp(12), dp(4));
        discBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 双保险：即使焦点系统把事件送进来了，没勾选也不放行
                if (discOn) {
                    showDisclaimer();
                }
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(8), 0, 0, 0);
        row.addView(discBtn, lp);

        // 勾选框：无文字，只有框和一个勾。用 TextView 自绘，
        // 不用系统 CheckBox —— minSdk 19 上 buttonTint 那套用不了，
        // 而且系统控件在电视主题下大小不可控。
        // 边长 17dp：实测旁边「免责声明」按钮高 37px（density 1.5 下约 24.7dp），
        // 这里取它的约 2/3，看起来是一枚「附属控件」而不是又一个按钮。
        // row 的 gravity 是 CENTER，勾选框跟按钮在垂直方向自动对中。
        discChk = new TextView(this);
        discChk.setTag("discchk");
        discChk.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        discChk.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                dp(17), dp(17));
        clp.setMargins(dp(8), 0, 0, 0);
        row.addView(discChk, clp);
        discChk.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleDisc();
            }
        });
        // 聚焦时跟数字键一样变蓝，否则用户不知道焦点在这儿
        discChk.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                paintChk(hasFocus);
            }
        });

        refreshFooter();
        return row;
    }

    /** 勾选框上色：聚焦 = 蓝色高亮（跟数字键一致），否则按勾选状态区分 */
    private void paintChk(boolean hasFocus) {
        if (hasFocus) {
            discChk.setBackgroundColor(C_KEY_FOCUS);
            discChk.setTextColor(discOn ? C_CHK_MARK : C_AMBER);
        } else {
            discChk.setBackgroundColor(discOn ? C_AMBER : C_CHK_OFF);
            discChk.setTextColor(discOn ? C_CHK_MARK : C_AMBER);
        }
    }

    /** 按当前勾选状态刷新按钮与勾选框的样子（勾选状态一变就调） */
    private void refreshFooter() {
        if (discBtn == null || discChk == null) {
            return;
        }
        if (discOn) {
            discBtn.setTextColor(C_AMBER);
            discBtn.setBackgroundColor(C_DISC_BG);
            discBtn.setFocusable(true);
            discBtn.setFocusableInTouchMode(true);
            discBtn.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    v.setBackgroundColor(hasFocus ? C_KEY_FOCUS : C_DISC_BG);
                }
            });
            discChk.setText("✓");
        } else {
            // 失效态：整条变灰，并且不可聚焦 —— 方向键会直接跳过去，
            // 快速换台时不可能误触到它。
            discBtn.setTextColor(C_SOFT_DIM);
            discBtn.setBackgroundColor(C_DISC_OFF);
            discBtn.setFocusable(false);
            discBtn.setFocusableInTouchMode(false);
            discBtn.setOnFocusChangeListener(null);
            discChk.setText("");
        }
        // 切换状态时焦点可能正停在勾选框上，保留它的高亮
        paintChk(discChk.hasFocus());
    }

    private void toggleDisc() {
        discOn = !discOn;
        getSharedPreferences(PREF, MODE_PRIVATE).edit().putBoolean(K_DISC_ON, discOn).apply();
        refreshFooter();
    }


    // ---------------- 界面：免责声明 ----------------
    /**
     * 免责声明不另开窗口，直接换掉本窗口里的内容。
     *
     * 本窗口是 TYPE_APPLICATION_OVERLAY（层级比普通应用窗口高），
     * 在它上面再弹一个 Dialog 会出现「弹窗被键盘挡住 / 焦点抢不过来」的麻烦，
     * 同一窗口里换内容就没有这些问题，遥控器焦点也是现成的。
     *
     * 正文比原来那块打赏面板长得多，所以用 ScrollView 兜着：
     * 高度先按宽度量出内容真实高度、再与屏高的 81.5% 取小值 ——
     * 正常情况下装得下、不滚动（一屏放完）；万一以后文案加长到装不下，
     * ScrollView 会自动滚，方向键一路往下按也能读完。
     */
    private void showDisclaimer() {
        inPanel = true;
        root.removeAllViews();
        // 声明页外边距收紧到 8dp（键盘页是 12dp）：正文按改键器的版式加大后
        // 整页变高，必须一屏放下，这里把能省的竖向空间都让给正文。
        // 返回键盘时 buildPad() 会把 padding 还原成 dp(14)/dp(12)。
        root.setPadding(dp(8), dp(8), dp(8), dp(8));

        TextView title = new TextView(this);
        title.setText(R.string.disc_title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTextColor(C_AMBER);
        title.setGravity(Gravity.CENTER);
        addCentered(title, ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(18), 0, dp(18), 0);
        appendSection(col, R.string.disc_p1_head, R.string.disc_p1_body);
        appendSection(col, R.string.disc_p2_head, R.string.disc_p2_body);
        appendSection(col, R.string.disc_p3_head, R.string.disc_p3_body);

        int wxId = drawableId("qr_wechat");
        int aliId = drawableId("qr_alipay");
        boolean two = wxId != 0 && aliId != 0;

        LinearLayout qrRow = new LinearLayout(this);
        qrRow.setOrientation(LinearLayout.HORIZONTAL);
        qrRow.setGravity(Gravity.CENTER);
        // 收款码离上面的正文远一点就够了（16dp）。正文格式改大后每段自带 8dp
        // 段后距，实际留白 = 8+16 = 24dp ≈ 36px，和上一版用户认可的观感一致。
        qrRow.setPadding(0, dp(16), 0, 0);
        if (wxId == 0 && aliId == 0) {
            TextView ph = new TextView(this);
            ph.setText(R.string.disc_noimg);
            ph.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            ph.setTextColor(C_SOFT_DIM);
            ph.setGravity(Gravity.CENTER);
            ph.setBackgroundColor(0x14FFFFFF);
            ph.setPadding(dp(16), dp(30), dp(16), dp(30));
            qrRow.addView(ph, new LinearLayout.LayoutParams(
                    dp(260), ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            int sw = getResources().getDisplayMetrics().widthPixels;
            // 二维码边长：正文按改键器版式放大后整页变高，二维码相应从 152dp 收到
            // 108dp（162px），把竖向空间让给正文 —— 收款码只要扫码时够清楚就行，
            // 162px 在 1080p 上是屏宽的 8.4%，手机凑近照样扫得出。
            int side = Math.min(dp(108), (sw - dp(320)) / (two ? 2 : 1));
            if (side < dp(88)) {
                side = dp(88);
            }
            if (wxId != 0) {
                qrRow.addView(qrCell(R.string.disc_wechat, wxId, side));
            }
            if (aliId != 0) {
                qrRow.addView(qrCell(R.string.disc_alipay, aliId, side));
            }
        }
        col.addView(qrRow);

        TextView ver = new TextView(this);
        ver.setText(R.string.disc_ver);
        ver.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        ver.setTextColor(C_SOFT_DIM);
        ver.setGravity(Gravity.CENTER);
        ver.setPadding(0, dp(3), 0, 0);
        col.addView(ver);

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setFillViewport(true);
        sv.addView(col, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // 正文一屏放完（用户硬要求，不能靠滚动看）：
        //   · 宽度给足 → 每行多塞字 → 行数最少；
        //   · 高度先按 920dp 宽量一遍真实内容高，再取「内容高」与「屏幕 81.5%」的小值。
        //     正常情况内容装得下 → ScrollView 正好包住内容，面板不拖一截空白；
        //     真遇上文案加长装不下 → 封顶可滚，且窗口不会被顶出屏幕外。
        // ⚠️ 上限的来源：窗口总高 = 外边距24 + 标题 ≈51 + 本块 + 返回键75，
        //    悬浮窗贴底 45px，所以本块 ≤ 1080-45-150 ≈ 885px；取 0.815×屏高 = 880px。
        int panelW = dp(920);
        col.measure(View.MeasureSpec.makeMeasureSpec(panelW, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int capH = (int) (getResources().getDisplayMetrics().heightPixels * 0.815f);
        addCentered(sv, panelW, Math.min(col.getMeasuredHeight(), capH));

        TextView back = new TextView(this);
        back.setTag("back");
        back.setText(R.string.disc_close);
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        back.setTextColor(C_TEXT);
        back.setGravity(Gravity.CENTER);
        back.setBackgroundColor(C_KEY);
        back.setFocusable(true);
        back.setFocusableInTouchMode(true);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                buildPad();
            }
        });
        back.setOnFocusChangeListener(focusBg);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(140), dp(40));
        blp.gravity = Gravity.CENTER_HORIZONTAL;
        blp.setMargins(0, dp(10), 0, 0);
        root.addView(back, blp);

        root.requestLayout();
        root.post(new Runnable() {
            @Override
            public void run() {
                View f = root.findViewWithTag("back");
                if (f != null) {
                    f.requestFocus();
                }
            }
        });
    }

    /** 免责声明里的一段：琥珀色小标题 + 灰色正文。
     *  版式（字号/行距/段距/配色）与按键改键器 DisclaimerDialog.section() 完全一致，
     *  两边的免责声明页看起来就是同一套排版。 */
    private void appendSection(LinearLayout col, int headRes, int bodyRes) {
        TextView h = new TextView(this);
        h.setText(headRes);
        h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        h.setTextColor(C_AMBER);
        h.setPadding(0, 0, 0, dp(6));
        col.addView(h);

        String txt = getString(bodyRes);
        TextView b = new TextView(this);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setTextColor(C_SOFT);
        b.setLineSpacing(dp(3), 1f);
        b.setPadding(0, 0, 0, dp(8));
        // 正文里带 github 地址的那段，把地址部分单独染成浅蓝（跟改键器一样），
        // 一眼能看出是可复制的链接。找不到就原样显示，不会出错。
        int at = txt.indexOf("github.com");
        if (at >= 0) {
            SpannableStringBuilder sb = new SpannableStringBuilder(txt);
            sb.setSpan(new ForegroundColorSpan(C_LINK), at, txt.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            b.setText(sb);
        } else {
            b.setText(txt);
        }
        col.addView(b);
    }

    /**
     * 一张收款码 + 正中间叠一个应用图标 + 底下的小字标签。
     *
     * 图标是「叠」上去的，不改动二维码图片本身 —— 二维码中间的图案是编码数据的一部分，
     * 直接改像素会破坏解码。实测（opencv QRCodeDetector）：
     * 支付宝收款码（纠错等级高）叠到码面 32% 仍能正常解出原始 URL，
     * 所以这里保守取 22%，留足余量。
     */
    private View qrCell(int labelRes, int resId, int side) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);

        FrameLayout stack = new FrameLayout(this);
        // 收款码是白底黑块，深色键盘上必须自己垫一块白，不然扫不出来
        stack.setBackgroundColor(0xFFFFFFFF);

        ImageView iv = new ImageView(this);
        iv.setImageResource(resId);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int p = dp(5);
        iv.setPadding(p, p, p, p);
        stack.addView(iv, new FrameLayout.LayoutParams(side, side));

        int appIcon = getApplicationInfo().icon;
        if (appIcon != 0) {
            int ls = Math.max(dp(24), (int) (side * 0.26f));
            ImageView lg = new ImageView(this);
            lg.setImageResource(appIcon);
            lg.setScaleType(ImageView.ScaleType.FIT_CENTER);
            // 白底圆角衬一下：图标本身是深色板，直角白块在码上又生硬。
            GradientDrawable plate = new GradientDrawable();
            plate.setColor(0xFFFFFFFF);
            plate.setCornerRadius(dp(5));
            lg.setBackground(plate);
            lg.setPadding(dp(1), dp(1), dp(1), dp(1));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ls, ls);
            lp.gravity = Gravity.CENTER;
            stack.addView(lg, lp);
        }

        col.addView(stack, new LinearLayout.LayoutParams(side, side));

        TextView lb = new TextView(this);
        lb.setText(labelRes);
        lb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        lb.setTextColor(C_SOFT);
        lb.setGravity(Gravity.CENTER);
        lb.setPadding(0, dp(2), 0, 0);
        col.addView(lb);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        // 两个码之间留够白：挨太近了拿手机扫容易扫到隔壁那个
        lp.setMargins(dp(24), 0, dp(24), 0);
        col.setLayoutParams(lp);
        return col;
    }

    /**
     * 收款码图片是可选的。
     * 打包时 res/drawable 里没有 qr_wechat / qr_alipay 就返回 0，界面自动降级成提示文字，
     * 这样加了图不用改一行代码，没加图也不会编译不过。
     */
    private int drawableId(String name) {
        try {
            return getResources().getIdentifier(name, "drawable", getPackageName());
        } catch (Throwable t) {
            return 0;
        }
    }

    // ---------------- 逻辑 ----------------
    private void press(String label) {
        if (getString(R.string.disc_btn).equals(label)) {
            // 没勾选就不放行。按钮已经不可聚焦了，这里是第二道保险 ——
            // 万一某个 ROM 的焦点逻辑把事件硬塞过来，也不会误进声明页。
            if (discOn) {
                showDisclaimer();
            }
            return;
        }
        if (getString(R.string.disc_close).equals(label)) {
            buildPad();
            return;
        }
        if ("换台".equals(label)) {
            commit();
            return;
        }
        if ("退".equals(label)) {
            if (value.length() > 0) {
                value.deleteCharAt(value.length() - 1);
                update();
            } else {
                close();
            }
            return;
        }
        if (value.length() < 5) {
            value.append(label);
            update();
        }
    }

    private void update() {
        display.setText(value.length() == 0 ? "—" : value.toString());
        if (status != null) {
            status.setText("");
        }
    }

    private void commit() {
        final String v = value.toString();
        if (v.length() == 0) {
            close();
            return;
        }
        status.setText("正在换台…");

        // ⚠️ 关键：必须先把自己的窗口关掉，把焦点还给后面的直播 app。
        // 悬浮窗是拿了焦点的（否则收不到遥控器按键），只要它还在，
        // input keyevent 注入的按键就会打进本应用自己，直播 app 什么都收不到。
        close();

        new Thread(new Runnable() {
            @Override
            public void run() {
                // 关窗口和焦点归还是异步的，这里先垫一下；
                // 真正保底的是助手的 KEYW——它会盯着焦点确认回到直播 app 才注入。
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ignored) {
                }

                String err = null;
                try {
                    android.util.Log.d("NumPad", "inject digits=" + v);
                    AdbShell.sendDigits(v);
                    android.util.Log.d("NumPad", "inject ok");
                } catch (Exception e) {
                    android.util.Log.w("NumPad", "inject failed", e);
                    // 提权通道不可用，退回米团TV 自己的本机接口
                    String msg = (e.getMessage() == null ? e.toString() : e.getMessage());
                    err = ChannelApi.play(v);
                    if (err != null) {
                        err = "按键注入失败（" + msg + "）；备用通道也失败：" + err;
                    }
                }

                if (err != null) {
                    final String message = err;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            android.widget.Toast.makeText(getApplicationContext(),
                                    "换台失败：" + message,
                                    android.widget.Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }

    private void close() {
        finish();
        overridePendingTransition(0, 0);
    }

    // ---------------- 按键 ----------------
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // 确定键：读当前焦点按钮上的文字
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            View f = getCurrentFocus();
            // 勾选框没有文字（勾上才显示一个 ✓），走不了「读文字」这条路，单独处理
            if (f != null && "discchk".equals(f.getTag())) {
                toggleDisc();
                return true;
            }
            if (f instanceof TextView) {
                String text = ((TextView) f).getText().toString();
                if (text.length() > 0) {
                    press(text);
                }
            }
            return true;
        }

        // 返回键：免责声明里先退回键盘；键盘里先当退格，没有内容才关闭
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_DEL) {
            if (inPanel) {
                buildPad();
                return true;
            }
            if (value.length() > 0) {
                press("退");
            } else {
                close();
            }
            return true;
        }

        // 方向键不拦，交给系统移动焦点
        return super.onKeyDown(keyCode, event);
    }
}
