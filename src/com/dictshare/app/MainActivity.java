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
import android.view.Menu;
import android.view.MenuItem;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final String PREFS = "dictshare";
    private static final String KEY_TEMPLATE = "url_template";
    private static final String KEY_APPEARANCE = "appearance";
    private static final String KEY_HIDE_CHROME = "hide_chrome";
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
    private static final int M_CUSTOM = 6;
    private static final int M_BROWSER = 7;
    private static final int M_APPEARANCE = 8;
    private static final int M_SIGNIN = 9;
    private static final int M_SIGNOUT = 10;
    private static final int M_NAV = 11;

    private WebView web;
    private String lastQuery = null;

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

    @SuppressLint("SetJavaScriptEnabled")
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
        applyWebDarkening(s);

        // Google blocks OAuth sign-in inside WebViews; removing the "; wv"
        // marker from the user agent makes the login flow work.
        String ua = s.getUserAgentString();
        s.setUserAgentString(ua.replace("; wv", ""));

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
            }
        });

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
        return text.toString().trim();
    }

    private void search(String query) {
        String template = getTemplate();
        String encoded = Uri.encode(query);
        String url;
        if (template.contains("%s")) {
            url = template.replace("%s", encoded);
        } else {
            url = template + (template.endsWith("/") ? "" : "/") + encoded;
        }
        web.loadUrl(url);
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

    /**
     * Injects (or removes) a persistent stylesheet hiding the site's top
     * navigation bar and footer. A style rule keeps working even for
     * elements rendered later, so no JavaScript timer is needed.
     */
    private void applyChromeVisibility() {
        String js;
        if (hideChrome()) {
            js = "(function(){if(document.getElementById('dictshareHide'))return;"
                    + "var st=document.createElement('style');st.id='dictshareHide';"
                    + "st.textContent='.navbar.navbar-inverse,.page-footer"
                    + "{display:none !important}';"
                    + "(document.head||document.documentElement).appendChild(st);})();";
        } else {
            js = "(function(){var st=document.getElementById('dictshareHide');"
                    + "if(st)st.parentNode.removeChild(st);})();";
        }
        web.evaluateJavascript(js, null);
    }

    /** Opens the Lingea login modal (the navbar link target #modalLogin). */
    private void signIn() {
        String js = "(function(){"
                + "var a=document.querySelector('a[href=\"#modalLogin\"],"
                + "a[data-target=\"#modalLogin\"]');"
                + "if(a){a.click();return 'ok';}"
                + "if(window.jQuery&&jQuery('#modalLogin').length)"
                + "{jQuery('#modalLogin').modal('show');return 'ok';}"
                + "return 'none';})();";
        web.evaluateJavascript(js, new android.webkit.ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                if ("\"none\"".equals(value)) {
                    Toast.makeText(MainActivity.this,
                            "No login found on this page. Try \u2018Show site "
                            + "navigation\u2019 and use the page itself.",
                            Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    /** Clicks the first link that looks like a logout action. */
    private void signOut() {
        String js = "(function(){var as=document.getElementsByTagName('a');"
                + "for(var i=0;i<as.length;i++){var a=as[i];"
                + "var t=(a.getAttribute('href')||'')+' '+(a.textContent||'');"
                + "if(/odhl|logout|sign\\s?out/i.test(t)){a.click();return 'ok';}}"
                + "return 'none';})();";
        web.evaluateJavascript(js, new android.webkit.ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                if ("\"none\"".equals(value)) {
                    Toast.makeText(MainActivity.this,
                            "No logout link found \u2013 you may not be signed in. "
                            + "Or try \u2018Show site navigation\u2019.",
                            Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, M_HOME, 0, "Home");
        menu.add(0, M_RELOAD, 1, "Reload");
        menu.add(0, M_EN, 2, "EN \u2194 SK (Lingea)");
        menu.add(0, M_DE, 3, "DE \u2194 SK (Lingea)");
        menu.add(0, M_IT, 4, "IT \u2194 SK (Lingea)");
        menu.add(0, M_CUSTOM, 5, "Custom dictionary URL\u2026");
        menu.add(0, M_APPEARANCE, 6, "Appearance\u2026");
        menu.add(0, M_SIGNIN, 7, "Sign in\u2026");
        menu.add(0, M_SIGNOUT, 8, "Sign out");
        menu.add(0, M_NAV, 9, "Show site navigation");
        menu.add(0, M_BROWSER, 10, "Open in browser");
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem nav = menu.findItem(M_NAV);
        if (nav != null) {
            nav.setTitle(hideChrome()
                    ? "Show site navigation" : "Hide site navigation");
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
            case M_EN:
                setTemplate(T_EN);
                return true;
            case M_DE:
                setTemplate(T_DE);
                return true;
            case M_IT:
                setTemplate(T_IT);
                return true;
            case M_CUSTOM:
                showTemplateDialog();
                return true;
            case M_APPEARANCE:
                showAppearanceDialog();
                return true;
            case M_SIGNIN:
                signIn();
                return true;
            case M_SIGNOUT:
                signOut();
                return true;
            case M_NAV:
                prefs().edit().putBoolean(KEY_HIDE_CHROME, !hideChrome()).apply();
                applyChromeVisibility();
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

    private void showTemplateDialog() {
        final EditText input = new EditText(this);
        input.setText(getTemplate());
        input.setHint("https://example.com/dictionary/%s");
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("Dictionary URL template");
        b.setMessage("Use %s where the searched word should be inserted. "
                + "If %s is missing, the word is appended to the end of the URL.");
        b.setView(input, pad, pad / 2, pad, 0);
        b.setPositiveButton("Save", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                String t = input.getText().toString().trim();
                if (t.startsWith("http://") || t.startsWith("https://")) {
                    setTemplate(t);
                } else {
                    Toast.makeText(MainActivity.this,
                            "URL must start with http(s)://", Toast.LENGTH_LONG).show();
                }
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
