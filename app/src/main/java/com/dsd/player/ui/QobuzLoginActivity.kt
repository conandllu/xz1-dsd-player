// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsd.player.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.dsd.player.data.QobuzPrefs
import org.json.JSONObject

/**
 * Qobuz **网页登录**（在应用内完成，用户体验仍是"输邮箱密码"）。
 *
 * 为什么必须这样（见 docs/DEV-NOTES.md）：
 * Qobuz 服务端已下线 `api.json/0.2/user/login` 的 app_id + 邮箱密码方式
 * （明文/MD5、GET/POST、换 app_id、换请求头，12 种组合全部 401；
 * 社区 streamrip issue #954 同样现象且"改密码也没用"）。
 * 唯一可行的取 token 方式是在 Qobuz 自己的登录页完成登录，然后截获它返回的 `user_auth_token`。
 *
 * 截获三路兜底：
 *  1) 注入 XHR/fetch 钩子，直接读 `user/login` 的响应体（最可靠）；
 *  2) 轮询 `localStorage.user_auth_token`；
 *  3) 轮询 `JSON.parse(localStorage.localuser).token`（新版播放器 8.3.x 的存法）。
 */
class QobuzLoginActivity : Activity() {

    companion object {
        private const val TAG = "QobuzLogin"
        const val LOGIN_URL = "https://play.qobuz.com/login"
        /** 桌面 UA：手机版落地页会往应用商店引导，桌面站点才能正常登录 */
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    private lateinit var web: WebView
    private lateinit var status: TextView
    private var finished = false
    private var pollCount = 0

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0B1218.toInt())
        }
        // 顶部条：说明 + 关闭
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 16, 24, 8)
        }
        status = TextView(this).apply {
            text = "在下方登录 Qobuz（登录成功后会自动返回）"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val close = Button(this).apply {
            text = "关闭"
            setOnClickListener { finish() }
        }
        bar.addView(status)
        bar.addView(close)
        root.addView(bar)

        web = WebView(this)
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true           // Qobuz 会话在 localStorage
            databaseEnabled = true
            userAgentString = UA
            loadWithOverviewMode = true
            useWideViewPort = true
        }
        // Qobuz 网页版要求 CSS 视口 ≥1024px，否则只给"请下载 App"页面。
        // 手机屏幕 402dp → 用 initialScale 把布局视口放大到 ~1300 CSS px，页面才肯渲染桌面版。
        web.setInitialScale(30)
        web.webChromeClient = object : WebChromeClient() {
            // 把网页 JS 的报错转到 logcat，方便真机上排查"登录成功但抓不到 token"
            override fun onConsoleMessage(m: android.webkit.ConsoleMessage?): Boolean {
                val lvl = m?.messageLevel()
                if (lvl == android.webkit.ConsoleMessage.MessageLevel.ERROR) {
                    Log.w(TAG, "js: ${m?.message()} @${m?.sourceId()}:${m?.lineNumber()}")
                }
                return true
            }
        }
        web.addJavascriptInterface(Bridge(), "QobuzBridge")
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // 再兜一层：把 innerWidth/screen.width 报成 1280，绕过 "need 1024px" 的检测
                view?.evaluateJavascript(
                    """(function(){
                         try {
                           var w = 1280;
                           Object.defineProperty(window, 'innerWidth', {get: function(){return w;}});
                           Object.defineProperty(window, 'outerWidth', {get: function(){return w;}});
                           Object.defineProperty(screen, 'width', {get: function(){return w;}});
                           Object.defineProperty(screen, 'availWidth', {get: function(){return w;}});
                         } catch(e){}
                       })();""".trimIndent(), null)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectHooks()
                pollCount = 0
            }

            override fun shouldInterceptRequest(
                view: WebView?, request: WebResourceRequest?
            ): android.webkit.WebResourceResponse? {
                val u = request?.url?.toString() ?: return null
                if (request.isForMainFrame && u.startsWith("https://play.qobuz.com")) {
                    patchedHtml(u)?.let { return it }
                }
                return null
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?, request: WebResourceRequest?
            ): Boolean {
                val u = request?.url?.toString() ?: return false
                // 站内跳转都留在 WebView 里；外链（条款等）交给系统浏览器
                return !u.startsWith("https://play.qobuz.com") && !u.startsWith("https://www.qobuz.com")
            }
        }
        web.loadUrl(LOGIN_URL)
        Log.i(TAG, "打开 $LOGIN_URL")
    }

    /** 注入三路截获：XHR / fetch 响应 + 周期性检查 localStorage */
    private fun injectHooks() {
        val js = """
            (function(){
              if (window.__dsdHooked) return;
              window.__dsdHooked = true;
              function report(t){ try { QobuzBridge.onToken(String(t||'')); } catch(e){} }
              function scan(s){
                try {
                  var o = JSON.parse(s);
                  if (o && o.user_auth_token) { report(o.user_auth_token); return; }
                  if (o && o.token) { report(o.token); return; }
                  if (o && o.user && o.user.user_auth_token) { report(o.user.user_auth_token); }
                } catch(e){}
              }
              var open = XMLHttpRequest.prototype.open;
              XMLHttpRequest.prototype.open = function(m,u){
                if (u && String(u).indexOf('user/login') >= 0) {
                  this.addEventListener('load', function(){ scan(this.responseText); });
                }
                return open.apply(this, arguments);
              };
              if (window.fetch) {
                var f = window.fetch;
                window.fetch = function(){
                  var p = f.apply(this, arguments);
                  try {
                    var u = (arguments[0] && (arguments[0].url || arguments[0])) + '';
                    if (u.indexOf('user/login') >= 0) {
                      p.then(function(r){ r.clone().text().then(scan); });
                    }
                  } catch(e){}
                  return p;
                };
              }
            })();
        """.trimIndent()
        web.evaluateJavascript(js, null)
        // localStorage 兜底轮询（登录成功后才有值）
        web.evaluateJavascript(
            """(function(){
                 try {
                   var a = localStorage.getItem('user_auth_token');
                   if (a) { QobuzBridge.onToken(a); return; }
                   var lu = localStorage.getItem('localuser');
                   if (lu) { var o = JSON.parse(lu); if (o && o.token) QobuzBridge.onToken(o.token); }
                 } catch(e){}
               })();""".trimIndent(), null
        )
        web.postDelayed({ if (!finished && !isFinishing) injectHooks() }, 1500)
        pollCount++
    }

    /**
     * 自己取主文档并改写 viewport。
     *
     * Qobuz 网页版在 CSS 视口 <1024px 时只显示"请下载 App"，而它的 `<meta name="viewport">`
     * 又会让 `setInitialScale` 失效 —— 唯一可靠的办法是在 HTML 到达 WebView 之前改掉这个 meta。
     * 顺带把 WebView 的 Cookie 带上，避免打断已建立的会话。
     */
    private fun patchedHtml(url: String): android.webkit.WebResourceResponse? = runCatching {
        val cookie = android.webkit.CookieManager.getInstance().getCookie(url).orEmpty()
        val req = okhttp3.Request.Builder().url(url)
            .header("User-Agent", UA)
            .apply { if (cookie.isNotBlank()) header("Cookie", cookie) }
            .build()
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        client.newCall(req).execute().use { r ->
            val body = r.body?.string() ?: return null
            val patched = if (body.contains("name=\"viewport\"") || body.contains("name='viewport'")) {
                body.replace(Regex("<meta[^>]*name=[\"']viewport[\"'][^>]*>", RegexOption.IGNORE_CASE),
                    "<meta name=\"viewport\" content=\"width=1280, initial-scale=0.32\">")
            } else body
            Log.i(TAG, "改写 viewport：$url（${body.length} → ${patched.length} B）")
            android.webkit.WebResourceResponse("text/html", "utf-8", patched.byteInputStream())
        }
    }.onFailure { Log.w(TAG, "拦截主文档失败：${it.message}") }.getOrNull()

    private inner class Bridge {
        @JavascriptInterface
        fun onToken(token: String) {
            if (finished || token.isBlank() || token.length < 40) return
            finished = true
            runOnUiThread {
                status.text = "已获取登录令牌，正在保存…"
                QobuzPrefs.saveLogin(this@QobuzLoginActivity, "网页登录", token, "")
                Log.i(TAG, "捕获到 user_auth_token（长度 ${token.length}，不打印内容）")
                setResult(Activity.RESULT_OK, Intent().putExtra("tokenLen", token.length))
                finish()
            }
        }
    }

    override fun onDestroy() {
        runCatching { web.destroy() }
        super.onDestroy()
    }
}
