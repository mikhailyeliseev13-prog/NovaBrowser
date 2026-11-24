package com.novabrowser.app

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {
    data class Tab(val id: Int, var title: String, var url: String, val web: WebView, val incognito: Boolean)
    private val tabs = mutableListOf<Tab>()
    private var activeId = -1
    private var nextId = 1
    private var dark = true
    private lateinit var root: LinearLayout
    private lateinit var toolbar: LinearLayout
    private lateinit var tabStrip: LinearLayout
    private lateinit var address: EditText
    private lateinit var progress: ProgressBar
    private lateinit var bottom: LinearLayout
    private lateinit var contentFrame: FrameLayout
    private val prefs by lazy { getSharedPreferences("nova_browser", MODE_PRIVATE) }
    private val bg get() = if (dark) Color.rgb(17,21,31) else Color.rgb(245,247,251)
    private val panel get() = if (dark) Color.rgb(27,33,47) else Color.WHITE
    private val fg get() = if (dark) Color.rgb(240,243,250) else Color.rgb(25,31,45)
    private val muted get() = if (dark) Color.rgb(154,164,183) else Color.rgb(105,116,136)
    private val accent = Color.rgb(102,132,255)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildShell()
        newTab("https://www.google.com", false)
    }
    private fun dp(v:Int)= (v*resources.displayMetrics.density).toInt()
    private fun label(s:String, size:Float=14f, color:Int=fg)=TextView(this).apply {
        text=s; textSize=size; setTextColor(color); gravity=Gravity.CENTER
        setPadding(dp(9),dp(7),dp(9),dp(7))
    }
    private fun button(s:String, action:()->Unit)=label(s,18f).apply {
        setOnClickListener { action() }; isClickable=true; minWidth=dp(38)
    }
    private fun buildShell() {
        root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(bg) }
        toolbar=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; setPadding(dp(8),dp(8),dp(8),dp(5)); setBackgroundColor(bg) }
        toolbar.addView(label("N",19f,Color.WHITE).apply { setBackgroundColor(accent); typeface=Typeface.DEFAULT_BOLD },LinearLayout.LayoutParams(dp(38),dp(40)))
        address=EditText(this).apply {
            hint="Поиск или адрес сайта"; textSize=14f; singleLine=true; setTextColor(fg); setHintTextColor(muted)
            setPadding(dp(12),0,dp(8),0); setBackgroundColor(panel); imeOptions=EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, actionId, event ->
                if(actionId==EditorInfo.IME_ACTION_GO || event?.keyCode==KeyEvent.KEYCODE_ENTER) { navigate(text.toString()); true } else false
            }
        }
        toolbar.addView(address,LinearLayout.LayoutParams(0,dp(42),1f).apply { leftMargin=dp(8); rightMargin=dp(5) })
        toolbar.addView(button("+") { newTab(null,false) })
        toolbar.addView(button("⋮") { showMenu() })
        root.addView(toolbar)
        progress=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply { max=100; visibility=View.GONE }
        root.addView(progress,LinearLayout.LayoutParams(-1,dp(3)))
        val scroll=HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false }
        tabStrip=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; setPadding(dp(7),dp(5),dp(7),dp(5)); setBackgroundColor(bg) }
        scroll.addView(tabStrip); root.addView(scroll,LinearLayout.LayoutParams(-1,dp(48)))
        contentFrame=FrameLayout(this)
        root.addView(contentFrame,LinearLayout.LayoutParams(-1,0,1f))
        bottom=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER; setBackgroundColor(panel) }
        listOf("‹","›","⌂","☆","▢").forEachIndexed { i,s ->
            val action:()->Unit=when(i) {
                0 -> { { goBack() } }; 1 -> { { goForward() } }; 2 -> { { newTab("https://www.google.com",false) } }
                3 -> { { addBookmark() } }; else -> { { showTabsOverview() } }
            }
            bottom.addView(button(s,action),LinearLayout.LayoutParams(0,dp(50),1f))
        }
        root.addView(bottom); setContentView(root); applyColors()
    }
    private fun applyColors() { root.setBackgroundColor(bg); toolbar.setBackgroundColor(bg); tabStrip.setBackgroundColor(bg); bottom.setBackgroundColor(panel); address.setTextColor(fg); address.setHintTextColor(muted) }

    private fun createWebView(incognito:Boolean):WebView = WebView(this).apply {
        settings.javaScriptEnabled=true
        settings.domStorageEnabled=true
        settings.loadsImagesAutomatically=true
        settings.javaScriptCanOpenWindowsAutomatically=false
        settings.setSupportMultipleWindows(false)
        settings.cacheMode=if(incognito) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
        settings.allowFileAccess=false
        settings.allowContentAccess=true
        settings.mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
        if(incognito) { clearCache(true) }
        webViewClient=object:WebViewClient() {
            override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean {
                val scheme=request.url.scheme ?: return false
                if(scheme=="http" || scheme=="https") return false
                return try { startActivity(Intent(Intent.ACTION_VIEW,request.url)); true } catch(_:Exception) { true }
            }
            override fun onPageStarted(view:WebView,url:String,icon:android.graphics.Bitmap?) {
                val t=tabs.find { it.web===view }
                t?.url=url
                if(t?.id==activeId) { address.setText(url); progress.visibility=View.VISIBLE; progress.progress=8 }
            }
            override fun onPageFinished(view:WebView,url:String) {
                val t=tabs.find { it.web===view } ?: return
                t.url=url; t.title=view.title?.takeIf { it.isNotBlank() } ?: url
                if(!t.incognito) saveHistory(t.title,url)
                if(t.id==activeId) { address.setText(url); progress.visibility=View.GONE; refreshTabs() }
            }
        }
        webChromeClient=object:WebChromeClient() {
            override fun onProgressChanged(view:WebView,p:Int) {
                if(tabs.find { it.web===view }?.id==activeId) { progress.progress=p; progress.visibility=if(p>=100) View.GONE else View.VISIBLE }
            }
            override fun onReceivedTitle(view:WebView,title:String?) { tabs.find { it.web===view }?.let { it.title=title?:it.url; refreshTabs() } }
        }
        setDownloadListener(DownloadListener { url,ua,disposition,mime,_ ->
            try {
                val filename=android.webkit.URLUtil.guessFileName(url,disposition,mime)
                val req=DownloadManager.Request(Uri.parse(url)).setMimeType(mime)
                    .addRequestHeader("User-Agent",ua).setTitle(filename).setDescription("Nova Browser")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,filename)
                CookieManager.getInstance().getCookie(url)?.let { req.addRequestHeader("Cookie",it) }
                (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                Toast.makeText(this@MainActivity,"Загрузка началась",Toast.LENGTH_SHORT).show()
            } catch(_:Exception) { Toast.makeText(this@MainActivity,"Не удалось начать загрузку",Toast.LENGTH_SHORT).show() }
        })
    }
    private fun newTab(url:String?,incognito:Boolean) {
        val web=createWebView(incognito)
        val t=Tab(nextId++,"Новая вкладка","about:blank",web,incognito)
        tabs.add(t); contentFrame.addView(web,FrameLayout.LayoutParams(-1,-1)); switchTab(t.id)
        if(url!=null) load(t,url) else showStartPage(t)
    }
    private fun showStartPage(t:Tab) {
        val html = "<html><meta name='viewport' content='width=device-width, initial-scale=1'><body style='margin:0;background:#11151f;color:#f2f5fa;font-family:Arial;padding:28px'><div style='font-size:34px;font-weight:700;margin-top:22vh'>Nova Browser</div><div style='color:#9aa4b7;margin-top:8px'>Всё нужное — в одном месте</div><div style='display:grid;grid-template-columns:repeat(2,1fr);gap:12px;margin-top:35px'><a style='padding:18px;background:#1b2130;border-radius:16px;color:white;text-decoration:none' href='https://www.google.com'>Google</a><a style='padding:18px;background:#1b2130;border-radius:16px;color:white;text-decoration:none' href='https://www.youtube.com'>YouTube</a><a style='padding:18px;background:#1b2130;border-radius:16px;color:white;text-decoration:none' href='https://wikipedia.org'>Wikipedia</a><a style='padding:18px;background:#1b2130;border-radius:16px;color:white;text-decoration:none' href='https://github.com'>GitHub</a></div></body></html>"
        t.web.loadDataWithBaseURL("https://novabrowser.local/",html,"text/html","UTF-8",null)
    }
    private fun switchTab(id:Int) {
        activeId=id
        tabs.forEach { it.web.visibility=if(it.id==id) View.VISIBLE else View.GONE }
        val t=tabs.firstOrNull { it.id==id } ?: return
        address.setText(if(t.url=="about:blank") "" else t.url); refreshTabs()
    }
    private fun load(t:Tab,url:String) { t.url=url; t.web.loadUrl(url) }
    private fun navigate(input:String) {
        val raw=input.trim(); if(raw.isEmpty()) return
        val url=when {
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            raw.contains(" ") || !raw.contains(".") -> "https://www.google.com/search?q="+Uri.encode(raw)
            else -> "https://$raw"
        }
        activeTab()?.let { load(it,url) }
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(address.windowToken,0)
        address.clearFocus()
    }
    private fun activeTab()=tabs.firstOrNull { it.id==activeId }
    private fun refreshTabs() {
        tabStrip.removeAllViews()
        tabs.forEach { t ->
            val name=(if(t.incognito) "◐ " else "")+t.title.take(12).ifBlank { "Вкладка" }
            val chip=label(name,12f,if(t.id==activeId) Color.WHITE else muted).apply {
                setBackgroundColor(if(t.id==activeId) accent else panel)
                setOnClickListener { switchTab(t.id) }
            }
            val lp=LinearLayout.LayoutParams(-2,dp(36)); lp.rightMargin=dp(7); tabStrip.addView(chip,lp)
        }
    }
    private fun goBack() { activeTab()?.let { if(it.web.canGoBack()) it.web.goBack() } }
    private fun goForward() { activeTab()?.let { if(it.web.canGoForward()) it.web.goForward() } }
    private fun addBookmark() {
        val t=activeTab()?:return
        if(!t.url.startsWith("http")) return
        val a=JSONArray(prefs.getString("bookmarks","[]"))
        a.put(JSONObject().put("title",t.title).put("url",t.url))
        prefs.edit().putString("bookmarks",a.toString()).apply()
        Toast.makeText(this,"Добавлено в закладки",Toast.LENGTH_SHORT).show()
    }
    private fun saveHistory(title:String,url:String) {
        if(!url.startsWith("http")) return
        val a=JSONArray(prefs.getString("history","[]"))
        a.put(JSONObject().put("title",title).put("url",url).put("time",System.currentTimeMillis()))
        while(a.length()>300) a.remove(0)
        prefs.edit().putString("history",a.toString()).apply()
    }
    private fun showMenu() {
        val items=arrayOf("Новая вкладка","Новая вкладка инкогнито","Закладки","История","Загрузки","Поделиться страницей","Очистить историю и данные","Сменить тему","Настройки")
        android.app.AlertDialog.Builder(this).setTitle("Nova Browser").setItems(items) { _,i ->
            when(i) {
                0 -> newTab(null,false)
                1 -> newTab(null,true)
                2 -> showSavedList("bookmarks","Закладки")
                3 -> showSavedList("history","История")
                4 -> try { startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) } catch(_:Exception) { Toast.makeText(this,"Откройте папку Downloads",Toast.LENGTH_SHORT).show() }
                5 -> sharePage()
                6 -> clearData()
                7 -> { dark=!dark; applyColors(); refreshTabs() }
                8 -> showSettings()
            }
        }.show()
    }
    private fun showSavedList(key:String,title:String) {
        val a=JSONArray(prefs.getString(key,"[]"))
        if(a.length()==0) { Toast.makeText(this,"Пока пусто",Toast.LENGTH_SHORT).show(); return }
        val labels=(0 until a.length()).map { i -> val o=a.getJSONObject(a.length()-1-i); o.optString("title").ifBlank { o.optString("url") }.take(48) }.toTypedArray()
        android.app.AlertDialog.Builder(this).setTitle(title).setItems(labels) { _,i -> newTab(a.getJSONObject(a.length()-1-i).optString("url"),false) }.setNegativeButton("Закрыть",null).show()
    }
    private fun showTabsOverview() {
        val names=tabs.mapIndexed { i,t -> "${i+1}. ${t.title.take(30)}${if(t.incognito) " (инкогнито)" else ""}" }.toMutableList()
        names.add("＋ Новая вкладка")
        android.app.AlertDialog.Builder(this).setTitle("Вкладки (${tabs.size})").setItems(names.toTypedArray()) { _,i ->
            if(i==tabs.size) newTab(null,false) else switchTab(tabs[i].id)
        }.setNeutralButton("Закрыть текущую") { _,_ -> closeActiveTab() }.setNegativeButton("Готово",null).show()
    }
    private fun closeActiveTab() {
        val t=activeTab()?:return
        tabs.remove(t); contentFrame.removeView(t.web)
        if(t.incognito) { t.web.clearHistory(); t.web.clearCache(true); t.web.loadUrl("about:blank") }
        t.web.destroy()
        if(tabs.isEmpty()) newTab("https://www.google.com",false) else switchTab(tabs.last().id)
    }
    private fun clearData() {
        android.app.AlertDialog.Builder(this).setTitle("Очистить данные?").setMessage("Удалятся история, cookies и кеш. Закладки останутся.")
            .setNegativeButton("Отмена",null).setPositiveButton("Очистить") { _,_ ->
                prefs.edit().remove("history").apply()
                CookieManager.getInstance().removeAllCookies(null)
                tabs.forEach { it.web.clearCache(true); it.web.clearHistory() }
                Toast.makeText(this,"Данные очищены",Toast.LENGTH_SHORT).show()
            }.show()
    }
    private fun showSettings() {
        android.app.AlertDialog.Builder(this).setTitle("Настройки").setMessage("Тема: ${if(dark) "тёмная" else "светлая"}\nПоиск: Google\nJavaScript: включён\nЗащищённое соединение: HTTPS по умолчанию")
            .setPositiveButton("Готово",null).show()
    }
    private fun sharePage() {
        val t=activeTab()?:return
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type="text/plain"; putExtra(Intent.EXTRA_TEXT,t.url) },"Поделиться"))
    }
    @Deprecated("Deprecated in Android")
    override fun onBackPressed() {
        val w=activeTab()?.web
        if(w!=null && w.canGoBack()) w.goBack() else super.onBackPressed()
    }
    override fun onDestroy() { tabs.forEach { it.web.destroy() }; tabs.clear(); super.onDestroy() }
}
