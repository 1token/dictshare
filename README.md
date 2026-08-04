# DictShare

A minimal Android WebView wrapper for online dictionaries (Lingea by default).
Adds the dictionary to the system share sheet and to the text-selection
toolbar (ACTION_PROCESS_TEXT), so highlighted text in any app can be looked
up with one tap.

- Default dictionary: https://slovniky.lingea.sk/anglicko-slovensky
- Menu: switch EN/DE/IT <-> SK Lingea dictionaries or set any custom URL
  template containing `%s` as the placeholder for the searched word.
- Appearance setting: match device / dark / light (darkens web content too
  on Android 10+).
- Shared text is cleaned before lookup: surrounding punctuation/quotes are
  stripped and URL lines dropped (browsers share selections as "word" + URL).
- Own lookup history kept separately per dictionary, covering shared words
  and words typed on the site; size configurable (default 30). The site's
  history card (capped at 15) is replaced in-page by the app's history,
  styled like the original; menu > History offers the same list plus
  Clear and Size controls.
- The site's navigation bar and footer are hidden to maximize reading space;
  the app menu provides Sign in / Sign out and a toggle to show the site
  navigation again when needed.

## Google sign-in limitation

Signing in with a Google account does **not** work inside the app and cannot
be fixed: Google deliberately blocks its login flow in embedded Android
WebViews (anti-phishing policy), detected via the `X-Requested-With` header
and JavaScript fingerprinting, not just the user agent. Chrome Custom Tabs
would allow the Google login but cannot share cookies with the WebView, so
the session would never reach the app.

**Use Lingea's own username/password login instead** - it is served directly
by Lingea, works normally, and the session persists in the WebView's cookie
store across app restarts.

## Building

No Gradle. Requirements: JDK (javac), aapt2, dalvik-exchange (dx),
zipalign, apksigner, and an `android.jar` platform stub.

```sh
SDK=/path/to/dir-with-android.jar ./build.sh
```

`dictshare.keystore` (not committed) must be present in the project root
for signing; keep it safe so updates install over the existing app.

## Development workflow

Claude clones this repo, commits enhancements, and exports them with
`git format-patch`. Apply locally and push:

```sh
git am 00*.patch
git push
```
