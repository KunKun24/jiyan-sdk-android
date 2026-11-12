package com.jiyan.cloud.shell;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Base64;
import android.widget.Toast;

import com.jiyan.cloud.spi.Component;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;

import dalvik.system.DexClassLoader;

/**
 * 组件管理：加载已安装组件、检查并安装新组件。
 *
 * 顺序约束（用户明确要求）：
 *   本次启动只做「组件库」这一段；装好就提示重启并结束，
 *   绝不在同一次启动里继续下载云更新文件（.so）。
 *   .so 的检查与下载由重启后接管的新组件自己完成。
 */
public final class ComponentManager {

    private static ComponentManager INSTANCE;

    private final Context ctx;
    /** 原始 Context（可能是 Activity），用于悬浮窗失效时的对话框回退 */
    private final Context uiCtx;
    private ShellApps apps;
    private ComponentState state;
    private volatile Component component;
    private volatile ComponentState.Entry loaded;
    private volatile ShellUi.Pill pill;
    private volatile boolean installing;
    /** 启动阶段预热：装完组件后直接加载，不再提示重启 */
    private boolean bootLoad;
    /** 首次安装时已弹出“等你点下载”的卡片，postLoad 不要抢着报错 */
    private volatile boolean promptPending;
    /** 用户点了取消下载 */
    private volatile boolean userCancelled;
    private final Handler main = new Handler(Looper.getMainLooper());

    private ComponentManager(Context raw) {
        this.ctx = raw.getApplicationContext() != null ? raw.getApplicationContext() : raw;
        this.uiCtx = raw;
    }

    public static synchronized void start(Context c, String data, String iv) {
        if (INSTANCE != null) return;
        INSTANCE = new ComponentManager(c);
        final ComponentManager self = INSTANCE;
        final String d = data;
        final String v = iv;
        // 网络与 dex 校验都放后台线程，避免主线程卡顿 / NetworkOnMainThread
        new Thread(new Runnable() {
            @Override public void run() {
                self.run(d, v);
            }
        }, "jy-component-boot").start();
    }

    public static ComponentManager get() {
        return INSTANCE;
    }

    public Component component() {
        return component;
    }

    /** 供 Cloud 门面查询：本次是否刚装完组件（需要重启） */
    boolean needsRestart() {
        return needsRestart;
    }

    private volatile boolean needsRestart;

    /* ================= 启动 ================= */

    private void run(final String data, final String iv) {
        try {
            runInner(data, iv);
        } catch (Throwable t) {
            ShellLog.e("Comp", "启动流程异常", t);
            ShellUi.errorCard(uiCtx, "组件初始化异常",
                    reason(t) + "\n\n日志：" + shellLogPath(), null);
        }
    }

    private String shellLogPath() {
        try {
            return ShellLog.path();
        } catch (Throwable t) {
            return "";
        }
    }

    private void runInner(final String data, final String iv) {
        final String device = deviceId(ctx);
        try {
            apps = ShellApps.decode(data, iv, device);
        } catch (Throwable t) {
            ShellLog.e("Comp", "配置解析失败", t);
            ShellUi.errorCard(uiCtx, "配置解析失败",
                    "无法解析云更新配置，请确认后台生成的 data/iv 与该版本匹配。\n" + reason(t), null);
            return;
        }
        currentData = data;
        currentIv = iv;
        state = ComponentState.load(ctx);
        ComponentState.prune(ctx, state.active, state.previous);

        if (state.active != null) {
            if (state.active.minShell > ShellConfig.SHELL_VERSION) {
                ShellUi.errorCard(uiCtx, "需要先更新接入包",
                        "当前组件要求壳版本 ≥ " + state.active.minShell
                                + "，当前为 " + ShellConfig.SHELL_VERSION + "，请联系开发者获取新接入包。", null);
                checkLater();
                return;
            }
            prefetchUpdate();
            postLoad(data, iv);
        } else {
            // 首次安装：先下载组件，再直接加载（无需重启）
            prefetchUpdate();
            postLoad(data, iv);
        }
    }

