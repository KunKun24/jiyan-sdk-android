package com.jiyan.cloud.shell;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 壳自己的最小化 UI：只用于「组件库下载/升级」这一段，
 * 组件加载成功后由组件自己的悬浮窗接管。
 */
final class ShellUi {

    private static final Handler UI = new Handler(Looper.getMainLooper());

    private ShellUi() {}

    /** 所有 View / WindowManager 操作统一切到主线程，后台线程只发请求 */
    static void post(final Runnable r) {
        if (r == null) return;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            try {
                r.run();
            } catch (Throwable t) {
                ShellLog.e("Ui", "UI 执行异常", t);
            }
            return;
        }
        UI.post(new Runnable() {
            @Override public void run() {
                try {
                    r.run();
                } catch (Throwable t) {
                    ShellLog.e("Ui", "UI 执行异常", t);
                }
            }
        });
    }

    /* ================= 权限 ================= */

    static boolean canDraw(Context c) {
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                return Settings.canDrawOverlays(c);
            } catch (Throwable t) {
                return false;
            }
        }
        return true;
    }

    static void requestPermission(Context c) {
        if (Build.VERSION.SDK_INT < 23) return;
        try {
            IntentHolder.start(c);
        } catch (Throwable ignored) {
        }
    }

    private static final class IntentHolder {
        static void start(Context c) throws Exception {
            android.content.Intent i = new android.content.Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + c.getPackageName()));
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        }
    }

    static void toast(Context c, String s) {
        try {
            Toast.makeText(c, s, Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
        }
    }

    static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics());
    }

    /* ================= 卡片 ================= */

    static final class Card {
        private final Context ctx;
        private View overlay;
        private WindowManager.LayoutParams wlp;
        private boolean attached;
        private android.app.Dialog dialog;
        private TextView titleView;
        private TextView subView;
        private TextView verView;
        private TextView toggleView;
        private View divider;
        private TextView stageView;
        private TextView contentView;
        private ProgressBar bar;
        private TextView progressView;
        private TextView leftBtn;
        private TextView rightBtn;
        private LinearLayout btnRow;
        private LinearLayout progRow;
        private ScrollView scroll;
        private int contentMaxPx;

        private Card(Context c) {
            this.ctx = c;
        }

        private static Card current;

        static Card show(final Context c, final String title) {
            final Card card = new Card(c);
            final Card old = current;
            current = card;
            if (old != null && old != card) {
                old.dismiss();
            }
            post(new Runnable() {
                @Override public void run() {
                    try {
                        card.build(title);
                    } catch (Throwable t) {
                        ShellLog.e("Ui", "卡片创建失败", t);
                    }
                }
            });
            return card;
        }

        private void build(String title) {
            final int pad = dp(ctx, 18);
            LinearLayout box = new LinearLayout(ctx);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(pad, dp(ctx, 22), pad, dp(ctx, 18));
            // 与 SO 云更新弹窗一致的玻璃白卡：渐变 + 内亮描边
            GradientDrawable bg = new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0xF2FFFFFF, 0xDBFFFFFF});
            bg.setCornerRadius(dp(ctx, 22));
            bg.setStroke(dp(ctx, 1), 0x73FFFFFF);
            box.setBackground(bg);

            titleView = new TextView(ctx);
            titleView.setText(title);
            titleView.setTextColor(0xFF111111);
            titleView.setTextSize(17);
            titleView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            box.addView(titleView, wrapLp(0));

            subView = new TextView(ctx);
            subView.setTextColor(0xFF8A8A8E);
            subView.setTextSize(12);
            subView.setVisibility(View.GONE);
            box.addView(subView, wrapLp(dp(ctx, 6)));

            stageView = new TextView(ctx);
            stageView.setTextColor(0xFF8A8A8E);
            stageView.setTextSize(12);
            stageView.setVisibility(View.GONE);
            box.addView(stageView, wrapLp(dp(ctx, 4)));

            ScrollView sv = new ScrollView(ctx);
            sv.setFillViewport(false);
            sv.setVerticalScrollBarEnabled(false);
            sv.setClipToPadding(false);
            sv.setMinimumHeight(dp(ctx, 60));

            LinearLayout body = new LinearLayout(ctx);
            body.setOrientation(LinearLayout.VERTICAL);

            verView = new TextView(ctx);
            verView.setTextColor(0xFF444448);
            verView.setTextSize(13);
            verView.setLineSpacing(dp(ctx, 4), 1f);
            verView.setVisibility(View.GONE);
            body.addView(verView, wrapLp(0));

            divider = new View(ctx);
            divider.setBackgroundColor(0xFFEDEDEF);
            divider.setVisibility(View.GONE);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1));
            dlp.topMargin = dp(ctx, 12);
            dlp.bottomMargin = dp(ctx, 12);
            body.addView(divider, dlp);

            contentView = new TextView(ctx);
            contentView.setTextColor(0xFF444448);
            contentView.setTextSize(13);
            contentView.setTypeface(android.graphics.Typeface.DEFAULT);
            contentView.setLineSpacing(dp(ctx, 4), 1f);
            contentView.setTextIsSelectable(true);
            contentView.setMaxLines(6);
            contentView.setEllipsize(TextUtils.TruncateAt.END);
            GradientDrawable blockBg = new GradientDrawable();
            blockBg.setColor(0xFFF7F7F9);
            blockBg.setCornerRadius(dp(ctx, 14));
            contentView.setBackground(blockBg);
            contentView.setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12));
            body.addView(contentView, wrapLp(0));

            sv.addView(body, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            box.addView(sv, wrapLp(dp(ctx, 10)));
            this.scroll = sv;

            toggleView = new TextView(ctx);
            toggleView.setText("展开全文");
            toggleView.setTextColor(0xFF3B82F6);
            toggleView.setTextSize(13);
            toggleView.setGravity(Gravity.END);
            toggleView.setVisibility(View.GONE);
            toggleView.setPadding(0, dp(ctx, 8), 0, 0);
            toggleView.setTag(Boolean.FALSE);
            toggleView.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    boolean expanded = Boolean.TRUE.equals(toggleView.getTag());
                    if (expanded) {
                        contentView.setMaxLines(6);
                        contentView.setEllipsize(TextUtils.TruncateAt.END);
                        toggleView.setText("展开全文");
                        toggleView.setTag(Boolean.FALSE);
                    } else {
                        contentView.setMaxLines(Integer.MAX_VALUE);
                        contentView.setEllipsize(null);
                        toggleView.setText("收起");
                        toggleView.setTag(Boolean.TRUE);
                    }
                    clampContent();
                }
            });
            box.addView(toggleView, wrapLp(0));

            progRow = new LinearLayout(ctx);
            progRow.setOrientation(LinearLayout.VERTICAL);
            bar = new ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(100);
            bar.setProgress(0);
            progRow.addView(bar, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 6)));
            progressView = new TextView(ctx);
            progressView.setTextColor(0xFF8A8A8E);
            progressView.setTextSize(12);
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            plp.topMargin = dp(ctx, 4);
            progRow.addView(progressView, plp);
            LinearLayout.LayoutParams prlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            prlp.topMargin = dp(ctx, 10);
            progRow.setVisibility(View.GONE);
            box.addView(progRow, prlp);

            btnRow = new LinearLayout(ctx);
            btnRow.setOrientation(LinearLayout.HORIZONTAL);
            btnRow.setGravity(Gravity.CENTER_VERTICAL);
            leftBtn = button("稍后");
            rightBtn = primaryButton("立即更新");
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, dp(ctx, 48), 1f);
            blp.rightMargin = dp(ctx, 6);
            btnRow.addView(leftBtn, blp);
            LinearLayout.LayoutParams brlp = new LinearLayout.LayoutParams(0, dp(ctx, 48), 1f);
            brlp.leftMargin = dp(ctx, 6);
            btnRow.addView(rightBtn, brlp);
            LinearLayout.LayoutParams brlp2 = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 48));
            brlp2.topMargin = dp(ctx, 16);
            box.addView(btnRow, brlp2);

            // 屏高上限：标题/副标题/版本/按钮 ≈ 200dp，内容区至少 60dp
            int screenH = ctx.getResources().getDisplayMetrics().heightPixels;
            int maxCard = screenH - dp(ctx, 96);
            this.contentMaxPx = Math.max(dp(ctx, 60), maxCard - dp(ctx, 200));

            int cardW = Math.min(ctx.getResources().getDisplayMetrics().widthPixels - dp(ctx, 40),
                    dp(ctx, 340));
            if (ctx instanceof Activity) {
                try {
                    android.app.Dialog d = new android.app.Dialog(ctx);
                    d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
                    d.setContentView(box, new ViewGroup.LayoutParams(cardW,
                            ViewGroup.LayoutParams.WRAP_CONTENT));
                    d.setCanceledOnTouchOutside(false);
                    android.view.Window win = d.getWindow();
                    if (win != null) {
                        win.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
                        win.setDimAmount(0f);
                        win.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
                        win.setLayout(cardW, ViewGroup.LayoutParams.WRAP_CONTENT);
                        win.setGravity(Gravity.CENTER);
                    }
                    d.show();
                    dialog = d;
                    return;
                } catch (Throwable t) {
                    ShellLog.e("Ui", "对话框创建失败，改用悬浮窗", t);
                    dialog = null;
                }
            }
            if (canDraw(ctx)) {
                WindowManager.LayoutParams wlp = new WindowManager.LayoutParams(
                        cardW,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Build.VERSION.SDK_INT >= 26
                                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                                : WindowManager.LayoutParams.TYPE_PHONE,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        android.graphics.PixelFormat.TRANSLUCENT);
                wlp.gravity = Gravity.CENTER;
                wlp.dimAmount = 0f;
                try {
                    WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
                    if (wm != null) {
                        wm.addView(box, wlp);
                        this.wlp = wlp;
                        this.attached = true;
                        this.overlay = box;
                    }
                } catch (Throwable t) {
                    ShellLog.e("Ui", "悬浮窗添加失败", t);
                    overlay = null;
                }
            }
            if (overlay == null) {
                ShellLog.w("Ui", "没有可用的界面载体（无悬浮窗权限且非 Activity），UI 降级为通知栏");
            }
        }

        private LinearLayout.LayoutParams wrapLp(int topMargin) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = topMargin;
            return lp;
        }

        /** 标题下的一行小字，例如「当前组件库 v1.4 · 可立即更新」 */
        Card sub(final String s) {
            UI.post(new Runnable() {
                @Override public void run() {
                    if (subView == null) return;
                    subView.setText(s == null ? "" : s);
                    subView.setVisibility(TextUtils.isEmpty(s) ? View.GONE : View.VISIBLE);
                }
            });
            return this;
        }

        /** 版本对比块 */
        Card ver(final String s) {
            UI.post(new Runnable() {
                @Override public void run() {
                    if (verView == null) return;
                    verView.setText(s == null ? "" : s);
                    boolean show = !TextUtils.isEmpty(s);
                    verView.setVisibility(show ? View.VISIBLE : View.GONE);
                    if (divider != null) divider.setVisibility(show ? View.VISIBLE : View.GONE);
                }
            });
            return this;
        }

        private TextView primaryButton(String text) {
            TextView t = button(text);
            t.setTextColor(0xFFFFFFFF);
            t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            GradientDrawable g = new GradientDrawable();
            g.setColor(0xFF3B82F6);
            g.setCornerRadius(dp(ctx, 12));
            t.setBackground(g);
            return t;
        }

        private TextView button(String text) {
            TextView t = new TextView(ctx);
            t.setText(text);
            t.setGravity(Gravity.CENTER);
            t.setTextSize(14);
            t.setTextColor(0xFF1C1C1E);
            t.setTypeface(android.graphics.Typeface.DEFAULT);
            GradientDrawable g = new GradientDrawable();
            g.setColor(0xFFF2F2F7);
            g.setCornerRadius(dp(ctx, 12));
            t.setBackground(g);
            return t;
        }

        Card stage(final String s) {
            UI.post(new Runnable() {
                @Override public void run() {
                    if (stageView == null) return;
                    if (TextUtils.isEmpty(s)) {
                        stageView.setVisibility(View.GONE);
                    } else {
                        stageView.setVisibility(View.VISIBLE);
                        stageView.setText(s);
                    }
                }
            });
            return this;
        }

        Card content(final String s) {
            UI.post(new Runnable() {
                @Override public void run() {
                    if (contentView == null) return;
                    contentView.setText(s == null ? "" : s);
                    if (toggleView != null) {
                        toggleView.setVisibility(View.GONE);
                        toggleView.setTag(Boolean.FALSE);
                        toggleView.setText("展开全文");
                    }
                    contentView.setMaxLines(6);
                    contentView.setEllipsize(TextUtils.TruncateAt.END);
                    clampContent();
                    updateToggle();
                }
            });
            return this;
        }

        /** 文本被截断时才显示「展开全文」 */
        private void updateToggle() {
            if (contentView == null || toggleView == null) return;
            contentView.post(new Runnable() {
                @Override public void run() {
                    try {
                        if (Boolean.TRUE.equals(toggleView.getTag())) return;
                        android.text.Layout lay = contentView.getLayout();
                        if (lay == null) return;
                        int lines = lay.getLineCount();
                        int last = lines - 1;
                        boolean truncated = lines > 6
                                || (last >= 0 && (lay.getEllipsisCount(last) > 0
                                || lay.getLineEnd(last) < contentView.length()));
                        toggleView.setVisibility(truncated ? View.VISIBLE : View.GONE);
                    } catch (Throwable ignored) {
                    }
                }
            });
        }

        /** 内容超过上限才滚动；短内容让卡片自然收缩 */
        private void clampContent() {
            if (scroll == null || contentView == null) return;
            scroll.post(new Runnable() {
                @Override public void run() {
                    try {
                        int avail = scroll.getWidth() - scroll.getPaddingLeft() - scroll.getPaddingRight();
                        if (avail <= 0) return;
                        contentView.measure(
                                View.MeasureSpec.makeMeasureSpec(avail, View.MeasureSpec.AT_MOST),
                                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                        int h = Math.min(contentView.getMeasuredHeight(), contentMaxPx);
                        ViewGroup.LayoutParams lp = scroll.getLayoutParams();
                        if (lp != null && lp.height != h) {
                            lp.height = h;
                            scroll.setLayoutParams(lp);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
        }

        Card progress(final int percent, final String text) {
            UI.post(new Runnable() {
                @Override public void run() {
                    if (progRow != null) progRow.setVisibility(View.VISIBLE);
                    if (bar != null) bar.setProgress(Math.max(0, Math.min(100, percent)));
                    if (progressView != null) progressView.setText(text == null ? "" : text);
                }
            });
            return this;
        }

        Card buttons(String left, final Runnable leftAction, String right, final Runnable rightAction) {
            final String l = left, r = right;
            UI.post(new Runnable() {
                @Override public void run() {
                    if (leftBtn != null) {
                        leftBtn.setText(l);
                        leftBtn.setOnClickListener(new View.OnClickListener() {
                            @Override public void onClick(View v) {
                                if (leftAction != null) leftAction.run();
                            }
                        });
                    }
                    if (rightBtn != null) {
                        rightBtn.setText(r);
                        rightBtn.setOnClickListener(new View.OnClickListener() {
                            @Override public void onClick(View v) {
                                if (rightAction != null) rightAction.run();
                            }
                        });
                    }
                }
            });
            return this;
        }

        /** 收起卡片但保留进度对象，下载继续；用于「后台更新」→ 胶囊 */
        void minimize() {
            UI.post(new Runnable() {
                @Override public void run() {
                    if (!attached || overlay == null) return;
                    try {
                        WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
                        if (wm != null) wm.removeView(overlay);
                        attached = false;
                    } catch (Throwable ignored) {
                    }
                }
            });
        }

        /** 从胶囊恢复卡片 */
        void resume() {
            UI.post(new Runnable() {
                @Override public void run() {
                    if (attached || overlay == null || wlp == null) return;
                    try {
                        WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
                        if (wm != null) {
                            wm.addView(overlay, wlp);
                            attached = true;
                        }
                    } catch (Throwable t) {
                        ShellLog.e("Ui", "恢复卡片失败", t);
                    }
                }
            });
        }

        boolean isMinimized() {
            return !attached;
        }

        /** 是否有悬浮窗可用；没有（对话框回退）时不做胶囊 */
        boolean hasOverlay() {
            return overlay != null;
        }

        /** 只留一个按钮（例如「重试」） */
        Card singleButton(String text, final Runnable action) {
            final String t = text;
            UI.post(new Runnable() {
                @Override public void run() {
                    if (leftBtn != null) leftBtn.setVisibility(View.GONE);
                    if (rightBtn != null) {
                        rightBtn.setText(t);
                        rightBtn.setOnClickListener(new View.OnClickListener() {
                            @Override public void onClick(View v) {
                                if (action != null) action.run();
                            }
                        });
                    }
                }
            });
            return this;
        }

        void dismiss() {
            UI.post(new Runnable() {
                @Override public void run() {
                    if (overlay != null) {
                        try {
                            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
                            if (wm != null) wm.removeView(overlay);
                        } catch (Throwable ignored) {
                        }
                        overlay = null;
                        attached = false;
                    }
                    if (dialog != null) {
                        try {
                            dialog.dismiss();
                        } catch (Throwable ignored) {
                        }
                        dialog = null;
                    }
                }
            });
        }
    }

    /* ================= 胶囊（最小化） ================= */

    static final class Pill {
        private final Context ctx;
        private final TextView view;
        private WindowManager wm;
        private boolean attached;

        private Pill(Context c) {
            this.ctx = c;
            view = new TextView(c);
            view.setText("0%");
            view.setTextSize(12);
            view.setTextColor(0xFFFFFFFF);
            view.setGravity(Gravity.CENTER);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(0xE6111111);
            view.setBackground(g);
        }

        static Pill show(final Context c, final Runnable onTap) {
            final Pill p = new Pill(c);
            p.attach(onTap);
            return p;
        }

        private void attach(final Runnable onTap) {
            post(new Runnable() {
                @Override public void run() {
                    attachNow(onTap);
                }
            });
        }

        private void attachNow(final Runnable onTap) {
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    dp(ctx, 56), dp(ctx, 56),
                    Build.VERSION.SDK_INT >= 26
                            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    android.graphics.PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.END;
            lp.x = dp(ctx, 16);
            lp.y = dp(ctx, 120);
            view.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (onTap != null) onTap.run();
                }
            });
            try {
                wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
                if (wm != null) {
                    wm.addView(view, lp);
                    attached = true;
                }
            } catch (Throwable t) {
                ShellLog.w("Ui", "胶囊添加失败，改用通知栏");
            }
        }

        void update(final int pct) {
            UI.post(new Runnable() {
                @Override public void run() {
                    view.setText(Math.max(0, Math.min(100, pct)) + "%");
                }
            });
        }

        void dismiss() {
            UI.post(new Runnable() {
                @Override public void run() {
                    if (!attached || wm == null) return;
                    try {
                        wm.removeView(view);
                    } catch (Throwable ignored) {
                    }
                    attached = false;
                }
            });
        }
    }

    /* ================= 重启提示 ================= */

    static void showRestart(final Context ctx, String label, final Runnable onLater) {
        Card c = Card.show(ctx, "组件库已就绪");
        c.stage(label + " · 下一步：重启游戏生效");
        c.content("组件库已下载并校验完成。\n重启后由组件接管界面；首次安装必须重启，之后的小版本更新可即时生效。");
        c.buttons("稍后", new Runnable() {
            @Override public void run() {
                if (onLater != null) onLater.run();
            }
        }, "立即重启", new Runnable() {
            @Override public void run() {
                restart(ctx);
            }
        });
    }

    static void restart(Context ctx) {
        try {
            if (ctx instanceof Activity) {
                ((Activity) ctx).finish();
            }
            android.os.Process.killProcess(android.os.Process.myPid());
        } catch (Throwable t) {
            ShellLog.e("Ui", "重启失败", t);
        }
    }

    static void errorCard(final Context ctx, final String title, final String msg, final Runnable retry) {
        final Card c = Card.show(ctx, title);
        c.content(msg);
        c.buttons("关闭", new Runnable() {
            @Override public void run() {
                c.dismiss();
            }
        }, retry == null ? "知道了" : "重试", retry == null ? new Runnable() {
            @Override public void run() {
                c.dismiss();
            }
        } : retry);
    }
}
