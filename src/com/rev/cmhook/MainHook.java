package com.rev.cmhook;

import java.io.File;
import java.io.FileWriter;
import java.io.Writer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class MainHook implements IXposedHookLoadPackage {
/**
 * CM Hook — 网易云音乐净化模块 (LSPosed, 单文件全量逻辑)
 * 目标: com.netease.cloudmusic 9.5.96 (versionCode 9005096), 兼容锚点漂移见各节注释
 *
 * 分区目录 (按方法名前缀可检索):
 *   [配置]   loadPrefs/savePref/setHudEnabled          — cmhook_prefs 读写, Application.attach 早载
 *   [日志]   flog/LSP_TAGS/trunc/rotateLogIfHuge       — 双通道: cm_hook.log(全量) + LSPosed(白名单镜像)
 *   [HUD]    hud/pushHud/renderHud/createHud/attachEntryChip/translatePath — 独白悬浮窗+设置入口芯片
 *   [面板]   showSettingsDialog/showProbeDialog/showTabsKeepDialog/mi* — Miuix 风格纯 View 弹窗
 *   [频道]   applyTopTabs/applyTabsCentering/filterHiddenTabs/reapplyLiveFilter/
 *            installFineDataFilter/resolveTabBaseViaDexkit/installTabGuard — 顶栏频道精细控制
 *   [乐迷团] installFansHideEventHook/hideFansGroupEntry/startFansHideWatcher/hideItemOf — 关注页透明化
 *   [卡片]   cardSweepTick/cardScan/replaceVipCard/isVipCardText — 抽屉VIP挽回卡替换为自定义图(方案A)
 *   [DexKit] dexBridge/dexkitFindMethodsByString/dexCacheGet/dexCachePut/dexHealthCheck/dexRebuildCache — 防漂移基建
 *   [去广告] 渠道闸门V()伪装 + LoadingAdManager.E + LoadingAdActivity + 网络层 ad/loading/* 清空 — 三层
 *   [清理]   isHomeFeedUrl/filterHomeFeed/emptyJsonArrayForKey/purgeHomeFeedCache/dumpFeed* — 首页/播客
 *   [防撤回] rv系列/notifyRevoke/hookRevokeHandlerMethod/dexkitFindRevokeHandlers — 三源台账+胶囊浮层
 *   [识曲]   startIdentify — 长按搜索区进识曲(PackageManager 枚举兜底)
 *   [入口]   handleLoadPackage/installBusinessHooks — hook 安装总装线(逐条 try/catch 独立)
 *   [反射]   fieldGet/invoke0/invoke1/callStr/callInt — 宿主类纯反射访问工具
 *
 * 纪律 (历史踩坑沉淀, 违反必翻车):
 *   1. 业务类在 DelegateLastClassLoader(Tinker系), hook 必须从 Activity 实例反拿真身加载器
 *   2. DexKit/搜索遍历严禁主线程 (曾 ANR); 高频路径 (getItem/getItemCount) 严禁写日志
 *   3. 新增 hook 一律 try/catch 独立安装, 失败不连坐
 *   4. 插入新代码用"安全锚点", v5/v6 区间曾有误删史
 */

    private static final String TARGET_PKG = "com.netease.cloudmusic";
    private static final Object LOG_LOCK = new Object();
    private static volatile boolean fileInit = false;
    private static Writer logWriter;
    private static final SimpleDateFormat TS = new SimpleDateFormat("HH:mm:ss.SSS");
    private static final int LOG_ROTATE_BYTES = 100 * 1024 * 1024;   // 100MB 轮转重建, 防日志无限膨胀
    private static int logWriteCount = 0;
    private static String currentLogPath = null;
    private static final Set<String> doneLoaders = new HashSet<String>();
    private static final AtomicBoolean probeInstalled = new AtomicBoolean(false);


    // ===== 项目2: 内心独白 HUD (可拖动/可折叠/大白话/✕关闭) =====
    private static volatile android.widget.TextView hudView;
    private static volatile android.view.ViewGroup hudRoot;   // v12: 容器(文本+✕关闭钮), WM窗口以其为单位
    private static volatile android.os.Handler hudHandler;
    private static volatile android.content.Context hudCtx;
    private static volatile android.view.WindowManager.LayoutParams hudLp;
    private static volatile boolean hudCollapsed = false;
    private static String hudSavedText = "";

    // ===== 设置入口与功能开关 =====
    private static volatile boolean prefHud = true;
    private static volatile boolean prefHudTranslate = true;   // v16: HUD 内容翻译开关
    private static volatile boolean prefAdBlock = true;
    private static volatile boolean prefAntiRevoke = true;  // v13: 消息防撤回
    private static volatile boolean prefHomeClean = true;   // v19: 首页推荐页内容清理(锚点块起整段移除)
    private static volatile boolean prefPodcastClean = true; // v20: 我的-播客-为你推荐 清理
    private static volatile boolean prefProtoCollect = true;  // v22: 协议采集(关=跳过采集hook与DexKit锚点搜索)
    private static volatile boolean prefFansHide = true;      // v31: 关注页隐藏「乐迷团」项
    private static volatile boolean prefCardCustom = true;    // v8.6: 抽屉VIP挽回卡替换为自定义内容
    private static volatile boolean prefIdentifyLongPress = true;  // v48: 长按搜索区=听歌识曲
    private static volatile String prefUiCollapsed = "g3";          // v52: 面板折叠的组 id(逗号分隔)
    private static volatile ClassLoader businessCl = null;    // v22: 业务运行时加载器(供 UI 手动重建缓存用)
    private static volatile String prefTabsKeep = "";   // 保留的频道名逗号分隔, 空=全部保留
    private static volatile boolean prefsLoaded = false;

    // ===== v19: 首页推荐页模块清理 (网络层, 与混淆无关) =====
    // 命中 URL: interface3.music.163.com/eapi/link/page/rcmd/resource/show
    // 机制: 响应体 data.blocks[] 按顺序渲染; 命中锚点块后, 该块及其后所有块整段切除
    private static final String HOME_FEED_URL_KEY = "link/page/rcmd/resource/show";
    // 锚点规则(逗号分隔), 两种写法混合可用:
    //   CODE:PAGE_RECOMMEND_XXX  -> 块 positionCode 精确匹配
    //   TEXT:最近常听            -> 块 JSON 内出现该文本即视为锚点(不依赖 code 名)
    private static volatile String prefHomeCleanAnchor = "";
    private static final String HOME_CLEAN_ANCHOR_DEFAULT = "CODE:PAGE_RECOMMEND_SHORTCUT,TEXT:home_recent_play_module";
    // v7.8: 锚点块消失后的兜底 —— 首页只保留这些 positionCode 的块(其余整段删除)
    private static final String HOME_CLEAN_KEEP_DEFAULT =
            "PAGE_RECOMMEND_DAILY_RECOMMEND,PAGE_RECOMMEND_PRIVATE_RCMD_SONG,PAGE_RECOMMEND_RED_SIMILAR_SONG";
    private static volatile String prefHomeCleanKeep = HOME_CLEAN_KEEP_DEFAULT;
    private static volatile boolean homeCleanArmed = false;   // 首屏命中锚点后, 后续分页批次整批清空
    // v20: 我的页 → 播客 → 「为你推荐」(blockCode=MY_PAGE_PODCAST_RECOMMEND)
    private static final String PODCAST_REC_URL_KEY = "my/podcast/tab/recommend";
    private static volatile int feedDumpCount = 0;
    private static final int FEED_DUMP_MAX = 6;
    private static volatile boolean cmForeground = false;
    private static final java.util.HashMap<Integer, android.view.View> entryChips = new java.util.HashMap<Integer, android.view.View>();

    // v9: 运行时从B0数据里发现的频道清单(含AI写歌等新增频道), 精细勾选框按真实频道动态展示
    private static volatile String[] discoveredTabs = null;
    // v9: 活着的首页顶栏适配器弱引用, 保留清单变更后原地重过滤, 免重启即时生效
    // v11: R8 连字段名都改(listTabs 是 metadata 还原名, 运行时反射不可得) — 改为记住
    //      咽喉触发时的"原始未过滤feed快照 + 咽喉方法名", 偏好变更直接重调咽喉方法喂过滤副本
    private static final java.util.List<TabAdapterRef> liveTabAdapters = new java.util.ArrayList<TabAdapterRef>();

    private static class TabAdapterRef {
        final java.lang.ref.WeakReference<Object> ref;
        volatile java.util.ArrayList<Object> lastFeed;   // 咽喉收到的原始列表快照(未过滤)
        volatile String feedMethod;                       // 咽喉方法名(P0/M0/...)
        TabAdapterRef(Object ad) { this.ref = new java.lang.ref.WeakReference<Object>(ad); }
    }

    private static TabAdapterRef findRef(Object adapter) {
        synchronized (liveTabAdapters) {
            for (TabAdapterRef r : liveTabAdapters) {
                if (r.ref.get() == adapter) return r;
            }
            if (liveTabAdapters.size() > 6) liveTabAdapters.remove(0);
            TabAdapterRef r = new TabAdapterRef(adapter);
            liveTabAdapters.add(r);
            flog("TOPTABS", "记住活适配器: " + adapter.getClass().getName()
                + " (当前" + liveTabAdapters.size() + "个)");
            return r;
        }
    }

    private static void rememberAdapter(Object adapter) {
        try { findRef(adapter); } catch (Throwable t) { }
    }

    private static void rememberFeed(Object adapter, java.util.List<?> original, String method) {
        if (reapplying) return;   // v11: 重调咽喉触发的递归feed不覆盖原始快照(否则丢失隐藏频道, 无法再恢复勾选)
        try {
            TabAdapterRef r = findRef(adapter);
            java.util.ArrayList<Object> snap = new java.util.ArrayList<Object>();
            for (Object o : original) snap.add(o);
            r.lastFeed = snap;
            r.feedMethod = method;
        } catch (Throwable t) { }
    }

    // 重调咽喉期间的递归护栏: 同线程同步调用, volatile 足够
    private static volatile boolean reapplying = false;

    private static void loadPrefs(android.content.Context c) {
        if (prefsLoaded) return;
        try {
            if (c != null) { dexHostCtx = c; dexDomain = null; }   // v21: 早抓宿主 Context, 并让缓存域用真实 versionCode 重算
            android.content.SharedPreferences sp = c.getSharedPreferences("cmhook_prefs", 0);
            prefHud = sp.getBoolean("hud", true);
            prefHudTranslate = sp.getBoolean("hud_translate", true);
            prefAdBlock = sp.getBoolean("adblock", true);
            prefAntiRevoke = sp.getBoolean("antirevoke", true);
            prefHomeClean = sp.getBoolean("home_clean", true);
            prefPodcastClean = sp.getBoolean("podcast_clean", true);
            prefProtoCollect = sp.getBoolean("proto_collect", true);
            prefFansHide = sp.getBoolean("fans_hide", true);
            prefCardCustom = sp.getBoolean("card_custom", true);
            prefIdentifyLongPress = sp.getBoolean("identify_longpress", true);
            prefUiCollapsed = sp.getString("ui_collapsed", "g3");
            prefHomeCleanAnchor = sp.getString("home_clean_anchor", HOME_CLEAN_ANCHOR_DEFAULT);
            prefHomeCleanKeep = sp.getString("home_clean_keep", HOME_CLEAN_KEEP_DEFAULT);
            rvCapRight = sp.getInt("rv_cap_right", -1);
            rvCapBottom = sp.getInt("rv_cap_bottom", -1);
            prefTabsKeep = sp.getString("top_tabs_keep", "");
            prefsLoaded = true;
            flog("SET", "配置载入 hud=" + prefHud + " hudTranslate=" + prefHudTranslate + " adblock=" + prefAdBlock + " antirevoke=" + prefAntiRevoke
                + " homeClean=" + prefHomeClean + " podcastClean=" + prefPodcastClean + " protoCollect=" + prefProtoCollect
                + " anchor=[" + prefHomeCleanAnchor + "]");
            // v19: 丢掉旧的本地 feed 缓存(否则秒开渲染会用未过滤副本)
            // v7.6/v7.7: 开关打开 = ① 切换那一刻立刻清 ② 之后每次冷启动都做一次"智能清"
            //   智能 = 只删含未过滤锚点(PAGE_RECOMMEND_SHORTCUT / home_recent_play_module / 最近常听)的缓存,
            //   已是过滤版的保留 —— 既不漏旧副本, 也不白白丢掉好缓存
            if (prefHomeClean) {
                final boolean firstTime = !sp.getBoolean("home_clean_purged", false);
                try {
                    Thread th = new Thread(new Runnable() {
                        public void run() { purgeHomeFeedCache(false); }
                    }, "cmhook-cache-boot");
                    th.setDaemon(true);
                    th.start();
                } catch (Throwable t2) { purgeHomeFeedCache(false); }
                try { sp.edit().putBoolean("home_clean_purged", true).commit(); } catch (Throwable t2) { }
                if (firstTime) flog("HOME_CLEAN", "首次启用首页清理: 已触发缓存体检");
            }
            applyHud();
        } catch (Throwable t) { }
    }

    private static void savePref(android.content.Context c, String k, boolean v) {
        try {
            c.getSharedPreferences("cmhook_prefs", 0).edit().putBoolean(k, v).commit();
        } catch (Throwable t) { }
    }

    private static void savePrefStr(android.content.Context c, String k, String v) {
        try {
            c.getSharedPreferences("cmhook_prefs", 0).edit().putString(k, v).commit();
        } catch (Throwable t) { }
    }

    private static void setHudEnabled(android.content.Context c, boolean on) {
        prefHud = on;
        savePref(c, "hud", on);
        applyHud();
    }

    private static void applyHud() {
        try {
            if (hudHandler == null) hudHandler = new android.os.Handler(android.os.Looper.getMainLooper());
            hudHandler.post(new Runnable() {
                public void run() {
                    try {
                        boolean want = prefHud && cmForeground && hudCtx != null;
                        if (want && hudRoot == null) createHud();
                        if (hudRoot != null) {
                            int vis = want ? android.view.View.VISIBLE : android.view.View.GONE;
                            if (hudRoot.getVisibility() != vis) hudRoot.setVisibility(vis);
                        }
                    } catch (Throwable t) { }
                }
            });
        } catch (Throwable t) { }
    }

    private static void applyTopTabs(final android.app.Activity act) {
        long[] delays = {200, 700, 2000};
        for (final long d : delays) {
            try {
                android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                h.postDelayed(new Runnable() {
                    public void run() {
                        try {
                            int id = act.getResources().getIdentifier("tabLayout", "id", act.getPackageName());
                            if (id == 0) return;
                            android.view.View v = act.findViewById(id);
                            if (v == null) return;
                            applyTopTabsFine(v, act);
                            // 实例级源头过滤: 从活的arkViewPager拿adapter, 直接删掉listTabs里的隐藏频道
                            // (类hook可能赶不上启动缓存恢复路径, 实例反射不依赖类加载器匹配)
                            try {
                                int vid = act.getResources().getIdentifier("arkViewPager", "id", act.getPackageName());
                                if (vid != 0) {
                                    android.view.View vp = act.findViewById(vid);
                                    if (vp != null) {
                                        Object ad = invoke0(vp, "getAdapter");
                                        if (ad != null) {
                                            Object lt = fieldGet(ad, "listTabs");
                                            if (lt instanceof java.util.List) {
                                                int before = ((java.util.List<?>) lt).size();
                                                filterHiddenTabs((java.util.List<?>) lt);
                                                if (((java.util.List<?>) lt).size() != before) {
                                                    invoke0(ad, "notifyDataSetChanged");
                                                    flog("TOPTABS", "适配器实例过滤: " + before + "->" + ((java.util.List<?>) lt).size() + " 并刷新");
                                                }
                                            }
                                        }
                                    }
                                }
                            } catch (Throwable t2) { }
                        } catch (Throwable t) { }
                    }
                }, d);
            } catch (Throwable t) { }
        }
    }

    private static void applyTopTabsFine(final android.view.View tabLayout, final android.app.Activity act) {
        try {
            android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            h.post(new Runnable() { public void run() {
                try {
                    java.util.List<android.view.View> tabs = new java.util.ArrayList<android.view.View>();
                    walkTabs(tabLayout, tabs);
                    for (android.view.View tab : tabs) {
                        String title = tabText(tab);
                        if (title.length() == 0) continue;
                        boolean keep = prefTabsKeep.length() == 0 || ("," + prefTabsKeep + ",").contains("," + title + ",");
                        int want = keep ? android.view.View.VISIBLE : android.view.View.GONE;
                        if (tab.getVisibility() != want) {
                            tab.setVisibility(want);
                            flog("TOPTABS", "频道[" + title + "] -> " + (keep ? "保留" : "隐藏"));
                        }
                    }
                    installTabGuard(act);
                    centerTabs(tabLayout);   // v14: 剩余频道居中
                } catch (Throwable t) { }
            }});
        } catch (Throwable t) { }
    }

    // ===== v31~v43: 关注页「乐迷团」去除(透明化) =====
    private static final String FANS_TEXT = "乐迷团";

    private static boolean shallowHasFansText(android.view.View v, int depth) {
        if (v == null || depth > 3) return false;
        try {
            if (v instanceof android.widget.TextView) {
                CharSequence cs = ((android.widget.TextView) v).getText();
                if (cs != null && FANS_TEXT.contentEquals(cs)) return true;
            }
            if (v instanceof android.view.ViewGroup) {
                android.view.ViewGroup g = (android.view.ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    if (shallowHasFansText(g.getChildAt(i), depth + 1)) return true;
                }
            }
        } catch (Throwable t) { }
        return false;
    }

    private static void fadeView(android.view.View v) {
        if (v == null) return;
        try { v.setAlpha(0f); } catch (Throwable t) { }
        try { v.setBackground(null); } catch (Throwable t) { }
        try { v.setBackgroundColor(0x00000000); } catch (Throwable t) { }
        try { if (v instanceof android.widget.TextView) ((android.widget.TextView) v).setTextColor(0x00000000); } catch (Throwable t) { }
        try { if (v instanceof android.widget.ImageView) ((android.widget.ImageView) v).setImageAlpha(0); } catch (Throwable t) { }
    }

    private static void hideItemOf(android.view.View tv) {
        try {
            android.view.View cur = tv;
            for (int i = 0; i < 4; i++) {
                fadeView(cur);
                android.view.ViewParent vp = cur.getParent();
                if (!(vp instanceof android.view.View)) break;
                android.view.View parent = (android.view.View) vp;
                try {
                    int w = parent.getWidth();
                    int sw = parent.getResources().getDisplayMetrics().widthPixels;
                    if (w > 0 && w >= sw * 0.6f) break;
                } catch (Throwable t) { }
                cur = parent;
            }
            flog("FANS", "已去除「" + FANS_TEXT + "」(透明化)");
        } catch (Throwable t) { }
    }

    private static void installFansHideEventHook(final ClassLoader cl) {
        try {
            XposedBridge.hookAllMethods(android.view.ViewGroup.class, "addView", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (!prefFansHide) return;
                    try {
                        Object a0 = param.args.length > 0 ? param.args[0] : null;
                        if (!(a0 instanceof android.view.View)) return;
                        android.view.View v = (android.view.View) a0;
                        if (shallowHasFansText(v, 0)) hideItemOf(v);
                    } catch (Throwable t) { }
                }
            });
            XposedBridge.hookAllMethods(android.widget.TextView.class, "setText", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (!prefFansHide) return;
                    try {
                        Object a0 = param.args.length > 0 ? param.args[0] : null;
                        if (a0 == null || !(a0 instanceof CharSequence)) return;
                        if (!FANS_TEXT.contentEquals((CharSequence) a0)) return;
                        if (param.thisObject instanceof android.view.View) hideItemOf((android.view.View) param.thisObject);
                    } catch (Throwable t) { }
                }
            });
            flog("INIT", "关注页乐迷团事件hook已装(addView + setText)");
        } catch (Throwable t) { flog("INIT", "乐迷团事件hook失败: " + t); }
    }

    private static void hideFansGroupEntry(android.app.Activity act, int attempt) {
        if (!prefFansHide) return;
        try {
            android.view.View root = act.getWindow() != null ? act.getWindow().getDecorView() : null;
            if (root == null) return;
            android.view.View tv = findByTextEquals(root, FANS_TEXT, 0);
            if (tv == null) {
                java.util.List<android.view.View> ws = allWindowRoots();
                for (int i = 0; i < ws.size() && tv == null; i++) tv = findByTextEquals(ws.get(i), FANS_TEXT, 0);
            }
            if (tv == null) return;
            hideItemOf(tv);
        } catch (Throwable t) { }
    }

    private static void startFansHideWatcher(final android.app.Activity act) {
        if (!prefFansHide) return;
        try {
            android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            for (int i = 1; i <= 60; i++) {
                final int k = i;
                h.postDelayed(new Runnable() {
                    public void run() { hideFansGroupEntry(act, k); }
                }, 700L * k);
            }
        } catch (Throwable t) { }
    }

    // ===== v15: 标题栏剩余频道居中 — 改用分叉原生 GRAVITY_CENTER 机制 =====
    // 网易的 MusicTabLayout 保留了 Material 的 tabGravity 字段与 applyModeAndGravity():
    //   SCROLLABLE 模式下 tabGravity=1(CENTER) → strip gravity 置 CENTER, tab 整组居中;
    //   onMeasure 里 tabGravity=1 且 tab 放不下(iMax*N > 宽-32dp)时原生自动回落 START+滚动。
    // v14.x 的 wrap_content/清margin/padding 自研方案与该 onMeasure 分发互相打架,
    // 是"切换频道瞬间频道栏闪一下"的根因, 全部移除。
    private static final java.util.Map<android.view.View, Boolean> tabsCenterTracked =
        java.util.Collections.synchronizedMap(new java.util.WeakHashMap<android.view.View, Boolean>());
    private static final android.view.View.OnLayoutChangeListener TABS_RELAYOUT =
        new android.view.View.OnLayoutChangeListener() {
            public void onLayoutChange(android.view.View v, int l, int t, int r, int b,
                int ol, int ot, int or2, int ob) {
                try { applyTabsCentering(v); } catch (Throwable tt) { }
            }
        };
    private static volatile int lastAppliedGravity = -1;   // -1=本次进程未应用过

    private static void centerTabs(final android.view.View tabLayout) {
        if (tabLayout == null) return;
        if (tabsCenterTracked.put(tabLayout, Boolean.TRUE) == null) {
            tabLayout.addOnLayoutChangeListener(TABS_RELAYOUT);
        }
        try {
            tabLayout.post(new Runnable() { public void run() { try { applyTabsCentering(tabLayout); } catch (Throwable t) { } } });
            android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            h.postDelayed(new Runnable() { public void run() { try { applyTabsCentering(tabLayout); } catch (Throwable t) { } } }, 150);
        } catch (Throwable t) { }
    }

    private static volatile int lastVisCount = -1;
    private static volatile int stateLogCount = 0;

    // ===== v21: DexKit 缓存 (免每次冷启动全量搜查) =====
    // 两层缓存:
    //   ① 结果级(决定性): 解析结果(类名/方法签名)存 prefs `cmhook_dex`, 命中即跳过 DexKit 调用 —— 冷启动"零搜查"
    //   ② 库级: DexKitCacheBridge 结果缓存落盘 + 单例 RecyclableBridge(域内复用, idle 自动回收)
    // 缓存域 = 模块逻辑版本 + 目标APP versionCode → APP 升级/模块改锚点即自动换域重查(旧域 key 顺手清理)
    private static final String DEXKIT_CACHE_VER = "v23";
    private static final String DEXKIT_PREFS = "cmhook_dex";
    private static volatile android.content.Context dexHostCtx = null;   // Application.attach 时抓到的宿主 Context
    private static volatile String dexDomain = null;
    private static volatile boolean dexCacheInitTried = false;
    private static volatile org.luckypray.dexkit.DexKitCacheBridge.RecyclableBridge dexRb = null;

    private static android.content.Context dexCtx() {
        android.content.Context c = dexHostCtx;
        if (c != null) return c;
        c = hudCtx;
        if (c != null) { dexHostCtx = c; return c; }
        try {
            java.lang.reflect.Method gm = Class.forName("android.app.ActivityThread").getDeclaredMethod("currentApplication");
            gm.setAccessible(true);
            android.content.Context a = (android.content.Context) gm.invoke(null);
            if (a != null) { dexHostCtx = a; return a; }
        } catch (Throwable t) { }
        return null;
    }

    private static String dexDomainKey() {
        String d = dexDomain;
        if (d != null) return d;
        String ver = "u", fp = "na";
        try {
            android.content.Context c = dexCtxBlocking();
            if (c != null) {
                android.content.pm.PackageInfo pi = c.getPackageManager().getPackageInfo(TARGET_PKG, 0);
                ver = "" + pi.versionCode;
                fp = dexFingerprint(c);
            }
        } catch (Throwable t) { }
        d = DEXKIT_CACHE_VER + "_" + ver + "_" + fp;
        dexDomain = d;
        return d;
    }

    // v22: APP 侧指纹 —— APK 本体(大小+mtime) + Tinker 热补丁目录(名字/大小/mtime 摘要)
    // 用途: versionCode 不变但 dex 被热补丁替换时, 缓存域也会变 → 自动重搜(治 Tinker 静默失效)
    private static String dexFingerprint(android.content.Context c) {
        StringBuilder sb = new StringBuilder();
        try {
            android.content.pm.ApplicationInfo ai = c.getPackageManager().getApplicationInfo(TARGET_PKG, 0);
            if (ai != null && ai.sourceDir != null) {
                java.io.File apk = new java.io.File(ai.sourceDir);
                sb.append(apk.length()).append('_').append(apk.lastModified() / 1000L);
            } else sb.append("na");
        } catch (Throwable t) { sb.append("na"); }
        String tp = "0";
        try {
            String[] cands = {"files/tinker", "tinker", "files/tinkerpatch", "app_tinker", "files/patch", "files/tinker_patch"};
            StringBuilder tb = new StringBuilder();
            java.io.File base = c.getFilesDir() == null ? null : c.getFilesDir().getParentFile();
            if (base != null) {
                for (int i = 0; i < cands.length; i++) {
                    java.io.File dir = new java.io.File(base, cands[i]);
                    if (!dir.isDirectory()) continue;
                    java.io.File[] fs = dir.listFiles();
                    if (fs == null) continue;
                    java.util.Arrays.sort(fs);
                    for (int j = 0; j < fs.length; j++) {
                        tb.append(fs[j].getName()).append(':').append(fs[j].length()).append(':').append(fs[j].lastModified() / 1000L).append(';');
                    }
                }
            }
            if (tb.length() > 0) tp = Integer.toHexString(tb.toString().hashCode());
        } catch (Throwable t) { }
        return sb.toString() + "_" + tp;
    }

    // v22: 丢单个缓存键(自愈: 兜底 0 命中 / 缓存值失效时调用)
    private static void dexCacheDrop(String key) {
        try {
            android.content.Context c = dexCtxBlocking();
            if (c == null) return;
            c.getSharedPreferences(DEXKIT_PREFS, 0).edit().remove(dexDomainKey() + "|" + key).commit();
            flog("DEXKIT", "缓存已丢弃 " + key + " (下轮需要时重搜)");
        } catch (Throwable t) { }
    }

    // v22 L0: 缓存有效性健康检查 —— 只验证缓存里的类/方法在当前 dex 里真能解析(不搜 dex / 不建桥)
    private static String dexHealthCheck(ClassLoader cl) {
        int ok = 0, bad = 0, missing = 0;
        String cls = dexCacheGet("c:RecommendTwoFlowAdapter");
        if (cls == null) missing++;
        else {
            try { cl.loadClass(cls); ok++; }
            catch (Throwable t) { bad++; dexCacheDrop("c:RecommendTwoFlowAdapter"); }
        }
        String rev = dexCacheGet("revoke");
        if (rev == null) missing++;
        else if (methodsResolvable(cl, splitCache(rev))) ok++;
        else { bad++; dexCacheDrop("revoke"); }
        String kk = dexCacheGet("m:xorDecode(ENCODE_SIGN_KEY)");
        if (kk == null) missing++;
        else if (methodsResolvable(cl, splitCache(kk))) ok++;
        else { bad++; dexCacheDrop("m:xorDecode(ENCODE_SIGN_KEY)"); }
        // v8.1: 私信库 DAO 兜底锚点(消息防撤回胶囊的读库路径)
        String dao = dexCacheGet("m:private_chat_message_db");
        if (dao == null) missing++;
        else if (methodsResolvable(cl, splitCache(dao))) ok++;
        else { bad++; dexCacheDrop("m:private_chat_message_db"); }
        return "有效" + ok + "/4 失效" + bad + " 缺缓存" + missing + dexHealthSkipNote();
    }

    // v22: 让"开关对齐"可观测 —— 报告哪些锚点因开关关闭被跳过(不搜 dex)
    private static String dexHealthSkipNote() {
        StringBuilder sb = new StringBuilder();
        if (!prefAntiRevoke) sb.append(" 跳过:防撤回(开关关)");
        if (!prefProtoCollect) sb.append(" 跳过:协议采集(开关关)");
        return sb.length() == 0 ? "" : (" |" + sb);
    }

    // 缓存里的 "类#方法(参数)" 是否仍能在运行时 dex 中解析出同名方法与类
    private static boolean methodsResolvable(ClassLoader cl, String[] hits) {
        if (hits == null || hits.length == 0) return false;
        for (int i = 0; i < hits.length; i++) {
            String[] cm = splitHit(hits[i]);
            if (cm[0].length() == 0) return false;
            try {
                boolean found = false;
                java.lang.reflect.Method[] ms = cl.loadClass(cm[0]).getDeclaredMethods();
                for (int j = 0; j < ms.length; j++) if (ms[j].getName().equals(cm[1])) { found = true; break; }
                if (!found) return false;
            } catch (Throwable t) { return false; }
        }
        return true;
    }

    // v22 L2(手动): 清缓存 + 立刻真搜重建(设置面板「重建 DexKit 缓存」)
    private static void dexRebuildCache(android.content.Context c, final ClassLoader cl) {
        final android.content.Context ctxF = c;
        try {
            c.getSharedPreferences(DEXKIT_PREFS, 0).edit().clear().commit();
            try { org.luckypray.dexkit.DexKitCacheBridge.clearCache(dexDomainKey()); } catch (Throwable t2) { }
            dexDomain = null;
            dexCacheInitTried = false;
            dexRb = null;
            flog("DEXKIT", "手动重建: 缓存已清, 域重算=" + dexDomainKey());
            toast(c, "DexKit 缓存已清, 正在重建…");
        } catch (Throwable t) { flog("DEXKIT", "清缓存失败: " + t); }
        if (cl == null) { flog("DEXKIT", "重建跳过: 无运行时加载器"); return; }
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    long t0 = System.currentTimeMillis();
                    Class<?> b = resolveTabBaseViaDexkit(cl, "RecommendTwoFlowAdapter");
                    flog("DEXKIT", "重建: 频道基类=" + (b != null ? b.getName() : "未命中"));
                    flog("DEXKIT", "重建: 防撤回=" + dexkitFindRevokeHandlers(cl).length + " 处");
                    flog("DEXKIT", "重建: 密钥=" + dexkitFindMethodsByString(cl, "xorDecode(ENCODE_SIGN_KEY)").length + " 处");
                    flog("DEXKIT", "重建完毕(" + (System.currentTimeMillis() - t0) + "ms), 域=" + dexDomainKey());
                    toast(ctxF, "DexKit 缓存重建完成 (" + (System.currentTimeMillis() - t0) + "ms)");
                } catch (Throwable t2) {
                    flog("DEXKIT", "重建失败: " + t2);
                    toast(ctxF, "DexKit 缓存重建失败: " + t2);
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private static String dexCacheGet(String key) {
        try {
            android.content.Context c = dexCtxBlocking();   // 时序兜底: 自检线程可能早于 Application.attach
            if (c == null) return null;
            return c.getSharedPreferences(DEXKIT_PREFS, 0).getString(dexDomainKey() + "|" + key, null);
        } catch (Throwable t) { return null; }
    }

    private static void dexCachePut(String key, String value) {
        try {
            android.content.Context c = dexCtxBlocking();
            if (c == null || value == null) return;
            android.content.SharedPreferences sp = c.getSharedPreferences(DEXKIT_PREFS, 0);
            String prefix = dexDomainKey() + "|";
            java.util.HashSet<String> stale = new java.util.HashSet<String>();
            for (String k : sp.getAll().keySet()) if (!k.startsWith(prefix)) stale.add(k);
            android.content.SharedPreferences.Editor e = sp.edit();
            e.putString(prefix + key, value);
            for (String k : stale) e.remove(k);
            e.commit();
        } catch (Throwable t) { }
    }

    private static String[] splitCache(String s) {
        if (s == null || s.length() == 0) return new String[0];
        return s.split("\n");
    }

    private static String joinCache(java.util.List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size(); i++) { if (i > 0) sb.append('\n'); sb.append(l.get(i)); }
        return sb.toString();
    }

    // "类#方法(参数)" → {类, 方法}
    private static String[] splitHit(String hit) {
        int i = hit.lastIndexOf('#');
        int j = hit.indexOf('(', i + 1);
        if (i < 0 || j < 0) return new String[]{"", ""};
        return new String[]{hit.substring(0, i), hit.substring(i + 1, j)};
    }

    // 等 Application.attach 走完拿到宿主 Context(自检在后台线程, 兜底路径在业务线程; 正常首轮即就绪)
    private static android.content.Context dexCtxBlocking() {
        android.content.Context c = dexCtx();
        if (c != null) return c;
        for (int i = 0; i < 60; i++) {
            try { Thread.sleep(50); } catch (Throwable t) { }
            c = dexCtx();
            if (c != null) return c;
        }
        return null;
    }

    // 库级: 磁盘结果缓存 + 单例桥
    private static void ensureDexCacheInit() {
        if (dexCacheInitTried) return;
        synchronized (MainHook.class) {
            if (dexCacheInitTried) return;
            android.content.Context c = dexCtxBlocking();
            if (c == null) throw new IllegalStateException("宿主Context未就绪, 推迟DexKit初始化");   // 不落 /data/local/tmp, 不建错域缓存
            dexCacheInitTried = true;
            try {
                java.io.File dir = new java.io.File(c.getFilesDir(), "dexkit_cache");
                dir.mkdirs();
                org.luckypray.dexkit.DexKitCacheBridge.init(new DiskCache(dir));
                org.luckypray.dexkit.DexKitCacheBridge.setCachePolicy(new org.luckypray.dexkit.DexKitCacheBridge.CachePolicy());
                try { org.luckypray.dexkit.DexKitCacheBridge.setIdleTimeoutMillis(300000L); } catch (Throwable t2) { }
                flog("DEXKIT", "库级缓存挂载 -> " + dir.getAbsolutePath() + " (域 " + dexDomainKey() + ")");
            } catch (Throwable t) { flog("DEXKIT", "库级缓存不可用(退化直连): " + t); }
        }
    }

    private static org.luckypray.dexkit.DexKitCacheBridge.RecyclableBridge dexBridge(ClassLoader cl) {
        if (!ensureDexkitLoaded()) throw new IllegalStateException("dexkit未加载");
        ensureDexCacheInit();
        org.luckypray.dexkit.DexKitCacheBridge.RecyclableBridge rb = dexRb;
        if (rb == null || rb.isRetired()) {
            rb = org.luckypray.dexkit.DexKitCacheBridge.create(dexDomainKey(), cl);
            dexRb = rb;
            flog("DEXKIT", "bridge 新建(域 " + dexDomainKey() + ")");
        }
        return rb;
    }

    // 文件型缓存实现(Cache 接口): 一 key 一文件, 落宿主 files/dexkit_cache/
    private static final class DiskCache implements org.luckypray.dexkit.DexKitCacheBridge.Cache {
        private final java.io.File dir;
        DiskCache(java.io.File d) { dir = d; try { d.mkdirs(); } catch (Throwable t) { } }
        private java.io.File file(String key) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < key.length() && i < 96; i++) {
                char c = key.charAt(i);
                sb.append(((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-') ? c : '_');
            }
            sb.append('_').append(Integer.toHexString(key.hashCode()));
            return new java.io.File(dir, sb.toString());
        }
        private static String read(java.io.File f) {
            try {
                java.io.FileInputStream in = new java.io.FileInputStream(f);
                byte[] buf = new byte[(int) f.length()];
                int off = 0, n;
                while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
                in.close();
                return new String(buf, 0, off, "UTF-8");
            } catch (Throwable t) { return null; }
        }
        private static void write(java.io.File f, String s) {
            try {
                java.io.FileOutputStream out = new java.io.FileOutputStream(f, false);
                out.write(s.getBytes("UTF-8"));
                out.close();
            } catch (Throwable t) { }
        }
        public String getString(String key, String defValue) {
            try {
                java.io.File f = file(key);
                if (!f.exists()) return defValue;
                String s = read(f);
                return s == null ? defValue : s;
            } catch (Throwable t) { return defValue; }
        }
        public void putString(String key, String value) {
            try { write(file(key), value); } catch (Throwable t) { }
        }
        public java.util.List<String> getStringList(String key, java.util.List<String> defValue) {
            try {
                String s = getString(key, null);
                if (s == null) return defValue;
                java.util.ArrayList<String> out = new java.util.ArrayList<String>();
                int start = 0;
                for (int i = 0; i <= s.length(); i++) {
                    if (i == s.length() || s.charAt(i) == '\n') { out.add(s.substring(start, i)); start = i + 1; }
                }
                return out;
            } catch (Throwable t) { return defValue; }
        }
        public void putStringList(String key, java.util.List<String> value) {
            try {
                StringBuilder sb = new StringBuilder();
                for (String v : value) sb.append(v).append('\n');
                write(file(key), sb.toString());
            } catch (Throwable t) { }
        }
        public void remove(String key) { try { file(key).delete(); } catch (Throwable t) { } }
        public java.util.Collection<String> getAllKeys() { return new java.util.ArrayList<String>(); }
        public void clearAll() {
            try {
                java.io.File[] fs = dir.listFiles();
                if (fs != null) for (java.io.File f : fs) f.delete();
            } catch (Throwable t) { }
        }
    }

    // DexKit 通用: 按 usingString 反查方法, 返回 "类#方法(参数)" 列表
    private static String[] dexkitFindMethodsByString(ClassLoader cl, String anchor) {
        final String ck = "m:" + anchor;
        String cached = dexCacheGet(ck);
        if (cached != null) {
            flog("DEXKIT", "结果级缓存命中 " + ck + " → " + cached.replace("\n", " | "));
            return splitCache(cached);
        }
        try {
            org.luckypray.dexkit.DexKitCacheBridge.RecyclableBridge rb = dexBridge(cl);
            final String a = anchor;
            java.util.List<org.luckypray.dexkit.wrap.DexMethod> list = rb.getMethods(
                new org.luckypray.dexkit.DexKitCacheBridge.RecyclableBridge.FindMethodBuilder() {
                    public void build(org.luckypray.dexkit.query.FindMethod fm) {
                        fm.matcher(new org.luckypray.dexkit.query.matchers.MethodMatcher().addUsingString(a));
                    }
                });
            java.util.ArrayList<String> hits = new java.util.ArrayList<String>();
            if (list != null) {
                for (org.luckypray.dexkit.wrap.DexMethod md : list) {
                    hits.add(md.getClassName() + "#" + md.getName() + "(" + md.getParamTypeNames() + ")");
                }
            }
            dexCachePut(ck, joinCache(hits));
            flog("DEXKIT", "查询并落缓存 " + ck + " → " + hits.size() + " 处");
            return hits.toArray(new String[hits.size()]);
        } catch (Throwable t) {
            flog("DEXKIT", "查询失败 " + ck + ": " + t);
            return new String[0];
        }
    }

    // 渠道无广告闸门的形状校验: 有静态单例 m() 且有无参 boolean V()(语义=needFilterAd, 见 9.5.96 classes7
    // LoadingAdManager$LoadingManager.a0 与 classes4 jk.a)。防 R8 短名被复用后误 hook 无关类。
    private static boolean adGateShape(Class<?> c) {
        try {
            java.lang.reflect.Method v = c.getDeclaredMethod("V");
            if (v.getReturnType() != boolean.class) return false;
            for (java.lang.reflect.Method mm : c.getDeclaredMethods()) {
                if (java.lang.reflect.Modifier.isStatic(mm.getModifiers()) && mm.getName().equals("m")) return true;
            }
        } catch (Throwable t) { }
        return false;
    }

    private static void applyTabsCentering(android.view.View tabLayout) {
        try {
            if (!(tabLayout instanceof android.view.ViewGroup)) return;
            java.util.List<android.view.View> tabs = new java.util.ArrayList<android.view.View>();
            walkTabs(tabLayout, tabs);
            android.view.View tab0 = null;
            int vis = 0, textMax = 0;
            for (android.view.View tv : tabs) {
                if (tv.getVisibility() != android.view.View.VISIBLE || tv.getWidth() <= 0) continue;
                if (tab0 == null) tab0 = tv;
                if (tv instanceof android.view.ViewGroup) {
                    for (int i2 = 0; i2 < ((android.view.ViewGroup) tv).getChildCount(); i2++) {
                        android.view.View ch = ((android.view.ViewGroup) tv).getChildAt(i2);
                        if (ch instanceof android.widget.TextView && ch.getWidth() > textMax) textMax = ch.getWidth();
                    }
                }
                vis++;
            }
            if (tab0 == null || vis == 0) return;
            // 清零原生分发到 TabView 上的水平 margin(观察到末位 tab 被设 568px 左 margin 顶到右端),
            // 否则 gravity CENTER 无法让紧挨的 tab 组居中
            for (android.view.View tv : tabs) {
                android.view.ViewGroup.LayoutParams lp = tv.getLayoutParams();
                if (lp instanceof android.view.ViewGroup.MarginLayoutParams) {
                    android.view.ViewGroup.MarginLayoutParams mlp = (android.view.ViewGroup.MarginLayoutParams) lp;
                    if (mlp.leftMargin != 0 || mlp.rightMargin != 0) {
                        mlp.leftMargin = 0;
                        mlp.rightMargin = 0;
                        tv.setLayoutParams(mlp);
                    }
                }
            }
            Object strip = tab0.getParent();
            if (!(strip instanceof android.view.View)) return;
            Object mt = ((android.view.View) strip).getParent();
            if (!(mt instanceof android.view.View)) return;
            Class<?> c = ((android.view.View) mt).getClass();
            java.lang.reflect.Field fg = null, ff = null;
            Class<?> cc = c;
            while (cc != null && (fg == null || ff == null)) {
                try { if (fg == null) fg = cc.getDeclaredField("tabGravity"); } catch (NoSuchFieldException e) { }
                try { if (ff == null) ff = cc.getDeclaredField("isUseNoteMarginBetweenTab"); } catch (NoSuchFieldException e) { }
                if (fg != null && ff != null) break;
                cc = cc.getSuperclass();
            }
            if (fg == null) { flog("TOPTABS", "居中: 未找到tabGravity字段"); return; }
            fg.setAccessible(true);
            if (ff != null) ff.setAccessible(true);
            int viewport = ((android.view.View) mt).getWidth();
            if (viewport <= 0) return;
            float density = ((android.view.View) mt).getResources().getDisplayMetrics().density;
            // 判据与原生 j.onMeasure 一致: iMax(=最宽tab, 原生 minWidth=文本宽+dpToPx(28))×数量 ≤ 宽 − 2*dpToPx(16)
            int iMax = textMax + (int) (density * 28.0f);
            int inset = (int) (density * 16.0f) * 2;
            boolean fit = (iMax * vis <= viewport - inset);
            int desired = fit ? 1 : 0;
            int curGravity = fg.getInt(mt);
            // 原生溢出时会把 tabGravity 改回 0 — 可见数量未变时尊重它的决定, 不打架
            if (lastVisCount == vis && curGravity == desired && lastAppliedGravity == desired) return;
            lastVisCount = vis;
            if (ff != null && ff.getBoolean(mt)) ff.setBoolean(mt, false);   // 关 weight 摊分(≤5 tab 时原生摊开, 是闪屏+空洞的根源)
            fg.setInt(mt, desired);
            java.lang.reflect.Method m = null;
            Class<?> c2 = c;
            while (c2 != null && m == null) {
                try { m = c2.getDeclaredMethod("applyModeAndGravity"); } catch (NoSuchMethodException e) { c2 = c2.getSuperclass(); }
            }
            if (m == null) { flog("TOPTABS", "居中: 未找到applyModeAndGravity方法"); return; }
            m.setAccessible(true);
            m.invoke(mt);
            lastAppliedGravity = desired;
            flog("TOPTABS", "居中: tabGravity=" + desired + (fit ? "(CENTER 整组居中)" : "(溢出/超限, 原生滚动)")
                + " 可见" + vis + "个 iMax=" + iMax + " viewport=" + viewport);
        } catch (Throwable t) {
            flog("TOPTABS", "居中应用失败: " + t);
        }
    }

    // ===== 频道源头上过滤: 在数据喂给 适配器(页)/TabLayout(标签) 之前就把隐藏频道删掉 =====
    // 咽喉点: ArkFragment.refreshAdapterData(List<HomeTopTabInfo>) — 服务端/缓存频道列表的唯一入口
    //          xo.a.s0(List<HomeTopTabInfo>) — 翻页适配器的数据设置器(兜底)
    // 页面从一开始就不创建, 无需任何滑动拦截
    // ⚠️ 不能用全局flag去重: installBusinessHooks先跑死副本加载器再跑真身加载器,
    //    flag会把真身上的安装跳过, hook落在死副本上永远不触发(DelegateLastClassLoader陷阱);
    //    去重交给 installBusinessHooks 的 doneLoaders(按加载器实例区分)
    private static volatile int filterDbgCount = 0;
    private static volatile int feedLogCount = 0;   // v11: 咽喉feed留痕限频(每进程前20次)

    private static boolean filterHiddenTabs(java.util.List<?> list) {
        if (list == null || prefTabsKeep.length() == 0) return false;
        try {
            if (filterDbgCount < 10) {
                filterDbgCount++;
                StringBuilder dbg = new StringBuilder("源过滤触发: size=" + list.size() + " titles=[");
                for (Object o : list) {
                    String t = "";
                    try { t = (String) invoke0(o, "getTitle"); } catch (Throwable tt) { t = "<ERR:" + tt + ">"; }
                    dbg.append(t).append(",");
                }
                flog("TOPTABS", dbg.append("]").toString());
            }
            java.util.Iterator<?> it = list.iterator();
            java.util.List<String> removed = new java.util.ArrayList<String>();
            while (it.hasNext()) {
                Object o = it.next();
                if (o == null) continue;
                String title = "";
                try { title = (String) invoke0(o, "getTitle"); } catch (Throwable t) { }
                if (title != null && title.length() > 0 && isHiddenTab(title)) {
                    it.remove();
                    removed.add(title);
                }
            }
            if (!removed.isEmpty()) flog("TOPTABS", "源头过滤: 移除频道" + removed);
            return !removed.isEmpty();
        } catch (Throwable t) { }
        return false;
    }

    // v9/v10: 动态记住实际频道清单(无论是否启用过滤), 勾选框按真实频道展示。
    // 只收录"含已知频道标题"的列表(锚点判断), 避免其他pager的标题污染勾选框;
    // 锚点=上次已发现清单∪默认清单, 因此新增频道(AI写歌等)也能被发现
    private static final String[] DEFAULT_TABS = {"心动", "推荐", "音乐", "播客", "听书", "午夜飞行"};

    private static void rememberTabs(java.util.List<?> list) {
        try {
            if (list == null || list.isEmpty()) return;
            String[] known = discoveredTabs != null ? discoveredTabs : DEFAULT_TABS;
            boolean anchor = false;
            for (Object o : list) {
                String t = "";
                try { t = (String) invoke0(o, "getTitle"); } catch (Throwable tt) { }
                if (t == null || t.length() == 0) continue;
                for (String k : known) { if (k.equals(t)) { anchor = true; break; } }
                if (anchor) break;
            }
            if (!anchor) return;
            java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<String>();
            String[] cur = discoveredTabs;
            if (cur != null) for (String s : cur) set.add(s);
            for (Object o : list) {
                String t = "";
                try { t = (String) invoke0(o, "getTitle"); } catch (Throwable tt) { }
                if (t != null && t.length() > 0) set.add(t);
            }
            if (cur == null || set.size() > cur.length) {
                discoveredTabs = set.toArray(new String[set.size()]);
            }
        } catch (Throwable t) { }
    }

    // v11: 保留清单变更后, 对活着的主页适配器原地重过滤 — 不做字段反射(R8连字段名都改),
    //      直接重调记录的咽喉方法(P0/M0)喂"快照按新偏好过滤后的副本", 等价官方clear+addAll+notify。
    //      全程留痕: 热切换失灵时日志能直接定位是"没记住适配器"还是"没拿到feed快照"
    private static void reapplyLiveFilter() {
        int alive = 0, refreshed = 0, dead = 0, nofeed = 0, err = 0;
        try {
            synchronized (liveTabAdapters) {
                for (java.util.Iterator<TabAdapterRef> it = liveTabAdapters.iterator(); it.hasNext(); ) {
                    TabAdapterRef r = it.next();
                    Object ad = r.ref.get();
                    if (ad == null) { it.remove(); dead++; continue; }
                    alive++;
                    try {
                        if (r.feedMethod == null || r.lastFeed == null) {
                            nofeed++;
                            flog("TOPTABS", "适配器" + ad.getClass().getName() + "无feed快照, 跳过(等下次数据咽喉触发)");
                            continue;
                        }
                        java.util.ArrayList<Object> copy = new java.util.ArrayList<Object>(r.lastFeed);
                        filterHiddenTabs(copy);
                        reapplying = true;
                        try { invoke1(ad, r.feedMethod, copy); }
                        finally { reapplying = false; }
                        refreshed++;
                        flog("TOPTABS", "偏好变更即时生效: " + ad.getClass().getName() + "." + r.feedMethod
                            + " 快照" + r.lastFeed.size() + " -> 喂" + copy.size());
                    } catch (Throwable t) {
                        err++;
                        flog("TOPTABS", "活适配器重过滤失败(" + ad.getClass().getName() + "." + r.feedMethod + "): " + t);
                    }
                }
            }
            flog("TOPTABS", "偏好变更重过滤: 活适配器=" + alive + " 已刷新=" + refreshed
                + " 无快照=" + nofeed + " 已失效=" + dead + " 异常=" + err);
        } catch (Throwable t) {
            flog("TOPTABS", "reapplyLiveFilter异常: " + t);
        }
    }

    private static final java.util.Set<String> seenAdapters = new java.util.HashSet<String>();

    // 探针: 记录每个挂到ViewPager2上的适配器类(一次性), 用于定位首页频道pager的真实数据源
    private static void installPagerProbe(ClassLoader cl) {
        try {
            Class<?> vp2 = XposedHelpers.findClass("androidx.viewpager2.widget.ViewPager2", cl);
            XposedBridge.hookAllMethods(vp2, "setAdapter", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        Object ad = param.args.length > 0 ? param.args[0] : null;
                        if (ad == null) return;
                        String cn = ad.getClass().getName();
                        synchronized (seenAdapters) {
                            if (seenAdapters.contains(cn)) return;
                            seenAdapters.add(cn);
                        }
                        flog("INIT", "setAdapter探针: " + cn
                            + " @loader" + Integer.toHexString(System.identityHashCode(ad.getClass().getClassLoader())));
                        // v11: 顶栏适配器可能先于数据咽喉被挂上pager — 探针侧一并记住,
                        // 热切换重过滤不再单纯依赖咽喉hook曾触发过
                        try {
                            if (fieldGet(ad, "listTabs") != null) rememberAdapter(ad);
                        } catch (Throwable t2) { }
                    } catch (Throwable t) { }
                }
            });
        } catch (Throwable t) { }
    }

    // ===== 频道源头过滤 v11: 三层解析(硬编码→反射→DexKit) + 统一数据咽喉 hook =====
    // 核心结论(v9实证): 首页顶栏数据咽喉是 适配器基类里 listTabs.clear()+addAll(data)+notifyDataSetChanged 的
    //   public void xxx(List) 方法 — 9.5.70 为 et.h.B0/et.e.z0, 9.5.96 混淆漂移为 dr.h.P0/dr.e.M0(结构不变)。
    //   在数据进咽喉前剔除隐藏频道 → listTabs 从加载那一刻起就不含隐藏频道:
    //   页面不创建(createFragment按position索引)、getLength()计数不含、左右滑动滑不进隐藏频道页。
    // v10/v11 防混淆: 硬编码名字在 APP 更新后会失效, 补兜底 —
    //   ① 硬编码快路径(9.5.96: dr.h/dr.e; 旧名et.h/et.e保留, loadClass失败即跳过)
    //   ② 反射: 从稳定宿主Fragment类(完整类名不受混淆)字段类型反推适配器基类, 无名字依赖
    //   ③ DexKit 2.2.0: 按字符串特征("RecommendTwoFlowAdapter"/"DiscoveryFragmentAdapter")全量搜索
    private static void installFineDataFilter(ClassLoader cl) {
        int n = 0;
        // ① 硬编码快路径
        String[] hardNames = {"dr.h", "dr.e", "et.h", "et.e"};
        for (String hn : hardNames) {
            try { n += installAdapterDataHook(cl.loadClass(hn), hn); } catch (Throwable t) { }
        }
        if (n > 0) {
            flog("INIT", "频道数据源头过滤hook已装(硬编码快路径 " + n + "处)");
        }
        // ② 反射兜底: 从稳定宿主类字段类型反推适配器基类(不依赖混淆名)
        if (n == 0) {
            String[] anchors = {
                "com.netease.cloudmusic.discovery.view.newframework.BaseArkV3Fragment",
                "com.netease.cloudmusic.discovery.view.newframework.DiscoveryMusicFragment",
                "com.netease.cloudmusic.discovery.view.arkview.ArkFragment",
                "com.netease.cloudmusic.discovery.view.arkview.ArkFragmentV2",
            };
            java.util.HashSet<String> seen = new java.util.HashSet<String>();
            for (String an : anchors) {
                try {
                    Class<?> host = cl.loadClass(an);
                    for (java.lang.reflect.Field f : host.getDeclaredFields()) {
                        Class<?> ft = f.getType();
                        for (int hop = 0; hop < 3 && ft != null; hop++) {
                            if (looksLikeTabAdapterBase(ft)) {
                                String key = ft.getName();
                                synchronized (seen) { if (!seen.add(key)) break; }
                                n += installAdapterDataHook(ft, ft.getName());
                                break;
                            }
                            ft = ft.getSuperclass();
                        }
                    }
                } catch (Throwable t) { }
            }
            if (n > 0) flog("INIT", "频道数据源头过滤hook已装(反射兜底 " + n + "处)");
        }
        // ③ DexKit 兜底: 字符串特征搜索(全量混淆也能命中)
        if (n == 0) {
            String[] anchors = {"RecommendTwoFlowAdapter", "DiscoveryFragmentAdapter"};
            for (String s : anchors) {
                Class<?> base = resolveTabBaseViaDexkit(cl, s);
                if (base != null) n += installAdapterDataHook(base, base.getName());
            }
            if (n > 0) flog("INIT", "频道数据源头过滤hook已装(DexKit兜底 " + n + "处)");
            else flog("INIT", "三层解析全部失败, 频道过滤不可用(各层错误见上)");
        }
        // 稳定名备份(完整类名不受混淆影响, 覆盖其他频道数据路径)
        installStableNameFilters(cl);
    }

    // 特征判定: 抽象类 + 持有ArrayList字段 + 含 public void xxx(java.util.List) 方法 → 疑似顶栏适配器基类
    private static boolean looksLikeTabAdapterBase(Class<?> c) {
        try {
            if (c == null || c == Object.class || !java.lang.reflect.Modifier.isAbstract(c.getModifiers())) return false;
            boolean hasListField = false;
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (f.getType() == java.util.ArrayList.class) { hasListField = true; break; }
            }
            if (!hasListField) return false;
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                if (m.getParameterTypes().length == 1 && m.getParameterTypes()[0] == java.util.List.class
                        && m.getReturnType() == void.class
                        && java.lang.reflect.Modifier.isPublic(m.getModifiers())) return true;
            }
        } catch (Throwable t) { }
        return false;
    }

    // 统一数据咽喉hook: 拦截适配器基类中所有 public void xxx(java.util.List) 数据入口
    private static int installAdapterDataHook(final Class<?> base, String tag) {
        int n = 0;
        try {
            for (java.lang.reflect.Method m : base.getDeclaredMethods()) {
                if (m.getParameterTypes().length != 1 || m.getParameterTypes()[0] != java.util.List.class
                        || m.getReturnType() != void.class
                        || !java.lang.reflect.Modifier.isPublic(m.getModifiers())) continue;
                final String mName = m.getName();
                XposedBridge.hookAllMethods(base, mName, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            if (param.args.length == 0 || !(param.args[0] instanceof java.util.List)) return;
                            java.util.List<?> l = (java.util.List<?>) param.args[0];
                            rememberTabs(l);                       // 动态频道发现(无论是否启用过滤)
                            rememberFeed(param.thisObject, l, mName); // 快照原始feed+方法名, 供热切换重调(过滤前快照)
                            if (feedLogCount < 20) {                // v11: 数据咽喉feed永久留痕(限频), 咽喉漂移可即时发现
                                feedLogCount++;
                                StringBuilder brief = new StringBuilder();
                                for (int i = 0; i < l.size() && i < 10; i++) {
                                    String t = "";
                                    try { t = (String) invoke0(l.get(i), "getTitle"); } catch (Throwable tt) { }
                                    brief.append(t).append(i < l.size() - 1 ? "," : "");
                                }
                                flog("TOPTABS", "数据咽喉feed: " + base.getName() + "." + mName
                                    + " size=" + l.size() + " titles=[" + brief + "]");
                            }
                            if (prefTabsKeep.length() == 0) return;
                            boolean changed = false;
                            try { changed = filterHiddenTabs(l); } catch (Throwable t) { }
                            if (!changed) {
                                // 兜底: data是不可变列表(remove异常被filterHiddenTabs吞掉) → 拷贝替换参数
                                boolean hasHidden = false;
                                for (Object o : l) {
                                    String title = "";
                                    try { title = (String) invoke0(o, "getTitle"); } catch (Throwable tt) { }
                                    if (title != null && title.length() > 0 && isHiddenTab(title)) { hasHidden = true; break; }
                                }
                                if (hasHidden) {
                                    java.util.ArrayList<Object> copy = new java.util.ArrayList<Object>();
                                    for (Object o : l) {
                                        String title = "";
                                        try { title = (String) invoke0(o, "getTitle"); } catch (Throwable tt) { }
                                        if (title != null && title.length() > 0 && isHiddenTab(title)) continue;
                                        copy.add(o);
                                    }
                                    param.args[0] = copy;
                                    flog("TOPTABS", "咽喉参数替换(不可变列表): " + l.size() + "->" + copy.size());
                                }
                            }
                        } catch (Throwable t) { flog("TOPTABS", "咽喉过滤异常: " + t); }
                    }
                });
                n++;
                flog("INIT", "频道数据源头过滤hook已装(" + tag + "." + mName + " ← 数据咽喉)");
            }
        } catch (Throwable t) {
            flog("INIT", "适配器咽喉hook失败(" + tag + "): " + t);
        }
        return n;
    }

    // DexKit 2.2.0 自身不调用 System.loadLibrary, 必须由使用者显式加载。
    // ① System.loadLibrary("dexkit") 依赖模块classloader的native搜索路径(LSPosed通常会配);
    // ② 兜底: 从模块APK里提取 lib/arm64-v8a/libdexkit.so 到可写目录再 System.load
    private static volatile boolean dexkitLibLoaded = false;

    private static synchronized boolean ensureDexkitLoaded() {
        if (dexkitLibLoaded) return true;
        try {
            System.loadLibrary("dexkit");
            dexkitLibLoaded = true;
            return true;
        } catch (Throwable t) {
            flog("INIT", "loadLibrary(dexkit)失败: " + t);
        }
        try {
            java.net.URL u = null;
            try {
                java.security.ProtectionDomain pd = MainHook.class.getProtectionDomain();
                if (pd != null) u = pd.getCodeSource() == null ? null : pd.getCodeSource().getLocation();
            } catch (Throwable t3) { }
            String apk = u != null ? u.getPath() : null;
            java.io.File f = (apk != null && apk.length() > 0) ? new java.io.File(apk) : null;
            if (f == null || !f.exists()) {
                // ART 下 getCodeSource 常为 null: 从模块包名反查 apk 路径
                String modApk = null;
                try {
                    java.lang.reflect.Method gm = Class.forName("android.app.ActivityThread").getDeclaredMethod("currentApplication");
                    gm.setAccessible(true);
                    android.app.Application app = (android.app.Application) gm.invoke(null);
                    if (app != null) {
                        modApk = app.getPackageManager().getApplicationInfo("com.rev.cmhook", 0).sourceDir;
                    }
                } catch (Throwable t2) { }
                f = (modApk != null) ? new java.io.File(modApk) : null;
                if (f == null || !f.exists()) { flog("INIT", "找不到模块APK路径, dexkit so兜底放弃"); return false; }
            }
            java.io.File cacheDir = null;
            try {
                if (hudCtx != null) cacheDir = hudCtx.getCacheDir();
            } catch (Throwable t2) { }
            if (cacheDir == null) cacheDir = new java.io.File("/data/local/tmp");
            cacheDir.mkdirs();
            java.io.File so = new java.io.File(cacheDir, "libdexkit.so");
            java.util.zip.ZipFile zf = new java.util.zip.ZipFile(f);
            String abi = "arm64-v8a";
            try {
                String arch = System.getProperty("os.arch", "").toLowerCase();
                if (!arch.contains("64") && !arch.contains("aarch64")) abi = "armeabi-v7a";
            } catch (Throwable t2) { }
            java.util.zip.ZipEntry e = zf.getEntry("lib/" + abi + "/libdexkit.so");
            if (e == null) e = zf.getEntry("lib/arm64-v8a/libdexkit.so");
            if (e == null) { zf.close(); flog("INIT", "模块APK内无libdexkit.so"); return false; }
            java.io.InputStream in = zf.getInputStream(e);
            java.io.OutputStream out = new java.io.FileOutputStream(so);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close(); out.close();
            zf.close();
            System.load(so.getAbsolutePath());
            dexkitLibLoaded = true;
            flog("INIT", "dexkit so兜底加载成功: " + so.getAbsolutePath());
            return true;
        } catch (Throwable t) {
            flog("INIT", "dexkit so兜底加载失败: " + t);
        }
        return false;
    }

    // DexKit 2.2.0: 按字符串特征搜索全量 dex。create(ClassLoader, true) 用 cookie 枚举内存dex
    // (Tinker patch 是内存dex/压缩apk, false 模式看不到), 失败自动回退 apkPath。混淆全换名也能命中。
    private static Class<?> resolveTabBaseViaDexkit(ClassLoader runtimeCl, String anchorString) {
        final String ck = "c:" + anchorString;
        String cached = dexCacheGet(ck);
        if (cached != null) {
            try {
                Class<?> cc = runtimeCl.loadClass(cached);
                flog("DEXKIT", "结果级缓存命中 " + ck + " → " + cached);
                return cc;
            } catch (Throwable t) {
                flog("DEXKIT", "缓存类名已失效(" + cached + "), 重新查询");
            }
        }
        try {
            org.luckypray.dexkit.DexKitCacheBridge.RecyclableBridge rb = dexBridge(runtimeCl);
            final java.util.ArrayList<String> keys = new java.util.ArrayList<String>();
            keys.add(anchorString);
            java.util.List<org.luckypray.dexkit.wrap.DexClass> list = rb.getClasses(
                new org.luckypray.dexkit.DexKitCacheBridge.RecyclableBridge.FindClassBuilder() {
                    public void build(org.luckypray.dexkit.query.FindClass fc) {
                        org.luckypray.dexkit.query.matchers.ClassMatcher cm = new org.luckypray.dexkit.query.matchers.ClassMatcher();
                        cm.usingStrings(keys);
                        fc.matcher(cm);
                    }
                });
            if (list == null || list.isEmpty()) {
                flog("INIT", "dexkit未命中字符串特征[" + anchorString + "]");
                return null;
            }
            String name = list.get(0).getClassName();
            dexCachePut(ck, name);
            Class<?> c = runtimeCl.loadClass(name);
            flog("INIT", "dexkit解析成功(已落缓存): 特征[" + anchorString + "] -> " + name);
            return c;
        } catch (Throwable t) {
            flog("INIT", "dexkit解析失败(特征[" + anchorString + "]): " + t);
            return null;
        }
    }

    // 稳定名备份hook(完整类名不受混淆影响, 覆盖其他可能的频道数据路径)
    private static void installStableNameFilters(ClassLoader cl) {
        try {
            Class<?> ark = XposedHelpers.findClass(
                "com.netease.cloudmusic.discovery.view.arkview.ArkFragment", cl);
            XposedBridge.hookAllMethods(ark, "refreshAdapterData", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args.length > 0 && param.args[0] instanceof java.util.List) {
                        filterHiddenTabs((java.util.List<?>) param.args[0]);
                    }
                }
            });
            flog("INIT", "备份过滤hook已装(ArkFragment.refreshAdapterData)");
        } catch (Throwable t) { }
        try {
            Class<?> te = XposedHelpers.findClass(
                "com.netease.cloudmusic.discovery.view.module.hometoptab.model.TabExtra", cl);
            XposedBridge.hookAllMethods(te, "getNowChannelItems", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.result instanceof java.util.List) {
                        filterHiddenTabs((java.util.List<?>) param.result);
                    }
                }
            });
            flog("INIT", "备份过滤hook已装(TabExtra.getNowChannelItems)");
        } catch (Throwable t) { }
        try {
            Class<?> dmf = XposedHelpers.findClass(
                "com.netease.cloudmusic.discovery.view.newframework.DiscoveryMusicFragment", cl);
            XposedBridge.hookAllMethods(dmf, "refreshAdapterData", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args.length > 0 && param.args[0] instanceof java.util.List) {
                        filterHiddenTabs((java.util.List<?>) param.args[0]);
                    }
                }
            });
            flog("INIT", "备份过滤hook已装(DiscoveryMusicFragment.refreshAdapterData)");
        } catch (Throwable t) { }
    }

    private static boolean isHiddenTab(String title) {
        return prefTabsKeep.length() != 0 && !("," + prefTabsKeep + ",").contains("," + title + ",");
    }

    // TabView 可见性守卫(纯保险): 万一有边缘路径创建了隐藏频道的TabView, 强制保持 GONE
    private static volatile boolean tabGuardHooked = false;

    private static void installTabGuard(android.content.Context ctx) {
        if (tabGuardHooked) return;
        try {
            ClassLoader cl = ctx.getClassLoader();
            Class<?> tv = XposedHelpers.findClass("com.netease.cloudmusic.theme.ui.tab.MusicTabLayout$TabView", cl);
            XposedBridge.hookAllMethods(tv, "setVisibility", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        if (prefTabsKeep.length() == 0) return;
                        Object vis0 = param.args.length > 0 ? param.args[0] : null;
                        if (!(vis0 instanceof Integer) || ((Integer) vis0) == android.view.View.GONE) return;
                        String title = tabText((android.view.View) param.thisObject);
                        if (title.length() > 0 && isHiddenTab(title)) {
                            param.args[0] = Integer.valueOf(android.view.View.GONE);
                        }
                    } catch (Throwable t) { }
                }
            });
            tabGuardHooked = true;
            flog("INIT", "TabView可见性守卫hook已装(保险)");
        } catch (Throwable t) {
            flog("INIT", "TabView守卫失败: " + t);
        }
    }

    private static void walkTabs(android.view.View v, java.util.List<android.view.View> out) {
        try {
            if (v.getClass().getName().endsWith("MusicTabLayout$TabView")) { out.add(v); return; }
            if (v instanceof android.view.ViewGroup) {
                android.view.ViewGroup g = (android.view.ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) walkTabs(g.getChildAt(i), out);
            }
        } catch (Throwable t) { }
    }

    private static String tabText(android.view.View tab) {
        StringBuilder sb = new StringBuilder();
        try {
            if (tab instanceof android.view.ViewGroup) collectText((android.view.ViewGroup) tab, sb);
        } catch (Throwable t) { }
        return sb.toString().trim();
    }

    private static void collectText(android.view.ViewGroup g, StringBuilder sb) {
        for (int i = 0; i < g.getChildCount(); i++) {
            android.view.View ch = g.getChildAt(i);
            if (ch instanceof android.widget.TextView) sb.append(((android.widget.TextView) ch).getText());
            if (ch instanceof android.view.ViewGroup) collectText((android.view.ViewGroup) ch, sb);
        }
    }

    private static void showTabsKeepDialog(final android.app.Activity act) {
        try {
            float d = miDens(act);
            final String[] KNOWN = (discoveredTabs != null && discoveredTabs.length > 0) ? discoveredTabs : DEFAULT_TABS;
            android.widget.LinearLayout panel = new android.widget.LinearLayout(act);
            panel.setOrientation(android.widget.LinearLayout.VERTICAL);
            panel.setBackground(miBg(TK_SURFACE, 24 * d));
            panel.setPadding((int) (16 * d), (int) (14 * d), (int) (16 * d), (int) (16 * d));
            panel.addView(miText(act, "保留哪些频道", 20, 0xFFFFFFFF, true));
            panel.addView(miText(act, "勾选=保留; 不勾=隐藏。隐藏频道连页面一起不存在, 滑动也进不去。", 12, TK_TEXT_SUB, false));
            android.widget.LinearLayout card = new android.widget.LinearLayout(act);
            card.setOrientation(android.widget.LinearLayout.VERTICAL);
            card.setBackground(miBg(TK_CARD, 18 * d));
            android.widget.LinearLayout.LayoutParams cardLp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            cardLp.setMargins(0, (int) (12 * d), 0, (int) (12 * d));
            panel.addView(card, cardLp);

            final java.util.List<android.widget.CheckBox> cbs = new java.util.ArrayList<android.widget.CheckBox>();
            for (final String name : KNOWN) {
                android.widget.CheckBox cb = new android.widget.CheckBox(act);
                cb.setText(name);
                cb.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
                cb.setTextColor(0xFFF2F2F5);
                try {
                    cb.getClass().getMethod("setButtonTintList", android.content.res.ColorStateList.class)
                        .invoke(cb, android.content.res.ColorStateList.valueOf(TK_PRIMARY));
                } catch (Throwable t3) { }
                cb.setChecked(prefTabsKeep.length() == 0 || ("," + prefTabsKeep + ",").contains("," + name + ","));
                cb.setPadding((int) (14 * d), (int) (10 * d), 0, (int) (10 * d));
                card.addView(cb);
                cbs.add(cb);
            }

            android.widget.LinearLayout btnRow = new android.widget.LinearLayout(act);
            btnRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            android.widget.LinearLayout.LayoutParams gap = new android.widget.LinearLayout.LayoutParams(0, (int) (44 * d), 1f);
            gap.setMargins(0, 0, (int) (7 * d), 0);
            android.widget.LinearLayout.LayoutParams gap2 = new android.widget.LinearLayout.LayoutParams(0, (int) (44 * d), 1f);
            gap2.setMargins((int) (7 * d), 0, 0, 0);
            final android.app.Dialog[] dh = new android.app.Dialog[1];
            android.widget.TextView cancel = miPill(act, "取消", 0xFF2C2C2E, false);
            cancel.setOnClickListener(new android.view.View.OnClickListener() {
                public void onClick(android.view.View v) { try { dh[0].dismiss(); } catch (Throwable t) { } }
            });
            android.widget.TextView save = miPill(act, "保存", TK_PRIMARY, true);
            save.setOnClickListener(new android.view.View.OnClickListener() {
                public void onClick(android.view.View v) {
                    try {
                        StringBuilder keep = new StringBuilder();
                        for (int i = 0; i < cbs.size(); i++) {
                            if (cbs.get(i).isChecked()) {
                                if (keep.length() > 0) keep.append(",");
                                keep.append(KNOWN[i]);
                            }
                        }
                        prefTabsKeep = keep.toString();
                        savePrefStr(act, "top_tabs_keep", prefTabsKeep);
                        applyTopTabs(act);
                        reapplyLiveFilter();
                        flog("SET", "保留频道 -> [" + prefTabsKeep + "]");
                    } catch (Throwable t) { }
                    try { dh[0].dismiss(); } catch (Throwable t2) { }
                }
            });
            btnRow.addView(cancel, gap);
            btnRow.addView(save, gap2);
            panel.addView(btnRow);

            final android.app.Dialog dlg = miDialog(act, panel);
            dh[0] = dlg;
            dlg.show();
        } catch (Throwable t) { flog("SET", "频道勾选失败: " + t); }
    }

    private static String translatePath(String p) {
        if (p.contains("healthstatus")) return "网络体检";
        if (p.contains("upgrade/get")) return "检查版本更新";
        if (p.contains("hotupdate")) return "热更新检查";
        if (p.contains("safe/bindings")) return "查账号绑定";
        if (p.contains("ad/get")) return "拉广告配置";
        if (p.contains("ad/start/info")) return "广告曝光上报";
        if (p.contains("player/url")) return "获取播放地址";
        if (p.contains("play/match")) return "播放匹配";
        if (p.contains("lyric")) return "获取歌词";
        if (p.contains("play-record")) return "读听歌记录";
        if (p.contains("command/report")) return "播放行为上报";
        if (p.contains("position/show/resource")) return "拉运营资源位";
        if (p.contains("rcmd/resource/show")) return "推荐资源上报";
        if (p.contains("minibar")) return "首页迷你吧推荐";
        if (p.contains("encrypt/upload")) return "上报加密埋点";
        if (p.contains("clientlog/upload")) return "上报日志";
        if (p.contains("register/device")) return "设备注册上报";
        if (p.contains("delivery/deliver")) return "配置投递";
        if (p.contains("batch-deliver")) return "批量配置投递";
        if (p.contains("eapi/batch")) return "批量接口";
        if (p.contains("user/setting")) return "读用户设置";
        if (p.contains("device-info")) return "设备信息上报";
        if (p.contains("listen/together")) return "一起听";
        if (p.contains("/im/")) return "私信服务";
        if (p.contains("playlist")) return "歌单";
        if (p.contains("login")) return "登录";
        if (p.contains("cashier")) return "会员收银台";
        if (p.contains("vip")) return "会员服务";
        if (p.contains("dj/")) return "播客";
        if (p.contains("search")) return "搜索";
        if (p.contains("comment")) return "评论";
        if (p.contains("gift")) return "礼物";
        if (p.contains("live")) return "直播";
        if (p.contains("song/")) return "歌曲信息";
        if (p.contains("album")) return "专辑";
        if (p.contains("artist")) return "歌手";
        if (p.contains("api/communication")) return "通知消息";
        if (p.contains("user/notices")) return "通知消息";
        if (p.contains("api/event/user/note")) return "用户动态";
        if (p.contains("play/state/submit")) return "播放状态上报";
        if (p.contains("pl/count")) return "播放计数";
        if (p.contains("api/about/config")) return "关于页配置";
        if (p.contains("link/scene/show")) return "场景资源位";
        if (p.contains("rcmd/block")) return "推荐资源";
        if (p.contains("note/pri")) return "隐私动态设置";
        return null;
    }

    // ===== HUD 显示: 分类着色 + 可选翻译 + 连续去重 =====
    private static final int HUD_C_NET = 0xFF39FF14;    // 网络: 绿
    private static final int HUD_C_KEY = 0xFFFFD24A;    // 密钥/加密: 金
    private static final int HUD_C_BLOCK = 0xFFFF6B6B;  // 拦截/防撤回: 红
    private static final int HUD_C_PAGE = 0xFF5CE1FF;   // 页面/服务: 青
    private static final int HUD_C_RAW = 0xFFB8E6B3;    // 原始模式: 浅绿
    private static final int HUD_MAX_LINES = 7;
    private static final java.util.ArrayDeque<Object[]> hudLines = new java.util.ArrayDeque<Object[]>();
    private static String lastHudText = null;
    private static int lastHudCount = 0;

    private static String hudShort(String s, int n) {
        if (s == null) return "";
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }

    private static void pushHud(String text, int color) {
        if (text == null || text.length() == 0) return;
        synchronized (hudLines) {
            if (text.equals(lastHudText)) {
                lastHudCount++;
                if (!hudLines.isEmpty()) {
                    Object[] first = hudLines.peekFirst();
                    first[0] = text + "  ×" + lastHudCount;
                }
            } else {
                lastHudText = text;
                lastHudCount = 1;
                hudLines.addFirst(new Object[]{text, Integer.valueOf(color)});
                while (hudLines.size() > HUD_MAX_LINES) hudLines.removeLast();
            }
        }
        renderHud();
    }

    private static void renderHud() {
        try {
            if (hudHandler == null) hudHandler = new android.os.Handler(android.os.Looper.getMainLooper());
            hudHandler.post(new Runnable() { public void run() {
                try {
                    if (!prefHud || hudCollapsed || hudView == null) return;
                    android.text.SpannableStringBuilder ssb = new android.text.SpannableStringBuilder();
                    synchronized (hudLines) {
                        boolean first = true;
                        for (Object[] e : hudLines) {
                            if (!first) ssb.append("\n");
                            int st = ssb.length();
                            ssb.append((String) e[0]);
                            ssb.setSpan(new android.text.style.ForegroundColorSpan(((Integer) e[1]).intValue()), st, ssb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                            first = false;
                        }
                    }
                    hudView.setText(ssb);
                } catch (Throwable t) { }
            }});
        } catch (Throwable t) { }
    }

    private static void hud(String tag, String msg) {
        if (!prefHud) return;
        String line = null;
        int color = HUD_C_NET;
        try {
            if (tag.equals("OKHTTP_NEW") || tag.equals("CRONET_REQ") || tag.equals("CRONET_NEW")) {
                color = HUD_C_NET;
                String p = msg;
                int sI = p.indexOf("//");
                if (sI >= 0) p = p.substring(sI + 2);
                int slash = p.indexOf('/');
                if (slash >= 0) p = p.substring(slash + 1);
                int q = p.indexOf('?');
                String query = "";
                if (q >= 0) { query = p.substring(q + 1); p = p.substring(0, q); }
                if (prefHudTranslate) {
                    String t = translatePath(p);
                    if (t == null) { t = p; if (t.length() > 30) t = t.substring(0, 30); }
                    line = "联网 → " + t;
                    if (query.length() > 2 && query.length() <= 50) line += " (" + query + ")";
                    else if (query.length() > 50) line += " (长参数)";
                } else {
                    line = "[NET] " + hudShort(p, 36) + (query.length() > 0 ? "?" + hudShort(query, 24) : "");
                }
            } else if (tag.equals("NMU_SERIALDATA")) {
                color = HUD_C_KEY;
                if (msg.startsWith("/")) {
                    int comma = msg.indexOf(',');
                    String p = comma > 0 ? msg.substring(0, comma) : msg;
                    if (prefHudTranslate) {
                        String t = translatePath(p);
                        line = "加密签名 → " + (t != null ? t : hudShort(p, 40));
                    } else {
                        line = "[SIGN] " + hudShort(msg, 60);
                    }
                }
            } else if (tag.equals("NMU_SERIALURL")) {
                color = HUD_C_KEY;
                line = prefHudTranslate ? "签名URL → " + hudShort(msg, 44) : "[SIGNURL] " + hudShort(msg, 60);
            } else if (tag.equals("XOR_DECODE")) {
                color = HUD_C_KEY;
                line = prefHudTranslate ? "解开密文 → " + hudShort(msg, 44) : "[XOR] " + hudShort(msg, 60);
            } else if (tag.equals("URS_DAT")) {
                color = HUD_C_KEY;
                line = prefHudTranslate ? "[密钥] 登录密钥库解密" : "[URS_DAT] decrypt";
            } else if (tag.equals("SESSION")) {
                color = HUD_C_KEY;
                line = prefHudTranslate ? "[密钥] 会话密钥协商" : "[SESSION] " + hudShort(msg, 50);
            } else if (tag.equals("UPDATE_PUBKEY")) {
                color = HUD_C_KEY;
                line = prefHudTranslate ? "[密钥] 公钥更新" : "[PUBKEY] " + hudShort(msg, 50);
            } else if (tag.equals("ENCODE_SIGN_KEY")) {
                color = HUD_C_KEY;
                line = prefHudTranslate ? "[密钥] 签名密钥已解锁" : "[ENCODE_SIGN_KEY]";
            } else if (tag.equals("ENCODE_STATIC_KEY")) {
                color = HUD_C_KEY;
                line = prefHudTranslate ? "[密钥] 静态密钥已解锁" : "[ENCODE_STATIC_KEY]";
            } else if (tag.equals("CMENC_IN")) {
                color = HUD_C_KEY;
                line = prefHudTranslate ? "[加密] 请求体加密" : "[CMENC_IN] " + hudShort(msg, 50);
            } else if (tag.equals("CMENC_OUT")) {
                color = HUD_C_KEY;
                line = prefHudTranslate ? "[加密] 加密完成" : "[CMENC_OUT] " + hudShort(msg, 50);
            } else if (tag.equals("CAESAR_ENC_IN") || tag.equals("CAESAR_ENC_OUT") || tag.equals("CAESAR_DEC_IN") || tag.equals("CAESAR_DEC_OUT")) {
                color = HUD_C_KEY;
                line = prefHudTranslate ? "[加密] caesar通道活动" : "[" + tag + "]";
            } else if (tag.equals("CIPHER")) {
                color = HUD_C_KEY;
                if (prefHudTranslate) {
                    String s = msg;
                    int bar = s.indexOf(" | ");
                    if (bar > 0) s = s.substring(0, bar);
                    s = s.replace("in=", "").replace("out=", "→").replace(" ", "");
                    line = "加解密 → " + s;
                } else {
                    line = "[CIPHER] " + hudShort(msg, 60);
                }
            } else if (tag.equals("ADBLOCK")) {
                color = HUD_C_BLOCK;
                line = (prefHudTranslate ? "[拦截] " : "[ADBLOCK] ") + hudShort(msg, 50);
            } else if (tag.equals("ANTIREVOKE")) {
                color = HUD_C_BLOCK;
                line = prefHudTranslate ? "[防撤回] 撤回已拦截, 原文保留" : "[ANTIREVOKE] " + hudShort(msg, 50);
            } else if (tag.equals("HOME_CLEAN")) {
                color = HUD_C_BLOCK;
                if (prefHudTranslate) {
                    line = msg.indexOf("未命中") >= 0 ? "[首页清理] 无匹配模块(未动)" : "[首页清理] 已移除「最近常听」及以下模块";
                } else {
                    line = "[HOME_CLEAN] " + hudShort(msg, 60);
                }
            } else if (tag.equals("FEED")) {
                color = HUD_C_NET;
                line = (prefHudTranslate ? "首页数据 → " : "[FEED] ") + hudShort(msg, 44);
            } else if (tag.equals("FANS")) {
                color = HUD_C_BLOCK;
                line = (prefHudTranslate ? "[关注页] " : "[FANS] ") + hudShort(msg, 50);
            } else if (tag.equals("IDENTIFY")) {
                color = HUD_C_PAGE;
                line = (prefHudTranslate ? "识曲 → " : "[IDENTIFY] ") + hudShort(msg, 50);
            } else if (tag.equals("CHIP")) {
                color = HUD_C_PAGE;
                line = prefHudTranslate ? ("模块入口: " + hudShort(msg, 50)) : "[CHIP] " + hudShort(msg, 60);
            } else if (tag.equals("PODCAST_CLEAN")) {
                color = HUD_C_BLOCK;
                line = prefHudTranslate ? "[播客清理] 「为你推荐」已移除" : "[PODCAST_CLEAN] " + hudShort(msg, 50);
            } else if (tag.equals("SANITY")) {
                color = HUD_C_PAGE;
                if (prefHudTranslate) line = msg.replace("Activity.onCreate: com.netease.cloudmusic.activity.", "进入页面: ").replace("Activity.onCreate: com.netease.cloudmusic.", "进入页面: ");
                else line = "[PAGE] " + hudShort(msg, 60);
            } else if (tag.equals("FACADE_PUT")) {
                color = HUD_C_PAGE;
                line = (prefHudTranslate ? "注册服务: " : "[FACADE] ") + hudShort(msg, 44);
            } else if (tag.equals("DPPKG_P") || tag.equals("DPPKG_O")) {
                color = HUD_C_KEY;
                line = (prefHudTranslate ? "打包层 → " : "[DPPKG] ") + hudShort(msg, 44);
            } else if (tag.equals("UTILC_A") || tag.equals("UTILC_B")) {
                color = HUD_C_KEY;
                line = (prefHudTranslate ? "密钥派生 → " : "[UTILC] ") + hudShort(msg, 44);
            } else if (tag.equals("URL_NEW")) {
                color = HUD_C_NET;
                if (prefHudTranslate) line = "新建URL → " + hudShort(msg, 44);
                else line = "[URL] " + hudShort(msg, 60);
            }
        } catch (Throwable t) { }
        if (line == null) return;
        pushHud(line, color);
    }

    private static void createHud() {
        try {
            android.content.Context c = hudCtx;
            if (c == null) return;
            android.widget.FrameLayout root = new android.widget.FrameLayout(c);
            android.widget.TextView tv = new android.widget.TextView(c);
            tv.setTextColor(0xFF39FF14);
            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
            android.graphics.drawable.GradientDrawable hbg = new android.graphics.drawable.GradientDrawable();
            hbg.setColor(0xE6000000);
            hbg.setCornerRadius(18);
            tv.setBackground(hbg);
            tv.setPadding(18, 12, 52, 12);   // 右侧预留 ✕ 徽章宽度, 文字不再被盖
            android.view.View.OnTouchListener touch = new android.view.View.OnTouchListener() {
                float downX, downY;
                int startX, startY;
                boolean moved;
                long downAt;
                public boolean onTouch(android.view.View v, android.view.MotionEvent e) {
                    try {
                        android.view.WindowManager wm = (android.view.WindowManager) c.getSystemService(android.content.Context.WINDOW_SERVICE);
                        int a = e.getActionMasked();
                        if (a == android.view.MotionEvent.ACTION_DOWN) {
                            downX = e.getRawX(); downY = e.getRawY();
                            startX = hudLp.x; startY = hudLp.y;
                            moved = false; downAt = System.currentTimeMillis();
                            return true;
                        } else if (a == android.view.MotionEvent.ACTION_MOVE) {
                            float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                            if (Math.abs(dx) > 10 || Math.abs(dy) > 10) moved = true;
                            if (moved) {
                                hudLp.x = startX + (int) dx;
                                hudLp.y = startY + (int) dy;
                                if (hudRoot != null) wm.updateViewLayout(hudRoot, hudLp);
                            }
                            return true;
                        } else if (a == android.view.MotionEvent.ACTION_UP) {
                            if (moved) {
                                try {
                                    android.content.SharedPreferences sp = c.getSharedPreferences("cmhook_prefs", 0);
                                    sp.edit().putInt("hud_x", hudLp.x).putInt("hud_y", hudLp.y).commit();
                                } catch (Throwable t) { }
                            }
                            if (!moved && System.currentTimeMillis() - downAt < 400) {
                                hudCollapsed = !hudCollapsed;
                                if (hudCollapsed) {
                                    hudSavedText = hudView.getText().toString();
                                    hudView.setText("● 独白(点开)");
                                } else {
                                    hudView.setText(hudSavedText == null ? "" : hudSavedText);
                                }
                            }
                            return true;
                        }
                    } catch (Throwable t) { }
                    return false;
                }
            };
            root.setOnTouchListener(touch);
            root.addView(tv, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
            // v12: 浮窗✕关闭钮(点击=关闭HUD, 与设置开关同路径持久生效)
            android.widget.TextView close = new android.widget.TextView(c);
            close.setText("✕");
            close.setTextColor(0xFFFFFFFF);
            close.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
            close.setGravity(android.view.Gravity.CENTER);
            android.graphics.drawable.GradientDrawable cbg = new android.graphics.drawable.GradientDrawable();
            cbg.setColor(0xFFE74C3C);
            cbg.setCornerRadius(40);
            close.setBackground(cbg);
            close.setClickable(true);
            close.setOnClickListener(new android.view.View.OnClickListener() {
                public void onClick(android.view.View v) {
                    flog("SET", "HUD 已通过浮窗✕关闭(⚙ CM Hook 可重新开启)");
                    try { setHudEnabled(c, false); } catch (Throwable t) { }
                }
            });
            android.widget.FrameLayout.LayoutParams clp = new android.widget.FrameLayout.LayoutParams(
                40, 40, android.view.Gravity.TOP | android.view.Gravity.END);
            clp.setMargins(0, 8, 8, 0);
            root.addView(close, clp);
            android.view.WindowManager wm = (android.view.WindowManager) c.getSystemService(android.content.Context.WINDOW_SERVICE);
            android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams();
            lp.width = android.view.WindowManager.LayoutParams.WRAP_CONTENT;
            lp.height = android.view.WindowManager.LayoutParams.WRAP_CONTENT;
            lp.type = 2038;
            lp.flags = android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            lp.format = android.graphics.PixelFormat.TRANSLUCENT;
            lp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
            lp.x = 12; lp.y = 400;
            try {
                android.content.SharedPreferences sp = c.getSharedPreferences("cmhook_prefs", 0);
                int sx = sp.getInt("hud_x", -1), sy = sp.getInt("hud_y", -1);
                if (sx >= 0 && sy >= 0) {
                    android.util.DisplayMetrics dm = c.getResources().getDisplayMetrics();
                    if (sx < dm.widthPixels - 80 && sy < dm.heightPixels - 80) { lp.x = sx; lp.y = sy; }
                }
            } catch (Throwable t) { }
            wm.addView(root, lp);
            hudLp = lp;
            hudView = tv;
            hudRoot = root;
        } catch (Throwable t) { hudView = null; hudRoot = null; }
    }

    private static void attachEntryChip(final android.app.Activity act) {
        try {
            Integer key = Integer.valueOf(System.identityHashCode(act));
            if (entryChips.containsKey(key)) return;
            android.widget.TextView chip = new android.widget.TextView(act);
            chip.setText("⚙ CM Hook");
            chip.setTextColor(0xFFFFFFFF);
            chip.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
            float den = act.getResources().getDisplayMetrics().density;
            chip.setPadding((int) (den * 18), (int) (den * 9), (int) (den * 18), (int) (den * 9));
            android.graphics.drawable.GradientDrawable chipBg = new android.graphics.drawable.GradientDrawable();
            chipBg.setColor(0xF2C20C0C);
            chipBg.setCornerRadius(den * 24);
            chip.setBackground(chipBg);
            chip.setOnClickListener(new android.view.View.OnClickListener() {
                public void onClick(android.view.View v) {
                    try { flog("SET", "入口点击触发"); showSettingsDialog(act); } catch (Throwable t) { flog("SET", "入口点击异常: " + t); }
                }
            });
            android.view.WindowManager wm = act.getWindowManager();
            android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams();
            lp.width = android.view.WindowManager.LayoutParams.WRAP_CONTENT;
            lp.height = android.view.WindowManager.LayoutParams.WRAP_CONTENT;
            lp.type = 2;
            lp.flags = android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            lp.format = android.graphics.PixelFormat.TRANSLUCENT;
            lp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.END;
            lp.x = 24; lp.y = 260;
            wm.addView(chip, lp);
            entryChips.put(key, chip);
            flog("SET", "设置页模块入口已显示");
        } catch (Throwable t) { flog("SET", "入口显示失败: " + t); }
    }

    // ===== v17: 模块页面 Miuix/HyperOS 风格(纯View复刻, 无外部依赖) =====
    // ===== v54: 设计 token(深色弹窗语言, 三弹窗共用) =====
    private static final int TK_PRIMARY    = 0xFFEC4141;   // 主色(网易云红): 只用于 开态/主按钮/选中
    private static final int TK_SURFACE    = 0xF218181A;   // 面板底
    private static final int TK_CARD       = 0xFF262629;   // 卡片
    private static final int TK_TEXT_MAIN  = 0xE6FFFFFF;   // 主文字 90%
    private static final int TK_TEXT_SUB   = 0x99FFFFFF;   // 次文字 60%
    private static final int TK_TEXT_HINT  = 0x61FFFFFF;   // 提示文字 38%
    private static final int TK_OFF_TRACK  = 0x33FFFFFF;   // 开关关态轨道
    private static final int TK_DIVIDER    = 0x14FFFFFF;   // 分隔线 8%
    // 分组点缀色(低饱和, 只做标识, 不参与操作语义)
    private static final int[] TK_GROUP    = { 0xFFEC4141, 0xFF5B8DEF, 0xFFF0A020, 0xFF4CC38A };

    private static float miDens(android.content.Context c) {
        return c.getResources().getDisplayMetrics().density;
    }

    private static android.graphics.drawable.GradientDrawable miBg(int color, float radius) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private static android.widget.TextView miText(android.content.Context c, String s, float sp, int color, boolean bold) {
        android.widget.TextView tv = new android.widget.TextView(c);
        tv.setText(s);
        tv.setTextColor(color);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return tv;
    }

    private static android.view.View miDivider(android.content.Context c) {
        android.view.View v = new android.view.View(c);
        v.setBackgroundColor(0xFF393939);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, (int) (miDens(c) + 0.5f)));
        lp.setMargins((int) (16 * miDens(c)), 0, (int) (16 * miDens(c)), 0);
        v.setLayoutParams(lp);
        return v;
    }

    private static android.widget.Switch miSwitch(android.content.Context c, boolean checked) {
        android.widget.Switch sw = new android.widget.Switch(c);
        sw.setChecked(checked);
        try { sw.getClass().getMethod("setShowText", boolean.class).invoke(sw, false); } catch (Throwable t) { }
        float d = miDens(c);
        int th = (int) (15 * d);
        android.graphics.drawable.GradientDrawable onT = miBg(TK_PRIMARY, th / 2f);
        android.graphics.drawable.GradientDrawable offT = miBg(0xFF48484A, th / 2f);
        android.graphics.drawable.StateListDrawable track = new android.graphics.drawable.StateListDrawable();
        track.addState(new int[]{android.R.attr.state_checked}, onT);
        track.addState(new int[]{}, offT);
        android.graphics.drawable.GradientDrawable thumb = miBg(0xFFFFFFFF, th / 2f);
        thumb.setSize(th, th);
        int ins = (int) (1.5f * d);
        sw.setThumbDrawable(thumb);
        sw.setTrackDrawable(new android.graphics.drawable.InsetDrawable(track, ins, ins, ins, ins));
        sw.setBackground(null);
        return sw;
    }

    private static android.widget.LinearLayout miRow(android.content.Context c, String title, String summary, android.view.View trailing) {
        float d = miDens(c);
        android.widget.LinearLayout row = new android.widget.LinearLayout(c);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding((int) (16 * d), (int) (11 * d), (int) (16 * d), (int) (11 * d));
        android.widget.LinearLayout col = new android.widget.LinearLayout(c);
        col.setOrientation(android.widget.LinearLayout.VERTICAL);
        col.addView(miText(c, title, 16, 0xFFFFFFFF, false));
        if (summary != null && summary.length() > 0) {
            col.addView(miText(c, summary, 12, TK_TEXT_SUB, false));
        }
        row.addView(col, new android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (trailing != null) {
            android.widget.FrameLayout fl = new android.widget.FrameLayout(c);
            fl.addView(trailing, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.CENTER));
            row.addView(fl, new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        }
        return row;
    }

    private static android.widget.LinearLayout miNav(android.content.Context c, String title, String summary, final Runnable onClick) {
        android.widget.LinearLayout row = miRow(c, title, summary, null);
        android.widget.TextView chev = miText(c, "›", 20, TK_TEXT_HINT, false);
        row.addView(chev, new android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setClickable(true);
        row.setOnClickListener(new android.view.View.OnClickListener() {
            public void onClick(android.view.View v) { onClick.run(); }
        });
        return row;
    }

    private static android.widget.TextView miPill(android.content.Context c, String text, int bg, boolean bold) {
        android.widget.TextView b = new android.widget.TextView(c);
        b.setText(text);
        b.setTextColor(0xFFFFFFFF);
        b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        if (bold) b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        b.setGravity(android.view.Gravity.CENTER);
        b.setBackground(miBg(bg, 26 * miDens(c)));
        return b;
    }

    private static android.app.Dialog miDialog(final android.app.Activity act, android.widget.LinearLayout panel) {
        float d = miDens(act);
        android.widget.ScrollView sc = new android.widget.ScrollView(act);
        sc.addView(panel);
        android.widget.FrameLayout root = new android.widget.FrameLayout(act);
        root.addView(sc);
        root.setPadding((int) (18 * d), (int) (28 * d), (int) (18 * d), (int) (28 * d));
        android.app.Dialog dlg = new android.app.Dialog(act);
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        android.view.Window w = dlg.getWindow();
        w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
        dlg.setContentView(root, new android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        android.view.WindowManager.LayoutParams wlp = w.getAttributes();
        wlp.gravity = android.view.Gravity.CENTER;
        wlp.dimAmount = 0.6f;
        w.setAttributes(wlp);
        return dlg;
    }

    // ===== v52: 设置页重构 —— 分组 + 可折叠(组头 ▾/▸, 状态持久化 ui_collapsed) =====
    private static android.widget.LinearLayout miCard(android.app.Activity act, float d) {
        android.widget.LinearLayout c = new android.widget.LinearLayout(act);
        c.setOrientation(android.widget.LinearLayout.VERTICAL);
        c.setBackground(miBg(TK_CARD, 18 * d));
        return c;
    }

    private static int groupHue(int idx) {
        int i = Math.max(0, Math.min(TK_GROUP.length - 1, idx));
        return TK_GROUP[i];
    }

    private static android.widget.Switch findSwitchIn(android.view.View v, int depth) {
        if (v == null || depth > 6) return null;
        try {
            if (v instanceof android.widget.Switch) return (android.widget.Switch) v;
            if (v instanceof android.view.ViewGroup) {
                android.view.ViewGroup g = (android.view.ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    android.widget.Switch r = findSwitchIn(g.getChildAt(i), depth + 1);
                    if (r != null) return r;
                }
            }
        } catch (Throwable t) { }
        return null;
    }

    // 状态摘要: "3/4 开" / ""(无开关)
    private static String groupStatus(java.util.List<android.view.View> rows) {
        try {
            int on = 0, total = 0;
            for (android.view.View row : rows) {
                android.widget.Switch sw = findSwitchIn(row, 0);
                if (sw != null) { total++; if (sw.isChecked()) on++; }
            }
            return total == 0 ? "" : (on + "/" + total + " 开");
        } catch (Throwable t) { return ""; }
    }

    private static void addGroup(final android.app.Activity act, android.widget.LinearLayout panel, float d,
                                 final String gid, String title, final boolean defaultCollapsed,
                                 final java.util.List<android.view.View> rows, final int idx) {
        // 组头: 标题 + 折叠箭头
        android.widget.LinearLayout head = new android.widget.LinearLayout(act);
        head.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        head.setPadding((int) (6 * d), (int) (14 * d), (int) (6 * d), (int) (6 * d));
        android.widget.TextView dot = miText(act, "●", 10, groupHue(idx), true);   // v54: 组色圆点(标识)
        dot.setPadding(0, 0, (int) (7 * d), 0);
        head.addView(dot);
        android.widget.TextView t = miText(act, title, 14, TK_TEXT_MAIN, true);
        head.addView(t, new android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        String st = groupStatus(rows);                                            // v54: 状态摘要(替代"N 项")
        android.widget.TextView cnt = miText(act, st, 12, TK_TEXT_SUB, false);
        cnt.setPadding(0, 0, (int) (8 * d), 0);
        head.addView(cnt);
        final android.widget.TextView arrow = miText(act, "▾", 13, TK_TEXT_SUB, false);
        head.addView(arrow);
        // 组体
        final android.widget.LinearLayout body = miCard(act, d);
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) { try { body.addView(miDivider(act)); } catch (Throwable tt) { } }
            body.addView(rows.get(i));
        }
        final boolean collapsed0 = isGroupCollapsed(gid, defaultCollapsed);
        body.setVisibility(collapsed0 ? android.view.View.GONE : android.view.View.VISIBLE);
        arrow.setText(collapsed0 ? "▸" : "▾");
        head.setOnClickListener(new android.view.View.OnClickListener() {
            public void onClick(android.view.View v) {
                boolean nowCollapse = (body.getVisibility() == android.view.View.VISIBLE);
                body.setVisibility(nowCollapse ? android.view.View.GONE : android.view.View.VISIBLE);
                arrow.setText(nowCollapse ? "▸" : "▾");
                setGroupCollapsed(act, gid, nowCollapse);
                flog("SET", "面板分组[" + gid + "] " + (nowCollapse ? "折叠" : "展开"));
            }
        });
        android.view.View sp = new android.view.View(act);
        sp.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, (int) (10 * d)));
        panel.addView(sp);
        panel.addView(head);
        panel.addView(body);
    }

    private static boolean isGroupCollapsed(String gid, boolean def) {
        try {
            for (String x : prefUiCollapsed.split(",")) if (x.trim().equals(gid)) return true;
        } catch (Throwable t) { }
        return def;
    }

    private static void setGroupCollapsed(android.content.Context c, String gid, boolean col) {
        try {
            java.util.HashSet<String> set = new java.util.HashSet<String>();
            for (String x : prefUiCollapsed.split(",")) if (x.trim().length() > 0) set.add(x.trim());
            if (col) set.add(gid); else set.remove(gid);
            StringBuilder sb = new StringBuilder();
            for (String x : set) { if (sb.length() > 0) sb.append(','); sb.append(x); }
            prefUiCollapsed = sb.toString();
            if (c != null) savePrefStr(c, "ui_collapsed", prefUiCollapsed);
        } catch (Throwable t) { }
    }

    private static void showSettingsDialog(final android.app.Activity act) {
        try {
            float d = miDens(act);
            android.widget.LinearLayout panel = new android.widget.LinearLayout(act);
            panel.setOrientation(android.widget.LinearLayout.VERTICAL);
            panel.setBackground(miBg(TK_SURFACE, 24 * d));
            panel.setPadding((int) (14 * d), (int) (12 * d), (int) (14 * d), (int) (14 * d));

            // v53: 标题区 —— 红色圆形徽章「CM」+ 标题/副标题列
            android.widget.LinearLayout headRow = new android.widget.LinearLayout(act);
            headRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            headRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
            android.widget.TextView badge = miText(act, "CM", 15, 0xFFFFFFFF, true);
            badge.setGravity(android.view.Gravity.CENTER);
            badge.setBackground(miBg(0xFFEC4141, 999 * d));
            int bs = (int) (42 * d);
            headRow.addView(badge, new android.widget.LinearLayout.LayoutParams(bs, bs));
            android.widget.LinearLayout titleCol = new android.widget.LinearLayout(act);
            titleCol.setOrientation(android.widget.LinearLayout.VERTICAL);
            android.widget.LinearLayout.LayoutParams tclp = new android.widget.LinearLayout.LayoutParams(
                0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tclp.setMargins((int) (10 * d), 0, 0, 0);
            titleCol.addView(miText(act, "CM Hook", 20, 0xFFFFFFFF, true));
            titleCol.addView(miText(act, "网易云音乐 · 10 项开关 / 3 个工具", 12, TK_TEXT_SUB, false));
            headRow.addView(titleCol, tclp);
            panel.addView(headRow);

            // ---------- 组 1: 界面与清理 ----------
            java.util.List<android.view.View> g1 = new java.util.ArrayList<android.view.View>();
            android.widget.Switch swHome = miSwitch(act, prefHomeClean);
            g1.add(miRow(act, "首页内容清理", "移除「最近常听」及以下模块", swHome));
            android.widget.Switch swPod = miSwitch(act, prefPodcastClean);
            g1.add(miRow(act, "播客为你推荐清理", "我的 → 播客 → 移除「为你推荐」", swPod));
            android.widget.Switch swFans = miSwitch(act, prefFansHide);
            g1.add(miRow(act, "关注页乐迷团", "隐藏关注页「乐迷团」项", swFans));
            android.widget.Switch swLp = miSwitch(act, prefIdentifyLongPress);
            g1.add(miRow(act, "长按搜索=识曲", "顶栏搜索区长按进听歌识曲", swLp));
            android.widget.Switch swCard = miSwitch(act, prefCardCustom);
            g1.add(miRow(act, "抽屉VIP卡自定义", "替换抽屉顶部VIP挽回卡(图片放 files/cmhook_drawer_card.png)", swCard));
            addGroup(act, panel, d, "g1", "界面与清理", false, g1, 0);

            // ---------- 抽屉VIP卡图片: 预览 + 选图 (v8.7) ----------
            android.widget.LinearLayout cardBox = miCard(act, d);
            android.widget.ImageView pv = new android.widget.ImageView(act);
            pv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            pv.setAdjustViewBounds(true);
            pv.setMaxHeight((int) (150 * d));
            pv.setVisibility(android.view.View.GONE);
            pv.setBackgroundColor(0xFF1A1A1C);
            cardPreviewView = pv;
            android.widget.LinearLayout.LayoutParams pvl = new android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            cardBox.addView(pv, pvl);
            android.widget.LinearLayout btns = new android.widget.LinearLayout(act);
            btns.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            android.widget.TextView bPick = miPill(act, "选图", TK_PRIMARY, true);
            bPick.setPadding((int) (10 * d), (int) (8 * d), (int) (10 * d), (int) (8 * d));
            bPick.setOnClickListener(new android.view.View.OnClickListener() {
                public void onClick(android.view.View v) { cardPickPhoto(act); }
            });
            android.widget.TextView bEdit = miPill(act, "编辑", 0xFF5B8DEF, true);
            bEdit.setPadding((int) (10 * d), (int) (8 * d), (int) (10 * d), (int) (8 * d));
            bEdit.setOnClickListener(new android.view.View.OnClickListener() {
                public void onClick(android.view.View v) {
                    new Thread(new Runnable() { public void run() {
                        android.graphics.Bitmap bm = null;
                        try {
                            java.io.File f = new java.io.File("/sdcard/Android/data/" + TARGET_PKG + "/files/cmhook_drawer_card.png");
                            if (f.exists()) bm = android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath());
                        } catch (Throwable t) { }
                        if (bm == null) { toast(act, "当前没有图片, 先选图"); return; }
                        cardOpenEditor(act, bm);
                    } }, "cmhook-card-edit").start();
                }
            });
            android.widget.TextView bClear = miPill(act, "恢复占位", 0xFF444448, false);
            bClear.setPadding((int) (10 * d), (int) (8 * d), (int) (10 * d), (int) (8 * d));
            bClear.setOnClickListener(new android.view.View.OnClickListener() {
                public void onClick(android.view.View v) { cardClearImage(act); }
            });
            android.widget.LinearLayout.LayoutParams blp = new android.widget.LinearLayout.LayoutParams(
                    0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            blp.setMargins((int) (8 * d), 0, (int) (4 * d), 0);
            btns.addView(bPick, blp);
            android.widget.LinearLayout.LayoutParams blp2 = new android.widget.LinearLayout.LayoutParams(
                    0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            blp2.setMargins((int) (4 * d), 0, (int) (4 * d), 0);
            btns.addView(bEdit, blp2);
            android.widget.LinearLayout.LayoutParams blp3 = new android.widget.LinearLayout.LayoutParams(
                    0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            blp3.setMargins((int) (4 * d), 0, (int) (8 * d), 0);
            btns.addView(bClear, blp3);
            android.widget.LinearLayout.LayoutParams btnsl = new android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            btnsl.setMargins(0, (int) (10 * d), 0, 0);
            cardBox.addView(btns, btnsl);
            android.widget.TextView hint = miText(act, "选图/编辑进裁切页, 框内区域替换抽屉顶部VIP卡", 12, TK_TEXT_HINT, false);
            hint.setPadding(0, (int) (8 * d), 0, 0);
            cardBox.addView(hint);
            android.widget.LinearLayout.LayoutParams cardBoxL = new android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            cardBoxL.setMargins((int) (12 * d), (int) (2 * d), (int) (12 * d), 0);
            panel.addView(cardBox, cardBoxL);
            new Thread(new Runnable() { public void run() {
                try {
                    java.io.File f = new java.io.File("/sdcard/Android/data/" + TARGET_PKG + "/files/cmhook_drawer_card.png");
                    if (f.exists()) {
                        android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath());
                        if (bm != null) cardRefreshPreview(bm);
                    }
                } catch (Throwable t) { }
            } }, "cmhook-card-preview").start();

            // ---------- 组 2: 播放与防护 ----------
            java.util.List<android.view.View> g2 = new java.util.ArrayList<android.view.View>();
            android.widget.Switch swAd = miSwitch(act, prefAdBlock);
            g2.add(miRow(act, "去广告拦截", "开屏广告·渠道闸门+网络层+兜底", swAd));
            android.widget.Switch swRev = miSwitch(act, prefAntiRevoke);
            g2.add(miRow(act, "消息防撤回", "私信撤回可见, 原文保留", swRev));
            addGroup(act, panel, d, "g2", "播放与防护", false, g2, 1);

            // ---------- 组 3: 调试与采集(默认折叠) ----------
            java.util.List<android.view.View> g3 = new java.util.ArrayList<android.view.View>();
            android.widget.Switch swHud = miSwitch(act, prefHud);
            g3.add(miRow(act, "独白 HUD", "屏幕实时透视 · 拖动/折叠", swHud));
            android.widget.Switch swTr = miSwitch(act, prefHudTranslate);
            g3.add(miRow(act, "HUD 翻译", "关 = 显示原始内容", swTr));
            android.widget.Switch swProto = miSwitch(act, prefProtoCollect);
            g3.add(miRow(act, "协议采集", "密钥/协议观测(关=省启动开销)", swProto));
            addGroup(act, panel, d, "g3", "调试与采集", true, g3, 2);

            // ---------- 组 4: 工具 ----------
            java.util.List<android.view.View> g4 = new java.util.ArrayList<android.view.View>();
            g4.add(miNav(act, "频道精细控制", "选择保留的频道, 源头移除", new Runnable() {
                public void run() { try { showTabsKeepDialog(act); } catch (Throwable t) { } }
            }));
            g4.add(miNav(act, "App 探测清单", "名单同源跟随 · 实时探测", new Runnable() {
                public void run() { try { showProbeDialog(act); } catch (Throwable t) { } }
            }));
            g4.add(miNav(act, "重建 DexKit 缓存", "清缓存并真搜一次(排障用)", new Runnable() {
                public void run() { try { dexRebuildCache(act, businessCl); } catch (Throwable t) { } }
            }));
            addGroup(act, panel, d, "g4", "工具", false, g4, 3);

            android.widget.TextView tip = miText(act, "改完即生效 · 点组标题可折叠", 12, TK_TEXT_HINT, false);
            tip.setPadding((int) (6 * d), (int) (12 * d), (int) (6 * d), 0);
            panel.addView(tip);

            // 完成按钮
            android.widget.LinearLayout btnRow = new android.widget.LinearLayout(act);
            btnRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            android.widget.LinearLayout.LayoutParams doneLp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, (int) (46 * d));
            doneLp.setMargins(0, (int) (12 * d), 0, 0);
            final android.app.Dialog[] dh = new android.app.Dialog[1];
            android.widget.TextView done = miPill(act, "完成", TK_PRIMARY, true);
            done.setOnClickListener(new android.view.View.OnClickListener() {
                public void onClick(android.view.View v) { try { dh[0].dismiss(); } catch (Throwable t) { } }
            });
            btnRow.addView(done, doneLp);
            panel.addView(btnRow);

            // ---------- 开关监听 ----------
            swHome.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(android.widget.CompoundButton btn, boolean checked) {
                    prefHomeClean = checked; savePref(act, "home_clean", checked);
                    if (checked) {
                        // v7.6: 立刻后台清缓存(只清未过滤那份) —— 否则首页会先渲染 MMKV 里的旧副本
                        savePref(act, "home_clean_purged", true);
                        try {
                            Thread th = new Thread(new Runnable() {
                                public void run() { purgeHomeFeedCache(false); }
                            }, "cmhook-cache");
                            th.setDaemon(true);
                            th.start();
                        } catch (Throwable t2) { purgeHomeFeedCache(false); }
                        toast(act, "首页缓存已清 → 回首页下拉一次即生效");
                        flog("SET", "首页内容清理 -> ON (已同步清缓存)");
                    } else {
                        savePref(act, "home_clean_purged", false);   // 复位: 下次再 ON 还会清
                        flog("SET", "首页内容清理 -> OFF (清了复位标记, 下次 ON 重新清)");
                    }
                }
            });
            swPod.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(android.widget.CompoundButton btn, boolean checked) {
                    prefPodcastClean = checked; savePref(act, "podcast_clean", checked);
                    flog("SET", "播客为你推荐清理 -> " + checked + " (重进播客页生效)");
                }
            });
            swFans.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(android.widget.CompoundButton btn, boolean checked) {
                    prefFansHide = checked; savePref(act, "fans_hide", checked);
                    flog("SET", "关注页乐迷团隐藏 -> " + checked + " (重进关注页生效)");
                }
            });
            swLp.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(android.widget.CompoundButton btn, boolean checked) {
                    prefIdentifyLongPress = checked; savePref(act, "identify_longpress", checked);
                    flog("SET", "长按搜索=识曲 -> " + checked);
                }
            });
            swCard.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(android.widget.CompoundButton btn, boolean checked) {
                    prefCardCustom = checked; savePref(act, "card_custom", checked);
                    flog("SET", "抽屉VIP卡自定义 -> " + checked + (checked ? " (重进抽屉生效)" : ""));
                }
            });
            swAd.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(android.widget.CompoundButton btn, boolean checked) {
                    prefAdBlock = checked; savePref(act, "adblock", checked);
                    flog("SET", "去广告拦截 -> " + checked);
                }
            });
            swRev.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(android.widget.CompoundButton btn, boolean checked) {
                    prefAntiRevoke = checked; savePref(act, "antirevoke", checked);
                    flog("SET", "消息防撤回 -> " + checked);
                }
            });
            swHud.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(android.widget.CompoundButton btn, boolean checked) { setHudEnabled(act, checked); }
            });
            swTr.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(android.widget.CompoundButton btn, boolean checked) {
                    prefHudTranslate = checked; savePref(act, "hud_translate", checked);
                    flog("SET", "HUD翻译 -> " + checked);
                }
            });
            swProto.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(android.widget.CompoundButton btn, boolean checked) {
                    prefProtoCollect = checked; savePref(act, "proto_collect", checked);
                    flog("SET", "协议采集 -> " + checked + " (重启APP后按新状态安装)");
                }
            });

            final android.app.Dialog dlg = miDialog(act, panel);
            dh[0] = dlg;
            dlg.show();
            flog("SET", "面板已渲染: 4 组(界面与清理/播放与防护/调试与采集/工具), 折叠状态=[" + prefUiCollapsed + "]");
        } catch (Throwable t) { flog("SET", "设置面板失败: " + t); }
    }


    // ===== App探测清单 (数据源: 网易云 ad#appSnifferList 官方映射) =====
    // ===== v51: 探测名单"同源跟随" =====
    // 官方名单在宿主 MMKV 配置里(明文 JSON): files/mmkv/com.netease.cloudmusic.core.customconfig.MMKV_CUSTOM_CONFIG
    //   "appSnifferList":[{"scheme":"taobao","pkgName":"com.taobao.taobao"}, ...]
    // 该配置由云端下发覆盖 ⇒ 读它 = 与官方同步(含新增/删除探测项); 本地 PROBE_APPS 仅作中文名表与兜底
    private static volatile String[] probeOfficial = null;   // 形如 "scheme|pkg|官方"

    private static String[] probeOfficialList() {
        if (probeOfficial != null) return probeOfficial;
        try {
            java.io.File f = new java.io.File("/data/data/" + TARGET_PKG
                    + "/files/mmkv/com.netease.cloudmusic.core.customconfig.MMKV_CUSTOM_CONFIG");
            if (!f.exists()) { flog("PROBE", "官方配置 MMKV 不存在"); return null; }
            long len = Math.min(f.length(), 8L * 1024 * 1024);
            byte[] buf = new byte[(int) len];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int off = 0, r;
            while (off < buf.length && (r = in.read(buf, off, buf.length - off)) > 0) off += r;
            in.close();
            String str = new String(buf, 0, off, "UTF-8");
            int i = str.indexOf("appSnifferList");
            if (i < 0) { flog("PROBE", "配置里未找到 appSnifferList"); return null; }
            int lb = str.indexOf('[', i);
            if (lb < 0) return null;
            int depth = 0; boolean inStr = false, esc = false; int end = -1;
            for (int p = lb; p < str.length(); p++) {
                char c = str.charAt(p);
                if (inStr) { if (esc) esc = false; else if (c == '\\') esc = true; else if (c == '"') inStr = false; continue; }
                if (c == '"') { inStr = true; continue; }
                if (c == '[') depth++;
                else if (c == ']') { depth--; if (depth == 0) { end = p; break; } }
            }
            if (end < 0) return null;
            String arr = str.substring(lb, end + 1);
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\\{\\s*\"scheme\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"pkgName\"\\s*:\\s*\"([^\"]+)\"")
                    .matcher(arr);
            java.util.ArrayList<String> out = new java.util.ArrayList<String>();
            java.util.HashSet<String> seen = new java.util.HashSet<String>();
            while (m.find()) {
                String sc = m.group(1), pk = m.group(2);
                if (sc == null || pk == null) continue;
                if (!seen.add(sc)) continue;
                out.add(sc + "|" + pk + "|官方");
            }
            if (out.isEmpty()) return null;
            probeOfficial = out.toArray(new String[out.size()]);
            flog("PROBE", "官方名单已同步: " + probeOfficial.length + " 项 (来源: 宿主 customconfig MMKV)");
            return probeOfficial;
        } catch (Throwable t) { flog("PROBE", "读官方名单失败: " + t); return null; }
    }

    // 合并: 官方名单为主(补中文名/分类), 本地独有的追加(标"本地补"); 输出保持 3 段 scheme|显示名|分类
    private static String[] probeMerged() {
        String[] off = probeOfficialList();
        if (off == null || off.length == 0) return PROBE_APPS;
        java.util.HashMap<String, String> local = new java.util.HashMap<String, String>();
        for (int i = 0; i < PROBE_APPS.length; i++) {
            String[] p = PROBE_APPS[i].split("\\|");
            if (p.length >= 3) local.put(p[0], p[1] + "|" + p[2]);
        }
        java.util.ArrayList<String> out = new java.util.ArrayList<String>();
        java.util.HashSet<String> used = new java.util.HashSet<String>();
        for (int i = 0; i < off.length; i++) {
            String[] p = off[i].split("\\|");
            String sc = p[0], pk = p[1];
            used.add(sc);
            String meta = local.get(sc);
            if (meta != null) {
                String[] mm = meta.split("\\|");
                out.add(sc + "|" + mm[0] + "|" + mm[1]);
            } else {
                out.add(sc + "|" + pk + "|官方新增");
            }
        }
        for (int i = 0; i < PROBE_APPS.length; i++) {
            String[] p = PROBE_APPS[i].split("\\|");
            if (p.length >= 3 && !used.contains(p[0])) out.add(p[0] + "|" + p[1] + "|" + p[2] + "·本地补");
        }
        return out.toArray(new String[out.size()]);
    }

    private static final String[] PROBE_APPS = {
        "changba|唱吧|音乐竞品",
        "hwmediacenter|华为音乐|音乐竞品",
        "luna|Luna音乐|音乐竞品",
        "qqmusic|QQ音乐|音乐竞品",
        "iting|喜马拉雅|音频竞品",
        "novelfm3040|番茄畅听|音频竞品",
        "novelfm8661|番茄畅听极速版|音频竞品",
        "snssdk1128|抖音|短视频竞品",
        "ksnebula|快手极速版|短视频竞品",
        "kwai|快手|短视频竞品",
        "snssdk561124|抖音火山版|短视频竞品",
        "heybox|小黑盒|游戏画像",
        "taptap|TapTap|游戏画像",
        "tencent102002255|崩坏:星穹铁道|游戏画像",
        "tencent1109288517|米游社|游戏画像",
        "wangzherongyao|王者荣耀|游戏画像",
        "glc|灵犀互娱(阿里游戏)|游戏画像",
        "hepingjingying|和平精英|游戏画像",
        "nnfour|无限暖暖(叠纸)|游戏画像",
        "tencent101922227|网易派对游戏|游戏画像",
        "tencent102015677|恋与深空|游戏画像",
        "tencent102029678|网易游戏|游戏画像",
        "tencent102048863|网易游戏|游戏画像",
        "tencent102068430|莉莉丝游戏|游戏画像",
        "tencent102086827|绝区零|游戏画像",
        "tencent102115418|网易(阴阳师系)|游戏画像",
        "tencent1104105906|腾讯游戏|游戏画像",
        "tencent1104833445|率土之滨|游戏画像",
        "tencent1106707429|网易游戏|游戏画像",
        "tencent1108199480|第五人格|游戏画像",
        "tencent1112253703|B站游戏|游戏画像",
        "yuanshengame|原神|游戏画像",
        "fleamarket|闲鱼|电商消费",
        "openapp.jdmobile|京东|电商消费",
        "pddopen|拼多多|电商消费",
        "taobao|淘宝|电商消费",
        "tmall|天猫|电商消费",
        "bieyang|别样|电商消费",
        "cainiao|菜鸟|电商消费",
        "dewuapp|得物|电商消费",
        "etao|一淘|电商消费",
        "pupumall|朴朴超市|电商消费",
        "smzdm|什么值得买|电商消费",
        "taobaolite|淘特|电商消费",
        "taobaoliveshare|淘宝直播|电商消费",
        "vipshop|唯品会|电商消费",
        "wireless1688|1688|电商消费",
        "alipay|支付宝|支付金融",
        "bocmbciphone|中国银行|金融风控",
        "bankabc|农业银行|金融风控",
        "ccbapp|建设银行|金融风控",
        "cmblife|招商银行|金融风控",
        "cmbmobilebank|招商银行|金融风控",
        "com.icbc.iphoneclient|工商银行|金融风控",
        "jdmobile|京东金融|金融风控",
        "tencent102730920|蚂蚁系App|金融风控",
        "tencent1112323990|兴业银行|金融风控",
        "etax|个人所得税|身份画像",
        "tencent100892246|千牛(淘宝商家)|身份画像",
        "tencent1101082284|脉脉|身份画像",
        "wxba9074d7f4eeae4e|中国移动|身份画像",
        "auto|华为智行HiCar|车主画像",
        "autohome|汽车之家|车主画像",
        "geely|吉利汽车|车主画像",
        "growing.64fe493cbf90b1bf|特斯拉|车主画像",
        "leapmotor|零跑汽车|车主画像",
        "lixiang|理想汽车|车主画像",
        "lynkco|领克|车主画像",
        "mercedesme|奔驰|车主画像",
        "micar|小米汽车|车主画像",
        "nio|蔚来|车主画像",
        "scheme.byd|比亚迪|车主画像",
        "volvocars|沃尔沃|车主画像",
        "xpeng|小鹏汽车|车主画像",
        "zeekr|极氪|车主画像",
        "caocaodcdriver|曹操司机端|网约车司机识别",
        "didiudriver|滴滴司机端|网约车司机识别",
        "hlldapp|货拉拉司机|网约车司机识别",
        "kfhxzdriver|花小猪司机|网约车司机识别",
        "saicdriver|享道司机|网约车司机识别",
        "t3driver|T3出行司机|网约车司机识别",
        "tencent1104590117|美团配送骑手|网约车司机识别",
        "tencent1106729031|国宾司机端|网约车司机识别",
        "wlqq.driver|网约车司机端|网约车司机识别",
        "ymm-driver|货运司机端|网约车司机识别",
        "kimi|Kimi|AI用户画像",
        "tiangong|天工AI|AI用户画像",
        "tongyi|通义|AI用户画像",
        "yuanbao|腾讯元宝|AI用户画像",
        "zhipuai|智谱清言|AI用户画像",
        "mqqapi|手机QQ|社交分享",
        "weixin|微信|社交分享",
        "sinaweibo|微博|社交内容",
        "xhsdiscover|小红书|社交内容",
        "zhihu|知乎|社交内容",
        "soul|Soul|社交画像",
        "imeituan|美团|本地生活",
        "dianping|大众点评|本地生活",
        "eleme|饿了么|本地生活",
        "kfcapplinkurl|肯德基|本地生活",
        "meituanwaimai|美团外卖|本地生活",
        "wbmain|58同城|本地生活",
        "ctrip|携程|旅行消费",
        "atourlife|亚朵酒店|旅行消费",
        "taobaotravel|飞猪|旅行消费",
        "tctclient|同程旅行|旅行消费",
        "iqiyi|爱奇艺|长视频",
        "qqlive|腾讯视频|长视频",
        "wx452924392e7fc5a7|芒果TV|长视频",
        "yangshipin|央视频|长视频",
        "youku|优酷|长视频",
        "bilibili|哔哩哔哩|视频内容",
        "mdd|埋堆堆|视频内容",
        "tencent1106969405|看漫|ACG内容",
        "bilicomic|哔哩哔哩漫画|ACG内容",
        "kuaikan|快看漫画|ACG内容",
        "u17app|有妖气漫画|ACG内容",
        "dragon1967|番茄免费小说|阅读画像",
        "dragon8662|凤凰悦读|阅读画像",
        "bookclub|樊登读书|阅读画像",
        "dangdang|当当|阅读画像",
        "keep|Keep|运动画像",
        "lianjiabeike|贝壳|居住画像",
        "ziroom|自如|居住画像",
        "lofter|LOFTER|网易生态",
        "mobimail|网易邮箱大师|网易生态",
        "neplay|网易PLAY|网易生态",
        "netease-cbgplatform|网易藏宝阁|网易生态",
        "yanxuan|网易严选|网易生态",
        "youdaoPro|有道词典|网易生态",
        "qklink|夸克浏览器|工具",
        "wps|WPS|工具",
        "xlcloud|迅雷|工具",
        "tencent1103409988|迅雷浏览器|工具",
        "tqt|天气通|工具",
        "uclink|UC浏览器|工具",
        "baiduboxapp|百度App|搜索入口",
        "amapuri|高德地图|出行",
        "oneTravel|滴滴出行|出行",
    };

    private static boolean schemeInstalled(android.content.Context c, String scheme) {
        try {
            android.content.Intent i = new android.content.Intent("android.intent.action.VIEW",
                    android.net.Uri.parse(scheme + "://cmprobe"));
            return c.getPackageManager().queryIntentActivities(i, 0).size() > 0;
        } catch (Throwable t) { return false; }
    }

    private static void showProbeDialog(final android.app.Activity act) {
        try {
            float d = miDens(act);
            android.widget.LinearLayout panel = new android.widget.LinearLayout(act);
            panel.setOrientation(android.widget.LinearLayout.VERTICAL);
            panel.setBackground(miBg(TK_SURFACE, 24 * d));
            panel.setPadding((int) (16 * d), (int) (14 * d), (int) (16 * d), (int) (16 * d));
            panel.addView(miText(act, "App 探测清单", 20, 0xFFFFFFFF, true));
            final String[] listNow = probeMerged();
            final int offN = (probeOfficialList() != null) ? probeOfficialList().length : 0;
            final android.widget.TextView sub = miText(act, "名单: 官方同源 " + offN + " 项 + 本地补 " + Math.max(0, listNow.length - offN)
                + " 项 (共 " + listNow.length + "), 实时重跑同款探测...", 12, TK_TEXT_SUB, false);
            android.widget.LinearLayout.LayoutParams subLp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            subLp.setMargins(0, 0, 0, (int) (10 * d));
            panel.addView(sub, subLp);

            android.widget.ScrollView scroll = new android.widget.ScrollView(act);
            android.widget.LinearLayout box = new android.widget.LinearLayout(act);
            box.setOrientation(android.widget.LinearLayout.VERTICAL);
            box.setBackground(miBg(TK_CARD, 18 * d));
            box.setPadding((int) (14 * d), (int) (10 * d), (int) (14 * d), (int) (10 * d));
            scroll.addView(box, new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
            int listH = (int) (act.getResources().getDisplayMetrics().heightPixels * 0.5f);
            android.widget.LinearLayout.LayoutParams scrollLp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, listH);
            scrollLp.setMargins(0, (int) (8 * d), 0, (int) (8 * d));
            panel.addView(scroll, scrollLp);

            android.widget.LinearLayout btnRow = new android.widget.LinearLayout(act);
            btnRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            android.widget.LinearLayout.LayoutParams closeLp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, (int) (44 * d));
            final android.app.Dialog[] dh = new android.app.Dialog[1];
            android.widget.TextView close = miPill(act, "关闭", 0xFF2C2C2E, false);
            close.setOnClickListener(new android.view.View.OnClickListener() {
                public void onClick(android.view.View v) { try { dh[0].dismiss(); } catch (Throwable t) { } }
            });
            btnRow.addView(close, closeLp);
            panel.addView(btnRow);

            final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            final android.widget.LinearLayout groupBox = box;
            new Thread(new Runnable() {
                public void run() {
                    String lastCat = "";
                    final android.widget.LinearLayout[] catBoxHolder = new android.widget.LinearLayout[1];
                    android.widget.LinearLayout catBox = null;
                    int installedCnt = 0;
                    for (final String e : probeMerged()) {
                        String[] p = e.split("\\|");
                        if (p.length < 3) continue;
                        if (!p[2].equals(lastCat)) {
                            lastCat = p[2];
                            final String cat = p[2];
                            final TextViewHolder th = new TextViewHolder();
                            h.post(new Runnable() { public void run() {
                                try {
                                    android.widget.TextView hv = miText(act, cat, 13, TK_TEXT_SUB, false);   // v54: 分类标题去红
                                    hv.setPadding(0, (int) (12 * miDens(act)), 0, (int) (4 * miDens(act)));
                                    groupBox.addView(hv);
                                    th.tv = hv;
                                } catch (Throwable t) { }
                            }});
                            int w = 0; while (th.tv == null && w++ < 100) { try { Thread.sleep(30); } catch (Exception ee) {} }
                            catBox = new android.widget.LinearLayout(act);
                            catBox.setOrientation(android.widget.LinearLayout.VERTICAL);
                            catBoxHolder[0] = catBox;
                            h.post(new Runnable() { public void run() {
                                try { groupBox.addView(catBoxHolder[0]); } catch (Throwable t) { }
                            }});
                        }
                        final boolean inst = schemeInstalled(act, p[0]);
                        if (inst) installedCnt++;
                        final android.widget.LinearLayout row = new android.widget.LinearLayout(act);
                        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                        row.setPadding(0, (int) (5 * miDens(act)), 0, (int) (5 * miDens(act)));
                        android.widget.TextView name = new android.widget.TextView(act);
                        name.setText(p[1]);
                        name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
                        name.setTextColor(0xFFF2F2F5);
                        android.widget.LinearLayout.LayoutParams nl = new android.widget.LinearLayout.LayoutParams(
                            0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                        row.addView(name, nl);
                        final android.widget.TextView st = new android.widget.TextView(act);
                        st.setText(inst ? "已装" : "未装");
                        st.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
                        st.setTextColor(inst ? TK_PRIMARY : TK_TEXT_HINT);   // v54: 已装=主色 / 未装=提示色
                        row.addView(st);
                        final android.widget.LinearLayout fRow = row;
                        h.post(new Runnable() { public void run() {
                            try { if (catBoxHolder[0] != null) catBoxHolder[0].addView(fRow); } catch (Throwable t) { }
                        }});
                    }
                    final int fc = installedCnt;
                    h.post(new Runnable() { public void run() {
                        try {
                            sub.setText("探测完成: 你的手机装了其中 " + fc + " 个。红色=已装(已上报), 灰色=未装(同样上报)");
                        } catch (Throwable t) { }
                    }});
                }
            }).start();

            final android.app.Dialog dlg = miDialog(act, panel);
            dh[0] = dlg;
            dlg.show();
        } catch (Throwable t) { flog("SET", "探测清单失败: " + t); }
    }

    private static class TextViewHolder {
        android.widget.TextView tv;
    }

    // v13: 撤回拦截 → 系统通知栏提醒(不依赖HUD开关; 连续撤回各自一条)
    private static final java.util.concurrent.atomic.AtomicInteger revokeNotifyId =
        new java.util.concurrent.atomic.AtomicInteger(0x4d4852);

    private static void notifyRevoke(String session) {
        try {
            android.content.Context c = hudCtx;
            if (c == null) return;
            Object nmObj = c.getSystemService(android.content.Context.NOTIFICATION_SERVICE);
            if (!(nmObj instanceof android.app.NotificationManager)) return;
            android.app.NotificationManager nm = (android.app.NotificationManager) nmObj;
            String channelId = "cmhook_antirevoke";
            android.app.Notification.Builder b;
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                // API26+ 必须走通知渠道; 编译桩是老android.jar无这些API → 反射调用(IMPORTANCE_DEFAULT=3)
                Class<?> chCls = Class.forName("android.app.NotificationChannel");
                Object ch = chCls.getConstructor(String.class, CharSequence.class, int.class)
                    .newInstance(channelId, "CM Hook 防撤回", 3);
                nm.getClass().getMethod("createNotificationChannel", chCls).invoke(nm, ch);
                b = android.app.Notification.Builder.class
                    .getConstructor(android.content.Context.class, String.class)
                    .newInstance(c, channelId);
            } else {
                b = new android.app.Notification.Builder(c);
            }
            b.setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle("CM Hook 防撤回")
                .setContentText("有人撤回了一条消息, 原文已保留" + (session.length() > 0 ? " (" + session + ")" : ""))
                .setAutoCancel(true);
            nm.notify(revokeNotifyId.incrementAndGet(), b.build());
        } catch (Throwable t) {
            flog("ANTIREVOKE", "通知栏提醒失败: " + t);
        }
    }

    // 防撤回拦截体: 丢弃撤回推送 → 本地库不删、观察者不广播、UI 无痕迹
    private static final XC_MethodHook BLOCK_REVOKE = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
            if (!prefAntiRevoke) return;
            String session = "";
            try { Object s = invoke0(param.args[0], "a"); if (s != null) session = String.valueOf(s); } catch (Throwable t) { }
            flog("ANTIREVOKE", "已拦截撤回推送(" + param.method.getName() + ") session=" + session + " 原文保留");
            notifyRevoke(session);
        }
    };

    private static int hookRevokeHandlerMethod(ClassLoader cl, Class<?> handler, String name, String paramFqn) {
        try {
            XposedHelpers.findAndHookMethod(handler, name, cl.loadClass(paramFqn), BLOCK_REVOKE);
            flog("INIT", "防撤回hook已装(" + handler.getName() + "." + name + " ← 撤回推送)");
            return 1;
        } catch (Throwable t) {
            flog("INIT", "防撤回 " + handler.getName() + "." + name + " 失败: " + t);
            return 0;
        }
    }

    // DexKit 反查撤回处理方法: 按 invoke(MsgDBHelper.saveRevokeMessage) 匹配, 返回 "类#方法(参数FQN)" 列表
    private static String[] dexkitFindRevokeHandlers(ClassLoader cl) {
        final String ck = "revoke";
        String cached = dexCacheGet(ck);
        if (cached != null) {
            flog("DEXKIT", "结果级缓存命中 " + ck + " → " + cached.replace("\n", " | "));
            return splitCache(cached);
        }
        try {
            org.luckypray.dexkit.DexKitCacheBridge.RecyclableBridge rb = dexBridge(cl);
            java.util.List<org.luckypray.dexkit.wrap.DexMethod> list = rb.getMethods(
                new org.luckypray.dexkit.DexKitCacheBridge.RecyclableBridge.FindMethodBuilder() {
                    public void build(org.luckypray.dexkit.query.FindMethod fm) {
                        fm.matcher(new org.luckypray.dexkit.query.matchers.MethodMatcher()
                            .addInvoke("Lcom/netease/nimlib/session/MsgDBHelper;->saveRevokeMessage(Ljava/lang/String;)V"));
                    }
                });
            java.util.ArrayList<String> hits = new java.util.ArrayList<String>();
            if (list != null) {
                for (org.luckypray.dexkit.wrap.DexMethod md : list) {
                    String cn = md.getClassName();
                    if (cn == null || !cn.startsWith("com.netease.nimlib.")) continue;
                    java.util.List<String> ps = md.getParamTypeNames();
                    if (ps == null || ps.size() != 1) continue;
                    String p = ps.get(0);
                    if (p.startsWith("L") && p.endsWith(";")) p = p.substring(1, p.length() - 1).replace('/', '.');
                    if (!p.startsWith("com.netease.nimlib.biz.e.j.") || p.equals("com.netease.nimlib.biz.e.j.w")) continue;
                    hits.add(cn + "#" + md.getName() + "(" + p + ")");
                }
            }
            dexCachePut(ck, joinCache(hits));
            flog("DEXKIT", "查询并落缓存 " + ck + " → " + hits.size() + " 处");
            return hits.toArray(new String[hits.size()]);
        } catch (Throwable t) {
            flog("DEXKIT", "防撤回查询失败: " + t);
            return new String[0];
        }
    }

    // LSPosed 日志白名单: 只镜像功能/生命周期事件。协议采集类高流量标签(HTTP_*/CRONET_*/CMENC_*/SQL_PROBE/
    // URL_NEW/CIPHER/serialdata 等)只进 cm_hook.log + HUD —— 否则刷爆 LSPosed 日志缓冲区(09-21 用户反馈)。
    // 新增高流量标签默认不进 LSP, 需要时往这里加。
    private static final java.util.HashSet<String> LSP_TAGS = new java.util.HashSet<String>(java.util.Arrays.asList(
            "INIT", "SET", "DEXKIT", "ANTIREVOKE", "IDENTIFY", "TOPTABS",
            "HOME_CLEAN", "PODCAST_CLEAN", "ADBLOCK", "FANS", "CHIP"));

    private static void flog(String tag, String msg) {
        String line = TS.format(new Date()) + " [" + tag + "] " + msg;
        if (LSP_TAGS.contains(tag)) {
            try { XposedBridge.log("[CMH] " + line); } catch (Throwable t) { }
        }
        try { hud(tag, msg); } catch (Throwable t) { }
        synchronized (LOG_LOCK) {
            try {
                if (!fileInit) {
                    fileInit = true;
                    try {
                        File dir = new File("/sdcard/Android/data/" + TARGET_PKG + "/files");
                        dir.mkdirs();
                        currentLogPath = new File(dir, "cm_hook.log").getAbsolutePath();
                        openLogWriter(currentLogPath);
                    } catch (Throwable t2) {
                        try {
                            File dir2 = new File("/data/data/" + TARGET_PKG + "/cache");
                            dir2.mkdirs();
                            currentLogPath = new File(dir2, "cm_hook.log").getAbsolutePath();
                            openLogWriter(currentLogPath);
                        } catch (Throwable t3) { logWriter = null; }
                    }
                }
                if (logWriter != null) {
                    logWriter.write(line + "\n");
                    logWriter.flush();
                    if ((++logWriteCount & 8191) == 0) rotateLogIfHuge();
                }
            } catch (Throwable t) { }
        }
    }

    // v10: 打开日志(超100MB直接重建) / 写满8192条后巡检一次, 防日志无限膨胀
    private static void openLogWriter(String path) throws Exception {
        File f = new File(path);
        if (f.exists() && f.length() > LOG_ROTATE_BYTES) f.delete();
        logWriter = new java.io.OutputStreamWriter(new java.io.FileOutputStream(f, true), "UTF-8");
    }

    private static void rotateLogIfHuge() {
        try {
            if (currentLogPath == null) return;
            File f = new File(currentLogPath);
            if (f.exists() && f.length() > LOG_ROTATE_BYTES) {
                logWriter.close();
                logWriter = null;
                openLogWriter(currentLogPath);
                try { XposedBridge.log("[CMH] 日志超过100MB已轮转重建"); } catch (Throwable t) { }
            }
        } catch (Throwable t) { }
    }

    private static String trunc(String s) {
        if (s == null) return "<null>";
        return s.length() > 2600 ? s.substring(0, 2600) + "...<len=" + s.length() + ">" : s;
    }

    private static String bytesPreview(byte[] b, int max) {
        if (b == null) return "<null>";
        int n = Math.min(b.length, max);
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append((char) (b[i] & 0xff));
        if (b.length > max) sb.append("...<len=").append(b.length).append(">");
        return sb.toString();
    }

    // v19: 清掉本地首页 feed 缓存(MMKV), 强制下次从网络(已过滤)重建
    // 缓存: /data/data/<pkg>/files/mmkv/HOME_RECOMMEND_PAGE_* / mix_cache_HOME_RECOMMEND_PAGE_*
    // v7.6: 智能清理 —— 只删"含未过滤锚点"的缓存; 已是过滤版的保留(不再无脑删好缓存)
    private static final String[] HOME_CACHE_STALE_MARKERS = {
        "PAGE_RECOMMEND_SHORTCUT", "home_recent_play_module", "最近常听"
    };

    private static int rvIndexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    // v7.9: 缓存里出现"白名单之外"的 positionCode → 这份缓存是旧(未清)版本 → 该删
    private static boolean rvFeedCacheIsStale(File f) {
        java.io.FileInputStream in = null;
        try {
            long len = f.length();
            if (len <= 0 || len > 16L * 1024 * 1024) return false;
            byte[] buf = new byte[(int) len];
            in = new java.io.FileInputStream(f);
            int off = 0, n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
            String s = new String(buf, 0, off, "UTF-8");
            // 旧锚点残留也算脏
            for (int k = 0; k < HOME_CACHE_STALE_MARKERS.length; k++) {
                if (s.indexOf(HOME_CACHE_STALE_MARKERS[k]) >= 0) return true;
            }
            java.util.HashSet<String> keep = new java.util.HashSet<String>();
            String[] ks = (prefHomeCleanKeep == null ? "" : prefHomeCleanKeep).split(",");
            for (int i = 0; i < ks.length; i++) {
                String k = ks[i].trim();
                if (k.length() > 0) keep.add(k);
            }
            if (keep.isEmpty()) return false;
            int p = 0;
            while ((p = s.indexOf("\"positionCode\"", p)) >= 0) {
                int q = s.indexOf(':', p + 14);
                if (q < 0) break;
                int r = s.indexOf('"', q + 1);
                if (r < 0) break;
                int e = s.indexOf('"', r + 1);
                if (e < 0) break;
                String code = s.substring(r + 1, e);
                if (!keep.contains(code)) return true;
                p = e + 1;
            }
        } catch (Throwable t) {
        } finally {
            try { if (in != null) in.close(); } catch (Throwable t) { }
        }
        return false;
    }

    private static void purgeHomeFeedCache() { purgeHomeFeedCache(false); }

    private static void purgeHomeFeedCache(boolean force) {
        try {
            File dir = new File("/data/data/" + TARGET_PKG + "/files/mmkv");
            if (!dir.isDirectory()) return;
            File[] fs = dir.listFiles();
            if (fs == null) return;
            int n = 0, kept = 0;
            for (int i = 0; i < fs.length; i++) {
                String nm = fs[i].getName();
                // v8.0: 块缓存真正落在 MMKV 实例 SP_MIX_CONTAINER(单文件存所有 mix 页面块)
                boolean cand = nm.startsWith("HOME_RECOMMEND_PAGE_")
                        || nm.startsWith("mix_cache_HOME_RECOMMEND_PAGE_")
                        || nm.startsWith("SP_MIX_CONTAINER");
                if (!cand) continue;
                boolean stale = force;
                if (!stale) stale = rvFeedCacheIsStale(fs[i]);   // 白名单外的块也算脏
                if (stale) {
                    if (fs[i].delete()) {
                        n++;
                        try { new File(fs[i].getAbsolutePath() + ".crc").delete(); } catch (Throwable t2) { }
                        flog("HOME_CLEAN", "删缓存: " + nm);
                    }
                } else kept++;
            }
            flog("HOME_CLEAN", "首页缓存清理: 删 " + n + " 个(含未过滤锚点)"
                + (kept > 0 ? " / 保留 " + kept + " 个(已是过滤版)" : "") + (force ? " [强制模式]" : ""));
        } catch (Throwable t) { }
    }

    // 任意线程 Toast(切主线程)
    private static void toast(final android.content.Context c, final String msg) {
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                public void run() {
                    try { android.widget.Toast.makeText(c, msg, android.widget.Toast.LENGTH_SHORT).show(); } catch (Throwable t) { }
                }
            });
        } catch (Throwable t) { }
    }

    // ===== v31: 关注页「乐迷团」项隐藏 =====
    // 关注页(底部导航中间) → 顶部「关注」tab 的第一条横滑项 = 乐迷团
    // 该页为 RN 渲染成原生 View(容器 id=rn_content / DC_NativeList), 文案来自本地资源(String#2388)
    // 手法: 按文本定位 TextView → 上溯到"该项容器"(宽度 < 屏宽60%) → GONE; RN 重渲染会覆盖 → 多次延时重试
    private static android.view.View findByTextEquals(android.view.View v, String text, int depth) {
        if (v == null || depth > 200) return null;
        try {
            if (v instanceof android.widget.TextView) {
                CharSequence cs = ((android.widget.TextView) v).getText();
                if (cs != null && text.contentEquals(cs)) return v;
            }
            if (v instanceof android.view.ViewGroup) {
                android.view.ViewGroup g = (android.view.ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    android.view.View r = findByTextEquals(g.getChildAt(i), text, depth + 1);
                    if (r != null) return r;
                }
            }
        } catch (Throwable t) { }
        return null;
    }

    // ===== v6.0: 撤回记录(胶囊 + 浮层) —— 数据 = 库与聊天列表比较 =====
    private static final String RV_CAP_TAG = "cmhook_rv_cap";
    private static final String RV_PANEL_TAG = "cmhook_rv_panel";
    private static final String RV_SCRIM_TAG = "cmhook_rv_scrim";   // v7.1: 点浮层外=关闭
    private static final String RV_FILE = "/sdcard/Android/data/com.netease.cloudmusic/files/cmhook_revoke.txt";
    private static final java.util.LinkedHashMap<String, String> RV_MAP = new java.util.LinkedHashMap<String, String>();
    private static volatile boolean rvLoaded = false;
    private static volatile Object rvAdapter = null;
    private static volatile android.app.Activity rvLastAct = null;

    // v7.0: 台账状态
    private static volatile long rvLastDbLog = 0L;
    private static volatile long rvDbLastMs = 0L;
    private static volatile boolean rvDbBusy = false;
    private static volatile int rvLastCapShown = -1;
    // v7.2: 胶囊位置(距右/下边距 px; -1 = 用默认)
    private static volatile int rvCapRight = -1;
    private static volatile int rvCapBottom = -1;
    private static volatile String rvLastDiffNote = "";
    // 会话 → (msgId → "发信人\u0001文本"): 见过之后消失 = 被撤回(源①)
    private static final java.util.HashMap<String, java.util.LinkedHashMap<String, String>> RV_SEEN =
            new java.util.HashMap<String, java.util.LinkedHashMap<String, String>>();
    // 会话 → (msgId → 文本): 上轮库内容(源③)
    private static final java.util.HashMap<String, java.util.LinkedHashMap<String, String>> RV_DBSEEN =
            new java.util.HashMap<String, java.util.LinkedHashMap<String, String>>();

    private static Object rvCall(Object o, String m) {
        if (o == null) return null;
        try {
            java.lang.reflect.Method mm = o.getClass().getMethod(m);
            mm.setAccessible(true);
            return mm.invoke(o);
        } catch (Throwable t) { return null; }
    }

    private static String rvStr(Object o) { return o == null ? null : String.valueOf(o); }

    private static synchronized void rvLoad() {
        if (rvLoaded) return;
        rvLoaded = true;
        try {
            java.io.File f = new java.io.File(RV_FILE);
            if (!f.exists()) return;
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
            String ln;
            while ((ln = br.readLine()) != null) {
                ln = ln.trim();
                if (ln.length() == 0) continue;
                String[] parts = ln.split(" \\| ");
                if (parts.length >= 3) RV_MAP.put(parts[0], parts[1] + "\u0001" + parts[2]);
                else if (parts.length == 2) RV_MAP.put(parts[0], "" + "\u0001" + parts[1]);   // 旧格式兼容
                else RV_MAP.put("k" + RV_MAP.size(), "" + "\u0001" + ln);
            }
            br.close();
            flog("ANTIREVOKE", "撤回台账载入: " + RV_MAP.size() + " 条");
        } catch (Throwable t) { flog("ANTIREVOKE", "台账载入失败: " + t); }
    }

    private static synchronized boolean rvHasRecord(String msgId) { rvLoad(); return RV_MAP.containsKey(msgId); }

    private static synchronized void rvRecord(String msgId, String ch, String text) {
        rvLoad();
        if (RV_MAP.containsKey(msgId)) return;
        try {
            RV_MAP.put(msgId, ch + "\u0001" + text);
            java.io.FileOutputStream os = new java.io.FileOutputStream(new java.io.File(RV_FILE), true);
            os.write((msgId + " | " + ch + " | " + text + "\n").getBytes("UTF-8"));
            os.flush();
            os.close();
            flog("ANTIREVOKE", "记账[" + ch + "]: " + text);
        } catch (Throwable t) { }
    }

    // v7.4: 当前页面自己的聊天列表(视图树优先 —— 全局适配器可能是别页/别会话的快照)
    private static java.util.List<?> rvListForAct(android.app.Activity act) {
        try {
            if (act != null && act.getWindow() != null) {
                java.util.List<?> l = rvListFromView(act.getWindow().getDecorView(), 0);
                if (l != null && !l.isEmpty()) return l;
            }
        } catch (Throwable t) { }
        return rvCurList(act);
    }

    // v7.4: 严卡"本会话"——识别链: 本页列表 → 宿主现场活 IMChat(≤60s) → 全局适配器(兜底)
    private static String rvCurrentChannel(android.app.Activity act) {
        try {
            String ch = rvChannelOf(rvListForAct(act));
            if (ch.length() > 0) return ch;
        } catch (Throwable t) { }
        try {
            if (rvLiveChat != null && System.currentTimeMillis() - rvLiveChatAt < 60000L) {
                Object c = rvCall(rvLiveChat, "getChannelId");
                if (c != null && String.valueOf(c).length() > 0) return String.valueOf(c);
            }
        } catch (Throwable t) { }
        try { return rvChannelOf(rvCurList(act)); } catch (Throwable t) { }
        return "";
    }

    // 会话 id(从列表条目取; IMessageData/RawMessage 都有 getChannelId)
    private static String rvChannelOf(java.util.List<?> cur) {
        try {
            if (cur == null) return "";
            for (int i = 0; i < cur.size() && i < 40; i++) {
                Object c = rvCall(cur.get(i), "getChannelId");
                if (c != null && String.valueOf(c).length() > 0) return String.valueOf(c);
            }
        } catch (Throwable t) { }
        return "";
    }

    private static String rvCurChannelId() {
        return rvChannelOf(rvCurList(rvLastAct));
    }

    // 发信人 userId(1:1 会话: == channelId 即对方, != 即自己)
    private static String rvSenderIdOf(Object item) {
        try {
            Object mb = rvCall(item, "getMsgBody");
            Object sender = rvCall(mb, "getSender");
            Object user = rvCall(sender, "getUser");
            Object id = rvCall(user, "getUserId");
            if (id == null) id = rvCall(sender, "getUserId");
            if (id == null) id = rvCall(item, "getSenderUserId");
            return id == null ? "" : String.valueOf(id).trim();
        } catch (Throwable t) { return ""; }
    }

    // 台账文本: 时间 + 昵称 + 正文
    private static String rvTextFor(Object item) {
        String txt = rvTextOf(item);
        if (txt == null || txt.length() == 0) return null;
        String nick = rvNickOf(item);
        String t = "";
        try {
            Object ts = rvCall(item, "getMsgTime");
            if (ts != null && !"0".equals(String.valueOf(ts))) {
                t = new java.text.SimpleDateFormat("MM-dd HH:mm").format(new java.util.Date(Long.parseLong(String.valueOf(ts)))) + "  ";
            } else {
                t = new java.text.SimpleDateFormat("MM-dd HH:mm").format(new java.util.Date()) + "  ";
            }
        } catch (Throwable x) { }
        return t + (nick == null ? "对方" : nick) + "： " + txt;
    }

    // 从任意 view 树里找 RecyclerView 的列表
    private static java.util.List<?> rvListFromView(android.view.View v, int depth) {
        if (v == null || depth > 40) return null;
        try {
            if (v instanceof android.view.ViewGroup) {
                if (v.getClass().getName().indexOf("RecyclerView") >= 0) {
                    Object ad = rvCall(v, "getAdapter");
                    Object l = rvCall(ad, "getCurrentList");
                    if (l instanceof java.util.List) return (java.util.List<?>) l;
                }
                android.view.ViewGroup g = (android.view.ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    java.util.List<?> r = rvListFromView(g.getChildAt(i), depth + 1);
                    if (r != null && !r.isEmpty()) return r;
                }
            }
        } catch (Throwable t) { }
        return null;
    }

    // 当前聊天列表: ① 已抓到的 adapter ② 遍历 RecyclerView 兜底
    private static java.util.List<?> rvCurList(android.app.Activity act) {
        try {
            Object l = rvCall(rvAdapter, "getCurrentList");
            if (l instanceof java.util.List && !((java.util.List<?>) l).isEmpty()) return (java.util.List<?>) l;
        } catch (Throwable t) { }
        try {
            if (act != null && act.getWindow() != null) {
                java.util.List<?> r2 = rvListFromView(act.getWindow().getDecorView(), 0);
                if (r2 != null && !r2.isEmpty()) { rvAdapter = null; return r2; }
            }
        } catch (Throwable t) { }
        return null;
    }

    // ===== v7.0 治本: 私信库 DAO 解析 =====
    //  9.5.96 实测(classes11/classes8):
    //    私信 DAO = j30.a  表 private_chat_message_db  private w(IMChat,int status,int limit,boolean desc):List
    //    圈聊 DAO = l51.a  表 circle_chat_message_db    (同源: extends x41.a, implements x41.c)
    //  旧硬编码 x50.a 在 9.5.96 已被 R8 复用成广告点击监听器 → NoSuchFieldException: b → 台账恒空 → 胶囊不显示
    //  解析链: ① 硬编码(表名 getter 校验) ② DexKit 按表名串反查类 ③ 失败即打日志, 不静默
    private static volatile Class<?> rvDaoCls = null;
    private static volatile Object rvDaoObj = null;
    private static volatile java.lang.reflect.Method rvDaoW = null;
    private static volatile Object rvLiveChat = null;      // 宿主现场抓到的活 IMChat
    private static volatile long rvLiveChatAt = 0L;        // v7.4: 活会话抓取时刻(超 60s 视为陈旧)
    private static volatile String rvLastChForUi = "";     // v7.4: 最近一次识别到的会话(识别不到时保底)
    private static volatile String rvDaoNote = "未解析";

    // 伴生对象的表名 getter(无参返回 String) —— 区分私信/圈聊两个同构 DAO
    private static String rvDaoTableOf(Class<?> daoCls) {
        try {
            java.lang.reflect.Field[] fs = daoCls.getDeclaredFields();
            for (int i = 0; i < fs.length; i++) {
                if (!java.lang.reflect.Modifier.isStatic(fs[i].getModifiers())) continue;
                Class<?> ft = fs[i].getType();
                if (ft == daoCls) continue;
                java.lang.reflect.Method[] ms = ft.getDeclaredMethods();
                for (int j = 0; j < ms.length; j++) {
                    if (ms[j].getParameterTypes().length != 0) continue;
                    if (ms[j].getReturnType() != String.class) continue;
                    try {
                        fs[i].setAccessible(true);
                        Object comp = fs[i].get(null);
                        if (comp == null) continue;
                        ms[j].setAccessible(true);
                        Object v = ms[j].invoke(comp);
                        if (v instanceof String) return (String) v;
                    } catch (Throwable t) { }
                }
            }
        } catch (Throwable t) { }
        return null;
    }

    private static boolean rvIsDaoClass(Class<?> c) {
        if (c == null) return false;
        try {
            java.lang.reflect.Method[] ms = c.getDeclaredMethods();
            for (int i = 0; i < ms.length; i++) {
                Class<?>[] ps = ms[i].getParameterTypes();
                if (ps.length == 4 && ps[0].getName().endsWith(".IMChat")
                        && ps[1] == Integer.TYPE && ps[2] == Integer.TYPE && ps[3] == Boolean.TYPE
                        && java.util.List.class.isAssignableFrom(ms[i].getReturnType())) return true;
            }
        } catch (Throwable t) { }
        return false;
    }

    private static java.lang.reflect.Method rvFindW(Class<?> daoCls) {
        try {
            java.lang.reflect.Method[] ms = daoCls.getDeclaredMethods();
            for (int i = 0; i < ms.length; i++) {
                if (!ms[i].getName().equals("w")) continue;
                Class<?>[] ps = ms[i].getParameterTypes();
                if (ps.length == 4 && ps[0].getName().endsWith(".IMChat")
                        && ps[1] == Integer.TYPE && ps[2] == Integer.TYPE && ps[3] == Boolean.TYPE
                        && java.util.List.class.isAssignableFrom(ms[i].getReturnType())) {
                    ms[i].setAccessible(true);
                    return ms[i];
                }
            }
        } catch (Throwable t) { }
        return null;
    }

    // 伴生对象(静态字段) → 无参且返回 DAO 类型的方法 → 单例
    private static Object rvDaoInstance(Class<?> daoCls) {
        try {
            java.lang.reflect.Field[] fs = daoCls.getDeclaredFields();
            for (int i = 0; i < fs.length; i++) {
                if (!java.lang.reflect.Modifier.isStatic(fs[i].getModifiers())) continue;
                if (fs[i].getType() != daoCls) continue;
                try {
                    fs[i].setAccessible(true);
                    Object v = fs[i].get(null);
                    if (v != null) return v;
                } catch (Throwable t) { }
            }
            for (int i = 0; i < fs.length; i++) {
                if (!java.lang.reflect.Modifier.isStatic(fs[i].getModifiers())) continue;
                Class<?> ft = fs[i].getType();
                if (ft == daoCls) continue;
                java.lang.reflect.Method[] ms = ft.getDeclaredMethods();
                for (int j = 0; j < ms.length; j++) {
                    if (ms[j].getParameterTypes().length != 0) continue;
                    if (ms[j].getReturnType() != daoCls) continue;
                    try {
                        fs[i].setAccessible(true);
                        Object comp = fs[i].get(null);
                        if (comp == null) continue;
                        ms[j].setAccessible(true);
                        Object v = ms[j].invoke(comp);
                        if (v != null) return v;
                    } catch (Throwable t) { }
                }
            }
        } catch (Throwable t) { }
        return null;
    }

    // 现场探针: 宿主每次查库都经过 w(...) → thisObject = DAO, args[0] = 活 IMChat
    private static void rvArmDaoProbe(final Class<?> daoCls) {
        try {
            XposedBridge.hookAllMethods(daoCls, "w", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        if (param.thisObject != null) rvDaoObj = param.thisObject;
                        if (param.args != null && param.args.length > 0 && param.args[0] != null) {
                            rvLiveChat = param.args[0];
                            rvLiveChatAt = System.currentTimeMillis();
                        }
                    } catch (Throwable t) { }
                }
            });
            flog("ANTIREVOKE", "DAO 现场探针已装: " + daoCls.getName() + "#w");
        } catch (Throwable t) { flog("ANTIREVOKE", "DAO 探针失败: " + t); }
    }

    private static synchronized void rvResolveDao(ClassLoader cl) {
        if (rvDaoCls != null && rvDaoW != null) return;
        if (cl == null) { rvDaoNote = "业务加载器未就绪"; return; }
        String[] known = {"j30.a", "l51.a"};
        for (int i = 0; i < known.length; i++) {
            Class<?> c = null;
            try { c = cl.loadClass(known[i]); } catch (Throwable t) { continue; }
            if (!rvIsDaoClass(c)) continue;
            String table = rvDaoTableOf(c);
            if (i == 0 && table != null && table.indexOf("private_chat_message") < 0) {
                flog("ANTIREVOKE", "库 DAO: " + known[i] + " 表=" + table + " 非私信, 跳过");
                continue;
            }
            rvDaoCls = c;
            rvDaoW = rvFindW(c);
            rvDaoNote = "硬编码 " + known[i] + " 表=" + table;
            break;
        }
        if (rvDaoCls == null) {
            try {
                String[] hits = dexkitFindMethodsByString(cl, "private_chat_message_db");
                for (int i = 0; i < hits.length && rvDaoCls == null; i++) {
                    int hash = hits[i].indexOf('#');
                    if (hash <= 0) continue;
                    Class<?> c = null;
                    try { c = cl.loadClass(hits[i].substring(0, hash)); } catch (Throwable t) { continue; }
                    if (!rvIsDaoClass(c)) continue;
                    rvDaoCls = c;
                    rvDaoW = rvFindW(c);
                    rvDaoNote = "DexKit " + c.getName();
                }
            } catch (Throwable t) { rvDaoNote = "DexKit失败 " + t; }
        }
        if (rvDaoCls != null && rvDaoW != null) {
            flog("ANTIREVOKE", "库 DAO 已解析: " + rvDaoCls.getName() + " (" + rvDaoNote + ")");
            rvArmDaoProbe(rvDaoCls);
        } else {
            flog("ANTIREVOKE", "库 DAO 解析失败: " + rvDaoNote);
        }
    }

    // 会话对象: 优先宿主现场的活 IMChat(会话 id 一致), 否则按 9 参构造并回读校验
    private static Object rvChatFor(String channelId) {
        try {
            Object live = rvLiveChat;
            if (live != null) {
                Object c = rvCall(live, "getChannelId");
                if (c != null && channelId.equals(String.valueOf(c))) return live;
            }
        } catch (Throwable t) { }
        try {
            Class<?> imChatCls = XposedHelpers.findClass("com.netease.cloudmusic.music.biz.chat.meta.IMChat", businessCl);
            java.lang.reflect.Constructor<?>[] cs = imChatCls.getDeclaredConstructors();
            for (int ci = 0; ci < cs.length; ci++) {
                Class<?>[] ps = cs[ci].getParameterTypes();
                if (ps.length != 9) continue;
                try {
                    cs[ci].setAccessible(true);
                    Object[] a = new Object[9];
                    a[0] = "private_message"; a[5] = channelId; a[6] = Boolean.TRUE; a[7] = Boolean.FALSE; a[8] = "PRIVATE";
                    for (int k = 0; k < 9; k++) {
                        if (a[k] != null) continue;
                        if (ps[k] == Boolean.TYPE) a[k] = Boolean.FALSE;
                        else if (ps[k] == Integer.TYPE) a[k] = Integer.valueOf(0);
                        else if (ps[k] == Long.TYPE) a[k] = Long.valueOf(0L);
                        else a[k] = null;
                    }
                    Object o = cs[ci].newInstance(a);
                    Object c = rvCall(o, "getChannelId");
                    if (c != null && channelId.equals(String.valueOf(c))) return o;
                } catch (Throwable t) { }
            }
        } catch (Throwable t) { }
        return null;
    }

    // 读库: j30.a#w(IMChat, status=0, limit=1000, desc=true)   status 0 = 本地库正常消息行(实机库核对)
    private static java.util.List<?> rvQueryDb(String channelId) {
        if (channelId == null || channelId.length() == 0) return null;
        try {
            if (rvDaoCls == null || rvDaoW == null) rvResolveDao(businessCl);
            if (rvDaoCls == null || rvDaoW == null) return null;
            Object dao = rvDaoObj;
            if (dao == null) {
                dao = rvDaoInstance(rvDaoCls);
                if (dao != null) rvDaoObj = dao;
            }
            if (dao == null) { flog("ANTIREVOKE", "库: 未取到 DAO 实例"); return null; }
            Object chat = rvChatFor(channelId);
            if (chat == null) { flog("ANTIREVOKE", "库: 无匹配 IMChat(等宿主现场)"); return null; }
            Object dl = rvDaoW.invoke(dao, chat, Integer.valueOf(0), Integer.valueOf(1000), Boolean.TRUE);
            if (dl instanceof java.util.List) {
                java.util.List<?> list = (java.util.List<?>) dl;
                long now = System.currentTimeMillis();
                if (now - rvLastDbLog > 60000L) {
                    rvLastDbLog = now;
                    flog("ANTIREVOKE", "库读成功: " + list.size() + " 条 (DAO=" + rvDaoCls.getName() + ")");
                }
                return list;
            }
            flog("ANTIREVOKE", "库: 返回 " + dl);
        } catch (Throwable t) { flog("ANTIREVOKE", "库读异常: " + t); }
        return null;
    }

    private static String rvTextOf(Object item) {
        try {
            Object mb = rvCall(item, "getMsgBody");
            Object body = rvCall(mb, "getBody");
            Object c = rvCall(body, "getText");
            if (c == null) c = rvCall(mb, "getBriefText");
            return rvStr(c);
        } catch (Throwable t) { return null; }
    }

    private static String rvNickOf(Object item) {
        try {
            Object mb = rvCall(item, "getMsgBody");
            Object sender = rvCall(mb, "getSender");
            Object user = rvCall(sender, "getUser");
            Object n = rvCall(user, "getNickname");
            if (n == null) n = rvCall(user, "nickname");
            return (n == null) ? null : String.valueOf(n);
        } catch (Throwable t) { return null; }
    }

    // ===== v7.0 三源台账 =====
    //  ① 列表消失: 进过列表的 msgId 后来不见了 = 被撤回(会话开着时立即命中, 不依赖库)
    //  ② 库窗口缺失: 库里有、列表没有 —— 只认"列表已加载时间跨度内"的行(0..最旧命中下标, 库 DESC),
    //     避免把没翻到的历史消息全判成撤回
    //  ③ 库条目消失: 上轮库里有、这轮库里没了 = 被撤回(覆盖"不在聊天页时被撤")
    private static int rvListVanish(String ch, java.util.List<?> cur, java.util.HashSet<String> curIds) {
        java.util.LinkedHashMap<String, String> seen;
        synchronized (RV_SEEN) {
            seen = RV_SEEN.get(ch);
            if (seen == null) { seen = new java.util.LinkedHashMap<String, String>(); RV_SEEN.put(ch, seen); }
        }
        int added = 0;
        java.util.ArrayList<String> gone = new java.util.ArrayList<String>();
        synchronized (seen) {
            for (java.util.Iterator<java.util.Map.Entry<String, String>> it = seen.entrySet().iterator(); it.hasNext(); ) {
                java.util.Map.Entry<String, String> e = it.next();
                if (curIds.contains(e.getKey())) continue;
                String v = e.getValue();
                int sep = v.indexOf('\u0001');
                String sid = sep > 0 ? v.substring(0, sep) : "";
                String body = sep > 0 ? v.substring(sep + 1) : v;
                it.remove();
                if (sid.length() > 0 && ch.length() > 0 && !sid.equals(ch)) {
                    flog("ANTIREVOKE", "列表消失但是自己发的, 跳过: " + body);
                    continue;
                }
                rvRecord(e.getKey(), ch, body);
                added++;
                gone.add(e.getKey());
            }
        }
        if (added > 0) flog("ANTIREVOKE", "① 列表消失→记账 " + added + " 条(会话 " + ch + ")");
        // 当前列表补进"见过"(只记对方的, 自己发的不记)
        for (int i = 0; i < cur.size(); i++) {
            Object item = cur.get(i);
            Object id = rvCall(item, "getId");
            if (id == null) continue;
            String mid = String.valueOf(id);
            if (!curIds.contains(mid)) continue;
            synchronized (seen) { if (seen.containsKey(mid)) continue; }
            String sid = rvSenderIdOf(item);
            if (sid.length() > 0 && ch.length() > 0 && !sid.equals(ch)) continue;
            String body = rvTextFor(item);
            if (body == null) continue;
            synchronized (seen) {
                if (!seen.containsKey(mid)) seen.put(mid, sid + "\u0001" + body);
                while (seen.size() > 400) {
                    java.util.Iterator<String> it = seen.keySet().iterator();
                    if (!it.hasNext()) break;
                    it.next();
                    it.remove();
                }
            }
        }
        return added;
    }

    // ② + ③: 后台线程跑(库查询 + JSON 解析重, 严禁主线程)
    private static int rvDbDiff(String ch, java.util.HashSet<String> curIds) {
        int added = 0;
        try {
            java.util.List<?> db = rvQueryDb(ch);
            if (db == null) return 0;
            java.util.ArrayList<String> ids = new java.util.ArrayList<String>();
            int maxMatched = -1, minMatched = -1;
            for (int i = 0; i < db.size(); i++) {
                Object id = rvCall(db.get(i), "getId");
                String mid = id == null ? null : String.valueOf(id);
                ids.add(mid);
                if (mid != null && curIds.contains(mid)) {
                    if (minMatched < 0) minMatched = i;
                    maxMatched = i;
                }
            }
            // ③ 库条目消失(先取本轮库中含文本的快照, 再比对上一轮)
            java.util.LinkedHashMap<String, String> curDb = new java.util.LinkedHashMap<String, String>();
            for (int i = 0; i < db.size(); i++) {
                String mid = ids.get(i);
                if (mid == null) continue;
                String body = rvTextFor(db.get(i));
                if (body != null) curDb.put(mid, body);
            }
            java.util.LinkedHashMap<String, String> prev;
            synchronized (RV_DBSEEN) {
                prev = RV_DBSEEN.get(ch);
                if (prev == null) { prev = new java.util.LinkedHashMap<String, String>(); RV_DBSEEN.put(ch, prev); }
            }
            synchronized (prev) {
                if (!prev.isEmpty()) {
                    for (java.util.Iterator<java.util.Map.Entry<String, String>> it = prev.entrySet().iterator(); it.hasNext(); ) {
                        java.util.Map.Entry<String, String> e = it.next();
                        if (curDb.containsKey(e.getKey())) continue;
                        it.remove();
                        rvRecord(e.getKey(), ch, e.getValue());
                        added++;
                        flog("ANTIREVOKE", "③ 库条目消失→记账: " + e.getValue());
                    }
                }
                prev.putAll(curDb);
                while (prev.size() > 600) {
                    java.util.Iterator<String> it = prev.keySet().iterator();
                    if (!it.hasNext()) break;
                    it.next();
                    it.remove();
                }
            }
            // ② 库窗口缺失
            int win = 0;
            int before = rvCountFor("");
            if (maxMatched >= 0) {
                for (int i = 0; i <= maxMatched; i++) {
                    String mid = ids.get(i);
                    if (mid == null || curIds.contains(mid)) continue;
                    if (rvHasRecord(mid)) continue;          // 已入台账 → 不重复解析(5s 一轮)
                    Object it = db.get(i);
                    String sid = rvSenderIdOf(it);
                    if (sid.length() > 0 && ch.length() > 0 && !sid.equals(ch)) continue;   // 自己撤回不记
                    String body = rvTextFor(it);
                    if (body == null) continue;
                    rvRecord(mid, ch, body);
                    added++;
                    win++;
                }
            }
            flog("ANTIREVOKE", "② 库窗口: 库=" + db.size() + " 命中=" + (maxMatched < 0 ? 0 : (maxMatched - minMatched + 1))
                 + " 窗口=0.." + maxMatched + " 窗口缺失=" + win + " 新入账=" + (rvCountFor("") - before));
        } catch (Throwable t) { flog("ANTIREVOKE", "库差集异常: " + t); }
        return added;
    }

    // 后台跑库差集(节流 5s; 单飞)
    private static void rvKickDbDiff(final String ch, final java.util.HashSet<String> curIds) {
        long now = System.currentTimeMillis();
        if (rvDbBusy || now - rvDbLastMs < 5000L) return;
        rvDbBusy = true;
        rvDbLastMs = now;
        final java.util.HashSet<String> ids = new java.util.HashSet<String>(curIds);
        try {
            Thread th = new Thread(new Runnable() {
                public void run() {
                    int n = 0;
                    try { n = rvDbDiff(ch, ids); } catch (Throwable t) { }
                    rvDbBusy = false;
                    if (n > 0) {
                        try {
                            new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                                public void run() {
                                    try {
                                        android.app.Activity a = rvTopActivity();
                                        if (a != null) rvShowCapsule(a);
                                    } catch (Throwable t) { }
                                }
                            });
                        } catch (Throwable t) { }
                    }
                }
            }, "cmhook-rvdiff");
            th.setDaemon(true);
            th.start();
        } catch (Throwable t) { rvDbBusy = false; }
    }

    // ---------- UI: 胶囊 + 浮层 ----------
    private static synchronized int rvCountFor(String ch) {
        rvLoad();
        if (ch == null || ch.length() == 0) return RV_MAP.size();
        int c = 0;
        for (String v : RV_MAP.values()) {
            int sep = v.indexOf('\u0001');
            String chOf = (sep > 0) ? v.substring(0, sep) : "";
            if (chOf.equals(ch)) c++;
        }
        return c;
    }

    private static volatile boolean rvWatcherStarted = false;

    // 反射取当前未暂停的 Activity(v6.5: 聊天页的 onResume 不在我们 hook 覆盖内)
    private static android.app.Activity rvTopActivity() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object cur = at.getMethod("currentActivityThread").invoke(null);
            java.lang.reflect.Field f = at.getDeclaredField("mActivities");
            f.setAccessible(true);
            java.util.Map<?, ?> map = (java.util.Map<?, ?>) f.get(cur);
            if (map == null) return null;
            for (Object rec : map.values()) {
                try {
                    java.lang.reflect.Field pf = rec.getClass().getDeclaredField("paused");
                    pf.setAccessible(true);
                    Object paused = pf.get(rec);
                    if (paused instanceof Boolean && !((Boolean) paused).booleanValue()) {
                        java.lang.reflect.Field af = rec.getClass().getDeclaredField("activity");
                        af.setAccessible(true);
                        Object a = af.get(rec);
                        if (a instanceof android.app.Activity) return (android.app.Activity) a;
                    }
                } catch (Throwable t) { }
            }
        } catch (Throwable t) { }
        return null;
    }

    // ===== 抽屉VIP挽回卡(方案A: 视图层替换, v8.6) =====
    // 卡片 = 服务端资源位(positionId=118「账号页卡板」, mod_vip, 按过期VIP人群投放, 第二行内容轮换)。
    // 文本在模块 hook 装好前已绑定(setText 探针抓不到) → 用巡检器驱动主动扫描: 全窗口找标题 TextView →
    // 上溯锁定卡片容器(高 11%~25% 屏高 且 宽≥40% 屏宽) → 原卡 GONE + 同位插入自定义 View。
    // 自定义图片: /sdcard/Android/data/com.netease.cloudmusic/files/cmhook_drawer_card.png
    // (支持 png/jpg/webp; 无图时显示模块占位卡)。
    private static final Object CARD_LOCK = new Object();
    private static final int CARD_PICK_REQ = 0x434D;           // 相册选图 requestCode ('CM')
    private static volatile boolean cardReplaced = false;      // 本次进程已替换(替换后停止扫描)
    private static volatile java.lang.ref.WeakReference<android.view.View> cardAppliedRef = null; // 已替换的卡片容器
    private static android.widget.ImageView cardPreviewView;  // 设置面板里的预览控件(对话框存续期有效)
    private static android.graphics.Bitmap cardBitmap;         // 自定义图缓存
    private static boolean cardBitmapTried = false;
    private static long cardLastMissLog = 0;

    private static boolean isVipCardText(String t) {
        if (t == null || t.length() == 0 || t.length() > 40) return false;
        return t.contains("期待您的回归") || t.contains("特权已失效") || t.contains("续费立享")
                || t.contains("会员特权") || t.contains("每日打卡") || t.contains("学生特惠")
                || t.contains("优惠开通") || t.contains("立享优惠");
    }

    private static void cardSweepTick() {
        if (!prefCardCustom || cardReplaced || !cmForeground) return;
        try {
            for (android.view.View r : allWindowRoots()) {
                if (cardReplaced) return;
                cardScan(r, 0);
            }
        } catch (Throwable t) { }
    }

    private static void cardScan(android.view.View v, int depth) {
        if (v == null || cardReplaced || depth > 60) return;
        if (v instanceof android.widget.TextView) {
            try {
                CharSequence cs = ((android.widget.TextView) v).getText();
                if (isVipCardText(cs == null ? null : cs.toString())) {
                    replaceVipCard((android.widget.TextView) v);
                    return;
                }
            } catch (Throwable t) { }
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = g.getChildCount() - 1; i >= 0; i--) {
                if (cardReplaced) return;
                cardScan(g.getChildAt(i), depth + 1);
            }
        }
    }

    private static android.graphics.Bitmap cardBitmap() {
        synchronized (CARD_LOCK) {
            if (cardBitmap != null || cardBitmapTried) return cardBitmap;
            cardBitmapTried = true;
            String[] cands = {"cmhook_drawer_card.png", "cmhook_drawer_card.jpg", "cmhook_drawer_card.webp"};
            for (String nm : cands) {
                try {
                    java.io.File f = new java.io.File("/sdcard/Android/data/" + TARGET_PKG + "/files/" + nm);
                    if (!f.exists() || f.length() == 0) continue;
                    android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath(), o);
                    int sample = 1;
                    while (o.outWidth / sample > 1080) sample *= 2;
                    android.graphics.BitmapFactory.Options o2 = new android.graphics.BitmapFactory.Options();
                    o2.inSampleSize = sample;
                    cardBitmap = android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath(), o2);
                    if (cardBitmap != null) { flog("CARDCUSTOM", "自定义图已加载: " + nm + " " + cardBitmap.getWidth() + "x" + cardBitmap.getHeight()); break; }
                } catch (Throwable t) { flog("CARDCUSTOM", "图片加载失败 " + nm + ": " + t); }
            }
            if (cardBitmap == null) flog("CARDCUSTOM", "未提供自定义图(files/cmhook_drawer_card.png), 使用占位卡");
            return cardBitmap;
        }
    }

    private static void replaceVipCard(android.widget.TextView tv) {
        try {
            android.view.View card = null;
            android.view.View cur = tv;
            int sh = tv.getResources().getDisplayMetrics().heightPixels;
            int sw = tv.getResources().getDisplayMetrics().widthPixels;
            StringBuilder chain = new StringBuilder();
            for (int i = 0; i < 10 && cur != null; i++) {
                chain.append(" <").append(cur.getClass().getSimpleName()).append(' ')
                     .append(cur.getWidth()).append('x').append(cur.getHeight()).append('>');
                int h = cur.getHeight(), w = cur.getWidth();
                if (h >= sh * 0.11f && h <= sh * 0.25f && w >= sw * 0.4f) { card = cur; break; }
                android.view.ViewParent vp = cur.getParent();
                cur = vp instanceof android.view.View ? (android.view.View) vp : null;
            }
            if (card == null) {
                long now = System.currentTimeMillis();
                if (now - cardLastMissLog > 60000L) {
                    cardLastMissLog = now;
                    flog("CARDCUSTOM", "容器未锁定(阈值未命中) 链:" + chain);
                }
                return;
            }
            if ("cmhook_vipcard_done".equals(card.getTag())) { cardReplaced = true; return; }
            int cw = card.getWidth(), ch = card.getHeight();
            if (cw <= 0 || ch <= 0) { flog("CARDCUSTOM", "容器未测量(" + cw + "x" + ch + "), 下轮再试"); return; }
            flog("CARDCUSTOM", "锁定容器 " + card.getClass().getName() + " " + cw + "x" + ch + " 链:" + chain + " → 覆盖替换");
            float den = card.getResources().getDisplayMetrics().density;
            // 主手段: setForeground —— foreground 永远绘制在所有子视图之上, 与 RN 的 zIndex/绘制顺序无关
            // (ReactViewGroup 自定义绘制顺序会让普通子视图盖不住原内容, 实测)。反射调用绕过旧编译桩。
            android.graphics.Bitmap bm = cardBitmap();
            try {
                invoke1(card, "setForeground", cardMakeDrawable(card, bm));
                card.setTag("cmhook_vipcard_done");
                cardAppliedRef = new java.lang.ref.WeakReference<android.view.View>(card);
                cardReplaced = true;
                flog("ADBLOCK", "抽屉VIP挽回卡已替换(foreground)");
                return;
            } catch (Throwable tf) { flog("CARDCUSTOM", "setForeground 失败, 回退子视图覆盖: " + tf); }
            // 回退: 原地覆盖 —— 自定义卡作为最后一个子视图塞进容器, 像素尺寸精确铺满
            android.widget.FrameLayout slot = new android.widget.FrameLayout(card.getContext());
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(0xFF262629);
            bg.setCornerRadius(12 * den);
            slot.setBackground(bg);
            if (bm != null) {
                android.widget.ImageView iv = new android.widget.ImageView(card.getContext());
                iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                iv.setImageBitmap(bm);
                int pad = (int) (6 * den);
                iv.setPadding(pad, pad, pad, pad);
                slot.addView(iv, new android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT));
            } else {
                android.widget.TextView ph = new android.widget.TextView(card.getContext());
                ph.setText("CM Hook · 自定义卡片位\n图片放 files/cmhook_drawer_card.png");
                ph.setTextSize(14);
                ph.setGravity(android.view.Gravity.CENTER);
                ph.setTextColor(0x80FFFFFF);
                slot.addView(ph, new android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT));
            }
            slot.setClickable(true);
            ((android.view.ViewGroup) card).addView(slot, new android.view.ViewGroup.LayoutParams(cw, ch));
            card.setTag("cmhook_vipcard_done");
            cardReplaced = true;
            flog("ADBLOCK", "抽屉VIP挽回卡已替换为自定义内容");
        } catch (Throwable t) { flog("CARDCUSTOM", "替换失败: " + t); }
    }

    // 卡片前景 Drawable: 有图=铺满位图, 无图=深色圆角占位
    private static android.graphics.drawable.Drawable cardMakeDrawable(android.view.View v, android.graphics.Bitmap bm) {
        if (bm != null) {
            android.graphics.drawable.BitmapDrawable bd = new android.graphics.drawable.BitmapDrawable(v.getResources(), bm);
            bd.setGravity(android.view.Gravity.FILL);
            return bd;
        }
        float den = v.getResources().getDisplayMetrics().density;
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(0xFF262629);
        g.setCornerRadius(12 * den);
        return g;
    }

    // 面板"选择图片": 借宿主 Activity 发起系统相册选图, 结果由 dispatchActivityResult 探针接住
    private static void cardPickPhoto(final android.app.Activity act) {
        try {
            android.content.Intent it = new android.content.Intent(android.content.Intent.ACTION_PICK,
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            act.startActivityForResult(it, CARD_PICK_REQ);
        } catch (Throwable t) {
            flog("CARDCUSTOM", "启动相册失败: " + t);
            toast(act, "无法启动相册: " + t);
        }
    }

    // 面板"恢复占位": 删除自定义图, 已替换的卡重设为深色占位
    private static void cardClearImage(final android.app.Activity act) {
        try {
            String[] cands = {"cmhook_drawer_card.png", "cmhook_drawer_card.jpg", "cmhook_drawer_card.webp"};
            for (String nm : cands) {
                try { new java.io.File("/sdcard/Android/data/" + TARGET_PKG + "/files/" + nm).delete(); } catch (Throwable t) { }
            }
        } catch (Throwable t) { }
        synchronized (CARD_LOCK) { cardBitmap = null; cardBitmapTried = true; }
        cardApplyToExisting(act, null);
        cardRefreshPreview(null);
        toast(act, "已恢复占位卡");
    }

    // 相册回传: 读流 → 校验可解码 → 落盘约定路径 → 刷新缓存/预览/已替换卡片
    private static void cardSaveFromUri(final android.app.Activity act, final android.net.Uri uri) {
        new Thread(new Runnable() { public void run() {
            try {
                java.io.InputStream in = act.getContentResolver().openInputStream(uri);
                if (in == null) throw new IllegalStateException("打开图片流失败");
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                    if (bos.size() > 25 * 1024 * 1024) { in.close(); throw new IllegalStateException("图片超过25MB"); }
                }
                in.close();
                byte[] bytes = bos.toByteArray();
                android.graphics.Bitmap check = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (check == null) throw new IllegalStateException("所选内容不是有效图片");
                flog("CARDCUSTOM", "选图已解码 " + check.getWidth() + "x" + check.getHeight() + " → 进入裁切编辑页");
                // 主线程打开编辑页(dispatchActivityResult 本就在主线程)
                cardOpenEditor(act, check);
            } catch (final Throwable t) {
                flog("CARDCUSTOM", "选图保存失败: " + t);
                toast(act, "选图失败: " + t.getMessage());
            }
        } }, "cmhook-card-save").start();
    }

    // 把(可能新的)图即时刷进已替换的卡片; bm=null → 占位
    private static void cardApplyToExisting(final android.app.Activity act, final android.graphics.Bitmap bm) {
        try {
            final android.view.View c = cardAppliedRef == null ? null : cardAppliedRef.get();
            if (c == null) return;
            final android.graphics.drawable.Drawable fg = cardMakeDrawable(c, bm);
            c.post(new Runnable() { public void run() {
                try { invoke1(c, "setForeground", fg); } catch (Throwable t) { }
            }});
        } catch (Throwable t) { }
    }

    // 面板预览刷新(主线程)
    private static void cardRefreshPreview(final android.graphics.Bitmap bm) {
        if (cardPreviewView == null) return;
        android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        h.post(new Runnable() { public void run() {
            try {
                if (cardPreviewView == null) return;
                if (bm != null) { cardPreviewView.setImageBitmap(bm); cardPreviewView.setVisibility(0); }
                else cardPreviewView.setVisibility(8);
            } catch (Throwable t) { }
        }});
    }

    // ===== 抽屉VIP卡: 裁切编辑页 (v8.8) =====
    // 选图/编辑都先进全屏编辑页: 图在卡片比例(1040x448)的裁切框内拖动+双指缩放, 确认后按框裁切
    // 输出 1040x448 PNG 并走保存→缓存→预览→已替换卡 四连刷新。纯 View 自绘, 无第三方依赖。
    private static final float CARD_RATIO = 1040f / 448f;

    private static void cardOpenEditor(final android.app.Activity act, final android.graphics.Bitmap src) {
        if (src == null) { toast(act, "没有可编辑的图片"); return; }
        // Dialog/ScaleGestureDetector(Handler) 必须建在主线程; 本函数会被选图保存/编辑按钮的后台线程调用
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() { public void run() {
                cardOpenEditor(act, src);
            }});
            return;
        }
        try {
            final float d = miDens(act);
            final android.widget.FrameLayout root = new android.widget.FrameLayout(act);
            root.setBackgroundColor(0xFF101012);
            final CardCropView cv = new CardCropView(act);
            cv.setBitmap(src);
            root.addView(cv, new android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT));
            android.widget.TextView hint = miText(act, "拖动 / 双指缩放 · 框内为卡片显示区域", 13, 0x80FFFFFF, false);
            android.widget.FrameLayout.LayoutParams hintL = new android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL);
            hintL.setMargins(0, (int) (40 * d), 0, 0);
            root.addView(hint, hintL);
            android.widget.LinearLayout bar = new android.widget.LinearLayout(act);
            bar.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            android.widget.TextView bCancel = miPill(act, "取消", 0xFF3A3A3E, true);
            android.widget.TextView bOk = miPill(act, "确认裁切", TK_PRIMARY, true);
            android.widget.LinearLayout.LayoutParams cl = new android.widget.LinearLayout.LayoutParams(
                    0, (int) (46 * d), 1f);
            cl.setMargins((int) (8 * d), 0, (int) (6 * d), 0);
            bar.addView(bCancel, cl);
            android.widget.LinearLayout.LayoutParams ol = new android.widget.LinearLayout.LayoutParams(
                    0, (int) (46 * d), 1.4f);
            ol.setMargins((int) (6 * d), 0, (int) (8 * d), 0);
            bar.addView(bOk, ol);
            android.widget.FrameLayout.LayoutParams barL = new android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.BOTTOM);
            barL.setMargins((int) (20 * d), 0, (int) (20 * d), (int) (34 * d));
            root.addView(bar, barL);
            final android.app.Dialog dlg = new android.app.Dialog(act);
            dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
            android.view.Window w = dlg.getWindow();
            if (w != null) {
                w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xFF101012));
                android.view.WindowManager.LayoutParams wlp = w.getAttributes();
                wlp.dimAmount = 0f;
                w.setAttributes(wlp);
                try { invoke1(w, "setStatusBarColor", 0xFF101012); } catch (Throwable t1) { }
                try { invoke1(w, "setNavigationBarColor", 0xFF101012); } catch (Throwable t1) { }
            }
            dlg.setContentView(root, new android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT));
            bCancel.setOnClickListener(new android.view.View.OnClickListener() {
                public void onClick(android.view.View v) { dlg.dismiss(); }
            });
            bOk.setOnClickListener(new android.view.View.OnClickListener() {
                public void onClick(android.view.View v) {
                    android.graphics.Bitmap out = cv.crop();
                    if (out == null) { toast(act, "裁切失败"); return; }
                    try {
                        java.io.File dst = new java.io.File("/sdcard/Android/data/" + TARGET_PKG + "/files/cmhook_drawer_card.png");
                        java.io.FileOutputStream fo = new java.io.FileOutputStream(dst);
                        out.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, fo);
                        fo.close();
                        synchronized (CARD_LOCK) { cardBitmap = out; cardBitmapTried = true; }
                        cardApplyToExisting(act, out);
                        cardRefreshPreview(out);
                        toast(act, "已裁切保存 " + out.getWidth() + "x" + out.getHeight());
                        flog("CARDCUSTOM", "编辑页裁切已保存 " + out.getWidth() + "x" + out.getHeight());
                    } catch (Throwable t) { toast(act, "保存失败: " + t); }
                    dlg.dismiss();
                }
            });
            dlg.show();
        } catch (Throwable t) { flog("CARDCUSTOM", "打开编辑页失败: " + t); toast(act, "打开编辑页失败: " + t); }
    }

    // 卡片比例裁切视图: 图可拖动/双指缩放, 暗色遮罩外+白色框内, crop() 按框裁源图输出 1040x448
    public static class CardCropView extends android.view.View {
        private android.graphics.Bitmap src;
        private final android.graphics.Matrix m = new android.graphics.Matrix();
        private final android.graphics.RectF frame = new android.graphics.RectF();
        private final android.graphics.Paint dim = new android.graphics.Paint();
        private final android.graphics.Paint stroke = new android.graphics.Paint();
        private final android.graphics.Paint bmpPaint = new android.graphics.Paint();
        private final android.view.ScaleGestureDetector sgd;
        private final android.graphics.PointF last = new android.graphics.PointF();
        private final float mar;
        private final float reserve;

        public CardCropView(android.content.Context c) {
            super(c);
            float d = c.getResources().getDisplayMetrics().density;
            mar = 28 * d;
            reserve = 150 * d;
            dim.setColor(0xB3000000);
            stroke.setColor(0xFFFFFFFF);
            stroke.setStyle(android.graphics.Paint.Style.STROKE);
            stroke.setStrokeWidth(Math.max(2f, 2 * d));
            bmpPaint.setFilterBitmap(true);
            sgd = new android.view.ScaleGestureDetector(c, new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(android.view.ScaleGestureDetector detector) {
                    m.postScale(detector.getScaleFactor(), detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY());
                    clamp(); invalidate(); return true;
                }
            });
            setOnTouchListener(new android.view.View.OnTouchListener() {
                @Override
                public boolean onTouch(android.view.View v, android.view.MotionEvent ev) {
                    sgd.onTouchEvent(ev);
                    switch (ev.getActionMasked()) {
                        case android.view.MotionEvent.ACTION_DOWN:
                            last.set(ev.getX(), ev.getY()); break;
                        case android.view.MotionEvent.ACTION_MOVE:
                            if (!sgd.isInProgress()) {
                                m.postTranslate(ev.getX() - last.x, ev.getY() - last.y);
                                clamp(); invalidate();
                            }
                            last.set(ev.getX(), ev.getY()); break;
                        default: break;
                    }
                    return true;
                }
            });
        }

        public void setBitmap(android.graphics.Bitmap b) { src = b; layoutFrame(); fit(); invalidate(); }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) { layoutFrame(); fit(); invalidate(); }

        private void layoutFrame() {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float availH = h - 2 * mar - reserve;
            float fw = Math.min(w - 2 * mar, availH * CARD_RATIO);
            float fh = fw / CARD_RATIO;
            float left = (w - fw) / 2f;
            float top = mar + Math.max(0f, (availH - fh) / 2f);
            frame.set(left, top, left + fw, top + fh);
        }

        private void fit() {
            if (src == null || frame.isEmpty()) return;
            float sc = Math.max(frame.width() / src.getWidth(), frame.height() / src.getHeight());
            m.reset();
            m.postScale(sc, sc);
            m.postTranslate(frame.centerX() - src.getWidth() * sc / 2f, frame.centerY() - src.getHeight() * sc / 2f);
            clamp(); invalidate();
        }

        // 保证图始终盖住裁切框: 不够盖的方向锁居中, 够盖的方向平移不得露框
        private void clamp() {
            if (src == null || frame.isEmpty()) return;
            float[] v = new float[9];
            m.getValues(v);
            float sc = v[android.graphics.Matrix.MSCALE_X];
            float sw = src.getWidth() * sc, sh = src.getHeight() * sc;
            float tx = v[android.graphics.Matrix.MTRANS_X], ty = v[android.graphics.Matrix.MTRANS_Y];
            tx = sw >= frame.width() ? Math.min(Math.max(tx, frame.left + frame.width() - sw), frame.left)
                                     : frame.centerX() - sw / 2f;
            ty = sh >= frame.height() ? Math.min(Math.max(ty, frame.top + frame.height() - sh), frame.top)
                                      : frame.centerY() - sh / 2f;
            v[android.graphics.Matrix.MTRANS_X] = tx;
            v[android.graphics.Matrix.MTRANS_Y] = ty;
            if (sc < Math.max(frame.width() / src.getWidth(), frame.height() / src.getHeight())) {
                float minSc = Math.max(frame.width() / src.getWidth(), frame.height() / src.getHeight());
                v[android.graphics.Matrix.MSCALE_X] = minSc;
                v[android.graphics.Matrix.MSCALE_Y] = minSc;
            }
            m.setValues(v);
        }

        @Override
        protected void onDraw(android.graphics.Canvas c) {
            super.onDraw(c);
            if (src == null || frame.isEmpty()) return;
            int save = c.save();
            c.concat(m);
            c.drawBitmap(src, 0, 0, bmpPaint);
            c.restoreToCount(save);
            c.drawRect(0, 0, getWidth(), frame.top, dim);
            c.drawRect(0, frame.bottom, getWidth(), getHeight(), dim);
            c.drawRect(0, frame.top, frame.left, frame.bottom, dim);
            c.drawRect(frame.right, frame.top, getWidth(), frame.bottom, dim);
            c.drawRoundRect(frame, 12, 12, stroke);
        }

        // 按框裁源图 → 1040x448 输出
        public android.graphics.Bitmap crop() {
            if (src == null || frame.isEmpty()) return null;
            android.graphics.Matrix inv = new android.graphics.Matrix(m);
            inv.invert(inv);
            android.graphics.RectF srcRect = new android.graphics.RectF(frame);
            inv.mapRect(srcRect);
            float l = Math.max(0, srcRect.left), t = Math.max(0, srcRect.top);
            float r = Math.min(src.getWidth(), srcRect.right), b = Math.min(src.getHeight(), srcRect.bottom);
            if (r <= l || b <= t) return null;
            android.graphics.RectF s = new android.graphics.RectF(l, t, r, b);
            android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(1040, 448, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c = new android.graphics.Canvas(out);
            c.drawBitmap(src, new android.graphics.Rect((int) s.left, (int) s.top, (int) s.right, (int) s.bottom),
                    new android.graphics.Rect(0, 0, 1040, 448), bmpPaint);
            return out;
        }
    }

    private static void startRvWatcher() {
        if (rvWatcherStarted) return;
        rvWatcherStarted = true;
        try {
            final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            h.postDelayed(new Runnable() {
                public void run() {
                    try {
                        android.app.Activity a = rvTopActivity();
                        if (a != null) rvDiffAndShow(a);
                    } catch (Throwable t) { }
                    try { cardSweepTick(); } catch (Throwable t) { }   // v8.6: 抽屉VIP卡替换扫描
                    try { h.postDelayed(this, 1500L); } catch (Throwable t) { }
                }
            }, 1500L);
            flog("INIT", "撤回巡检器已启动(1.5s 一次)");
        } catch (Throwable t) { }
    }

    private static void rvDiffAndShow(final android.app.Activity act) {
        rvLastAct = act;
        try {
            java.util.List<?> cur = rvListForAct(act);          // v7.4: 本页视图树优先(防串会话)
            if (cur != null && !cur.isEmpty()) {
                java.util.HashSet<String> curIds = new java.util.HashSet<String>();
                for (int i = 0; i < cur.size(); i++) {
                    Object id = rvCall(cur.get(i), "getId");
                    if (id != null) curIds.add(String.valueOf(id));
                }
                String ch = rvCurrentChannel(act);
                if (ch.length() > 0) {
                    rvNote(act.getClass().getSimpleName() + " 列表=" + curIds.size() + " 会话=" + ch);
                    try { rvListVanish(ch, cur, curIds); } catch (Throwable t) { flog("ANTIREVOKE", "源①异常: " + t); }
                    rvKickDbDiff(ch, curIds);
                } else {
                    rvNote("未取到 channelId");
                }
            } else {
                rvNote("未取得聊天列表");
            }
        } catch (Throwable t) { flog("ANTIREVOKE", "差集异常: " + t); }
        try { rvShowCapsule(act); } catch (Throwable t) { }
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                public void run() {
                    try { rvShowCapsule(act); } catch (Throwable t) { }
                }
            }, 1500L);
        } catch (Throwable t) { }
    }

    // 差集状态去抖: 状态没变不刷日志(此前每 1.5s 一条把日志冲爆)
    private static void rvNote(String s) {
        if (s == null || s.equals(rvLastDiffNote)) return;
        rvLastDiffNote = s;
        flog("ANTIREVOKE", "差集状态: " + s);
    }

    private static void rvShowCapsule(final android.app.Activity act) {
        if (!prefAntiRevoke) return;
        try {
            if (act.getClass().getName().indexOf("PrivateMsgDetail") < 0) return;
            rvLoad();
            android.view.View root = act.getWindow().getDecorView();
            if (!(root instanceof android.view.ViewGroup)) return;
            float d = miDens(act);
            android.widget.TextView cap = null;
            android.view.View ex = root.findViewWithTag(RV_CAP_TAG);
            if (ex instanceof android.widget.TextView) cap = (android.widget.TextView) ex;
            if (cap == null) {
                cap = new android.widget.TextView(act);
                cap.setTag(RV_CAP_TAG);
                cap.setTextColor(0xFFFFFFFF);
                cap.setTextSize(11);
                cap.setPadding((int) (12 * d), (int) (6 * d), (int) (12 * d), (int) (6 * d));
                cap.setBackground(miBg(0xE6EC4141, 999 * d));
                android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.Gravity.BOTTOM | android.view.Gravity.END);
                lp.bottomMargin = rvCapBottom >= 0 ? rvCapBottom : (int) (140 * d);
                lp.rightMargin = rvCapRight >= 0 ? rvCapRight : (int) (12 * d);
                ((android.view.ViewGroup) root).addView(cap, lp);
                // v7.2: 可拖动(松手落位持久化); 未拖动=点击展开/收起浮层
                cap.setOnTouchListener(new android.view.View.OnTouchListener() {
                    float downX, downY;
                    int startR, startB;
                    boolean moved;
                    long downAt;
                    public boolean onTouch(android.view.View v, android.view.MotionEvent e) {
                        try {
                            android.view.ViewGroup.LayoutParams rawLp = v.getLayoutParams();
                            if (!(rawLp instanceof android.view.ViewGroup.MarginLayoutParams)) return false;
                            android.view.ViewGroup.MarginLayoutParams mlp = (android.view.ViewGroup.MarginLayoutParams) rawLp;
                            int a = e.getActionMasked();
                            if (a == android.view.MotionEvent.ACTION_DOWN) {
                                downX = e.getRawX(); downY = e.getRawY();
                                startR = mlp.rightMargin; startB = mlp.bottomMargin;
                                moved = false; downAt = System.currentTimeMillis();
                                return true;
                            } else if (a == android.view.MotionEvent.ACTION_MOVE) {
                                float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                                if (Math.abs(dx) > 10 || Math.abs(dy) > 10) moved = true;
                                if (moved) {
                                    android.util.DisplayMetrics dm = act.getResources().getDisplayMetrics();
                                    int maxR = Math.max(0, dm.widthPixels - v.getWidth());
                                    int maxB = Math.max(0, dm.heightPixels - v.getHeight());
                                    int nr = startR - (int) dx;
                                    int nb = startB - (int) dy;
                                    if (nr < 0) nr = 0; else if (nr > maxR) nr = maxR;
                                    if (nb < 0) nb = 0; else if (nb > maxB) nb = maxB;
                                    mlp.rightMargin = nr;
                                    mlp.bottomMargin = nb;
                                    v.setLayoutParams(mlp);
                                }
                                return true;
                            } else if (a == android.view.MotionEvent.ACTION_UP || a == android.view.MotionEvent.ACTION_CANCEL) {
                                if (moved) {
                                    rvCapRight = mlp.rightMargin;
                                    rvCapBottom = mlp.bottomMargin;
                                    try {
                                        android.content.SharedPreferences sp = act.getSharedPreferences("cmhook_prefs", 0);
                                        sp.edit().putInt("rv_cap_right", rvCapRight).putInt("rv_cap_bottom", rvCapBottom).commit();
                                    } catch (Throwable t2) { }
                                    flog("ANTIREVOKE", "胶囊位置已保存: right=" + rvCapRight + " bottom=" + rvCapBottom);
                                } else if (System.currentTimeMillis() - downAt < 400) {
                                    long nowMs = System.currentTimeMillis();
                                    if (nowMs - rvLastToggleMs > 350L) {   // v7.5: 抬手去抖
                                        rvLastToggleMs = nowMs;
                                        rvTogglePanel(act);
                                    }
                                }
                                return true;
                            }
                        } catch (Throwable t) { }
                        return false;
                    }
                });
            }
            // v7.4: 会话识别不到就不报可能错的数字(留「撤回 –」半透明), 识别到才按本会话计数
            String ch = rvCurrentChannel(act);
            if (ch.length() > 0) rvLastChForUi = ch;
            int cnt = ch.length() > 0 ? rvCountFor(ch) : -1;
            cap.setText(cnt < 0 ? "撤回 –" : "撤回 " + cnt);
            cap.setVisibility(android.view.View.VISIBLE);
            if (cnt != rvLastCapShown) {
                rvLastCapShown = cnt;
                cap.setTextColor(cnt <= 0 ? 0xCCFFFFFF : 0xFFFFFFFF);
                cap.setBackground(miBg(cnt <= 0 ? 0x99888890 : 0xE6EC4141, 999 * d));
                flog("ANTIREVOKE", "胶囊: " + (cnt < 0 ? "会话未识别(撤回 –)" : (cnt == 0 ? "空态 撤回 0(半透明)" : "撤回 " + cnt))
                        + " (会话=" + ch + " 台账=" + rvCountFor("") + " 条)");
            }
        } catch (Throwable t) { flog("ANTIREVOKE", "胶囊失败: " + t); }
    }

    private static volatile long rvPanelOpenedAt = 0L;
    private static volatile long rvLastToggleMs = 0L;

    // v7.1: 统一关闭出口(胶囊再点 / 点浮层外 / 返回键); v7.5: 带原因 + 开启后 350ms 忽略关闭
    private static boolean rvClosePanel(android.app.Activity act) {
        return rvClosePanel(act, "?");
    }

    private static boolean rvClosePanel(android.app.Activity act, String why) {
        try {
            if (System.currentTimeMillis() - rvPanelOpenedAt < 350L) {   // 开屏尾事件护栏(同一手势的 UP 打到新加的遮罩)
                flog("ANTIREVOKE", "浮层关闭被忽略(" + why + ", 距展开 <350ms)");
                return false;
            }
            if (act == null || act.getWindow() == null) return false;
            android.view.View root = act.getWindow().getDecorView();
            if (!(root instanceof android.view.ViewGroup)) return false;
            android.view.ViewGroup g = (android.view.ViewGroup) root;
            boolean closed = false;
            android.view.View p = g.findViewWithTag(RV_PANEL_TAG);
            if (p != null) { g.removeView(p); closed = true; }
            android.view.View sc = g.findViewWithTag(RV_SCRIM_TAG);
            if (sc != null) { g.removeView(sc); closed = true; }
            if (closed) flog("ANTIREVOKE", "浮层已关闭(" + why + ")");
            return closed;
        } catch (Throwable t) { return false; }
    }

    private static void rvTogglePanel(android.app.Activity act) {
        try {
            android.view.View root = act.getWindow().getDecorView();
            if (!(root instanceof android.view.ViewGroup)) return;
            android.view.View old = root.findViewWithTag(RV_PANEL_TAG);
            if (old != null) { rvClosePanel(act, "胶囊再点"); return; }
            float d = miDens(act);
            android.widget.LinearLayout panel = new android.widget.LinearLayout(act);
            panel.setTag(RV_PANEL_TAG);
            panel.setOrientation(android.widget.LinearLayout.VERTICAL);
            panel.setBackground(miBg(0xF218181A, 18 * d));
            panel.setPadding((int) (14 * d), (int) (12 * d), (int) (14 * d), (int) (12 * d));

            // v7.4: 严格本会话 —— 识别不到就不列任何条目(绝不串会话)
            String curCh = rvCurChannelId();
            if (curCh.length() == 0) curCh = rvLastChForUi;
            int cntAll = curCh.length() == 0 ? 0 : rvCountFor(curCh);
            String titleStr = curCh.length() == 0
                    ? "当前会话未识别 · 暂不展示"
                    : ("本会话 #" + curCh + " 已拦截撤回 · " + cntAll + " 条");
            android.widget.TextView title = miText(act, titleStr, 14, 0xFFFFFFFF, true);
            panel.addView(title);
            android.widget.TextView hint = miText(act, "数据来源：本地库与聊天记录比对 · 点空白/返回键关闭", 11, 0x99FFFFFF, false);
            panel.addView(hint);

            android.widget.ScrollView sv = new android.widget.ScrollView(act);
            android.widget.LinearLayout list = new android.widget.LinearLayout(act);
            list.setOrientation(android.widget.LinearLayout.VERTICAL);
            int n = 0;
            java.util.ArrayList<String> rvVals = new java.util.ArrayList<String>();
            synchronized (MainHook.class) { rvVals.addAll(RV_MAP.values()); }
            for (String v : rvVals) {
                if (n >= 100) break;
                int sep = v.indexOf('\u0001');
                String chOf = (sep > 0) ? v.substring(0, sep) : "";
                String txtOf = (sep > 0) ? v.substring(sep + 1) : v;
                if (curCh.length() == 0) break;                       // 会话未识别: 一条都不列
                if (chOf.length() > 0 && !chOf.equals(curCh)) continue;   // 只列当前会话
                n++;
                android.widget.TextView row = miText(act, txtOf, 12, 0xE6FFFFFF, false);
                row.setPadding(0, (int) (6 * d), 0, (int) (6 * d));
                row.setTextIsSelectable(true);
                list.addView(row);
            }
            if (n == 0) {
                android.widget.TextView empty = miText(act, curCh.length() == 0
                        ? "（还没识别到当前会话 —— 停留 1~2 秒再点一次）"
                        : "（本会话还没有拦到的撤回记录）", 12, 0x99FFFFFF, false);
                empty.setPadding(0, (int) (8 * d), 0, (int) (8 * d));
                list.addView(empty);
            }
            sv.addView(list);
            android.widget.LinearLayout.LayoutParams slp = new android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, (int) (260 * d));
            slp.topMargin = (int) (8 * d);
            panel.addView(sv, slp);

            panel.setOnClickListener(new android.view.View.OnClickListener() {
                public void onClick(android.view.View v) { }
            });
            android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.BOTTOM);
            lp.leftMargin = (int) (10 * d);
            lp.rightMargin = (int) (10 * d);
            lp.bottomMargin = (int) (120 * d);
            android.view.View scrim = new android.view.View(act);
            scrim.setTag(RV_SCRIM_TAG);
            scrim.setBackgroundColor(0x66000000);
            scrim.setOnClickListener(new android.view.View.OnClickListener() {
                public void onClick(android.view.View v) { rvClosePanel(act, "点空白"); }
            });
            ((android.view.ViewGroup) root).addView(scrim, new android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT));
            ((android.view.ViewGroup) root).addView(panel, lp);
            rvPanelOpenedAt = System.currentTimeMillis();
            flog("ANTIREVOKE", "浮层已展开(本会话 " + curCh + " · " + cntAll + " 条)");
        } catch (Throwable t) { flog("ANTIREVOKE", "浮层失败: " + t); }
    }

    // ===== v23: 首页「听歌识曲」入口 =====
    //   ① 搜索图标右侧插入识曲图标(点击进入) ② 长按搜索图标 = 进入识曲
    //   目标页: com.netease.cloudmusic.music.biz.recognition.ui.activity.IdentifyActivityV2 (manifest 实测)
    private static final String IDENTIFY_ACTS[] = {
        "com.netease.cloudmusic.music.biz.recognition.ui.activity.IdentifyActivityV2",
        "com.netease.cloudmusic.music.biz.recognition.ui.activity.IdentifyActivity",
        "com.netease.cloudmusic.music.biz.recognition.ui.activity.IdentifyGuideActivity"
    };
    private static final String IDENTIFY_TAG = "cmhook_identify_entry";

    private static void startIdentify(android.content.Context c) {
        for (int i = 0; i < IDENTIFY_ACTS.length; i++) {
            try {
                android.content.Intent it = new android.content.Intent();
                it.setClassName(TARGET_PKG, IDENTIFY_ACTS[i]);
                if (!(c instanceof android.app.Activity)) it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(it);
                flog("IDENTIFY", "进入听歌识曲 → " + IDENTIFY_ACTS[i]);
                return;
            } catch (Throwable t) {
                flog("IDENTIFY", "跳转失败(" + IDENTIFY_ACTS[i] + "): " + t);
            }
        }
        // v49 兜底: 硬编码链全失败 → PackageManager 枚举包内 Activity(名含 Identify/Recogni), 排除结果/历史/引导/失败/列表页
        try {
            android.content.pm.PackageManager pm = c.getPackageManager();
            android.content.pm.PackageInfo pi = pm.getPackageInfo(TARGET_PKG, 0x2000);   // GET_ACTIVITIES
            String best = null; int bestScore = -99;
            if (pi != null && pi.activities != null) {
                for (int i = 0; i < pi.activities.length; i++) {
                    String n = pi.activities[i].name;
                    if (n == null) continue;
                    String ln = n.toLowerCase();
                    if (ln.indexOf("identify") < 0 && ln.indexOf("recogni") < 0) continue;
                    // 排除: 结果/历史/引导/失败/列表/悬浮窗/艺术家作品 等非"入口"页
                    if (ln.indexOf("result") >= 0 || ln.indexOf("history") >= 0 || ln.indexOf("guide") >= 0
                            || ln.indexOf("fail") >= 0 || ln.indexOf("list") >= 0 || ln.indexOf("float") >= 0
                            || ln.indexOf("artist") >= 0 || ln.indexOf("service") >= 0) continue;
                    int score = 0;
                    if (ln.indexOf("music.biz.recognition") >= 0) score += 5;   // 正确的包
                    if (ln.endsWith("activityv2")) score += 3;                  // 新版优先
                    if (ln.indexOf("shortcut") >= 0) score -= 2;                // loading 包的快捷入口, 次选
                    if (ln.endsWith("identifyactivity")) score += 2;            // IdentifyActivity(旧版)
                    flog("IDENTIFY", "枚举候选: " + n + " (score=" + score + ")");
                    if (score > bestScore) { bestScore = score; best = n; }
                }
            }
            if (best != null) {
                try {
                    android.content.Intent it2 = new android.content.Intent();
                    it2.setClassName(TARGET_PKG, best);
                    if (!(c instanceof android.app.Activity)) it2.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    c.startActivity(it2);
                    flog("IDENTIFY", "枚举兜底进入 → " + best + " (score=" + bestScore + ")");
                    return;
                } catch (Throwable t2) { flog("IDENTIFY", "枚举兜底启动失败: " + t2); }
            }
            flog("IDENTIFY", "枚举兜底: 无可用候选");
        } catch (Throwable t) { flog("IDENTIFY", "枚举兜底异常: " + t); }
    }

    // v25: 反射拿所有窗口的根视图(首页顶栏可能不在当前 Activity 的 decorView 里)
    private static java.util.List<android.view.View> allWindowRoots() {
        java.util.List<android.view.View> out = new java.util.ArrayList<android.view.View>();
        try {
            Class<?> wmg = Class.forName("android.view.WindowManagerGlobal");
            java.lang.reflect.Method getInstance = wmg.getDeclaredMethod("getInstance");
            getInstance.setAccessible(true);
            Object inst = getInstance.invoke(null);
            java.lang.reflect.Method getViewRootNames = wmg.getDeclaredMethod("getViewRootNames");
            getViewRootNames.setAccessible(true);
            java.lang.reflect.Method getRootView = wmg.getDeclaredMethod("getRootView", String.class);
            getRootView.setAccessible(true);
            String[] names = (String[]) getViewRootNames.invoke(inst);
            if (names != null) {
                for (int i = 0; i < names.length; i++) {
                    try {
                        Object rv = getRootView.invoke(inst, names[i]);
                        if (rv instanceof android.view.View) out.add((android.view.View) rv);
                    } catch (Throwable t) { }
                }
            }
        } catch (Throwable t) { }
        return out;
    }

    // ===== v19: 首页推荐页模块清理 =====
    // 判据: URL 命中首页推荐页 feed 接口
    private static boolean isHomeFeedUrl(String url) {
        return url != null && url.indexOf(HOME_FEED_URL_KEY) >= 0;
    }

    // 首页 feed 全文落盘(限次, 固定文件名覆盖写, 不堆存储) — 用于标定锚点规则/排障
    private static void dumpFeed(String url, String body) {
        synchronized (LOG_LOCK) {
            if (feedDumpCount >= FEED_DUMP_MAX) return;
            try {
                File dir = new File("/sdcard/Android/data/" + TARGET_PKG + "/files");
                dir.mkdirs();
                File f = new File(dir, "cm_feed_raw.json");
                java.io.OutputStreamWriter w = new java.io.OutputStreamWriter(new java.io.FileOutputStream(f, false), "UTF-8");
                w.write(url + "\n");
                w.write(body);
                w.close();
                flog("FEED", "落盘 " + f.getName() + " url=" + url + " len=" + body.length());
                feedDumpCount++;
            } catch (Throwable t) { flog("FEED", "<落盘失败 " + t + ">"); }
        }
    }

    // 过滤后 JSON 落盘(校验用, 与 cm_feed_raw.json 对照)
    private static void dumpFeedFiltered(String body, int seq) {
        synchronized (LOG_LOCK) {
            try {
                File dir = new File("/sdcard/Android/data/" + TARGET_PKG + "/files");
                dir.mkdirs();
                File f = new File(dir, "cm_feed_filtered.json");
                java.io.OutputStreamWriter w = new java.io.OutputStreamWriter(new java.io.FileOutputStream(f, false), "UTF-8");
                w.write(body);
                w.close();
            } catch (Throwable t) { }
        }
    }

    // v20: 把指定 key 的数组清空(data:[...] → data:[]), 字符串感知的括号匹配
    private static String emptyJsonArrayForKey(String json, String key) {
        String pat = "\"" + key + "\":";
        int i = json.indexOf(pat);
        if (i < 0) return null;
        int lb = json.indexOf('[', i + pat.length());
        if (lb < 0) return null;
        int depth = 0;
        boolean inStr = false, esc = false;
        for (int p = lb; p < json.length(); p++) {
            char c = json.charAt(p);
            if (inStr) {
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') { inStr = true; continue; }
            if (c == '[' || c == '{') depth++;
            else if (c == ']' || c == '}') {
                depth--;
                if (depth == 0) return json.substring(0, lb + 1) + json.substring(p);
            }
        }
        return null;
    }

    private static String blockCode(String s, int from, int to) {        int i = s.indexOf("\"positionCode\":\"", from);
        if (i < 0 || i + 16 >= to) return null;
        int a = i + 16;
        int b = s.indexOf('"', a);
        if (b < 0 || b > to) return null;
        return s.substring(a, b);
    }

    // 字符串级 blocks 切除(不解析/不重排其余字段): 命中锚点块后, 该块及其后所有块整段删除
    private static String filterHomeFeed(String json) {
        String anchor = prefHomeCleanAnchor;
        if (anchor == null || anchor.trim().length() == 0) return null;
        int bi = json.indexOf("\"blocks\"");
        if (bi < 0) return null;
        int lb = json.indexOf('[', bi + 8);
        if (lb < 0) return null;
        java.util.ArrayList<int[]> ranges = new java.util.ArrayList<int[]>();
        java.util.ArrayList<String> codes = new java.util.ArrayList<String>();
        int n = json.length();
        int depth = 0, start = -1, rbPos = -1;   // rbPos: blocks 数组的 ']'(v7.8 重建用)
        boolean inStr = false, esc = false;
        for (int p = lb + 1; p < n; p++) {
            char c = json.charAt(p);
            if (inStr) {
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') { inStr = true; continue; }
            if (c == '{') { if (depth == 0) start = p; depth++; continue; }
            if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    ranges.add(new int[]{start, p});
                    codes.add(blockCode(json, start, p));
                    start = -1;
                }
                continue;
            }
            if (c == ']' && depth == 0) { rbPos = p; break; }
        }
        if (ranges.isEmpty()) return null;
        boolean firstBatch = json.indexOf("PAGE_RECOMMEND_DAILY_RECOMMEND") >= 0;
        String[] rules = anchor.split(",");
        int cutAt = -1;
        for (int i = 0; i < ranges.size() && cutAt < 0; i++) {
            String code = codes.get(i);
            for (int r = 0; r < rules.length; r++) {
                String ru = rules[r].trim();
                if (ru.length() == 0) continue;
                if (ru.startsWith("TEXT:")) {
                    String txt = ru.substring(5);
                    int[] rr = ranges.get(i);
                    if (txt.length() > 0 && json.substring(rr[0], rr[1] + 1).indexOf(txt) >= 0) { cutAt = i; break; }
                } else {
                    String want = ru.startsWith("CODE:") ? ru.substring(5) : ru;
                    if (code != null && code.equals(want)) { cutAt = i; break; }
                }
            }
        }
        // v7.8: 锚点没命中 → 走"保留白名单"过滤(服务端已不再下发锚点块)
        if (cutAt < 0) {
            String keep = prefHomeCleanKeep;
            if (keep != null && keep.trim().length() > 0 && rbPos > lb) {
                java.util.HashSet<String> keepSet = new java.util.HashSet<String>();
                String[] ks = keep.split(",");
                for (int i = 0; i < ks.length; i++) {
                    String k = ks[i].trim();
                    if (k.length() > 0) keepSet.add(k);
                }
                StringBuilder sb = new StringBuilder();
                sb.append(json, 0, lb + 1);
                java.util.ArrayList<String> kept = new java.util.ArrayList<String>();
                java.util.ArrayList<String> dropped = new java.util.ArrayList<String>();
                int cntKeep = 0;
                for (int i = 0; i < ranges.size(); i++) {
                    String code = codes.get(i);
                    if (code != null && keepSet.contains(code)) {
                        if (cntKeep > 0) sb.append(',');
                        sb.append(json, ranges.get(i)[0], ranges.get(i)[1] + 1);
                        cntKeep++;
                        kept.add(code);
                    } else {
                        dropped.add(String.valueOf(code));
                    }
                }
                sb.append(json, rbPos, json.length());
                String out = sb.toString();
                if (cntKeep == 0) {
                    out = out.replace("\"hasMore\":true", "\"hasMore\":false");
                    homeCleanArmed = false;
                }
                if (firstBatch) homeCleanArmed = (cntKeep > 0);
                flog("HOME_CLEAN", "白名单过滤: 块 " + ranges.size() + " → 保留 " + cntKeep + " " + kept
                    + " / 删除 " + dropped + "  (" + json.length() + " → " + out.length() + " 字节)"
                    + (cntKeep == 0 ? " + hasMore=false" : ""));
                return out;
            }
            // 没配白名单 → 保留旧行为(首屏没命中就不动; 续页整批清)
            if (firstBatch) { homeCleanArmed = false; return null; }
            if (!homeCleanArmed) return null;
            cutAt = 0;
        } else if (firstBatch) {
            homeCleanArmed = true;
        }
        int delStart = ranges.get(cutAt)[0];
        int delEnd = ranges.get(ranges.size() - 1)[1];
        String head = json.substring(0, delStart);
        String tail = json.substring(delEnd + 1);
        if (cutAt > 0) {
            int k = head.length() - 1;
            while (k >= 0 && Character.isWhitespace(head.charAt(k))) k--;
            if (k >= 0 && head.charAt(k) == ',') {
                int k2 = k - 1;
                while (k2 >= 0 && Character.isWhitespace(head.charAt(k2))) k2--;
                head = head.substring(0, k2 + 1) + " ";
            }
        }
        String out = head + tail;
        if (cutAt == 0) {
            // 整批清空: 同时把 hasMore 置 false, 让客户端停止继续分页请求(否则滚到底会反复拉空批)
            out = out.replace("\"hasMore\":true", "\"hasMore\":false");
        }
        flog("HOME_CLEAN", "blocks " + ranges.size() + " 块 → 切除 " + (ranges.size() - cutAt) + " 块(第" + cutAt + "块起, code=" + codes.get(cutAt) + "), "
            + json.length() + " → " + out.length() + " 字节");
        return out;
    }

    // 用新 JSON 重建 okhttp Response(4.x: ResponseBody.create + Response.newBuilder)
    // 说明: 全部走 java 反射(compile-only stub 里没有 callStaticMethod / setResult)
    private static Object rebuildResponse(Object resp, String text, ClassLoader cl) {
        try {
            Class<?> rbCls = cl.loadClass("okhttp3.ResponseBody");
            Class<?> mtCls = cl.loadClass("okhttp3.MediaType");
            Object oldBody = invoke0(resp, "body");
            Object ct = oldBody != null ? invoke0(oldBody, "contentType") : null;
            Object newBody = null;
            try {
                Method m = rbCls.getMethod("create", mtCls, String.class);
                newBody = m.invoke(null, ct, text);
            } catch (Throwable t1) {
                try {
                    Method m2 = rbCls.getMethod("create", mtCls, byte[].class);
                    newBody = m2.invoke(null, ct, text.getBytes("UTF-8"));
                } catch (Throwable t2) { flog("HOME_CLEAN", "<body构造失败 " + t1 + " / " + t2 + ">"); }
            }
            if (newBody == null) return null;
            Object builder = invoke0(resp, "newBuilder");
            invoke1(builder, "body", newBody);
            Object newResp = invoke0(builder, "build");
            try { if (oldBody != null) invoke0(oldBody, "close"); } catch (Throwable t3) { }
            return newResp;
        } catch (Throwable t) { flog("HOME_CLEAN", "<Response重建失败 " + t + ">"); return null; }
    }

    // afterHookedMethod 里改返回值: 运行时 MethodHookParam.setResult(Object)
    private static void setParamResult(Object param, Object value) {
        try {
            Method m = param.getClass().getMethod("setResult", Object.class);
            m.invoke(param, value);
        } catch (Throwable t) { flog("HOME_CLEAN", "<setResult失败 " + t + ">"); }
    }

    public void handleLoadPackage(final LoadPackageParam lpp) throws Throwable {
        if (!TARGET_PKG.equals(lpp.packageName)) return;
        flog("INIT", "loaded, process=" + lpp.processName);

        // 业务类可能由 Tinker 类加载器加载, 用 loadClass 探针抓运行时加载器
        try {
            Class<?> clClass = XposedHelpers.findClass("java.lang.ClassLoader", lpp.classLoader);
            XposedBridge.hookAllMethods(clClass, "loadClass", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Object name0 = param.args.length > 0 ? param.args[0] : null;
                    if (!(name0 instanceof String)) return;
                    String n = (String) name0;
                    if (n.equals("com.netease.cloudmusic.utils.xu")
                            || n.equals("com.netease.cloudmusic.utils.wu")
                            || n.equals("com.netease.cloudmusic.network.encrypt.CMEncryptService")) {
                        String key = String.valueOf(System.identityHashCode(param.thisObject));
                        synchronized (doneLoaders) {
                            if (doneLoaders.contains(key)) return;
                            doneLoaders.add(key);
                        }
                        ClassLoader real = (ClassLoader) param.thisObject;
                        flog("INIT", "运行时类加载器: " + param.thisObject.getClass().getName()
                                + " @0x" + Integer.toHexString(System.identityHashCode(real)));
                        try {
                            installBusinessHooks(real);
                        } catch (Throwable t) {
                            flog("INIT", "install失败: " + t);
                        }
                    }
                }
            });
            probeInstalled.set(true);
            flog("INIT", "loadClass探针已装");
        } catch (Throwable t) { flog("INIT", "loadClass探针失败: " + t); }


        // ===== 宽撒网探针 =====
        try {
            // a) BaseDexClassLoader.loadClass 探针: 抓真实业务类加载器
            Class<?> bdc = XposedHelpers.findClass("dalvik.system.BaseDexClassLoader", lpp.classLoader);
            XposedBridge.hookAllMethods(bdc, "loadClass", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        Object r = param.result;
                        if (r == null) return;
                        String n = ((Class<?>) r).getName();
                        if (n.equals("com.netease.cloudmusic.utils.xu")
                                || n.equals("com.netease.cloudmusic.utils.wu")
                                || n.equals("com.netease.cloudmusic.network.encrypt.CMEncryptService")
                                || n.equals("com.netease.cloudmusic.crypto.caesar.APICryptor")
                                || n.equals("okhttp3.internal.connection.RealCall")) {
                            String key = String.valueOf(System.identityHashCode(param.thisObject));
                            boolean first;
                            synchronized (doneLoaders) {
                                first = doneLoaders.add("L:" + key);
                            }
                            if (first) {
                                flog("PROBE", "业务类 " + n + " 由 " + param.thisObject.getClass().getName()
                                        + " @0x" + Integer.toHexString(System.identityHashCode(param.thisObject)) + " 加载");
                                installBusinessHooks((ClassLoader) param.thisObject);
                            }
                        }
                    } catch (Throwable t) { }
                }
            });
            flog("INIT", "BaseDexClassLoader探针已装");
        } catch (Throwable t) { flog("INIT", "BaseDexClassLoader探针失败: " + t); }

        try {
            // b) ServiceFacade.put: 看哪个 IEncryptService 实现真正注册
            Class<?> sf = XposedHelpers.findClass("com.netease.cloudmusic.common.ServiceFacade", lpp.classLoader);
            XposedBridge.hookAllMethods(sf, "put", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Object k = param.args.length > 0 ? param.args[0] : null;
                        Object v = param.args.length > 1 ? param.args[1] : null;
                        flog("FACADE_PUT", (k == null ? "?" : ((Class<?>) k).getName()) + " -> "
                                + (v == null ? "null" : v.getClass().getName()));
                    }
                });
            flog("INIT", "ServiceFacade.put探针已装");
        } catch (Throwable t) { flog("INIT", "ServiceFacade探针失败: " + t); }

        try {
            // c) Cipher.doFinal: 所有JCE加解密总闸
            XposedHelpers.findAndHookMethod("javax.crypto.Cipher", lpp.classLoader, "doFinal",
                "[B", new XC_MethodHook() {
                    private int count = 0;
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        count++;
                        if (count > 200) return;
                        byte[] in = param.args.length > 0 && param.args[0] instanceof byte[] ? (byte[]) param.args[0] : null;
                        byte[] out = param.result instanceof byte[] ? (byte[]) param.result : null;
                        StackTraceElement[] st = new Throwable().getStackTrace();
                        StringBuilder chain = new StringBuilder();
                        int picked = 0;
                        for (int i = 0; i < st.length && picked < 8; i++) {
                            String cn = st[i].getClassName();
                            if (cn.startsWith("javax.crypto") || cn.startsWith("com.android.org")
                                    || cn.startsWith("java.") || cn.startsWith("[")
                                    || cn.startsWith("com.rev.cmhook")
                                    || cn.startsWith("org.lsposed")
                                    || cn.startsWith("de.robv")
                                    || cn.startsWith("QOlJwxewM")) continue;
                            if (chain.length() > 0) chain.append(" <- ");
                            chain.append(cn).append(".").append(st[i].getMethodName()).append(":").append(st[i].getLineNumber());
                            picked++;
                        }
                        String desc = (in != null ? ("in=" + in.length + "B") : "in=?")
                                + (out != null ? " out=" + out.length + "B" : "") + " | " + chain;
                        flog("CIPHER", desc);
                    }
                });
            flog("INIT", "Cipher.doFinal探针已装");
        } catch (Throwable t) { flog("INIT", "Cipher探针失败: " + t); }

        try {
            // d) cronet 请求观测
            Class<?> cur = XposedHelpers.findClass("org.chromium.net.impl.CronetUrlRequest", lpp.classLoader);
            XposedBridge.hookAllConstructors(cur, new XC_MethodHook() {
                private int count = 0;
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    count++;
                    if (count > 30) return;
                    StringBuilder sb = new StringBuilder();
                    for (Object o : param.args) { String s = "" + o; if (s.length() > 120) s = s.substring(0, 120) + ".."; sb.append(s).append(" | "); }
                    flog("CRONET_NEW", sb.toString());
                }
            });
            flog("INIT", "cronet探针已装");
        } catch (Throwable t) { flog("INIT", "cronet探针失败: " + t); }

        try {
            // e) Activity.onCreate 存活校验
            XposedHelpers.findAndHookMethod("android.app.Activity", lpp.classLoader, "onCreate",
                "android.os.Bundle", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        flog("SANITY", "Activity.onCreate: " + param.thisObject.getClass().getName());
                        try {
                            if (hudCtx == null) hudCtx = (android.content.Context) param.thisObject;
                            loadPrefs((android.content.Context) param.thisObject);
                            ClassLoader actCl = param.thisObject.getClass().getClassLoader();
                            String k = "act:" + System.identityHashCode(actCl);
                            boolean first;
                            synchronized (doneLoaders) { first = doneLoaders.add(k); }
                            if (first) {
                                flog("INIT", "从Activity拿到运行时加载器 @0x" + Integer.toHexString(System.identityHashCode(actCl))
                                        + " (" + actCl.getClass().getName() + ")");
                                installBusinessHooks(actCl);
                            }
                        } catch (Throwable t) { flog("INIT", "actLoader失败: " + t); }
                    }
                });
            flog("INIT", "Activity探针已装");
        } catch (Throwable t) { flog("INIT", "Activity探针失败: " + t); }

        try {
            // 设置页模块入口: 设置页(RN容器)onResume时浮现chip, onDestroy移除
            XposedHelpers.findAndHookMethod("android.app.Activity", lpp.classLoader, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        cmForeground = true;
                        try { flog("INIT", "onResume: " + param.thisObject.getClass().getName()); } catch (Throwable t0) { }
                        applyHud();
                        if (param.thisObject.getClass().getName().startsWith("com.netease.cloudmusic")) {
                            // v6.4: 三个调用各自 try/catch 隔离(此前 applyTopTabs 抛异常会吞掉后面两个)
                            try { applyTopTabs((android.app.Activity) param.thisObject); } catch (Throwable t1) { }
                            try { startFansHideWatcher((android.app.Activity) param.thisObject); } catch (Throwable t2) { }
                            try { rvDiffAndShow((android.app.Activity) param.thisObject); } catch (Throwable t3) { }
                            try { startRvWatcher(); } catch (Throwable t4) { }   // v5.0: 撤回横幅   // v31: 关注页乐迷团隐藏(15s 观察窗口)
                            // v69: 旧快照法停用(会误报页面文案); 真链路(拦截撤回命令)已成主力
                        }
                    } catch (Throwable t) { }
                    try {
                        String cn = param.thisObject.getClass().getName();
                        if (cn.contains("RNActivity")) {
                            android.app.Activity ra = (android.app.Activity) param.thisObject;
                            // 探针: 记录各 RN 页面的 Intent/标题特征(诊断用, 常驻)
                            try {
                                android.content.Intent it = ra.getIntent();
                                StringBuilder sb = new StringBuilder("RNResume: cls=" + cn);
                                sb.append(" title=").append(ra.getTitle());
                                if (it != null) {
                                    sb.append(" data=").append(it.getData());
                                    android.os.Bundle ex = it.getExtras();
                                    if (ex != null) {
                                        for (String k : ex.keySet()) {
                                            Object v = ex.get(k);
                                            String s = String.valueOf(v);
                                            sb.append(" | ").append(k).append("=").append(s.length() > 100 ? s.substring(0, 100) + ".." : s);
                                        }
                                    }
                                }
                                flog("INIT", sb.toString());
                            } catch (Throwable tt) { }
                            // 入口芯片仅在设置页显示: 按 RN 模块名过滤(设置页 = rn-setting@...)
                            boolean isSetting = false;
                            String mod = null;
                            try {
                                android.content.Intent it2 = ra.getIntent();
                                android.os.Bundle ex2 = it2 != null ? it2.getExtras() : null;
                                if (ex2 != null) mod = ex2.getString("extra_module_name");
                            } catch (Throwable tt) { }
                            // v23 修 bug: 原 contains("setting") 会把「播放页底部功能自定义」(rn-play-setting@…)
                            //   等页面也判成设置页 → 徽章乱入。改为精确锚定设置页自身, 并显式排除 play*
                            String ml = (mod == null) ? "" : mod.toLowerCase();
                            boolean isSettingPage = (ml.equals("rn-setting") || ml.startsWith("rn-setting@") || ml.startsWith("rn-setting/"))
                                    && ml.indexOf("play") < 0;
                            if (isSettingPage) isSetting = true;
                            if (mod != null) flog("CHIP", "RN页面=" + mod + " → 徽章=" + (isSettingPage ? "显示" : "隐藏"));
                            Integer key = Integer.valueOf(System.identityHashCode(ra));
                            if (isSetting) {
                                attachEntryChip(ra);
                            } else {
                                android.view.View stale = entryChips.remove(key);
                                if (stale != null) {   // 同实例复用换页时移除残留芯片
                                    try {
                                        android.view.WindowManager wm = (android.view.WindowManager) ra.getSystemService(android.content.Context.WINDOW_SERVICE);
                                        wm.removeView(stale);
                                    } catch (Throwable tt) { }
                                }
                            }
                        }
                    } catch (Throwable t) { }
                }
            });
            XposedHelpers.findAndHookMethod("android.app.Activity", lpp.classLoader, "onDestroy", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        Integer key = Integer.valueOf(System.identityHashCode(param.thisObject));
                        android.view.View chip = entryChips.remove(key);
                        if (chip != null) {
                            android.view.WindowManager wm = (android.view.WindowManager) ((android.app.Activity) param.thisObject).getWindowManager();
                            wm.removeView(chip);
                        }
                    } catch (Throwable t) { }
                }
            });
            flog("INIT", "设置页入口hook已装");
        } catch (Throwable t) { flog("INIT", "设置页入口失败: " + t); }

        try {
            // v7.1: 撤回浮层 —— 返回键关闭(开着时吞掉 BACK, 不退出聊天页)
            XposedHelpers.findAndHookMethod("android.app.Activity", lpp.classLoader, "dispatchKeyEvent",
                "android.view.KeyEvent", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            android.view.KeyEvent ev = (android.view.KeyEvent) param.args[0];
                            if (ev == null || ev.getKeyCode() != android.view.KeyEvent.KEYCODE_BACK) return;
                            if (ev.getAction() != android.view.KeyEvent.ACTION_UP) return;
                            if (!(param.thisObject instanceof android.app.Activity)) return;
                            if (rvClosePanel((android.app.Activity) param.thisObject, "返回键")) {
                                java.lang.reflect.Method sr = param.getClass().getMethod("setResult", Object.class);
                                sr.setAccessible(true);
                                sr.invoke(param, new Object[] { Boolean.TRUE });
                            }
                        } catch (Throwable t) { }
                    }
                });
            flog("INIT", "撤回浮层返回键hook已装");
        } catch (Throwable t) { flog("INIT", "撤回浮层返回键hook失败: " + t); }

        try {
            // 前台跟踪: 任意Activity onPause -> 非前台, HUD隐藏
            XposedHelpers.findAndHookMethod("android.app.Activity", lpp.classLoader, "onPause", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    cmForeground = false;
                    applyHud();
                }
            });
            // 配置提前加载: Application.attach 是APP生命周期最早点
            XposedHelpers.findAndHookMethod("android.app.Application", lpp.classLoader, "attach",
                "android.content.Context", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        if (hudCtx == null) hudCtx = (android.content.Context) param.thisObject;
                        loadPrefs((android.content.Context) param.thisObject);
                        applyHud();
                    } catch (Throwable t) { }
                }
            });
            flog("INIT", "前台跟踪+配置早载hook已装");
        } catch (Throwable t) { flog("INIT", "前台跟踪hook失败: " + t); }


        // ===== 深挖: 密钥派生 utils.c / datapackage 打包层 / URL定位 =====
        try {
            Class<?> uc = XposedHelpers.findClass("com.netease.cloudmusic.utils.c", lpp.classLoader);
            XposedBridge.hookAllMethods(uc, "a", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    StringBuilder sb = new StringBuilder();
                    for (Object o : param.args) { String s = "" + o; sb.append(s.length() > 15000 ? s.substring(0, 15000) + ".." : s).append(" | "); }
                    flog("UTILC_A", sb + "ret=" + param.result);
                }
            });
            XposedBridge.hookAllMethods(uc, "b", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    StringBuilder sb = new StringBuilder();
                    for (Object o : param.args) { String s = "" + o; sb.append(s.length() > 15000 ? s.substring(0, 15000) + ".." : s).append(" | "); }
                    flog("UTILC_B", sb + "ret=" + param.result);
                }
            });
            flog("INIT", "utils.c hook已装");
        } catch (Throwable t) { flog("INIT", "utils.c 失败: " + t); }

        try {
            Class<?> dp = XposedHelpers.findClass("com.netease.cloudmusic.network.datapackage.a", lpp.classLoader);
            XposedBridge.hookAllMethods(dp, "P", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    flog("DPPKG_P", "ret=" + param.result);
                }
            });
            XposedBridge.hookAllMethods(dp, "O", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    flog("DPPKG_O", "ret=" + param.result);
                }
            });
            flog("INIT", "datapackage.a P/O hook已装");
        } catch (Throwable t) { flog("INIT", "datapackage 失败: " + t); }

        try {
            Class<?> urlC = XposedHelpers.findClass("java.net.URL", lpp.classLoader);
            XposedBridge.hookAllConstructors(urlC, new XC_MethodHook() {
                private int count = 0;
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    count++;
                    if (count > 60) return;
                    try {
                        Object u = param.thisObject;
                        String s = "" + u;
                        if (s.contains("netease") || s.contains("163.com")) {
                            StackTraceElement[] st = new Throwable().getStackTrace();
                            StringBuilder chain = new StringBuilder();
                            int picked = 0;
                            for (int i = 0; i < st.length && picked < 6; i++) {
                                String cn = st[i].getClassName();
                                if (cn.startsWith("java.") || cn.startsWith("[") || cn.startsWith("com.rev.cmhook")
                                        || cn.startsWith("QOlJwxewM") || cn.startsWith("org.lsposed") || cn.startsWith("de.robv")) continue;
                                if (chain.length() > 0) chain.append(" <- ");
                                chain.append(cn).append(".").append(st[i].getMethodName()).append(":").append(st[i].getLineNumber());
                                picked++;
                            }
                            flog("URL_NEW", s + " | " + chain);
                        }
                    } catch (Throwable t) { }
                }
            });
            flog("INIT", "URL探针已装");
        } catch (Throwable t) { flog("INIT", "URL探针失败: " + t); }

        // 同时尝试默认类加载器 (无补丁时直接命中)
        try {
            installBusinessHooks(lpp.classLoader);
        } catch (Throwable t) {
            flog("INIT", "默认加载器install: " + t);
        }
        flog("INIT", "handleLoadPackage完毕");
    }

    private static void installBusinessHooks(final ClassLoader cl) {
        flog("INIT", "== INSTALL_ENTER ==");
        businessCl = cl;   // v22: 供 UI「重建 DexKit 缓存」使用
        String key = "default:" + String.valueOf(System.identityHashCode(cl));
        synchronized (doneLoaders) {
            if (doneLoaders.contains(key)) return;
            doneLoaders.add(key);
        }

        // ===== 频道源头过滤: 必须赶在首页频道数据加载前装好 =====
        installFineDataFilter(cl);
        installPagerProbe(cl);

        // ===== 方向2-1: xu.a 全局 xorDecode =====
        // 9.5.70 为 utils.wu.a(String), 9.5.96 混淆类名漂移为 utils.xu (实现不变: Base64→NeteaseMusicUtils.p→native)
        XC_MethodHook xorDecodeHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                flog("XOR_DECODE", param.args[0] + "  ==>  " + param.result);
            }
        };
        boolean xorHooked = false;
        for (String xorCls : new String[]{"com.netease.cloudmusic.utils.xu", "com.netease.cloudmusic.utils.wu"}) {
            try {
                XposedHelpers.findAndHookMethod(xorCls, cl, "a", "java.lang.String", xorDecodeHook);
                flog("INIT", "hooked " + xorCls + ".a(String) @" + Integer.toHexString(System.identityHashCode(cl)));
                xorHooked = true;
                break;
            } catch (Throwable t) { }
        }
        if (!xorHooked && !prefProtoCollect) {
            flog("INIT", "xorDecode: 协议采集关闭, 跳过 DexKit 锚点搜索");
        }
        if (!xorHooked && prefProtoCollect) {
            // DexKit 兜底: 解码器唯一行为锚点 = 调用 NeteaseMusicUtils.p([BI)[B, 按 invoke 反查
            // v21: 结果级缓存(键 xor:p) —— 命中即跳过 dex 扫描
            final String ck = "xor:p";
            String cached = dexCacheGet(ck);
            try {
                java.util.ArrayList<String> hits = new java.util.ArrayList<String>();
                if (cached != null) {
                    flog("DEXKIT", "结果级缓存命中 " + ck + " → " + cached.replace("\n", " | "));
                    for (String s : splitCache(cached)) hits.add(s);
                } else {
                    org.luckypray.dexkit.DexKitCacheBridge.RecyclableBridge rb = dexBridge(cl);
                    java.util.List<org.luckypray.dexkit.wrap.DexMethod> list = rb.getMethods(
                        new org.luckypray.dexkit.DexKitCacheBridge.RecyclableBridge.FindMethodBuilder() {
                            public void build(org.luckypray.dexkit.query.FindMethod fm) {
                                fm.matcher(new org.luckypray.dexkit.query.matchers.MethodMatcher()
                                    .addInvoke("Lcom/netease/cloudmusic/utils/NeteaseMusicUtils;->p([BI)[B"));
                            }
                        });
                    if (list != null) {
                        for (org.luckypray.dexkit.wrap.DexMethod md : list) {
                            String cn2 = md.getClassName();
                            if (cn2 == null || !cn2.startsWith("com.netease.cloudmusic.utils.")) continue;
                            java.util.List<String> ps = md.getParamTypeNames();
                            if (ps == null || ps.size() != 1 || !"java.lang.String".equals(ps.get(0))) continue;
                            hits.add(cn2 + "#" + md.getName() + "(java.lang.String)");
                        }
                    }
                    dexCachePut(ck, joinCache(hits));
                    flog("DEXKIT", "查询并落缓存 " + ck + " → " + hits.size() + " 处");
                }
                int n2 = 0;
                for (String h : hits) {
                    String[] cm = splitHit(h);
                    if (cm[0].length() == 0) continue;
                    try {
                        XposedHelpers.findAndHookMethod(cm[0], cl, cm[1], "java.lang.String", xorDecodeHook);
                        n2++;
                        flog("INIT", "xorDecode DexKit兜底: hooked " + cm[0] + "#" + cm[1]);
                    } catch (Throwable t2) { }
                }
                if (n2 == 0) { flog("DEXKIT", "xorDecode 兜底 0 命中 → 丢缓存, 下轮重查"); dexCacheDrop("xor:p"); }
                flog("INIT", "xorDecode DexKit兜底: " + n2 + "处");
            } catch (Throwable t) { flog("INIT", "xorDecode DexKit兜底失败: " + t); }
        }

        // ===== 方向2-2: i42.f 密钥常量 =====
        // 9.5.70 时为 n62.f, 9.5.96 混淆包名漂移为 i42 (结构不变: f/g/h/i, 密文未轮换)
        // 类名再漂移时: 搜 "ENCODE_SIGN_KEY" 字符串锚点定位新包名
        Class<?> nf = null;
        String[] keyClassNames = {"i42.f", "n62.f"};
        for (String kcn : keyClassNames) {
            try { nf = XposedHelpers.findClass(kcn, cl); flog("INIT", "hooked " + kcn + ".g/h"); break; } catch (Throwable t) { }
        }
        if (nf != null) {
            XposedBridge.hookAllMethods(nf, "g", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    flog("ENCODE_SIGN_KEY", "" + param.result);
                }
            });
            XposedBridge.hookAllMethods(nf, "h", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    flog("ENCODE_STATIC_KEY", "" + param.result);
                }
            });
        } else if (!prefProtoCollect) {
            flog("INIT", "密钥: 协议采集关闭, 跳过 DexKit 锚点搜索");
        } else {
            // DexKit 兜底: 类名漂移时按 Intrinsics 标注串反查密钥方法(锚点唯一且随版本稳定)
            try {
                String[] hits = dexkitFindMethodsByString(cl, "xorDecode(ENCODE_SIGN_KEY)");
                String[] hits2 = dexkitFindMethodsByString(cl, "xorDecode(ENCODE_STATIC_KEY)");
                for (String h : hits) {
                    int hash = h.indexOf('#');
                    Class<?> kc = cl.loadClass(h.substring(0, hash));
                    String mn = h.substring(hash + 1, h.indexOf('(', hash));
                    XposedBridge.hookAllMethods(kc, mn, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            flog("ENCODE_SIGN_KEY", "" + param.result);
                        }
                    });
                    flog("INIT", "DexKit兜底: hooked ENCODE_SIGN_KEY @ " + h);
                }
                for (String h : hits2) {
                    int hash = h.indexOf('#');
                    Class<?> kc = cl.loadClass(h.substring(0, hash));
                    String mn = h.substring(hash + 1, h.indexOf('(', hash));
                    XposedBridge.hookAllMethods(kc, mn, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            flog("ENCODE_STATIC_KEY", "" + param.result);
                        }
                    });
                    flog("INIT", "DexKit兜底: hooked ENCODE_STATIC_KEY @ " + h);
                }
                if (hits.length == 0 && hits2.length == 0) {
                    flog("DEXKIT", "密钥兜底 0 命中 → 丢缓存, 下轮重查");
                    dexCacheDrop("m:xorDecode(ENCODE_SIGN_KEY)");
                    dexCacheDrop("m:xorDecode(ENCODE_STATIC_KEY)");
                }
            } catch (Throwable t) { flog("INIT", "密钥 DexKit兜底失败: " + t); }
        }

        // ===== 方向2-3 + 方向1: CMEncryptService =====
        try {
            Class<?> ces = XposedHelpers.findClass("com.netease.cloudmusic.network.encrypt.CMEncryptService", cl);
            XposedBridge.hookAllMethods(ces, "setSession", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Object a0 = param.args.length > 0 ? param.args[0] : null;
                    Object a1 = param.args.length > 1 ? param.args[1] : null;
                    flog("SESSION", "id=" + a0 + " key=" + a1);
                }
            });
            XposedBridge.hookAllMethods(ces, "updatePublicKey", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    flog("UPDATE_PUBKEY", "arg=" + (param.args.length > 0 ? param.args[0] : "?") + " ret=" + param.result);
                }
            });
            XposedBridge.hookAllMethods(ces, "encrypt", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    flog("CMENC_IN", "" + param.args[0]);
                    flog("CMENC_OUT", "" + param.result);
                }
            });
            flog("INIT", "hooked CMEncryptService");
        } catch (Throwable t) { flog("INIT", "CMEncryptService 失败: " + t); }

        // ===== 方向1: caesar APICryptor =====
        try {
            Class<?> api = XposedHelpers.findClass("com.netease.cloudmusic.crypto.caesar.APICryptor", cl);
            XposedBridge.hookAllMethods(api, "encrypt", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args.length > 0 && param.args[0] instanceof byte[]) {
                        flog("CAESAR_ENC_IN", bytesPreview((byte[]) param.args[0], 1500));
                    }
                    flog("CAESAR_ENC_OUT", "" + param.result);
                }
            });
            XposedBridge.hookAllMethods(api, "decrypt", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    flog("CAESAR_DEC_IN", "" + (param.args.length > 0 ? param.args[0] : "?"));
                    if (param.result instanceof byte[]) {
                        flog("CAESAR_DEC_OUT", trunc(new String((byte[]) param.result, "UTF-8")));
                    } else {
                        flog("CAESAR_DEC_OUT", "" + param.result);
                    }
                }
            });
            flog("INIT", "hooked APICryptor");
        } catch (Throwable t) { flog("INIT", "APICryptor 失败: " + t); }

        // ===== 方向1: NeteaseMusicUtils serial* =====
        try {
            Class<?> nmu = XposedHelpers.findClass("com.netease.cloudmusic.utils.NeteaseMusicUtils", cl);
            XposedBridge.hookAllMethods(nmu, "serialdata", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    StringBuilder args = new StringBuilder();
                    for (Object o : param.args) args.append(o).append(",");
                    flog("NMU_SERIALDATA", "args=" + args + " ret=" + param.result);
                }
            });
            XposedBridge.hookAllMethods(nmu, "serialurl", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    flog("NMU_SERIALURL", "" + (param.args.length > 0 ? param.args[0] : "?") + "  ==>  " + param.result);
                }
            });
            flog("INIT", "hooked NeteaseMusicUtils serial*");
        } catch (Throwable t) { flog("INIT", "NeteaseMusicUtils 失败: " + t); }

        // ===== 项目1: 去开屏广告 =====
        // ① 渠道闸门伪装(v8.4): jk.a.V() = b2.g("google") = 官方"GP渠道无广告"总闸(9.5.70=km.a)。
        //    强制 true 后 App 走 nogetad/needFilterAd 分支, 开屏广告请求根本不发, 预取同样被掐,
        //    冷启广告等待 2000ms→300ms。全 App 6 处调用点均在广告链路, 无其它副作用。
        //    定位链: 硬编码 jk.a(形状校验) → DexKit 按类体特征串 "Session.Account" 反查会话管理器类。
        //    注: VIP 客户端标志 E()=isBlackVip 只控黑胶启动图不控广告; 服务端按真实账号 vipType
        //    决定下发(SVIP 照样下发, 实证), 伪装 VIP 无效 —— 渠道闸门才是客户端唯一总闸。
        try {
            Class<?> gateCls = null;
            try {
                Class<?> c1 = XposedHelpers.findClass("jk.a", cl);
                if (adGateShape(c1)) gateCls = c1;
            } catch (Throwable t1) { }
            if (gateCls == null) {
                try {
                    for (String h : dexkitFindMethodsByString(cl, "Session.Account")) {
                        int ph = h.indexOf('#');
                        if (ph <= 0) continue;
                        try {
                            Class<?> c2 = XposedHelpers.findClass(h.substring(0, ph), cl);
                            if (adGateShape(c2)) { gateCls = c2; break; }
                        } catch (Throwable t3) { }
                    }
                } catch (Throwable t2) { }
            }
            if (gateCls != null) {
                final Class<?> gateClsF = gateCls;
                XposedBridge.hookAllMethods(gateCls, "V", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args != null && param.args.length > 0) return;   // 只动无参 boolean V()
                        if (!prefAdBlock) return;
                        flog("ADBLOCK", "渠道无广告闸门 " + gateClsF.getName() + ".V() -> true (App跳过广告请求)");
                        param.result = Boolean.TRUE;
                    }
                });
                flog("INIT", "hooked " + gateCls.getName() + ".V (渠道无广告闸门伪装)");
            } else {
                flog("INIT", "渠道无广告闸门未定位(jk.a 失配且 DexKit 未命中), 网络层仍兜底");
            }
        } catch (Throwable t) { flog("INIT", "渠道闸门伪装失败: " + t); }

        try {
            Class<?> lam = XposedHelpers.findClass("com.netease.cloudmusic.module.ad.LoadingAdManager", cl);
            Class<?> adInfoCls = XposedHelpers.findClass("com.netease.cloudmusic.module.ad.meta.AdInfo", cl);
            int hitAd = 0;
            for (java.lang.reflect.Method mm2 : lam.getDeclaredMethods()) {
                if (mm2.getParameterTypes().length == 1 && mm2.getParameterTypes()[0] == adInfoCls
                    && mm2.getReturnType() == boolean.class) {
                    final String mn2 = mm2.getName();
                    XposedBridge.hookAllMethods(lam, mn2, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (!prefAdBlock) return;
                            flog("ADBLOCK", "已拦截开屏广告判断 " + mn2 + "(AdInfo) -> 强制不展示");
                            param.result = Boolean.FALSE;
                        }
                    });
                    hitAd++;
                    flog("INIT", "hooked LoadingAdManager." + mn2 + " (开屏广告开关, 签名扫描)");
                }
            }
            if (hitAd == 0) flog("INIT", "LoadingAdManager 的 (AdInfo)boolean 方法未找到, 开屏拦截缺位");
        } catch (Throwable t) { flog("INIT", "LoadingAdManager 失败: " + t); }

        try {
            Class<?> laa = XposedHelpers.findClass("com.netease.cloudmusic.activity.LoadingAdActivity", cl);
            XposedBridge.hookAllMethods(laa, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    flog("ADBLOCK", "LoadingAdActivity 已创建 -> 立即finish(兜底)");
                    try {
                        ((android.app.Activity) param.thisObject).finish();
                    } catch (Throwable t2) { }
                }
            });
            flog("INIT", "hooked LoadingAdActivity.onCreate (兜底秒退)");
        } catch (Throwable t) { flog("INIT", "LoadingAdActivity 失败: " + t); }
        flog("INIT", "开屏广告三层拦截=①渠道闸门V()伪装(不请求) ②网络层(ad/loading/* → ads:[]) ③E()/LoadingAdActivity兜底");

        // ===== 方向2-4: URS dat 私钥库 =====
        try {
            Class<?> dm = XposedHelpers.findClass("com.netease.android.dat.library.DatManager", cl);
            XposedBridge.hookAllMethods(dm, "decryptDataFromDat", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    flog("URS_DAT", "ret=" + trunc("" + param.result));
                }
            });
            flog("INIT", "hooked DatManager");
        } catch (Throwable t) { flog("INIT", "DatManager 失败: " + t); }

        // ===== 方向1: okhttp 请求/响应 =====
        try {
            Class<?> realCall = XposedHelpers.findClass("okhttp3.internal.connection.RealCall", cl);
            XposedBridge.hookAllMethods(realCall, "getResponseWithInterceptorChain$okhttp", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        Object req = fieldGet(param.thisObject, "originalRequest");
                        String url = callStr(req, "url");
                        if (url == null || !(url.contains("netease") || url.contains("163.com"))) return;
                        String method = callStr(req, "method");
                        Object headers = invoke0(req, "headers");
                        String hdrs = headers != null ? callStr(headers, "toString") : "?";
                        flog("HTTP_REQ", method + " " + url + "  HDRS: " + trunc(hdrs.replace("\n", " | ")));
                        Object body = invoke0(req, "body");
                        if (body != null) {
                            Object buf = cl.loadClass("okio.Buffer").getDeclaredConstructor().newInstance();
                            invoke1(body, "writeTo", buf);
                            flog("HTTP_REQ_BODY", trunc(callStr(buf, "readUtf8")));
                        }
                    } catch (Throwable t) { flog("HTTP_REQ", "<err " + t + ">"); }
                    try {
                        Object resp = param.result;
                        if (resp == null) return;
                        Object req2 = fieldGet(resp, "request");
                        String url2 = req2 != null ? callStr(req2, "url") : null;
                        if (url2 == null || !(url2.contains("netease") || url2.contains("163.com"))) return;
                        Integer code = callInt(resp, "code");
                        boolean homeFeed = isHomeFeedUrl(url2);
                        Object pb = invoke1(resp, "peekBody", homeFeed ? 8388608L : 2097152L);
                        String text = pb != null ? callStr(pb, "string") : null;
                        flog("HTTP_RESP", "url=" + url2 + " code=" + code);
                        if (homeFeed && text != null && text.length() > 100 && text.length() < 8000000
                                && text.charAt(text.length() - 1) == '}') {
                            try {
                                dumpFeed(url2, text);
                                if (prefHomeClean) {
                                    String filtered = filterHomeFeed(text);
                                    if (filtered != null) {
                                        dumpFeedFiltered(filtered, feedDumpCount);
                                        Object nr = rebuildResponse(resp, filtered, cl);
                                        if (nr != null) setParamResult(param, nr);
                                    } else {
                                        flog("HOME_CLEAN", "未命中锚点(规则=[" + prefHomeCleanAnchor + "])");
                                    }
                                }
                            } catch (Throwable th) { flog("HOME_CLEAN", "<err " + th + ">"); }
                        }
                        if (text == null) text = "?";
                        flog("HTTP_RESP_BODY", text.length() > 200000 ? text.substring(0, 200000) + "...<len=" + text.length() + ">" : text);
                        // v20: 我的-播客-为你推荐 (xeapi/my/podcast/tab/recommend) → data[] 清空
                        if (url2 != null && url2.indexOf(PODCAST_REC_URL_KEY) >= 0 && prefPodcastClean) {
                            try {
                                String out = emptyJsonArrayForKey(text, "data");
                                if (out != null) {
                                    flog("PODCAST_CLEAN", "为你推荐已清空: " + text.length() + " → " + out.length() + " 字节");
                                    Object nr2 = rebuildResponse(resp, out, cl);
                                    if (nr2 != null) setParamResult(param, nr2);
                                } else {
                                    flog("PODCAST_CLEAN", "未找到 data 数组(响应结构变化?)");
                                }
                            } catch (Throwable t4) { flog("PODCAST_CLEAN", "<err " + t4 + ">"); }
                        }
                        // v8.2: 开屏广告网络层拦截 — 9.5.96 开屏广告走 /eapi|xeapi/ad/loading/get(展示)
                        // 与 /ad/loading/bidget(预取) 两个端点, 且素材预取完成时 LoadingAdManager.E
                        // 根本不被调用(老 hook 零命中)。把顶层 ads[] 清成 [] = 服务端自己的"无广告"
                        // 合法形态, App 直接跳主页面。前缀匹配 ad/loading/ 同时覆盖两个端点。
                        if (prefAdBlock && url2 != null && url2.indexOf("ad/loading/") >= 0) {
                            try {
                                if (text != null && text.length() > 0) {
                                    String out3 = emptyJsonArrayForKey(text, "ads");
                                    if (out3 != null && out3.length() != text.length()) {
                                        flog("ADBLOCK", "开屏广告响应已清空: " + text.length() + " → " + out3.length() + " 字节");
                                        Object nr3 = rebuildResponse(resp, out3, cl);
                                        if (nr3 != null) setParamResult(param, nr3);
                                    } else if (out3 != null) {
                                        flog("ADBLOCK", "loading-ad ads 已为空(服务端未下发, len=" + text.length() + ")");
                                    } else {
                                        flog("ADBLOCK", "loading-ad 响应无 ads 数组(len=" + text.length() + ", 结构变化?)");
                                    }
                                }
                            } catch (Throwable t5) { flog("ADBLOCK", "<bidget err " + t5 + ">"); }
                        }
                    } catch (Throwable t) { flog("HTTP_RESP", "<err " + t + ">"); }
                }
            });
            flog("INIT", "hooked okhttp RealCall");
        } catch (Throwable t) { flog("INIT", "okhttp 失败: " + t); }

        try {
            Class<?> realCall2 = XposedHelpers.findClass("okhttp3.internal.connection.RealCall", cl);
            XposedBridge.hookAllConstructors(realCall2, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        Object req = param.args.length > 1 ? param.args[1] : null;
                        if (req == null) return;
                        String url = callStr(req, "url");
                        if (url != null && (url.contains("netease") || url.contains("163.com"))) {
                            flog("OKHTTP_NEW", callStr(req, "method") + " " + url);
                        }
                    } catch (Throwable t) { }
                }
            });
            flog("INIT", "RealCall构造器hook已装");
        } catch (Throwable t) { flog("INIT", "RealCall构造器失败: " + t); }

        try {
            Class<?> urb = XposedHelpers.findClass("org.chromium.net.impl.UrlRequestBuilderImpl", cl);
            XposedBridge.hookAllMethods(urb, "addHeader", new XC_MethodHook() {
                private int count = 0;
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    count++;
                    if (count > 400) return;
                    Object k = param.args.length > 0 ? param.args[0] : null;
                    Object v = param.args.length > 1 ? param.args[1] : null;
                    String vs = "" + v;
                    flog("CRONET_HDR", k + ": " + (vs.length() > 400 ? vs.substring(0, 400) + ".." : vs));
                }
            });
            flog("INIT", "cronet addHeader hook已装");
        } catch (Throwable t) { flog("INIT", "cronet builder失败: " + t); }

        try {
            Class<?> cur = XposedHelpers.findClass("org.chromium.net.impl.CronetUrlRequest", cl);
            XposedBridge.hookAllConstructors(cur, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    StringBuilder sb = new StringBuilder();
                    for (Object o : param.args) { String s = "" + o; if (s.length() > 100) s = s.substring(0, 100) + ".."; sb.append(s).append(" | "); }
                    flog("CRONET_REQ", sb.toString());
                }
            });
            flog("INIT", "CronetUrlRequest hook已装");
        } catch (Throwable t) { flog("INIT", "CronetUrlRequest失败: " + t); }

        flog("INIT", "== BEFORE_V6 ==");
        // ===== v6.0: 撤回记录(库/列表差集) =====
        try {
            Class<?> pAd = XposedHelpers.findClass("androidx.paging.PagedListAdapter", cl);
            XposedBridge.hookAllMethods(pAd, "getItemCount", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    try { if (param.thisObject != null) rvAdapter = param.thisObject; } catch (Throwable t) { }
                }
            });
            // 撤回命令被吞掉时也做一次差集(即时)
            Class<?> mACls = XposedHelpers.findClass("com.netease.cloudmusic.messagecenter.detail.MessageCenterDetailFragment$m$a", cl);
            XposedBridge.hookAllMethods(mACls, "e", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (!prefAntiRevoke) return;
                    try {
                        Object raw = param.args.length > 0 ? param.args[0] : null;
                        if (raw == null) return;
                        Object mb = rvCall(raw, "getMsgBody");
                        Object body = rvCall(mb, "getBody");
                        if (body == null) return;
                        if (!body.getClass().getName().equals("com.netease.cloudmusic.music.biz.chat.meta.SystemCommandMsg")) return;
                        Object st = rvCall(body, "getSubType");
                        int sub = (st == null) ? -1 : Integer.parseInt(String.valueOf(st).trim());
                        if (sub != 4) return;
                        flog("ANTIREVOKE", "撤回命令到达, 做差集比对");
                        try {
                            java.lang.reflect.Method sr = param.getClass().getMethod("setResult", Object.class);
                            sr.setAccessible(true);
                            sr.invoke(param, new Object[] { null });
                        } catch (Throwable t) { }
                    } catch (Throwable t) { }
                }
            });
            flog("INIT", "撤回记录 hook 已装(adapter 抓取 + 命令拦截)");
            flog("INIT", "== AFTER_V6 ==");
        } catch (Throwable t) { flog("INIT", "撤回记录 hook 失败: " + t); }

        // ===== v7.9: 首页 feed 缓存"读"时过滤(APP 从 MMKV 取旧副本渲染的那条路) =====
        try {
            Class<?> mmkvCls = XposedHelpers.findClass("com.tencent.mmkv.MMKV", cl);
            XposedBridge.hookAllMethods(mmkvCls, "decodeString", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        if (!prefHomeClean) return;
                        Object r = null;   // stub 里没有 getResult() → 纯反射拿返回值
                        try {
                            java.lang.reflect.Method gm = param.getClass().getMethod("getResult");
                            gm.setAccessible(true);
                            r = gm.invoke(param);
                        } catch (Throwable t) { return; }
                        if (!(r instanceof String)) return;
                        String s = (String) r;
                        if (s.length() < 2048) return;
                        if (s.indexOf("\"positionCode\"") < 0 || s.indexOf("\"blocks\"") < 0) return;
                        String out = filterHomeFeed(s);
                        if (out != null && out.length() != s.length()) {
                            setParamResult(param, out);
                            flog("HOME_CLEAN", "MMKV 读取时过滤: " + s.length() + " → " + out.length() + " 字节");
                        }
                    } catch (Throwable t) { }
                }
            });
            flog("INIT", "MMKV decodeString hook已装(缓存副本读取时过滤)");
        } catch (Throwable t) { flog("INIT", "MMKV hook失败: " + t); }

        // ===== 乐迷团(事件驱动) =====
        installFansHideEventHook(cl);

        // ===== 抽屉VIP挽回卡(侦察探针, v8.6) =====
        try {
            Class<?> tvCls = XposedHelpers.findClass("android.widget.TextView", cl);
            XposedBridge.hookAllMethods(tvCls, "setText", new XC_MethodHook() {
                private long lastLog = 0;
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        if (param.args == null || param.args.length == 0) return;
                        Object a0 = param.args[0];
                        String t = a0 instanceof CharSequence ? a0.toString() : null;
                        if (t == null || t.length() == 0 || t.length() > 40) return;
                        boolean hit = t.contains("期待您的回归") || t.contains("特权已失效") || t.contains("续费立享")
                                || t.contains("会员特权") || t.contains("每日打卡") || t.contains("学生特惠")
                                || t.contains("优惠开通") || t.contains("立享优惠");
                        if (!hit) return;
                        long now = System.currentTimeMillis();
                        if (now - lastLog < 2000) return;
                        lastLog = now;
                        StringBuilder sb = new StringBuilder("命中[" + t + "] 链:");
                        android.view.View v = (android.view.View) param.thisObject;
                        for (int i = 0; i < 10 && v != null; i++) {
                            sb.append(" <").append(v.getClass().getSimpleName())
                              .append(' ').append(v.getWidth()).append('x').append(v.getHeight());
                            Object tag = v.getTag();
                            if (tag != null) sb.append(" tag=").append(tag);
                            sb.append('>');
                            android.view.ViewParent vp = v.getParent();
                            v = vp instanceof android.view.View ? (android.view.View) vp : null;
                        }
                        flog("CARDCUSTOM", sb.toString());
                    } catch (Throwable t) { }
                }
            });
            flog("INIT", "hooked TextView.setText (抽屉VIP卡侦察探针)");
        } catch (Throwable t) { flog("INIT", "抽屉VIP卡探针失败: " + t); }

        // ===== 抽屉VIP卡: 相册选图结果回传(v8.7) =====
        // 子类普遍覆盖 onActivityResult 不调 super, hook 基类收不到; dispatchActivityResult 是
        // onActivityResult 的唯一上游分发点(基类实现, 子类不覆盖), 在这里一定能看到所有回传
        try {
            XposedBridge.hookAllMethods(android.app.Activity.class, "dispatchActivityResult", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        if (param.args == null || param.args.length < 4) return;
                        Object req = param.args[1], res = param.args[2], data = param.args[3];
                        if (!(req instanceof Integer) || ((Integer) req) != CARD_PICK_REQ) return;
                        if (!(res instanceof Integer) || ((Integer) res) != -1) return;   // RESULT_OK
                        if (!(data instanceof android.content.Intent)) return;
                        final android.net.Uri uri = ((android.content.Intent) data).getData();
                        if (uri == null) return;
                        if (!(param.thisObject instanceof android.app.Activity)) return;
                        cardSaveFromUri((android.app.Activity) param.thisObject, uri);
                    } catch (Throwable t) { }
                }
            });
            flog("INIT", "hooked dispatchActivityResult (卡片选图回传)");
        } catch (Throwable t) { flog("INIT", "选图回传hook失败: " + t); }

        // ===== v57: 探针3 —— SQLite 层(不依赖类名) =====
        try {
            XposedBridge.hookAllMethods(android.database.sqlite.SQLiteDatabase.class, "delete", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                    try {
                        flog("SQL_PROBE", "DELETE " + p.args[0] + " where=" + p.args[1]
                            + " args=" + (p.args.length > 2 ? String.valueOf(p.args[2]) : ""));
                    } catch (Throwable t) { }
                }
            });
            XposedBridge.hookAllMethods(android.database.sqlite.SQLiteDatabase.class, "update", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                    try {
                        flog("SQL_PROBE", "UPDATE " + p.args[0] + " values=" + p.args[1]
                            + " where=" + p.args[2] + " args=" + (p.args.length > 3 ? String.valueOf(p.args[3]) : ""));
                    } catch (Throwable t) { }
                }
            });
            flog("INIT", "SQL 探针已装(SQLiteDatabase delete/update)");
        } catch (Throwable t) { flog("INIT", "SQL 探针失败: " + t); }

        // 撤回命令处理类 nimlib biz.c.i.m: a(v)=单条推送, b(ab)=批量推送 → 内部 MsgDBHelper.deleteMessage 删本地库再广播
        //   d(w)=自己撤回的服务器响应 → 不拦(保本机撤回); biz.c.i.o=会话列表同步(混正常更新) → 不拦
        // DexKit兜底锚点: 撤回处理方法必调用 MsgDBHelper.saveRevokeMessage(String) —
        //   按 invoke 反查(类名+方法名+参数), 天然排除 d(w)(参数是基类biz.e.a)与ack辅助a(ab)(不调save)

        // v10: DexKit 自检(后台线程, 不阻塞): 用运行时加载器(cl, 含Tinker patch)验证
        //   libdexkit.so 可加载 + 字符串特征查询可命中 patch 层类。快路径正常时不参与过滤,
        //   但通路必须提前验明, 否则下次APP升级混淆改名时才会暴露问题。
        // v22 分档:
        //   L0 健康检查(每次冷启动, 只校验缓存有效性 —— 不搜 dex、不建桥)
        //   L1 缓存构建(域变化/缓存缺失时, 由业务侧兜底或重建入口真搜一次并落缓存)
        //   L2 深度重建(设置面板「重建 DexKit 缓存」手动触发)
        try {
            final ClassLoader clD = cl;
            Thread t = new Thread(new Runnable() {
                public void run() {
                    android.content.Context cc = dexCtxBlocking();
                    flog("DEXKIT", "L0: 宿主Context=" + (cc != null) + " 域=" + dexDomainKey());
                    try {
                        String health = dexHealthCheck(clD);
                        flog("INIT", "DexKit L0健康检查: " + health
                            + (dexRb == null ? " [零搜索: 未建bridge]" : " [bridge 在]"));
                    } catch (Throwable t2) { flog("INIT", "DexKit L0异常: " + t2); }
                    // v8.1: L1 按需构建 —— 新功能锚点(私信库 DAO / 首页块缓存宿主)缓存缺失时真搜一次
                    try {
                        if (dexCacheGet("m:private_chat_message_db") == null) {
                            long t0 = System.currentTimeMillis();
                            String[] hits = dexkitFindMethodsByString(clD, "private_chat_message_db");
                            flog("DEXKIT", "L1 私信DAO锚点: " + hits.length + " 处(" + (System.currentTimeMillis() - t0) + "ms)"
                                + (hits.length > 0 ? (" → " + hits[0]) : " (硬编码快路径已命中, 兜底待用)"));
                        }
                    } catch (Throwable t3) { flog("DEXKIT", "L1 私信DAO锚点失败: " + t3); }
                    // v8.1: 其余三个锚点也预热(缺失才搜, 遵守开关: 协议采集/防撤回 关则不搜)
                    try {
                        if (dexCacheGet("c:RecommendTwoFlowAdapter") == null) {
                            long t0 = System.currentTimeMillis();
                            Class<?> b = resolveTabBaseViaDexkit(clD, "RecommendTwoFlowAdapter");
                            flog("DEXKIT", "L1 频道锚点: " + (b != null ? b.getName() : "未命中")
                                + "(" + (System.currentTimeMillis() - t0) + "ms)");
                        }
                    } catch (Throwable t4) { flog("DEXKIT", "L1 频道锚点失败: " + t4); }
                    try {
                        if (prefAntiRevoke && dexCacheGet("revoke") == null) {
                            long t0 = System.currentTimeMillis();
                            String[] h = dexkitFindRevokeHandlers(clD);
                            flog("DEXKIT", "L1 防撤回锚点: " + h.length + " 处(" + (System.currentTimeMillis() - t0) + "ms)"
                                + (h.length > 0 ? (" → " + h[0]) : ""));
                        }
                    } catch (Throwable t5) { flog("DEXKIT", "L1 防撤回锚点失败: " + t5); }
                    try {
                        if (prefProtoCollect && dexCacheGet("m:xorDecode(ENCODE_SIGN_KEY)") == null) {
                            long t0 = System.currentTimeMillis();
                            String[] h2 = dexkitFindMethodsByString(clD, "xorDecode(ENCODE_SIGN_KEY)");
                            flog("DEXKIT", "L1 密钥锚点: " + h2.length + " 处(" + (System.currentTimeMillis() - t0) + "ms)"
                                + (h2.length > 0 ? (" → " + h2[0]) : ""));
                        }
                    } catch (Throwable t6) { flog("DEXKIT", "L1 密钥锚点失败: " + t6); }
                }
            });
            t.setDaemon(true);
            t.start();
        } catch (Throwable t) { }
        flog("INIT", "installBusinessHooks完毕 @" + Integer.toHexString(System.identityHashCode(cl)));
    }

    private static Object fieldGet(Object obj, String name) throws Exception {
        Class<?> c = obj.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException e) { c = c.getSuperclass(); }
        }
        throw new NoSuchFieldException(name);
    }

    private static Object invoke0(Object obj, String name) throws Exception {
        Class<?> c = obj.getClass();
        while (c != null) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterTypes().length == 0) {
                    m.setAccessible(true);
                    return m.invoke(obj);
                }
            }
            c = c.getSuperclass();
        }
        throw new NoSuchMethodException(name);
    }

    private static Object invoke1(Object obj, String name, Object arg) throws Exception {
        Class<?> c = obj.getClass();
        while (c != null) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterTypes().length == 1) {
                    m.setAccessible(true);
                    return m.invoke(obj, arg);
                }
            }
            c = c.getSuperclass();
        }
        throw new NoSuchMethodException(name);
    }

    private static String callStr(Object obj, String name) {
        try { return "" + invoke0(obj, name); } catch (Throwable t) { return "<" + t.getClass().getSimpleName() + ">"; }
    }

    private static Integer callInt(Object obj, String name) {
        try { return (Integer) invoke0(obj, name); } catch (Throwable t) { return -1; }
    }
}