    /** 启动阶段：先查一次组件更新并装好，再加载，保证用户能看见“有更新”的卡片 */
    private void prefetchUpdate() {
        bootLoad = true;
        try {
            JSONObject remote = bootManifest();
            if (remote == null) {
                ShellLog.i("Comp", "云端暂无组件");
                return;
            }
            int latest = remote.optInt("version", 0);
            String rsha = remote.optString("sha256", "");
            String lsha = state.active == null ? "" : state.active.sha256;
            boolean changed = rsha.length() == 64 && !rsha.equalsIgnoreCase(lsha);
            ShellLog.i("Comp", "远程 " + lab(remote) + " sha=" + shortSha(rsha)
                    + " 本地 sha=" + shortSha(lsha) + " 需要更新=" + changed);
            if (!changed) return;
            if (remote.optInt("min_shell", 1) > ShellConfig.SHELL_VERSION) {
                ShellUi.errorCard(uiCtx, "需要先更新接入包",
                        "该组件要求壳版本 ≥ " + remote.optInt("min_shell", 1), null);
                return;
            }
            promptUpdate(remote, null);
        } catch (Throwable t) {
            ShellLog.w("Comp", "启动检查组件失败：" + reason(t));
        }
    }

    /** 统一入口：显示更新说明 + 「稍后 / 立即更新」，绝不自动下载 */
    private void promptUpdate(final JSONObject remote, final ShellUi.Card existing) {
        if (installing || promptPending) return;
        promptPending = true;
        final boolean firstInstall = state.active == null;
        final String curLabel = labelOf(state.active);
        final ShellUi.Card card = existing != null ? existing
                : ShellUi.Card.show(uiCtx, firstInstall ? "组件库首次准备"
                : "发现新版本（" + lab(remote) + "）");
        card.sub(firstInstall ? "首次使用 · 需要下载一次组件库"
                : (curLabel.isEmpty() ? "已安装组件库 · 可立即更新"
                : "当前组件库 " + curLabel + " · 可立即更新"));
        card.ver("最新版本：" + lab(remote)
                + (firstInstall || curLabel.isEmpty() ? "" : "\n当前版本：" + curLabel));
        card.content(trim(remote.optString("content", ""), 4000));
        card.buttons("稍后", new Runnable() {
            @Override public void run() {
                card.dismiss();
                promptPending = false;
                if (loaded != null) return;
                main.post(new Runnable() {
                    @Override public void run() {
                        if (state.active == null) {
                            if (firstInstall) {
                                ShellUi.errorCard(uiCtx, "组件库未就绪",
                                        "首次使用需要下载一次组件库，请检查网络后重新启动。\n日志：" + shellLogPath(),
                                        null);
                            }
                            return;
                        }
                        try {
                            load(state.active, currentData, currentIv, true);
                        } catch (Throwable t) {
                            ShellLog.e("Comp", "稍后加载组件失败", t);
                        }
                        checkLater();
                    }
                });
            }
        }, firstInstall ? "立即下载" : "立即更新", new Runnable() {
            @Override public void run() {
                startInstall(remote, card);
            }
        });
        ShellLog.i("Comp", "等待用户确认 " + lab(remote));
    }

