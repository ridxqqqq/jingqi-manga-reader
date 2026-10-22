package com.gugu.jingqi;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36";
    private static final String CATALOG_URL = "http://www.gugu5.cc/o/zhongguojingqixiansh/";
    private static final int ORANGE = 0xFFFF6B3D;
    private static final int DARKBG = 0xFF0D0F14;
    private static final Pattern P_MURL =
            Pattern.compile("qTcms_S_m_murl_e\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern P_PAIRS =
            Pattern.compile("history_in\\('((?:\\\\'|[^'])*)'\\s*,\\s*'((?:\\\\'|[^'])*)'\\)");
    private static final Pattern P_PID =
            Pattern.compile("/(\\d+)\\.html");

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences sp;
    private FrameLayout root;
    private ListView catalogList;
    private CatalogAdapter adapter;
    private TextView continueBtn;
    private TextView catalogHint;
    private WebView reader;
    private LinearLayout topBar;
    private TextView titleView;
    private TextView pageBadge;
    private LinearLayout loadBox;
    private TextView loadingText;
    private ProgressBar loadingBar;
    private Runnable hideRunnable;

    // ---- 自动阅读 ----
    private TextView autoBtn;
    private volatile boolean autoReading = false;
    private static final int DEFAULT_CHAPTER_SEC = 60;

    private List<Chapter> chapters = new ArrayList<Chapter>();
    private volatile int curIndex = -1;
    private volatile int pendingRestoreImg = 0; // 页面加载完后要跳回的图序号：0 = 回顶部
    private boolean readerVisible = false;
    private volatile boolean retryOnClick = false;
    private volatile boolean switching = false; // 翻话/加载进行中，防重复触发

    static class Chapter {
        int pid;
        String title;
        String url;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sp = getSharedPreferences("jingqi", MODE_PRIVATE);
        buildUi();
        loadCatalog();
    }

    // ================= UI =================

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(DARKBG);
        setContentView(root);

        // ===== 目录屏 =====
        adapter = new CatalogAdapter();
        catalogList = new ListView(this);
        catalogList.setBackgroundColor(DARKBG);
        catalogList.setDivider(new android.graphics.drawable.ColorDrawable(0xFF171B26));
        catalogList.setDividerHeight(1);
        catalogList.setCacheColorHint(DARKBG);
        catalogList.setAdapter(adapter);
        catalogList.addHeaderView(buildCatalogHeader(), null, false);
        catalogList.setOnItemClickListener((parent, view, position, id) -> {
            int idx = position - catalogList.getHeaderViewsCount();
            if (idx >= 0 && idx < chapters.size()) openChapter(idx);
        });
        // 长按章节：设置该章的自定义阅读时间
        catalogList.setOnItemLongClickListener((parent, view, position, id) -> {
            int idx = position - catalogList.getHeaderViewsCount();
            if (idx >= 0 && idx < chapters.size()) {
                showChapterTimeDialog(idx);
                return true;
            }
            return false;
        });
        root.addView(catalogList, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ===== 阅读屏 =====
        reader = new WebView(this);
        WebSettings ws = reader.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setSupportZoom(false);
        ws.setBuiltInZoomControls(false);
        ws.setDisplayZoomControls(false);
        ws.setUseWideViewPort(true);
        ws.setLoadWithOverviewMode(true);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        ws.setUserAgentString(UA);
        ws.setBlockNetworkImage(false);
        reader.setBackgroundColor(DARKBG);
        reader.setVisibility(View.GONE);
        reader.addJavascriptInterface(new Bridge(), "Android");
        reader.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                restoreProgress();
                // 自动阅读进行中：换章/重试加载完成后用新章节的时间重新计时
                if (autoReading) ui.postDelayed(() -> injectAuto(), 900);
            }
        });
        root.addView(reader, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 顶栏
        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundColor(0xE60D0F14);
        topBar.setVisibility(View.GONE);
        topBar.setPadding(dp(6), dp(6), dp(12), dp(6));

        TextView back = new TextView(this);
        back.setText("‹ 目录");
        back.setTextColor(Color.WHITE);
        back.setTextSize(15);
        back.setTypeface(Typeface.DEFAULT_BOLD);
        back.setPadding(dp(10), dp(8), dp(10), dp(8));
        back.setOnClickListener(v -> showCatalog());
        topBar.addView(back);

        titleView = new TextView(this);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(15);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        titleView.setGravity(Gravity.CENTER);
        titleView.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        topBar.addView(titleView);

        pageBadge = new TextView(this);
        pageBadge.setTextColor(0xFF9AA3B5);
        pageBadge.setTextSize(13);
        pageBadge.setPadding(dp(10), 0, 0, 0);
        topBar.addView(pageBadge);

        // 自动阅读开关：点击 开/停；长按 设置全局默认时长
        autoBtn = new TextView(this);
        autoBtn.setText("自动");
        autoBtn.setTextColor(0xFF9AA3B5);
        autoBtn.setTextSize(13);
        autoBtn.setTypeface(Typeface.DEFAULT_BOLD);
        autoBtn.setPadding(dp(10), 0, 0, 0);
        autoBtn.setOnClickListener(v -> toggleAutoRead());
        autoBtn.setOnLongClickListener(v -> { showDefaultTimeDialog(); return true; });
        topBar.addView(autoBtn);

        root.addView(topBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 加载层（最上层）
        loadBox = new LinearLayout(this);
        loadBox.setOrientation(LinearLayout.VERTICAL);
        loadBox.setGravity(Gravity.CENTER);
        loadBox.setBackgroundColor(0xF50D0F14);
        loadBox.setVisibility(View.GONE);
        loadBox.setOnClickListener(v -> {
            if (retryOnClick && curIndex >= 0) openChapter(curIndex);
        });
        loadingBar = new ProgressBar(this);
        loadBox.addView(loadingBar, new LinearLayout.LayoutParams(dp(44), dp(44)));
        loadingText = new TextView(this);
        loadingText.setTextColor(0xFF8A93A6);
        loadingText.setTextSize(14);
        loadingText.setPadding(dp(24), dp(14), dp(24), 0);
        loadingText.setGravity(Gravity.CENTER);
        loadBox.addView(loadingText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(loadBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private View buildCatalogHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(20), dp(26), dp(20), dp(10));

        TextView logo = new TextView(this);
        logo.setText("惊");
        logo.setTextColor(Color.WHITE);
        logo.setTextSize(28);
        logo.setTypeface(Typeface.DEFAULT_BOLD);
        logo.setGravity(Gravity.CENTER);
        GradientDrawable lg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{0xFF2A2F3E, ORANGE});
        lg.setCornerRadius(dp(16));
        logo.setBackground(lg);
        logo.setLayoutParams(new LinearLayout.LayoutParams(dp(56), dp(56)));
        header.addView(logo);

        TextView title = new TextView(this);
        title.setText("中国惊奇先生（神鬼七杀令）");
        title.setTextColor(Color.WHITE);
        title.setTextSize(21);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(0, dp(16), 0, dp(3));
        header.addView(title);

        TextView sub = new TextView(this);
        sub.setText("作者：权迎升 · 港台 · 玄幻 · 连载中 · 每周一三五更新");
        sub.setTextColor(0xFF8A93A6);
        sub.setTextSize(13);
        sub.setPadding(0, 0, 0, dp(14));
        header.addView(sub);

        continueBtn = new TextView(this);
        continueBtn.setText("加载中…");
        continueBtn.setTextColor(Color.WHITE);
        continueBtn.setTextSize(15);
        continueBtn.setTypeface(Typeface.DEFAULT_BOLD);
        continueBtn.setGravity(Gravity.CENTER);
        GradientDrawable cbg = new GradientDrawable();
        cbg.setCornerRadius(dp(12));
        cbg.setColor(ORANGE);
        continueBtn.setBackground(cbg);
        continueBtn.setPadding(dp(16), dp(13), dp(16), dp(13));
        continueBtn.setOnClickListener(v -> openChapter(lastChapterIndex(), true));
        header.addView(continueBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        catalogHint = new TextView(this);
        catalogHint.setText("  章节目录（旧 → 新）");
        catalogHint.setTextColor(0xFF6B7488);
        catalogHint.setTextSize(12);
        catalogHint.setPadding(0, dp(18), 0, dp(8));
        header.addView(catalogHint);
        return header;
    }

    private void showLoading(boolean show, String text) {
        loadBox.setVisibility(show ? View.VISIBLE : View.GONE);
        if (text != null) loadingText.setText(text);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ================= 目录数据 =================

    private void loadCatalog() {
        showLoading(true, "正在加载目录…");
        new Thread(() -> {
            List<Chapter> local = null;
            try {
                InputStream is = getAssets().open("catalog.json");
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] t = new byte[8192];
                int n;
                while ((n = is.read(t)) > 0) buf.write(t, 0, n);
                is.close();
                local = parseJson(buf.toByteArray());
            } catch (Exception ignored) {
            }
            final List<Chapter> fLocal = local;
            ui.post(() -> {
                if (fLocal != null && !fLocal.isEmpty()) {
                    chapters = fLocal;
                    adapter.notifyDataSetChanged();
                    refreshContinueBtn();
                    showLoading(false, null);
                }
            });
            refreshCatalogLive();
        }).start();
    }

    private void refreshCatalogLive() {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(CATALOG_URL).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", UA);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] t = new byte[8192];
            int n;
            while ((n = in.read(t)) > 0) buf.write(t, 0, n);
            in.close();
            String html = decode(buf.toByteArray());
            final List<Chapter> list = parseHtml(html);
            if (list == null || list.isEmpty()) return;
            ui.post(() -> {
                if (readerVisible) return;
                boolean changed = chapters.size() != list.size()
                        || (chapters.isEmpty() && !list.isEmpty())
                        || (!chapters.isEmpty() && !list.isEmpty()
                            && chapters.get(chapters.size() - 1).pid
                                != list.get(list.size() - 1).pid);
                if (changed) {
                    // 保持当前阅读位置映射
                    int curPid = curIndex >= 0 && curIndex < chapters.size()
                            ? chapters.get(curIndex).pid : -1;
                    chapters = list;
                    if (curPid > 0) {
                        for (int i = 0; i < chapters.size(); i++) {
                            if (chapters.get(i).pid == curPid) { curIndex = i; break; }
                        }
                    }
                    adapter.notifyDataSetChanged();
                    refreshContinueBtn();
                }
            });
        } catch (Exception ignored) {
        }
    }

    private List<Chapter> parseJson(byte[] bytes) throws Exception {
        JSONArray arr = new JSONArray(new String(bytes, StandardCharsets.UTF_8));
        List<Chapter> list = new ArrayList<Chapter>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            Chapter c = new Chapter();
            c.pid = o.getInt("p");
            c.title = o.getString("t");
            c.url = o.getString("u");
            list.add(c);
        }
        return list;
    }

    private List<Chapter> parseHtml(String html) {
        try {
            Matcher m = P_PAIRS.matcher(html);
            // 站点 DOM 顺序为新→旧且尾部有 33 个补传替换条目：①按章节号去重 ②反转+按号排序 → 旧→新
            java.util.LinkedHashMap<String, Chapter> dedup =
                    new java.util.LinkedHashMap<String, Chapter>();
            while (m.find()) {
                String t = m.group(1).trim();
                String u = m.group(2).trim();
                if (!u.contains("/p/34726/")) continue;
                Matcher pm = P_PID.matcher(u);
                if (!pm.find()) continue;
                Chapter c = new Chapter();
                c.pid = Integer.parseInt(pm.group(1));
                c.title = t.replace("\\'", "'");
                c.url = u;
                String key = chapKey(c.title);
                if (!dedup.containsKey(key)) dedup.put(key, c);
            }
            // ② 反转后按章节号稳定排序（无编号条目锚定在前一话之后）→ 旧→新
            List<Chapter> rev = new ArrayList<Chapter>(dedup.values());
            java.util.Collections.reverse(rev);
            int n = rev.size();
            final double[] keys = new double[n];
            Integer[] order = new Integer[n];
            double lastAssigned = 0.0;
            for (int i = 0; i < n; i++) {
                Double num = chapNum(rev.get(i).title);
                if (num != null) {
                    keys[i] = num;
                    lastAssigned = num;
                } else {
                    keys[i] = lastAssigned + 0.5;
                    lastAssigned = keys[i];
                }
                order[i] = i;
            }
            java.util.Arrays.sort(order, (a, b) -> Double.compare(keys[a], keys[b]));
            List<Chapter> out = new ArrayList<Chapter>(n);
            for (Integer i : order) out.add(rev.get(i));
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private static Double chapNum(String t) {
        Matcher m = Pattern.compile("^(\\d+)").matcher(t);
        if (m.find()) return Double.parseDouble(m.group(1));
        m = Pattern.compile("第\\s*(\\d+)\\s*话").matcher(t);
        if (m.find()) return Double.parseDouble(m.group(1));
        return null;
    }

    private static String chapKey(String t) {
        Double n = chapNum(t);
        if (n != null) return "n" + n.intValue();
        return "t" + t.replaceAll("\\s+", "").replace('罈', '坛');
    }

    private void refreshContinueBtn() {
        if (chapters.isEmpty()) {
            continueBtn.setText("目录为空，请检查网络");
            return;
        }
        int idx = lastChapterIndex();
        Chapter c = chapters.get(idx);
        if (sp.getInt("last_pid", -1) == c.pid) {
            continueBtn.setText("继续阅读：" + c.title);
        } else {
            continueBtn.setText("开始阅读：" + c.title);
        }
    }

    private int lastChapterIndex() {
        int lastPid = sp.getInt("last_pid", -1);
        if (lastPid > 0) {
            for (int i = 0; i < chapters.size(); i++) {
                if (chapters.get(i).pid == lastPid) return i;
            }
        }
        return 0;
    }

    private void showCatalog() {
        stopAutoRead(false);
        readerVisible = false;
        saveProgressNow();
        reader.stopLoading();
        reader.setVisibility(View.GONE);
        topBar.setVisibility(View.GONE);
        catalogList.setVisibility(View.VISIBLE);
        refreshContinueBtn();
    }

    // ================= 阅读 =================

    private void openChapter(final int index) {
        openChapter(index, false);
    }

    private void openChapter(final int index, final boolean resume) {
        if (index < 0 || index >= chapters.size()) return;
        switching = true;
        final Chapter c = chapters.get(index);
        curIndex = index;
        // 只有“继续阅读”才恢复到上次的图；目录点入 / 上一话 / 下一话都从顶部看
        pendingRestoreImg = resume ? Math.max(0, sp.getInt("last_img", 0)) : 0;
        readerVisible = true;
        retryOnClick = false;
        catalogList.setVisibility(View.GONE);
        reader.setVisibility(View.VISIBLE);
        titleView.setText(c.title);
        pageBadge.setText("");
        topBar.setVisibility(View.VISIBLE);
        autoHideBar();
        showLoading(true, "正在加载 " + c.title);

        final String url = c.url;
        new Thread(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(12000);
                conn.setReadTimeout(25000);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("User-Agent", UA);
                conn.setRequestProperty("Referer", "http://m.gugu5.cc/");
                InputStream in = conn.getInputStream();
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] t = new byte[8192];
                int n;
                while ((n = in.read(t)) > 0) buf.write(t, 0, n);
                in.close();
                String html = decode(buf.toByteArray());
                Matcher m = P_MURL.matcher(html);
                if (!m.find()) throw new Exception("页面解析失败");
                byte[] decoded = Base64.decode(m.group(1).trim(), Base64.DEFAULT);
                String joined = new String(decoded, StandardCharsets.UTF_8);
                String[] parts = joined.split("\\$qingtiandy\\$");
                final List<String> imgs = new ArrayList<String>();
                for (String p : parts) {
                    p = p.trim();
                    if (p.startsWith("http")) {
                        // 与站点阅读器同款改写：s1/s2.bzcdn.net 是带广告注入的旧源，
                        // 站点实际从 p8.taoman.cc 加载干净图（根因：截图中的“包子漫画”二维码广告）
                        p = p.replaceAll("^https?://s[12]\\.[^/]+/scomic/", "https://p8.taoman.cc/scomic/");
                        imgs.add(p);
                    }
                }
                if (imgs.isEmpty()) throw new Exception("未找到图片");
                ui.post(() -> onChapterLoaded(index, imgs.toArray(new String[0])));
            } catch (final Exception e) {
                ui.post(() -> {
                    switching = false;
                    retryOnClick = true;
                    loadingBar.setVisibility(View.GONE);
                    showLoading(true, "加载失败：" + e.getMessage() + "\n\n点击屏幕重试");
                    pageBadge.setText("!");
                });
            }
        }).start();
    }

    private void onChapterLoaded(int index, String[] imgs) {
        retryOnClick = false;
        switching = false;
        loadingBar.setVisibility(View.VISIBLE);
        showLoading(false, null);
        Chapter c = chapters.get(index);
        renderReader(c, imgs);
        if (pendingRestoreImg > 0) {
            // 恢复模式：保留 last_img，等页面加载完跳回
            sp.edit().putInt("last_pid", c.pid).apply();
        } else {
            sp.edit().putInt("last_pid", c.pid).putInt("last_img", 0).apply();
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void renderReader(Chapter c, String[] imgs) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><meta charset='utf-8'>");
        sb.append("<meta name='viewport' content='width=device-width,initial-scale=1,user-scalable=no,viewport-fit=cover'>");
        sb.append("<style>");
        sb.append("html,body{margin:0;padding:0;background:#0D0F14;-webkit-tap-highlight-color:transparent;}");
        sb.append(".p{position:relative;width:100%;min-height:132vw;background:#12151d;}");
        sb.append(".p.ok{min-height:0;}");
        sb.append("img{display:block;width:100%;height:auto;margin:0;padding:0;opacity:0;transition:opacity .25s;}");
        sb.append("img.ld{opacity:1;}");
        sb.append(".tip{position:absolute;left:50%;top:50%;transform:translate(-50%,-50%);color:#5a6377;font:12px sans-serif;}");
        sb.append(".end{padding:34px 0 10px;color:#8a93a6;font:14px sans-serif;text-align:center;}");
        sb.append(".end b{display:block;color:#FF6B3D;font-size:16px;margin-bottom:8px;}");
        sb.append(".ctime{color:#5a6377;font:12px sans-serif;text-align:center;padding:0 0 80px;}");
        sb.append("</style></head><body>");
        for (String u : imgs) {
            sb.append("<div class='p'><span class='tip'>加载中…</span><img data-src='")
              .append(u).append("' alt=''></div>");
        }
        sb.append("<div class='end'><b>本章完</b>")
          .append(curIndex < chapters.size() - 1 ? "点按任意位置进入下一话" : "已是最新一话")
          .append("</div>");
        // 每个章节下面：本章阅读时间（自动阅读该章总时长），点按可自定义
        int ctSec = chapterSec(c.pid);
        sb.append("<div class='ctime' id='ctime'>本章阅读时间：").append(ctSec).append(" 秒")
          .append(sp.getInt("auto_ct_" + c.pid, 0) > 0 ? "（自定义）" : "（默认）")
          .append(" · 点按设置</div>");
        sb.append("<script>");
        sb.append("var TOTAL=").append(imgs.length).append(";var LOADED=0;");
        sb.append("var ims=[].slice.call(document.images);");
        // 懒加载
        sb.append("if('IntersectionObserver' in window){");
        sb.append("var io=new IntersectionObserver(function(es){");
        sb.append("es.forEach(function(e){if(e.isIntersecting){var im=e.target;");
        sb.append("if(!im.src){im.src=im.getAttribute('data-src');}}});},");
        sb.append("{rootMargin:'1600px 0px'});");
        sb.append("ims.forEach(function(im){io.observe(im);});");
        sb.append("}else{ims.forEach(function(im){im.src=im.getAttribute('data-src');});}");
        // 加载完成回调 + 占位清理
        sb.append("ims.forEach(function(im){");
        sb.append("im.addEventListener('load',function(){");
        sb.append("im.classList.add('ld');im.parentNode.classList.add('ok');");
        sb.append("var tip=im.previousSibling;if(tip)tip.style.display='none';");
        sb.append("LOADED++;try{Android.page(LOADED,TOTAL);}catch(e){}});");
        sb.append("im.addEventListener('error',function(){");
        sb.append("if(!im.retry){im.retry=1;im.src=im.getAttribute('data-src')+'&r=1';}");
        sb.append("else{im.parentNode.querySelector('.tip').textContent='加载失败';}});");
        sb.append("});");
        // 点击手势：左 1/3 上一话（顶部），右 1/3 下一话（底部），其余切换顶栏
        sb.append("document.addEventListener('click',function(e){");
        sb.append("var x=e.clientX,w=window.innerWidth;");
        sb.append("var d=document.documentElement;");
        sb.append("var st=d.scrollTop||document.body.scrollTop;");
        sb.append("var max=d.scrollHeight-window.innerHeight;");
        sb.append("if(max>0&&st>=max-10&&x>w*0.6){Android.nextChapter();return;}");
        sb.append("if(st<=4&&x<w*0.34){if(Android.hasPrev())Android.prevChapter();return;}");
        sb.append("Android.toggle();});");
        sb.append("var end=document.querySelector('.end');");
        sb.append("if(end)end.addEventListener('click',function(e){e.stopPropagation();Android.nextChapter();});");
        // 章末“本章阅读时间”点按 → 原生设置弹窗
        sb.append("var ctm=document.getElementById('ctime');");
        sb.append("if(ctm)ctm.addEventListener('click',function(e){e.stopPropagation();Android.setChapterTime();});");
        // 自动阅读引擎：按“剩余距离 / 剩余时间”自适应匀速滚动，读完自动进下一话。
        // 图片懒加载导致总高度增长时速度自动修正；手动滑动也不会打乱节奏。
        sb.append("window.__auto={t:null};");
        sb.append("window.__autoStop=function(){if(window.__auto.t){clearInterval(window.__auto.t);window.__auto.t=null;}};");
        sb.append("window.__autoStart=function(sec){window.__autoStop();");
        sb.append("var d=document.documentElement;var t0=Date.now(),last=t0;");
        sb.append("window.__auto.t=setInterval(function(){");
        sb.append("var now=Date.now();var dt=(now-last)/1000;last=now;");
        sb.append("var max=d.scrollHeight-window.innerHeight;");
        sb.append("var st=d.scrollTop||document.body.scrollTop;");
        sb.append("var rt=sec-(now-t0)/1000;");
        sb.append("if(rt<=0||st>=max-4){window.__autoStop();try{Android.autoNext();}catch(e){}return;}");
        sb.append("var v=(max-st)/Math.max(rt,0.5);if(v<15)v=15;");
        sb.append("window.scrollBy(0,v*dt);},50);};");
        sb.append("</script></body></html>");

        reader.loadDataWithBaseURL(null, sb.toString(), "text/html", "utf-8", null);
    }

    private void restoreProgress() {
        if (curIndex < 0 || curIndex >= chapters.size()) return;
        if (pendingRestoreImg <= 0) {
            // 新开/翻话：强制回顶部，防止 WebView 保留上一章的滚动位置
            reader.postDelayed(() -> reader.evaluateJavascript(
                    "window.scrollTo(0,0);", null), 120);
            reader.postDelayed(() -> reader.evaluateJavascript(
                    "window.scrollTo(0,0);", null), 700);
            return;
        }
        // 继续阅读：跳回上次看到的图（两次校正，避免图片渐次加载把位置推偏）
        final int target = pendingRestoreImg;
        pendingRestoreImg = 0;
        Runnable jump = () -> reader.evaluateJavascript(
                "var im=document.images[" + target + "];" +
                "if(im){im.scrollIntoView({block:'start'});}else{window.scrollTo(0,0);}", null);
        reader.postDelayed(jump, 500);
        reader.postDelayed(jump, 1300);
    }

    private void saveProgressNow() {
        if (!readerVisible || curIndex < 0 || curIndex >= chapters.size()) return;
        final Chapter c = chapters.get(curIndex);
        try {
            reader.evaluateJavascript(
                    "(function(){var ims=document.images;var best=0;" +
                    "for(var i=0;i<ims.length;i++){var r=ims[i].getBoundingClientRect();" +
                    "if(r.top>=-60){best=i;break;}}return best;})()",
                    (ValueCallback<String>) v -> {
                        try {
                            int img = Integer.parseInt(v.replace("\"", "").trim());
                            sp.edit().putInt("last_pid", c.pid)
                                    .putInt("last_img", Math.max(0, img)).apply();
                        } catch (Exception ignored) {
                        }
                    });
        } catch (Exception ignored) {
        }
    }

    // ================= 自动阅读 =================

    /** 本章生效的阅读时间（秒）：优先该章自定义，否则全局默认 */
    private int chapterSec(int pid) {
        int v = sp.getInt("auto_ct_" + pid, 0);
        return v > 0 ? v : sp.getInt("auto_default_sec", DEFAULT_CHAPTER_SEC);
    }

    private void toggleAutoRead() {
        if (autoReading) stopAutoRead(true);
        else startAutoRead();
    }

    private void startAutoRead() {
        if (!readerVisible || curIndex < 0 || curIndex >= chapters.size() || switching) {
            toast("请等章节加载完成后再开启");
            return;
        }
        autoReading = true;
        refreshAutoBtn();
        injectAuto();
        toast("自动阅读开启：" + chapterSec(chapters.get(curIndex).pid) + " 秒/章");
    }

    private void stopAutoRead(boolean showToast) {
        if (!autoReading) return;
        autoReading = false;
        try { reader.evaluateJavascript("window.__autoStop&&__autoStop();", null); }
        catch (Exception ignored) {}
        refreshAutoBtn();
        if (showToast) toast("自动阅读已停止");
    }

    /** 用当前章节的生效时间启动/重启 JS 自动滚动 */
    private void injectAuto() {
        if (!autoReading || curIndex < 0 || curIndex >= chapters.size()) return;
        int sec = chapterSec(chapters.get(curIndex).pid);
        reader.evaluateJavascript("window.__autoStart&&__autoStart(" + sec + ");", null);
    }

    private void refreshAutoBtn() {
        if (autoBtn == null) return;
        autoBtn.setText(autoReading ? "停止" : "自动");
        autoBtn.setTextColor(autoReading ? ORANGE : 0xFF9AA3B5);
    }

    /** 设置某章的自定义阅读时间（秒），可清除恢复默认 */
    private void showChapterTimeDialog(final int chapterIdx) {
        if (chapterIdx < 0 || chapterIdx >= chapters.size()) return;
        final Chapter c = chapters.get(chapterIdx);
        final android.widget.EditText et = new android.widget.EditText(this);
        et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        int cur = sp.getInt("auto_ct_" + c.pid, 0);
        if (cur > 0) et.setText(String.valueOf(cur));
        et.setHint("秒（5~3600），当前默认 " + sp.getInt("auto_default_sec", DEFAULT_CHAPTER_SEC) + " 秒");
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(dp(20), dp(8), dp(20), 0);
        wrap.addView(et);
        new android.app.AlertDialog.Builder(this)
                .setTitle("本章阅读时间 · " + c.title)
                .setView(wrap)
                .setPositiveButton("确定", (d, w) -> {
                    try {
                        int v = Integer.parseInt(et.getText().toString().trim());
                        v = Math.max(5, Math.min(3600, v));
                        sp.edit().putInt("auto_ct_" + c.pid, v).apply();
                        toast("「" + c.title + "」阅读时间设为 " + v + " 秒");
                        adapter.notifyDataSetChanged();
                        onChapterTimeChanged();
                    } catch (Exception e) {
                        toast("请输入数字秒数");
                    }
                })
                .setNeutralButton("清除自定义", (d, w) -> {
                    sp.edit().remove("auto_ct_" + c.pid).apply();
                    toast("已恢复默认时间");
                    adapter.notifyDataSetChanged();
                    onChapterTimeChanged();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 设置全局默认每章阅读时间（长按“自动”按钮触发） */
    private void showDefaultTimeDialog() {
        final android.widget.EditText et = new android.widget.EditText(this);
        et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        et.setText(String.valueOf(sp.getInt("auto_default_sec", DEFAULT_CHAPTER_SEC)));
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(dp(20), dp(8), dp(20), 0);
        wrap.addView(et);
        new android.app.AlertDialog.Builder(this)
                .setTitle("默认每章阅读时间（秒）")
                .setView(wrap)
                .setPositiveButton("确定", (d, w) -> {
                    try {
                        int v = Integer.parseInt(et.getText().toString().trim());
                        v = Math.max(5, Math.min(3600, v));
                        sp.edit().putInt("auto_default_sec", v).apply();
                        toast("默认时间设为 " + v + " 秒/章");
                        adapter.notifyDataSetChanged();
                        onChapterTimeChanged();
                    } catch (Exception e) {
                        toast("请输入数字秒数");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 时间变更后：刷新章末标签；自动阅读进行中则用新时间重新计时 */
    private void onChapterTimeChanged() {
        if (!readerVisible || curIndex < 0 || curIndex >= chapters.size()) return;
        Chapter c = chapters.get(curIndex);
        String label = "本章阅读时间：" + chapterSec(c.pid) + " 秒"
                + (sp.getInt("auto_ct_" + c.pid, 0) > 0 ? "（自定义）" : "（默认）")
                + " · 点按设置";
        try {
            reader.evaluateJavascript("var e=document.getElementById('ctime');if(e)e.textContent="
                    + JSONObject.quote(label) + ";", null);
        } catch (Exception ignored) {}
        if (autoReading) injectAuto();
    }

    private void toggleBar() {
        if (!readerVisible) return;
        if (hideRunnable != null) ui.removeCallbacks(hideRunnable);
        if (topBar.getVisibility() == View.VISIBLE) {
            topBar.setVisibility(View.GONE);
        } else {
            topBar.setVisibility(View.VISIBLE);
            autoHideBar();
        }
    }

    private void autoHideBar() {
        hideRunnable = () -> {
            if (readerVisible) topBar.setVisibility(View.GONE);
        };
        ui.postDelayed(hideRunnable, 3200);
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopAutoRead(false);
        saveProgressNow();
    }

    @Override
    public void onBackPressed() {
        if (readerVisible) {
            showCatalog();
        } else {
            super.onBackPressed();
        }
    }

    private String decode(byte[] b) {
        try {
            CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return dec.decode(ByteBuffer.wrap(b)).toString();
        } catch (Exception e) {
            try {
                return new String(b, "GBK");
            } catch (Exception e2) {
                return new String(b, StandardCharsets.UTF_8);
            }
        }
    }

    // ================= 适配器 =================

    private class CatalogAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return chapters.size();
        }

        @Override
        public Object getItem(int position) {
            return chapters.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            TextView tv;
            if (convertView instanceof LinearLayout) {
                row = (LinearLayout) convertView;
                tv = (TextView) row.getChildAt(0);
            } else {
                row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(20), 0, dp(16), 0);
                row.setLayoutParams(new AbsListView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
                tv = new TextView(MainActivity.this);
                tv.setTextColor(0xFFD8DEE9);
                tv.setTextSize(15);
                tv.setSingleLine(true);
                tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
                tv.setLayoutParams(new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                row.addView(tv);
            }
            Chapter c = chapters.get(position);
            tv.setText(c.title);
            // 右侧角标
            if (row.getChildCount() > 1) row.removeViews(1, row.getChildCount() - 1);
            int lastPid = sp.getInt("last_pid", -1);
            // 旧→新顺序：最新话在末尾
            if (position == chapters.size() - 1) {
                row.addView(makeBadge("最新", true));
            } else if (c.pid == lastPid) {
                row.addView(makeBadge("上次", false));
                tv.setTextColor(Color.WHITE);
            } else {
                tv.setTextColor(0xFFD8DEE9);
            }
            // 设了自定义阅读时间的章节：显示 ⏱ 角标
            int csec = sp.getInt("auto_ct_" + c.pid, 0);
            if (csec > 0) row.addView(makeBadge(csec + "s", false));
            TextView idx = new TextView(MainActivity.this);
            idx.setText((position + 1) + "");
            idx.setTextColor(0xFF4A5266);
            idx.setTextSize(11);
            idx.setPadding(dp(10), 0, 0, 0);
            row.addView(idx);
            return row;
        }

        private TextView makeBadge(String text, boolean filled) {
            TextView b = new TextView(MainActivity.this);
            b.setText(text);
            b.setTextColor(filled ? Color.WHITE : ORANGE);
            b.setTextSize(10);
            b.setGravity(Gravity.CENTER);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(6));
            if (filled) bg.setColor(ORANGE);
            else bg.setStroke(dp(1), ORANGE);
            b.setBackground(bg);
            b.setPadding(dp(7), dp(2), dp(7), dp(3));
            return b;
        }
    }

    // ================= JS 桥 =================

    private class Bridge {
        @JavascriptInterface
        public void toggle() {
            ui.post(() -> toggleBar());
        }

        @JavascriptInterface
        public void page(final int loaded, final int total) {
            ui.post(() -> pageBadge.setText(loaded + "/" + total));
        }

        @JavascriptInterface
        public boolean hasPrev() {
            return curIndex > 0;
        }

        @JavascriptInterface
        public void nextChapter() {
            ui.post(() -> {
                if (switching) return; // 已在翻话/加载中，忽略重复点击
                if (retryOnClick && curIndex >= 0) {
                    openChapter(curIndex);
                } else if (curIndex < chapters.size() - 1) {
                    openChapter(curIndex + 1);
                } else {
                    toast("已是最新一话");
                }
            });
        }

        @JavascriptInterface
        public void prevChapter() {
            ui.post(() -> {
                if (switching) return;
                if (curIndex > 0) openChapter(curIndex - 1);
                else toast("已经是第一话");
            });
        }

        /** 自动阅读：本章滚完 → 进下一话；已是最新则停止 */
        @JavascriptInterface
        public void autoNext() {
            ui.post(() -> {
                if (!autoReading) return;
                if (curIndex < chapters.size() - 1) {
                    openChapter(curIndex + 1); // onPageFinished 里会重启自动滚动
                } else {
                    stopAutoRead(false);
                    toast("已是最新一话，自动阅读结束");
                }
            });
        }

        /** 章末“本章阅读时间”点击 → 弹出设置窗口 */
        @JavascriptInterface
        public void setChapterTime() {
            ui.post(() -> { if (curIndex >= 0) showChapterTimeDialog(curIndex); });
        }
    }
}
