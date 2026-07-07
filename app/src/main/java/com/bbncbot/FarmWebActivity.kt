package com.bbncbot

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.bbncbot.service.FloatingWindowService
import com.bbncbot.util.PermissionUtils
import org.json.JSONArray

/**
 * 芭芭农场 WebView 页面
 *
 * - 加载 UC 芭芭农场 H5 页面
 * - 用户在 WebView 内登录 UC 账号
 * - 提供 JavaScript 执行接口供 AutomationController 调用
 */
class FarmWebActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "FarmWebActivity"
        // UC 芭芭农场 H5 入口
        private const val FARM_URL = "https://broccoli.uc.cn/apps/ucfarm/routes/farm"

        @Volatile
        private var instance: FarmWebActivity? = null

        /** 获取当前实例 */
        fun getInstance(): FarmWebActivity? = instance
    }

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            // 使用 UC 浏览器极速版 UA，确保加载 UC 芭芭农场页面
            settings.userAgentString = "Mozilla/5.0 (Linux; U; Android 10; zh-CN; SM-G977N Build/QP1A.190711.020) AppleWebKit/534.46 (KHTML, like Gecko) Version/5.1 Mobile Safari/534.46 UCBrowser/13.2.5.1100 UWS/2.21.3.1 Mobile"
            webViewClient = FarmWebViewClient()
            webChromeClient = FarmWebChromeClient()
        }

        setContentView(webView)

        // 加载 UC 芭芭农场页面（用户需在 WebView 内登录 UC 账号）
        Log.i(TAG, "Loading $FARM_URL")
        webView.loadUrl(FARM_URL)

        // 自动启动悬浮窗服务
        startFloatingWindowService()
    }

    /** 启动悬浮窗服务 */
    private fun startFloatingWindowService() {
        if (!PermissionUtils.canDrawOverlays(this)) {
            Log.w(TAG, "Overlay permission not granted, floating window will not start")
            return
        }
        val intent = Intent(this, FloatingWindowService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Log.i(TAG, "FloatingWindowService started")
    }

    private inner class FarmWebViewClient : WebViewClient() {
        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            Log.i(TAG, "onPageFinished: $url")
            // 延迟 3 秒后执行检查，等待页面 JS 渲染完成
            webView.postDelayed({
                checkPageStatus(view)
            }, 3000)
        }

        private fun checkPageStatus(view: WebView?) {
            // 检查登录状态
            view?.evaluateJavascript(
                "(function(){return document.cookie.includes('lgc=') ? 'logged_in' : 'not_logged_in';})()"
            ) { result ->
                Log.i(TAG, "login status: $result")
            }
            // 输出页面文本和标题用于调试
            view?.evaluateJavascript(
                "(function(){return 'TITLE:' + document.title + ' | BODY:' + (document.body ? document.body.textContent : '').substring(0, 1000);})()"
            ) { result ->
                Log.i(TAG, "page content: $result")
            }
            // 输出所有按钮和链接文本
            view?.evaluateJavascript(
                "(function(){var texts=[];var els=document.querySelectorAll('button,a,[role=button],div[onclick],span[onclick],.button,.bind-btn,.tourist-btn');for(var i=0;i<els.length;i++){var t=(els[i].textContent||'').trim();if(t&&t.length<50)texts.push(t);}return 'CLICKABLES:'+texts.join('|');})()"
            ) { result ->
                Log.i(TAG, "clickables: $result")
            }
            // 自动点击"同意并登录"按钮（如果存在）
            view?.evaluateJavascript(
                "(function(){var btn=document.querySelector('.button.agree');if(btn){btn.click();return 'clicked agree button';}return 'no agree button';})()"
            ) { result ->
                Log.i(TAG, "auto login: $result")
                // 如果点击了同意并登录按钮，延迟 5 秒后再次检查页面状态并点击登录淘宝账号按钮
                if (result.contains("clicked")) {
                    webView.postDelayed({
                        Log.i(TAG, "=== Checking page status after login click ===")
                        view?.evaluateJavascript(
                            "(function(){return JSON.stringify({url: window.location.href, title: document.title, bodyText: (document.body ? document.body.textContent : '').substring(0, 500), clickables: (function(){var texts=[];var els=document.querySelectorAll('button,a,[role=button],div[onclick],span[onclick],.button,.bind-btn,.tourist-btn,[class*=btn]');for(var i=0;i<els.length;i++){var t=(els[i].textContent||'').trim();if(t&&t.length<50)texts.push(t);}return texts.join('|');})()});})()"
                        ) { status ->
                            Log.i(TAG, "page status after login: $status")
                        }
                        // 自动点击"登录淘宝账号"按钮
                        view?.evaluateJavascript(
                            "(function(){var els=document.querySelectorAll('[class*=btn],.button,a,div[onclick]');for(var i=0;i<els.length;i++){var t=(els[i].textContent||'').trim();if(t.indexOf('登录淘宝')>=0||t.indexOf('登陆淘宝')>=0){els[i].click();return 'clicked login taobao: '+t;}}return 'no login taobao button';})()"
                        ) { clickResult ->
                            Log.i(TAG, "auto login taobao: $clickResult")
                        }
                    }, 5000)
                }
            }
        }

        override fun onReceivedError(
            view: WebView?,
            request: android.webkit.WebResourceRequest?,
            error: android.webkit.WebResourceError?
        ) {
            super.onReceivedError(view, request, error)
            Log.e(TAG, "onReceivedError: ${error?.description} url=${request?.url}")
        }
    }

    private inner class FarmWebChromeClient : WebChromeClient() {
        override fun onJsAlert(
            view: WebView?,
            url: String?,
            message: String?,
            result: android.webkit.JsResult?
        ): Boolean {
            Log.i(TAG, "JS Alert: $message")
            result?.confirm()
            return true
        }

        override fun onJsConfirm(
            view: WebView?,
            url: String?,
            message: String?,
            result: android.webkit.JsResult?
        ): Boolean {
            Log.i(TAG, "JS Confirm: $message")
            result?.confirm()
            return true
        }

        override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
            Log.i(TAG, "Console: ${consoleMessage?.message()}")
            return true
        }
    }

    /**
     * 执行 JavaScript 并返回结果
     * @param script JavaScript 代码
     * @param callback 回调函数
     */
    fun evaluateJs(script: String, callback: (String) -> Unit) {
        webView.post {
            webView.evaluateJavascript(script) { result ->
                callback(result ?: "")
            }
        }
    }

    /**
     * 查找所有包含指定关键词的可点击元素
     * @param keywords 关键词列表
     * @param callback 回调函数，返回匹配元素的列表
     */
    fun findClickableElements(keywords: List<String>, callback: (List<ClickTarget>) -> Unit) {
        val keywordsJson = JSONArray(keywords).toString()
        val script = """
            (function(){
                var keywords = $keywordsJson;
                var results = [];
                var seen = {};
                var elements = document.querySelectorAll('*');
                for (var i = 0; i < elements.length; i++) {
                    var el = elements[i];
                    var text = (el.textContent || '').trim();
                    var desc = el.getAttribute('aria-label') || '';
                    var matched = false;
                    for (var j = 0; j < keywords.length; j++) {
                        var kw = keywords[j].toLowerCase();
                        if (text.toLowerCase().indexOf(kw) >= 0 ||
                            desc.toLowerCase().indexOf(kw) >= 0) {
                            matched = true;
                            break;
                        }
                    }
                    if (matched) {
                        var clickable = el;
                        var depth = 0;
                        while (clickable && depth < 10) {
                            if (clickable.onclick ||
                                clickable.getAttribute('role') === 'button' ||
                                clickable.tagName === 'BUTTON' ||
                                clickable.tagName === 'A' ||
                                clickable.classList.contains('clickable') ||
                                clickable.classList.contains('button')) {
                                break;
                            }
                            clickable = clickable.parentElement;
                            depth++;
                        }
                        if (!clickable) clickable = el;
                        var rect = clickable.getBoundingClientRect();
                        var key = Math.round(rect.left) + ',' + Math.round(rect.top);
                        if (!seen[key] && rect.width > 0 && rect.height > 0) {
                            seen[key] = true;
                            results.push({
                                text: text.substring(0, 50),
                                x: rect.left + rect.width / 2,
                                y: rect.top + rect.height / 2,
                                width: rect.width,
                                height: rect.height
                            });
                        }
                    }
                }
                return JSON.stringify(results);
            })()
        """.trimIndent()

        webView.post {
            webView.evaluateJavascript(script) { result ->
                val targets = mutableListOf<ClickTarget>()
                try {
                    val jsonStr = result
                        ?.removeSurrounding("\"")
                        ?.replace("\\\"", "\"")
                        ?.replace("\\\\", "\\")
                        ?.replace("\\n", "\n")
                        ?.replace("\\/", "/")
                    Log.d(TAG, "findClickableElements result: $jsonStr")
                    val arr = JSONArray(jsonStr ?: "[]")
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        targets.add(
                            ClickTarget(
                                text = obj.getString("text"),
                                x = obj.getDouble("x").toFloat(),
                                y = obj.getDouble("y").toFloat()
                            )
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "parse findClickableElements error: ${e.message}, result=$result")
                }
                callback(targets)
            }
        }
    }

    /**
     * 查找并点击第一个包含指定关键词的元素
     * @param keywords 关键词列表
     * @param callback 回调函数，返回是否点击成功
     */
    fun findAndClick(keywords: List<String>, callback: (Boolean) -> Unit) {
        val keywordsJson = JSONArray(keywords).toString()
        val script = """
            (function(){
                var keywords = $keywordsJson;
                var elements = document.querySelectorAll('*');
                for (var i = 0; i < elements.length; i++) {
                    var el = elements[i];
                    var text = (el.textContent || '').trim();
                    var desc = el.getAttribute('aria-label') || '';
                    for (var j = 0; j < keywords.length; j++) {
                        var kw = keywords[j].toLowerCase();
                        if (text.toLowerCase().indexOf(kw) >= 0 ||
                            desc.toLowerCase().indexOf(kw) >= 0) {
                            var clickable = el;
                            var depth = 0;
                            while (clickable && depth < 10) {
                                if (clickable.onclick ||
                                    clickable.getAttribute('role') === 'button' ||
                                    clickable.tagName === 'BUTTON' ||
                                    clickable.tagName === 'A' ||
                                    clickable.classList.contains('clickable')) {
                                    break;
                                }
                                clickable = clickable.parentElement;
                                depth++;
                            }
                            if (!clickable) clickable = el;
                            clickable.click();
                            return true;
                        }
                    }
                }
                return false;
            })()
        """.trimIndent()

        webView.post {
            webView.evaluateJavascript(script) { result ->
                callback(result == "true")
            }
        }
    }

    /**
     * 向下滚动页面
     * @param callback 回调函数
     */
    fun scrollDown(callback: (Boolean) -> Unit) {
        val script = """
            (function(){
                window.scrollBy(0, window.innerHeight * 0.8);
                return true;
            })()
        """.trimIndent()

        webView.post {
            webView.evaluateJavascript(script) { result ->
                callback(result == "true")
            }
        }
    }

    /**
     * 获取页面文本内容（用于调试）
     * @param callback 回调函数，返回页面文本
     */
    fun getPageText(callback: (String) -> Unit) {
        val script = """
            (function(){
                return (document.body ? document.body.textContent : '').substring(0, 500);
            })()
        """.trimIndent()

        webView.post {
            webView.evaluateJavascript(script) { result ->
                callback(result?.removeSurrounding("\"")?.replace("\\n", "\n") ?: "")
            }
        }
    }

    /** 点击目标 */
    data class ClickTarget(
        val text: String,
        val x: Float,
        val y: Float
    )

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        instance = null
        webView.destroy()
        super.onDestroy()
    }
}