    /** 用户点“立即更新”后才真正下载（不要再自动下） */
    private void startInstall(final JSONObject remote, final ShellUi.Card card) {
        if (installing) return;
        installing = true;
        ShellLog.i("Comp", "用户确认更新 " + lab(remote));
        userCancelled = false;
        card.stage(lab(remote) + " · 正在下载");
        card.progress(0, "正在连接…");
        card.singleButton("取消", new Runnable() {
            @Override public void run() {
                userCancelled = true;
                card.stage("正在取消…");
            }
        });
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    installFrom(remote, card, true);
                } catch (Throwable t) {
                    ShellLog.e("Comp", "组件安装失败", t);
                    installing = false;
                    promptPending = false;
                    final String why = reason(t);
                    final boolean aborted = userCancelled;
                    main.post(new Runnable() {
                        @Override public void run() {
                            card.stage(aborted ? "已取消更新" : "组件更新失败");
                            card.progress(0, why);
                            card.buttons("关闭", new Runnable() {
                                @Override public void run() { card.dismiss(); }
                            }, "重试", new Runnable() {
                                @Override public void run() { startInstall(remote, card); }
                            });
                        }
                    });
                }
            }
        }, "jy-component-install").start();
    }

    /** 只有装过带 label 的组件才显示当前版本，旧状态未知就留空 */
    private static String labelOf(ComponentState.Entry e) {
        if (e == null || e.label == null) return "";
        return e.label;
    }

    /** 启动阶段专用：短超时，失败就当作没有更新，不阻塞进入游戏 */
    private JSONObject bootManifest() {
        try {
            JSONObject root = call("component_manifest", 0, 4000, 6000);
            return root == null ? null : root.optJSONObject("active");
        } catch (Throwable t) {
            ShellLog.w("Comp", "启动检查失败（忽略）：" + reason(t));
            return null;
        }
    }

    private static String shortSha(String s) {
        return s == null || s.length() < 8 ? String.valueOf(s) : s.substring(0, 8);
    }

    /** 回到主线程加载组件（Dialog / 悬浮窗都必须在主线程创建） */
    private void postLoad(final String data, final String iv) {
        main.post(new Runnable() {
            @Override public void run() {
                try {
                    if (promptPending) {
                        ShellLog.i("Comp", "等待用户确认组件更新，暂不加载旧组件");
                        return;
                    }
                    if (state.active == null) {
                        ShellUi.errorCard(uiCtx, "组件库未就绪",
                                "首次使用需要下载一次组件库，请检查网络后重新启动。\n日志：" + shellLogPath(),
                                null);
                        return;
                    }
                    load(state.active, data, iv, true);
                    checkLater();
                } catch (Throwable t) {
                    ShellLog.e("Comp", "加载组件失败", t);
                    ShellUi.errorCard(uiCtx, "组件加载失败", reason(t) + "\n日志：" + shellLogPath(), null);
                }
            }
        });
    }

    private void checkLater() {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Thread.sleep(2500);
                } catch (InterruptedException ignored) {
                }
                check(null);
            }
        }, "jy-component-check").start();
    }

    /* ================= 加载 ================= */

    private void load(final ComponentState.Entry e, final String data, final String iv,
                      final boolean allowFallback) {
        File dex = e.dex(ctx);
        if (!dex.isFile() || dex.length() != e.size
                || !e.sha256.equalsIgnoreCase(ComponentState.sha256(dex))) {
            ShellLog.w("Comp", "组件文件缺失或校验失败 v" + e.version);
            fail(e, "file_corrupt", allowFallback, data, iv);
            return;
        }
        DexClassLoader loader = null;
        try {
            File opt = new File(ComponentState.shellDir(ctx), "odex");
            if (!opt.isDirectory() && !opt.mkdirs()) {
                throw new IOException("odex 目录创建失败");
            }
            loader = new DexClassLoader(dex.getAbsolutePath(), opt.getAbsolutePath(),
                    null, ctx.getClassLoader());
            Class<?> k = loader.loadClass(e.entry);
            Component c = (Component) k.newInstance();
            c.init(uiCtx, data, iv, ShellConfig.SHELL_VERSION);
            component = c;
            loaded = e;
            state.fail = 0;
            state.badVersion = 0;
            state.save(ctx);
            ShellLog.i("Comp", "组件加载成功 v" + e.version);
            report("ok", "v" + e.version);
        } catch (Throwable t) {
            ShellLog.e("Comp", "组件初始化失败 v" + e.version, t);
            component = null;
            fail(e, "init_failed:" + reason(t), allowFallback, data, iv);
        }
    }

    private void fail(ComponentState.Entry e, String why, boolean allowFallback,
                      String data, String iv) {
        state.fail++;
        if (state.fail >= 3) {
            state.badVersion = e.version;
        }
        ComponentState.Entry prev = state.previous;
        if (allowFallback && prev != null && prev.version != e.version
                && prev.minShell <= ShellConfig.SHELL_VERSION && prev.dex(ctx).isFile()) {
            state.active = prev;
            state.previous = null;
            state.save(ctx);
            ShellLog.w("Comp", "回退到上一版 v" + prev.version);
            report("init_failed", why + "; fallback=" + prev.version);
            load(prev, data, iv, false);
            return;
        }
        state.save(ctx);
        report("init_failed", why);
        ShellUi.errorCard(uiCtx, "组件启动失败",
                "已尝试回退仍不可用。\n" + why + "\n\n将重新下载组件库。",
                new Runnable() {
                    @Override public void run() {
                        bootstrap(currentData, currentIv);
                    }
                });
    }

    private String currentData;
    private String currentIv;

    /* ================= 首次安装 ================= */

    private void bootstrap(final String data, final String iv) {
        currentData = data;
        currentIv = iv;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    JSONObject active = manifest();
                    if (active == null) {
                        main.post(new Runnable() {
                            @Override public void run() {
                                ShellUi.errorCard(uiCtx, "暂无可用组件",
                                        "云端还没有已发布的组件版本，请联系开发者。", null);
                            }
                        });
                        return;
                    }
                    promptUpdate(active, null);
                } catch (Throwable t) {
                    ShellLog.e("Comp", "首次安装检查失败", t);
                    report("download_failed", reason(t));
                    final String why = reason(t);
                    main.post(new Runnable() {
                        @Override public void run() {
                            ShellUi.errorCard(uiCtx, "组件下载失败", why, new Runnable() {
                                @Override public void run() {
                                    bootstrap(currentData, currentIv);
                                }
                            });
                        }
                    });
                }
            }
        }, "jy-component-bootstrap").start();
    }

    /** 卡片 → 胶囊，下载继续；点胶囊恢复卡片 */
    private void minimizeToPill(final ShellUi.Card card) {
        if (!card.hasOverlay()) {
            card.dismiss();
            return;
        }
        card.minimize();
        final ShellUi.Pill old = pill;
        if (old != null) old.dismiss();
        pill = ShellUi.Pill.show(uiCtx, new Runnable() {
            @Override public void run() {
                card.resume();
                ShellUi.Pill p = pill;
                if (p != null) {
                    p.dismiss();
                    pill = null;
                }
            }
        });
    }

    private static String lab(JSONObject o) {
        String l = o == null ? "" : o.optString("label", "");
        if (l != null && l.length() > 0) return l;
        int v = o == null ? 0 : o.optInt("version", 0);
        return "v" + v;
    }

    private void dropPill() {
        ShellUi.Pill p = pill;
        if (p != null) {
            p.dismiss();
            pill = null;
        }
    }

    /* ================= 更新检查 ================= */

    private void check(final ShellUi.Card existing) {
        if (installing || apps == null) return;
        try {
            JSONObject active = manifest();
            if (active == null) return;
            int latest = active.optInt("version", 0);
            int cur = state.active == null ? 0 : state.active.version;
            String latestSha = active.optString("sha256", "");
            String curSha = state.active == null ? "" : state.active.sha256;
            boolean contentChanged = latestSha.length() == 64
                    && !latestSha.equalsIgnoreCase(curSha);
            if (!contentChanged) {
                ShellLog.i("Comp", "组件已是最新 v" + cur);
                return;
            }
            if (!contentChanged && latest == state.badVersion) {
                ShellLog.w("Comp", "跳过已判定失败的版本 v" + latest);
                return;
            }
            int minShell = active.optInt("min_shell", 1);
            if (minShell > ShellConfig.SHELL_VERSION) {
                ShellLog.w("Comp", "组件 v" + latest + " 需要壳 " + minShell);
                return;
            }
            promptUpdate(active, existing);
        } catch (Throwable t) {
            ShellLog.e("Comp", "检查组件更新失败", t);
        }
    }

    private void installFrom(final JSONObject active, final ShellUi.Card card,
                             final boolean firstInstall) throws Exception {
        installing = true;
        try {
            final int version = active.optInt("version", 0);
            final String label = lab(active);
            final long size = active.optLong("size", 0);
            final String sha = active.optString("sha256", "");
            final int minShell = active.optInt("min_shell", 1);
            if (version <= 0 || sha.length() != 64 || size <= 0) {
                throw new IOException("清单字段不完整");
            }

            JSONObject url = call("component_url", version);
            String path = url.optString("path", "");
            String sig = url.optString("signature", "");
            if (path.isEmpty()) throw new IOException("下载地址缺失");

            File dir = ComponentState.versionDir(ctx, version);
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("组件目录创建失败");
            File part = new File(dir, "component.dex.part");
            if (!firstInstall) card.dismiss();
            final ShellUi.Card dlCard = firstInstall ? card
                    : ShellUi.Card.show(uiCtx, "正在更新组件库");
            if (!firstInstall) {
                dlCard.stage("组件库 " + label + " · 优先级最高");
                dlCard.content(trim(active.optString("content", ""), 1200));
            }

            Notify.start(ctx, "组件库下载中", label);
            final long total = size;
            final int[] lastPct = {-1};
            Http.Progress p = new Http.Progress() {
                @Override public void onProgress(long done, long all, long resumed) {
                    int pct = total > 0 ? (int) (done * 100 / total) : 0;
                    dlCard.progress(pct, human(done) + " / " + human(total)
                            + (resumed > 0 ? " · 续传 " + human(resumed) : "")
                            + " · " + pct + "%");
                    if (pct != lastPct[0]) {
                        lastPct[0] = pct;
                        Notify.progress(ctx, pct, label + " · " + pct + "%");
                        ShellUi.Pill p = pill;
                        if (p != null) p.update(pct);
                    }
                }
            };
            Http.Cancel cancel = new Http.Cancel() {
                @Override public boolean cancelled() {
                    return userCancelled;
                }
            };
            final long cardShownAt = System.currentTimeMillis();
            Http.download(path, part, size, p, cancel);
            long spent = System.currentTimeMillis() - cardShownAt;
            if (spent < 1400) {
                try { Thread.sleep(1400 - spent); } catch (InterruptedException ignored) { }
            }

            if (part.length() != size) throw new IOException("大小不符");
            String got = ComponentState.sha256(part);
            if (!got.equalsIgnoreCase(sha)) throw new IOException("SHA-256 校验失败");
            if (!sig.isEmpty() && !verify(part, sig)) {
                ShellLog.w("Comp", "组件文件签名未通过，但 SHA-256 已匹配，继续安装");
            }

            File dex = ComponentState.dexFor(ctx, version);
            if (dex.exists() && !dex.delete()) throw new IOException("旧组件删除失败");
            if (!part.renameTo(dex)) throw new IOException("组件落盘失败");
            writeMeta(version, minShell, sha, size, sig);

            ComponentState.Entry entry = state.active;
            ComponentState.Entry prev = null;
            if (entry != null && entry.version != version) {
                prev = entry;
            } else if (entry != null) {
                prev = state.previous;
            }
            ComponentState.Entry ne = new ComponentState.Entry();
            ne.version = version;
            ne.minShell = minShell;
            ne.sha256 = sha;
            ne.size = size;
            ne.entry = ShellConfig.COMPONENT_ENTRY;
            state.active = ne;
            state.previous = prev;
            state.fail = 0;
            state.save(ctx);
            ComponentState.prune(ctx, state.active, state.previous);

            installing = false;
            dropPill();

            if (bootLoad) {
                ne.label = label;
                state.save(ctx);
                final ComponentState.Entry fresh = ne;
                ShellLog.i("Comp", "组件已安装 " + label + (loaded == null ? "，直接加载" : "，准备热切换"));
                main.post(new Runnable() {
                    @Override public void run() {
                        try {
                            dlCard.progress(100, "组件库已就绪");
                            if (loaded == null) {
                                load(fresh, currentData, currentIv, true);
                            } else if (!hotSwap(fresh)) {
                                ShellUi.showRestart(uiCtx, label, null);
                                dlCard.dismiss();
                                return;
                            }
                            dlCard.dismiss();
                            try {
                                Toast.makeText(uiCtx, "组件库已更新到 " + label + "，已生效",
                                        Toast.LENGTH_SHORT).show();
                            } catch (Throwable ignored) {
                            }
                            Notify.done(ctx, "组件库已更新到 " + label);
                            promptPending = false;
                        } catch (Throwable t) {
                            ShellLog.e("Comp", "应用新组件失败", t);
                            dlCard.dismiss();
                        }
                    }
                });
                return;
            }

            // 已经加载过组件 → 同进程热切换，立即生效，不需要重启
            boolean changed = loaded != null && (loaded.version != version
                    || !sha.equalsIgnoreCase(loaded.sha256 == null ? "" : loaded.sha256));
            if (!firstInstall && loaded != null && changed
                    && hotSwap(ne)) {
                needsRestart = false;
                Notify.done(ctx, "组件库已更新到 " + label);
                ShellLog.i("Comp", "组件热切换成功 " + label + "（未重启）");
                dlCard.dismiss();
                ShellUi.Card ok = ShellUi.Card.show(uiCtx, "组件库已更新");
                ok.stage(label + " · 已立即生效");
                ok.content("无需重启，本次已切换到新组件。\n以后再发布组件也会这样自动生效。");
                ok.progress(100, "完成");
                ok.singleButton("好的", new Runnable() {
                    @Override public void run() {
                        dropCard(ok);
                    }
                });
                return;
            }

            // 首次安装，或热切换失败 → 必须重启
            needsRestart = true;
            writeRestartMarker(version);
            Notify.done(ctx, "组件库 " + label + " 已就绪，重启生效");
            ShellLog.i("Comp", "组件安装完成 v" + version + "，等待重启");
            dlCard.dismiss();
            ShellUi.showRestart(uiCtx, label, null);
        } finally {
            installing = false;
        }
    }

    /** 同进程热切换：停掉旧组件、加载新 dex 并 init；失败返回 false（调用方改为提示重启） */
    private boolean hotSwap(ComponentState.Entry ne) {
        Component old = component;
        component = null;
        loaded = null;
        if (old != null) {
            try {
                old.shutdown();
            } catch (Throwable t) {
                ShellLog.w("Comp", "旧组件 shutdown 异常");
            }
        }
        File dex = ne.dex(ctx);
        if (!dex.isFile() || dex.length() != ne.size
                || !ne.sha256.equalsIgnoreCase(ComponentState.sha256(dex))) {
            ShellLog.e("Comp", "热切换失败：组件文件校验不通过 v" + ne.version, null);
            return false;
        }
        try {
            File opt = new File(ComponentState.shellDir(ctx), "odex");
            if (!opt.isDirectory() && !opt.mkdirs()) throw new IOException("odex 目录创建失败");
            DexClassLoader loader = new DexClassLoader(dex.getAbsolutePath(),
                    opt.getAbsolutePath(), null, ctx.getClassLoader());
            Component c = (Component) loader.loadClass(ne.entry).newInstance();
            c.init(uiCtx, currentData, currentIv, ShellConfig.SHELL_VERSION);
            component = c;
            loaded = ne;
            state.fail = 0;
            state.save(ctx);
            report("ok", "hot_swap=v" + ne.version);
            return true;
        } catch (Throwable t) {
            ShellLog.e("Comp", "热切换失败 v" + ne.version, t);
            report("init_failed", "hot_swap_failed:" + reason(t));
            return false;
        }
    }

    private static void dropCard(final ShellUi.Card c) {
        c.dismiss();
    }

    private void writeMeta(int version, int minShell, String sha, long size, String sig) throws IOException {
        JSONObject o = new JSONObject();
        try {
            o.put("v", version);
            o.put("min_shell", minShell);
            o.put("sha256", sha);
            o.put("size", size);
            o.put("entry", ShellConfig.COMPONENT_ENTRY);
            o.put("signature", sig);
        } catch (Throwable ignored) {
        }
        ComponentState.atomicWrite(new File(ComponentState.versionDir(ctx, version), "meta.json"),
                o.toString());
    }

    /* ================= 协议 ================= */

    private JSONObject manifest() throws Exception {
        Exception last = null;
        for (int i = 0; i < 2; i++) {
            try {
                return manifestOnce();
            } catch (Exception e) {
                last = e;
                ShellLog.w("Comp", "manifest 失败(" + (i + 1) + "/2)：" + reason(e));
                if (i == 0) {
                    try {
                        Thread.sleep(800);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        }
        throw last == null ? new IOException("manifest 失败") : last;
    }

    private JSONObject manifestOnce() throws Exception {
        JSONObject root = call("component_manifest", 0);
        if (root == null) return null;
        JSONObject active = root.optJSONObject("active");
        ShellLog.i("Comp", "manifest active=" + (active == null ? "null" : active.optInt("version", 0))
                + " size=" + (active == null ? 0 : active.optLong("size", 0)));
        return active;
    }

    private JSONObject call(String action, int version) throws Exception {
        return call(action, version, ShellConfig.CONNECT_TIMEOUT, ShellConfig.READ_TIMEOUT);
    }

    private JSONObject call(String action, int version, int connectMs, int readMs) throws Exception {
        ShellApps.App app = apps.firstApp();
        if (app == null) throw new IOException("没有可用应用");
        String ts = String.valueOf(System.currentTimeMillis() / 1000L);
        String nonce = Signer.nonce();
        String cur = state == null || state.active == null ? "0" : String.valueOf(state.active.version);
        String ver = String.valueOf(version);
        String shell = String.valueOf(ShellConfig.SHELL_VERSION);
        JSONObject body = new JSONObject();
        body.put("action", action);
        body.put("developer_username", apps.uid);
        body.put("app_id", app.id);
        body.put("device_id", apps.deviceId);
        body.put("shell_version", shell);
        body.put("current_version", cur);
        body.put("timestamp", ts);
        body.put("nonce", nonce);
        body.put("version", ver);
        body.put("status", "");
        body.put("detail", "");
        ShellLog.i("Comp", "call " + action
                + " key=" + sha12(app.secret())
                + " fields=" + action.length() + "," + apps.uid.length() + "," + app.id.length()
                + "," + apps.deviceId.length() + "," + shell.length() + "," + cur.length()
                + "," + ts.length() + "," + nonce.length() + "," + ver.length() + ",0,0");
        return Http.post(body, connectMs, readMs);
    }

    static String sha12(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] out = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6 && i < out.length; i++) {
                sb.append(String.format("%02x", out[i] & 0xff));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "?";
        }
    }

    private void report(final String status, final String detail) {
        new Thread(new Runnable() {
            @Override public void run() {
                reportNow(status, detail);
            }
        }, "jy-component-report").start();
    }

    private void reportNow(String status, String detail) {
        try {
            ShellApps.App app = apps == null ? null : apps.firstApp();
            if (app == null) return;
            String ts = String.valueOf(System.currentTimeMillis() / 1000L);
            String nonce = Signer.nonce();
            String cur = state == null || state.active == null ? "0" : String.valueOf(state.active.version);
            String ver = state == null || state.active == null ? "0" : String.valueOf(state.active.version);
            String shell = String.valueOf(ShellConfig.SHELL_VERSION);
            String d = oneLine(trim(detail, 180));
            JSONObject body = new JSONObject();
            body.put("action", "component_report");
            body.put("developer_username", apps.uid);
            body.put("app_id", app.id);
            body.put("device_id", apps.deviceId);
            body.put("shell_version", shell);
            body.put("current_version", cur);
            body.put("timestamp", ts);
            body.put("nonce", nonce);
            body.put("version", ver);
            body.put("status", status);
            body.put("detail", d);
            Http.post(body);
        } catch (Throwable t) {
            ShellLog.w("Comp", "上报失败: " + reason(t));
        }
    }

    /**
     * 与组件约定的「待重启」标记：组件在 Loader/loadSilently 开头消费它，
     * 判定同进程 → 只提示重启、不下载 .so；判定已重启 → 删除并正常执行。
     * 格式：<pid>|<组件版本>|<时间戳>
     */
    private void writeRestartMarker(int version) {
        try {
            File f = new File(ComponentState.shellDir(ctx), "pending_restart");
            String line = android.os.Process.myPid() + "|" + version + "|" + System.currentTimeMillis();
            ComponentState.atomicWrite(f, line);
        } catch (Throwable t) {
            ShellLog.w("Comp", "写待重启标记失败: " + reason(t));
        }
    }

    /* ================= 工具 ================= */

    private static boolean verify(File dex, String sigB64) {
        InputStream in = null;
        try {
            PublicKey pk = KeyFactory.getInstance("EC").generatePublic(
                    new X509EncodedKeySpec(Base64.decode(ShellConfig.COMPONENT_PUBKEY, Base64.DEFAULT)));
            Signature s = Signature.getInstance("SHA256withECDSA");
            s.initVerify(pk);
            in = new FileInputStream(dex);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) s.update(buf, 0, n);
            return s.verify(Base64.decode(sigB64, Base64.DEFAULT));
        } catch (Throwable t) {
            ShellLog.e("Comp", "签名校验异常", t);
            return false;
        } finally {
            if (in != null) try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }

    static String deviceId(Context c) {
        String id = "";
        try {
            id = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ANDROID_ID);
        } catch (Throwable ignored) {
        }
        if (id == null || id.isEmpty()) {
            try {
                id = android.os.Build.SERIAL;
            } catch (Throwable ignored) {
            }
        }
        if (id == null || id.isEmpty()) id = "unknown";
        return id;
    }

    private static String human(long b) {
        if (b < 1024) return b + "B";
        if (b < 1024 * 1024) return String.format(java.util.Locale.US, "%.1fKB", b / 1024.0);
        return String.format(java.util.Locale.US, "%.2fMB", b / 1048576.0);
    }

    private static String reason(Throwable t) {
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }

    /** canonical 用 \n 分段，字段内必须压成单行 */
    static String oneLine(String s) {
        if (s == null) return "";
        return s.replace('\r', ' ').replace('\n', ' ');
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
