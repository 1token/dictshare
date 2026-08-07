package com.dictshare.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.GestureDetector;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final String PREFS = "dictshare";
    private static final String KEY_TEMPLATE = "url_template";
    private static final String KEY_APPEARANCE = "appearance";
    private static final String KEY_HIDE_CHROME = "hide_chrome";
    private static final String KEY_HISTORY_PREFIX = "history:";
    private static final String KEY_HISTORY_SIZE = "history_size";
    private static final String KEY_AUTOPRONOUNCE = "autopronounce";
    private static final int DEFAULT_HISTORY_SIZE = 30;
    private static final String DEFAULT_TEMPLATE =
            "https://slovniky.lingea.sk/anglicko-slovensky/%s";

    private static final String T_EN = "https://slovniky.lingea.sk/anglicko-slovensky/%s";
    private static final String T_DE = "https://slovniky.lingea.sk/nemecko-slovensky/%s";
    private static final String T_IT = "https://slovniky.lingea.sk/taliansko-slovensky/%s";

    // Appearance values
    private static final int APP_SYSTEM = 0;
    private static final int APP_DARK = 1;
    private static final int APP_LIGHT = 2;

    private static final int M_HOME = 1;
    private static final int M_RELOAD = 2;
    private static final int M_EN = 3;
    private static final int M_DE = 4;
    private static final int M_IT = 5;
    private static final int M_BROWSER = 7;
    private static final int M_APPEARANCE = 8;
    private static final int M_NAV = 11;
    private static final int M_HISTORY = 12;
    private static final int M_PRONOUNCE = 13;

    private WebView web;
    private String lastQuery = null;
    private boolean resumed = false;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(newBase);
        // Force day/night resources according to the appearance preference so
        // that both the DayNight theme and WebView darkening follow it.
        int mode = newBase.getSharedPreferences(PREFS, MODE_PRIVATE)
                .getInt(KEY_APPEARANCE, APP_SYSTEM);
        if (mode != APP_SYSTEM) {
            Configuration cfg = new Configuration();
            cfg.uiMode = (mode == APP_DARK
                    ? Configuration.UI_MODE_NIGHT_YES
                    : Configuration.UI_MODE_NIGHT_NO)
                    | Configuration.UI_MODE_TYPE_NORMAL;
            applyOverrideConfiguration(cfg);
        }
    }

    private boolean isDarkEffective() {
        int mode = prefs().getInt(KEY_APPEARANCE, APP_SYSTEM);
        if (mode == APP_DARK) {
            return true;
        }
        if (mode == APP_LIGHT) {
            return false;
        }
        int night = getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return night == Configuration.UI_MODE_NIGHT_YES;
    }

    @SuppressLint({"SetJavaScriptEnabled", "ClickableViewAccessibility"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Pick the activity theme before any views are created.
        if (Build.VERSION.SDK_INT >= 29) {
            setTheme(android.R.style.Theme_DeviceDefault_DayNight);
        } else {
            setTheme(isDarkEffective()
                    ? android.R.style.Theme_DeviceDefault
                    : android.R.style.Theme_DeviceDefault_Light_DarkActionBar);
        }
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        setContentView(web);
        web.setBackgroundColor(isDarkEffective() ? 0xFF121212 : 0xFFFFFFFF);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        // Allow programmatic (auto-)pronunciation without a tap
        s.setMediaPlaybackRequiresUserGesture(false);
        applyWebDarkening(s);

        // Google blocks OAuth sign-in inside WebViews; removing the "; wv"
        // marker from the user agent makes the login flow work.
        String ua = s.getUserAgentString();
        s.setUserAgentString(ua.replace("; wv", ""));

        // Horizontal fling = previous/next dictionary entry, mirroring the
        // site's mi-index_prev / mi-index_next buttons (works even while
        // the menu bar is hidden). The listener never consumes events, so
        // normal scrolling and tapping are unaffected.
        final GestureDetector gestures = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onFling(MotionEvent e1, MotionEvent e2,
                            float vx, float vy) {
                        if (e1 == null || e2 == null) {
                            return false;
                        }
                        float dx = e2.getX() - e1.getX();
                        float dy = e2.getY() - e1.getY();
                        float min = 100
                                * getResources().getDisplayMetrics().density;
                        if (Math.abs(dx) > min
                                && Math.abs(dx) > 2 * Math.abs(dy)
                                && Math.abs(vx) > 300) {
                            entryStep(dx < 0);
                            return true;
                        }
                        return false;
                    }
                });
        web.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                gestures.onTouchEvent(event);
                return false;
            }
        });

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    return false; // keep browsing inside the app
                }
                // Hand anything else (mailto:, intent:, ...) to the system
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception ignored) {
                }
                return true;
            }

            @Override
            public void onPageCommitVisible(WebView view, String url) {
                applyChromeVisibility();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                applyChromeVisibility();
                injectHistoryCard();
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url,
                    boolean isReload) {
                if (!isReload) {
                    // Captures words typed directly into the site as well.
                    // Matched against every known dictionary so that words
                    // land in the history of the dictionary they belong to.
                    for (String t : knownTemplates()) {
                        String w = wordFromUrl(url, t);
                        if (w != null) {
                            recordHistory(w, t);
                            scheduleEntryEnhancements();
                            break;
                        }
                    }
                }
            }
        });

        // v1.2 kept one mixed-language history; remove the legacy entry
        prefs().edit().remove("history").apply();

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
            if (web.getUrl() == null) {
                web.loadUrl(homeUrl());
            }
        } else {
            handleIntent(getIntent());
        }
    }

    @SuppressWarnings("deprecation")
    private void applyWebDarkening(WebSettings s) {
        boolean dark = isDarkEffective();
        int mode = prefs().getInt(KEY_APPEARANCE, APP_SYSTEM);
        if (Build.VERSION.SDK_INT >= 33) {
            // Follows the (possibly overridden) uiMode of this context.
            s.setAlgorithmicDarkeningAllowed(mode != APP_LIGHT);
        } else if (Build.VERSION.SDK_INT >= 29) {
            if (mode == APP_SYSTEM) {
                s.setForceDark(WebSettings.FORCE_DARK_AUTO);
            } else {
                s.setForceDark(dark ? WebSettings.FORCE_DARK_ON
                        : WebSettings.FORCE_DARK_OFF);
            }
        }
        // Below API 29 the web content simply stays as the site renders it.
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        web.onResume();
    }

    @Override
    protected void onPause() {
        // Pauses WebView processing and any playing pronunciation audio;
        // together with the resumed flag this stops auto-pronunciation
        // from firing while the app is in the background.
        web.onPause();
        resumed = false;
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        String q = extractQuery(intent);
        if (q != null && q.length() > 0) {
            lastQuery = q;
            search(q);
        } else if (web.getUrl() == null) {
            web.loadUrl(homeUrl());
        }
    }

    private String extractQuery(Intent intent) {
        if (intent == null) {
            return null;
        }
        String action = intent.getAction();
        CharSequence text = null;
        if (Intent.ACTION_SEND.equals(action)) {
            text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        } else if (Intent.ACTION_PROCESS_TEXT.equals(action)) {
            text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
        }
        if (text == null) {
            return null;
        }
        return cleanQuery(text.toString());
    }

    /** Characters stripped from the beginning and end of shared text. */
    private static final String STRIP =
            "[\\s\"\u201E\u201C\u201D\u00AB\u00BB\u2039\u203A(){}\\[\\]<>"
            + ",.;:!?\u2026\u00B7\\-\u2013\u2014]+";

    /**
     * Cleans shared text before looking it up: drops URL lines (browsers
     * often share the selection as `"word"` followed by the page URL),
     * joins the remaining lines, trims surrounding punctuation and double
     * quotes, and strips single quotes only when they appear as a pair,
     * so a real trailing apostrophe (Italian po') survives.
     */
    private String cleanQuery(String raw) {
        if (raw == null) {
            return null;
        }
        String[] lines = raw.split("\\r?\\n");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty()) {
                continue;
            }
            String low = t.toLowerCase();
            if (low.startsWith("http://") || low.startsWith("https://")
                    || low.startsWith("www.")) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(t);
        }
        String s = sb.toString();
        s = s.replaceAll("^" + STRIP, "").replaceAll(STRIP + "$", "");
        if (s.length() >= 2) {
            char a = s.charAt(0);
            char b = s.charAt(s.length() - 1);
            boolean qa = a == '\'' || a == '\u2018' || a == '\u201A' || a == '\u2019';
            boolean qb = b == '\'' || b == '\u2019' || b == '\u2018';
            if (qa && qb) {
                s = s.substring(1, s.length() - 1).trim();
            }
        }
        return s;
    }

    private void search(String query) {
        recordHistory(query, getTemplate());
        web.loadUrl(buildUrl(query));
    }

    private String buildUrl(String query) {
        String template = getTemplate();
        String encoded = Uri.encode(query);
        if (template.contains("%s")) {
            return template.replace("%s", encoded);
        }
        return template + (template.endsWith("/") ? "" : "/") + encoded;
    }

    /** Current template plus the built-in presets, deduplicated. */
    private List<String> knownTemplates() {
        List<String> ts = new ArrayList<String>();
        ts.add(getTemplate());
        String[] presets = {T_EN, T_DE, T_IT};
        for (String t : presets) {
            if (!ts.contains(t)) {
                ts.add(t);
            }
        }
        return ts;
    }

    private String getTemplate() {
        return prefs().getString(KEY_TEMPLATE, DEFAULT_TEMPLATE);
    }

    private void setTemplate(String template) {
        prefs().edit().putString(KEY_TEMPLATE, template).apply();
        if (lastQuery != null) {
            search(lastQuery); // re-run the last lookup in the new dictionary
        } else {
            web.loadUrl(homeUrl());
        }
    }

    private String homeUrl() {
        String t = getTemplate().replace("%s", "");
        while (t.endsWith("/")) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private boolean hideChrome() {
        return prefs().getBoolean(KEY_HIDE_CHROME, true);
    }

    /** Clicks the site's next (true) or previous (false) entry button. */
    private void entryStep(boolean next) {
        String sel = next
                ? "[title=\"Nasleduj\u00face\"], .menu-icon.mi-index_next"
                : "[title=\"Predch\u00e1dzaj\u00face\"], .menu-icon.mi-index_prev";
        String js = "(function(){var el=document.querySelector('" + sel
                + "');if(el)el.click();})();";
        web.evaluateJavascript(js, null);
    }

    /**
     * The redesigned site navigates between entries without full page
     * loads, so history injection and auto-pronunciation are (re)applied
     * shortly after every recorded word navigation, giving the page time
     * to render the entry.
     */
    private void scheduleEntryEnhancements() {
        web.postDelayed(new Runnable() {
            @Override
            public void run() {
                applyChromeVisibility();
                injectHistoryCard();
                autoPronounce();
            }
        }, 500);
    }

    private boolean autoPronounceEnabled() {
        return prefs().getBoolean(KEY_AUTOPRONOUNCE, true);
    }

    /**
     * Clicks the headword's speaker (the <wsnd> element on the current
     * site, the old .lex_ful_wsnd.play as fallback) so the searched word
     * is pronounced without tapping the tiny button. Guarded per URL and
     * time so redirects or repeated callbacks do not toggle it off again.
     */
    private void autoPronounce() {
        if (!autoPronounceEnabled() || !resumed) {
            return;
        }
        String js = "(function(){"
                + "var k=location.href,n=Date.now();"
                + "if(window.__dsPh===k&&n-(window.__dsPt||0)<2000)return;"
                + "window.__dsPh=k;window.__dsPt=n;"
                + "var el=document.querySelector('wsnd, .lex_ful_wsnd.play');"
                + "if(el)el.click();})();";
        web.evaluateJavascript(js, null);
    }

    // ---- Lookup history -------------------------------------------------

    /**
     * Extracts the looked-up word from a dictionary URL by matching it
     * against the current template (prefix before %s, suffix after it).
     */
    private String wordFromUrl(String url, String template) {
        if (url == null) {
            return null;
        }
        int idx = template.indexOf("%s");
        String prefix;
        String suffix;
        if (idx >= 0) {
            prefix = template.substring(0, idx);
            suffix = template.substring(idx + 2);
        } else {
            prefix = template + (template.endsWith("/") ? "" : "/");
            suffix = "";
        }
        if (!url.startsWith(prefix)) {
            return null;
        }
        String rest = url.substring(prefix.length());
        int cut = rest.indexOf('#');
        if (cut >= 0) {
            rest = rest.substring(0, cut);
        }
        cut = rest.indexOf('?');
        if (cut >= 0) {
            rest = rest.substring(0, cut);
        }
        if (!suffix.isEmpty() && rest.endsWith(suffix)) {
            rest = rest.substring(0, rest.length() - suffix.length());
        }
        if (rest.isEmpty() || rest.contains("/")) {
            return null;
        }
        String w = Uri.decode(rest).trim();
        return w.isEmpty() ? null : w;
    }

    private int historySize() {
        return prefs().getInt(KEY_HISTORY_SIZE, DEFAULT_HISTORY_SIZE);
    }

    private List<String> loadHistory(String template) {
        String raw = prefs().getString(KEY_HISTORY_PREFIX + template, "");
        List<String> list = new ArrayList<String>();
        if (!raw.isEmpty()) {
            String[] parts = raw.split("\n");
            for (String p : parts) {
                if (!p.isEmpty()) {
                    list.add(p);
                }
            }
        }
        return list;
    }

    private void saveHistory(String template, List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (String s : list) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(s);
        }
        prefs().edit().putString(KEY_HISTORY_PREFIX + template,
                sb.toString()).apply();
    }

    /** Puts the word at the top of the history, deduplicated, size-capped. */
    private void recordHistory(String word, String template) {
        if (word == null) {
            return;
        }
        word = word.trim();
        if (word.isEmpty()) {
            return;
        }
        List<String> list = loadHistory(template);
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).equalsIgnoreCase(word)) {
                list.remove(i);
            }
        }
        list.add(0, word);
        int max = historySize();
        while (list.size() > max) {
            list.remove(list.size() - 1);
        }
        saveHistory(template, list);
    }

    private static String htmlEscape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    /**
     * Fills the site's history list with the app's per-dictionary
     * history. The site renders the list twice (a mobile and a desktop
     * variant of .area-right); the items are injected into every
     * .area-right ul. Injected entries use the site's own classes
     * but as real links. Because the site is a reactive app that may
     * re-render the list at any time, a MutationObserver re-applies the
     * app's items whenever they get overwritten. With an empty app
     * history the site's own list is left untouched.
     */
    private void injectHistoryCard() {
        List<String> list = loadHistory(getTemplate());
        StringBuilder h = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            String w = list.get(i);
            boolean last = i == list.size() - 1;
            h.append("<li><a href=\"").append(htmlEscape(buildUrl(w)))
                    .append("\" class=\"hover:cursor-pointer ")
                    .append("hover:underline break-all\">")
                    .append(htmlEscape(w)).append("</a>");
            if (!last) {
                h.append("<span class=\"mr-1\">,</span>");
            }
            h.append("</li>");
        }
        String js = "(function(){"
                + "window.__dsHist=" + JSONObject.quote(h.toString()) + ";"
                + "function ap(){"
                + "if(!window.__dsHist)return;"
                + "var uls=document.querySelectorAll('.area-right ul');"
                + "for(var i=0;i<uls.length;i++){var ul=uls[i];"
                + "if(ul.getAttribute('data-dictshare')==='1'"
                + "&&ul.__dsSet===window.__dsHist)continue;"
                + "ul.innerHTML=window.__dsHist;"
                + "ul.setAttribute('data-dictshare','1');"
                + "ul.__dsSet=window.__dsHist;}}"
                + "if(!window.__dsObs){"
                + "window.__dsObs=new MutationObserver(function(){ap();});"
                + "window.__dsObs.observe(document.documentElement,"
                + "{childList:true,subtree:true});}"
                + "ap();})();";
        web.evaluateJavascript(js, null);
    }

    private void showHistoryDialog() {
        final String template = getTemplate();
        final List<String> list = loadHistory(template);
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("History (" + list.size() + "/" + historySize() + ")");
        final AlertDialog[] holder = new AlertDialog[1];
        if (list.isEmpty()) {
            b.setMessage("No lookups yet.");
        } else {
            final float density = getResources().getDisplayMetrics().density;
            final ArrayAdapter<String> adapter =
                    new ArrayAdapter<String>(this, 0, list) {
                @Override
                public View getView(int position, View convertView,
                        ViewGroup parent) {
                    final String word = getItem(position);
                    final ArrayAdapter<String> self = this;
                    LinearLayout row = new LinearLayout(MainActivity.this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    int pad = (int) (12 * density);

                    TextView tv = new TextView(MainActivity.this);
                    tv.setText(word);
                    tv.setTextSize(16);
                    tv.setPadding(pad * 2, pad, pad, pad);
                    tv.setLayoutParams(new LinearLayout.LayoutParams(0,
                            LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                    tv.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            if (holder[0] != null) {
                                holder[0].dismiss();
                            }
                            lastQuery = word;
                            search(word);
                        }
                    });

                    TextView del = new TextView(MainActivity.this);
                    del.setText("\u2715");
                    del.setTextSize(16);
                    del.setPadding(pad, pad, pad * 2, pad);
                    del.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            self.remove(word);
                            saveHistory(template, list);
                            if (holder[0] != null) {
                                holder[0].setTitle("History (" + list.size()
                                        + "/" + historySize() + ")");
                            }
                        }
                    });

                    row.addView(tv);
                    row.addView(del);
                    return row;
                }
            };
            ListView lv = new ListView(this);
            lv.setAdapter(adapter);
            b.setView(lv);
        }
        b.setNegativeButton("Close", null);
        b.setNeutralButton("Clear", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                prefs().edit().remove(KEY_HISTORY_PREFIX + template).apply();
                Toast.makeText(MainActivity.this, "History cleared",
                        Toast.LENGTH_SHORT).show();
            }
        });
        b.setPositiveButton("Size\u2026", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                showHistorySizeDialog();
            }
        });
        holder[0] = b.create();
        holder[0].show();
    }

    private void showHistorySizeDialog() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(historySize()));
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("History size");
        b.setMessage("How many words to keep (5\u2013500).");
        b.setView(input, pad, pad / 2, pad, 0);
        b.setPositiveButton("Save", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                int n;
                try {
                    n = Integer.parseInt(input.getText().toString().trim());
                } catch (NumberFormatException e) {
                    n = DEFAULT_HISTORY_SIZE;
                }
                if (n < 5) {
                    n = 5;
                }
                if (n > 500) {
                    n = 500;
                }
                prefs().edit().putInt(KEY_HISTORY_SIZE, n).apply();
                for (String t : knownTemplates()) {
                    List<String> list = loadHistory(t);
                    if (list.size() > n) {
                        while (list.size() > n) {
                            list.remove(list.size() - 1);
                        }
                        saveHistory(t, list);
                    }
                }
                Toast.makeText(MainActivity.this,
                        "History size: " + n, Toast.LENGTH_SHORT).show();
            }
        });
        b.setNegativeButton("Cancel", null);
        b.show();
    }

    /**
     * Injects (or removes) a persistent stylesheet hiding the site's top
     * navigation bar and footer. A style rule keeps working even for
     * elements rendered later, so no JavaScript timer is needed.
     */
    private void applyChromeVisibility() {
        StringBuilder rules = new StringBuilder();
        // Always: the history column is hidden on small screens by the
        // site (lg:block hidden); keep it visible. Hide the mobile ad.
        rules.append(".area-right{display:block !important}");
        rules.append(".premium.mobile{display:none !important}");
        if (hideChrome()) {
            rules.append("nav,footer{display:none !important}");
        }
        String js = "(function(){"
                + "var st=document.getElementById('dictshareHide');"
                + "if(!st){st=document.createElement('style');"
                + "st.id='dictshareHide';"
                + "(document.head||document.documentElement).appendChild(st);}"
                + "st.textContent=" + JSONObject.quote(rules.toString())
                + ";})();";
        web.evaluateJavascript(js, null);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, M_HOME, 0, "Home");
        menu.add(0, M_RELOAD, 1, "Reload");
        menu.add(0, M_HISTORY, 2, "History\u2026");
        menu.add(0, M_EN, 2, "EN \u2194 SK (Lingea)");
        menu.add(0, M_DE, 3, "DE \u2194 SK (Lingea)");
        menu.add(0, M_IT, 4, "IT \u2194 SK (Lingea)");
        menu.add(0, M_APPEARANCE, 6, "Appearance\u2026");
        menu.add(0, M_NAV, 9, "Show site navigation");
        MenuItem pron = menu.add(0, M_PRONOUNCE, 10, "Auto-pronounce");
        pron.setCheckable(true);
        menu.add(0, M_BROWSER, 11, "Open in browser");
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem nav = menu.findItem(M_NAV);
        if (nav != null) {
            nav.setTitle(hideChrome()
                    ? "Show site navigation" : "Hide site navigation");
        }
        MenuItem pron = menu.findItem(M_PRONOUNCE);
        if (pron != null) {
            pron.setChecked(autoPronounceEnabled());
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case M_HOME:
                web.loadUrl(homeUrl());
                return true;
            case M_RELOAD:
                web.reload();
                return true;
            case M_HISTORY:
                showHistoryDialog();
                return true;
            case M_EN:
                setTemplate(T_EN);
                return true;
            case M_DE:
                setTemplate(T_DE);
                return true;
            case M_IT:
                setTemplate(T_IT);
                return true;
            case M_APPEARANCE:
                showAppearanceDialog();
                return true;
            case M_NAV:
                prefs().edit().putBoolean(KEY_HIDE_CHROME, !hideChrome()).apply();
                applyChromeVisibility();
                return true;
            case M_PRONOUNCE:
                boolean on = !autoPronounceEnabled();
                prefs().edit().putBoolean(KEY_AUTOPRONOUNCE, on).apply();
                Toast.makeText(this, on
                        ? "Auto-pronounce enabled"
                        : "Auto-pronounce disabled", Toast.LENGTH_SHORT).show();
                return true;
            case M_BROWSER:
                String current = web.getUrl();
                if (current != null) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(current)));
                    } catch (Exception ignored) {
                    }
                }
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void showAppearanceDialog() {
        final String[] names = {"Match device", "Dark", "Light"};
        int current = prefs().getInt(KEY_APPEARANCE, APP_SYSTEM);
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("Appearance");
        b.setSingleChoiceItems(names, current, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                prefs().edit().putInt(KEY_APPEARANCE, which).apply();
                dialog.dismiss();
                recreate(); // re-applies theme; WebView state is restored
            }
        });
        b.setNegativeButton("Cancel", null);
        b.show();
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }
}
