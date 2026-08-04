package io.github.lisrain.fuckcainiao

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

class XposedInit : XposedModule() {
    companion object {
        const val TAG = "FuckCainiao"
        const val URL_ABOUT_FUCK_CAINIAO = "guoguo://go/about_fuck_cainiao"
        const val TARGET_PACKAGE = "com.cainiao.wireless"
        const val HOME_PAGE_ACTIVITY = "com.cainiao.wireless.homepage.view.activity.HomePageActivity"
        const val TARGET_WUHUANWU = "物换物"

        private val PROFILE_CLEAN_JS = """
(function(){
  if (window.__fcClean) return;
  window.__fcClean = 1;
  var container = null;
  function findContainer(){
    if (container && container.isConnected) return container;
    container = null;
    var divs = document.querySelectorAll('div');
    for (var i = 0; i < divs.length; i++) {
      var el = divs[i];
      if (el.childElementCount === 0 && (el.textContent || '').trim() === '我的订单') {
        var card = el.parentElement;
        if (card && card.parentElement) { container = card.parentElement; return container; }
      }
    }
    return null;
  }
  function clean(){
    var c = findContainer();
    if (!c) return;
    var order = null;
    for (var i = 0; i < c.children.length; i++) {
      if ((c.children[i].textContent || '').indexOf('我的订单') >= 0) { order = c.children[i]; break; }
    }
    if (!order) return;
    var n = order.nextElementSibling;
    while (n) {
      var next = n.nextElementSibling;
      c.removeChild(n);
      n = next;
    }
  }
  var scheduled = false;
  function schedule(){
    if (scheduled) return;
    scheduled = true;
    requestAnimationFrame(function(){ scheduled = false; clean(); });
  }
  new MutationObserver(schedule).observe(document.documentElement, {childList: true, subtree: true});
  setInterval(clean, 2000);
  clean();
})();
        """.trimIndent()
    }

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(Log.INFO, TAG, "onModuleLoaded: ${param.processName}")
        log(Log.INFO, TAG, "framework: $frameworkName($frameworkVersionCode) API $apiVersion")
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        log(Log.INFO, TAG, "onPackageLoaded: ${param.packageName}")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != TARGET_PACKAGE) return
        log(Log.INFO, TAG, "onPackageReady: ${param.packageName}")
        installHooks(param.classLoader)
    }

    private fun installHooks(classLoader: ClassLoader) {
        hookHomePageActivity(classLoader)
        hookCubeXLinearLayoutFragment(classLoader)
        hookLogisticDetailBannerView(classLoader)
        hookWelcomeActivity(classLoader)
        hookCNRecommendView(classLoader)
        hookLogisticDetailTemplateFragment(classLoader)
        hookHomeHeaderSection(classLoader)
        hookPackageTimeLineDecorateView(classLoader)
        hookNewBottomFloatBanner(classLoader)
        hookDXRecyclerViewAdapter(classLoader)
        hookAboutFuckCainiaoIntent()
        hookLogisticDetailTANXBannerView(classLoader)
        hookLogisticNoticeProtocolView(classLoader)
        hookLogisticVipBannerView(classLoader)
        hookGuoJiangPop(classLoader)
        hookProfileH5Cleaner(classLoader)
        hookGuideAdsSection(classLoader)
        hookPackageListSection(classLoader)
        hookNavigationViewStripRefresh(classLoader)
        hookHomeHeaderSetBizData(classLoader)
        hookTextViewHider()
        hookActivityFocusScanner()
    }

    /**
     * 隐藏首页推荐流(CNRecommendView)的内容，但保留视图自身占位：
     * initView 会给它设置不透明的浅色背景，刚好盖住透明列表底部透出的
     * 促销蓝背景，又不影响顶部蓝色头部和金刚栏。
     */
    private fun hookCNRecommendView(classLoader: ClassLoader) {
        runCatching {
            val clazz = Class.forName("com.cainiao.wireless.recommend.CNRecommendView", false, classLoader)
            val method = clazz.findMethod { it.name == "initView" } ?: return
            hook(method).intercept { chain ->
                val result = chain.proceed()
                val view = chain.thisObject as? ViewGroup ?: return@intercept result
                for (i in 0 until view.childCount) {
                    view.getChildAt(i).visibility = View.GONE
                }
                result
            }
        }.onFailure { log(Log.ERROR, TAG, "hookCNRecommendView failed", it) }
    }

    private fun hookHomePageActivity(classLoader: ClassLoader) {
        runCatching {
            val clazz = Class.forName("com.cainiao.wireless.homepage.view.activity.HomePageActivity", false, classLoader)
            val method = clazz.findMethod { it.name == "onCreate" && it.parameterTypes.size == 1 } ?: return
            hook(method).intercept { chain ->
                val result = chain.proceed()
                val activity = chain.thisObject as Activity
                val layoutId = activity.resources.getIdentifier("ll_navigation_tab_layout", "id", activity.packageName)
                if (layoutId != 0) {
                    activity.findViewById<LinearLayout>(layoutId)?.apply {
                        // 保留首尾两个 tab(首页、我的)，隐藏中间的 tab/广告
                        for (i in 1 until childCount - 1) {
                            getChildAt(i)?.visibility = View.GONE
                        }
                    }
                }
                syncNavigationBarColor(activity)
                result
            }
        }.onFailure { log(Log.ERROR, TAG, "hookHomePageActivity failed", it) }
    }

    /**
     * 8.10+ 的 V81012 底栏会把 tab 条和系统导航栏刷成同一颜色(浅色=白 / 深色=cn_black / 特殊高亮色)。
     * 这里在 app 每次刷新底栏配色后，把系统导航栏与分割线同步成底栏实际背景色，
     * 避免底栏底部(小白条上方)因颜色断层出现黑线。
     */
    private fun hookNavigationViewStripRefresh(classLoader: ClassLoader) {
        runCatching {
            val clazz = Class.forName("com.cainiao.commonlibrary.navigation.NavigationView", false, classLoader)
            val method = clazz.findMethod { it.name == "refreshTabStripBackground" } ?: return
            hook(method).intercept { chain ->
                val result = chain.proceed()
                syncNavigationBarColor(chain.thisObject as View)
                result
            }
        }.onFailure { log(Log.ERROR, TAG, "hookNavigationViewStripRefresh failed", it) }
    }

    private fun syncNavigationBarColor(view: View) {
        val activity = view.context.findActivity() ?: return
        syncNavigationBarColor(activity)
    }

    private fun syncNavigationBarColor(activity: Activity) {
        runCatching {
            val tabViewId = activity.resources.getIdentifier("navigation_tab_view", "id", activity.packageName)
            val tabLayoutId = activity.resources.getIdentifier("ll_navigation_tab_layout", "id", activity.packageName)
            val color = (activity.findViewById<View>(tabViewId)?.background as? ColorDrawable)?.color
                ?: (activity.findViewById<View>(tabLayoutId)?.background as? ColorDrawable)?.color
                ?: return
            activity.window.apply {
                navigationBarColor = color
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    navigationBarDividerColor = color
                }
            }
        }.onFailure { log(Log.ERROR, TAG, "syncNavigationBarColor failed", it) }
    }

    private fun hookCubeXLinearLayoutFragment(classLoader: ClassLoader) {
        runCatching {
            val clazz = Class.forName("com.cainiao.wireless.cubex.mvvm.view.CubeXLinearLayoutFragment", false, classLoader)
            val method = clazz.findMethod { it.name == "setEmpty" } ?: return
            hook(method).intercept { chain ->
                val jsonArray = chain.getArg(1) as? MutableList<*> ?: return@intercept chain.proceed()
                for (i in jsonArray.size - 1 downTo 1) {
                    jsonArray.removeAt(i)
                }
                chain.proceed()
            }
        }.onFailure { log(Log.ERROR, TAG, "hookCubeXLinearLayoutFragment failed", it) }
    }

    private fun hookLogisticDetailBannerView(classLoader: ClassLoader) {
        runCatching {
            val adsEntityClass = Class.forName("com.taobao.cainiao.logistic.response.model.LdAdsCommonEntity", false, classLoader)
            val clazz = Class.forName("com.taobao.cainiao.logistic.ui.view.component.LogisticDetailBannerView", false, classLoader)
            clazz.findAllMethods { it.parameterTypes.size == 1 && it.parameterTypes[0] == adsEntityClass }
                .forEach { method ->
                    hook(method).intercept { chain ->
                        chain.proceed(arrayOfNulls(1))
                    }
                }
        }.onFailure { log(Log.ERROR, TAG, "hookLogisticDetailBannerView failed", it) }
    }

    private fun hookWelcomeActivity(classLoader: ClassLoader) {
        runCatching {
            val clazz = Class.forName("com.cainiao.wireless.homepage.view.activity.WelcomeActivity", false, classLoader)
            clazz.findAllMethods { it.name == "onCreate" }.forEach { method ->
                hook(method).intercept { chain ->
                    val activity = chain.thisObject as Activity
                    if (activity.intent.getBooleanExtra("isHotLaunch", false)) {
                        activity.finish()
                    }
                    chain.proceed()
                }
            }
            clazz.findAllMethods { it.name == "requestMamaAndRtbSplash" }.forEach { method ->
                hook(method).intercept { false }
            }
        }.onFailure { log(Log.ERROR, TAG, "hookWelcomeActivity failed", it) }
    }

    private fun hookLogisticDetailTemplateFragment(classLoader: ClassLoader) {
        runCatching {
            val clazz = Class.forName("com.taobao.cainiao.logistic.ui.newview.LogisticDetailTemplateFragment", false, classLoader)
            val method = clazz.findMethod { it.name == "updateAdsInfo" } ?: return
            hook(method).intercept { chain ->
                val result = chain.proceed()
                (chain.thisObject.getObjectOrNull("mLogisticRedPacketViewStub") as? View)?.visibility = View.GONE
                result
            }
        }.onFailure { log(Log.ERROR, TAG, "hookLogisticDetailTemplateFragment failed", it) }
    }

    private fun hookHomeHeaderSection(classLoader: ClassLoader) {
        runCatching {
            val clazz = Class.forName("com.cainiao.wireless.homepage.v9.HomeHeaderSection", false, classLoader)
            val method = clazz.findMethod { it.name == "processHeaderData" } ?: return
            hook(method).intercept { chain ->
                val result = chain.proceed()
                val dataArray = chain.getArg(0) // com.alibaba.fastjson.JSONArray
                val size = dataArray.invokeMethodAutoAs<Int>("size") ?: 0
                for (i in size - 1 downTo 1) {
                    dataArray.invokeMethodAutoAs<Any>("remove", i)
                }

                val data0 = dataArray.invokeMethodAutoAs<Any>("get", 0)
                if (data0 != null) {
                    Log.d(TAG, "HomeHeaderSection header data0: $data0")
                    val bizData = data0.invokeMethodAutoAs<Any>("get", "bizData")
                    val items = bizData?.invokeMethodAutoAs<Any>("get", "items")
                    if (items != null) {
                        val itemCount = items.invokeMethodAutoAs<Int>("size") ?: 0
                        for (i in 0 until itemCount) {
                            val item = items.invokeMethodAutoAs<Any>("get", i) ?: continue
                            val key = item.invokeMethodAutoAs<Any>("get", "key")
                            if (key == "exchange_old_things") {
                                Log.d(TAG, "remove exchange_old_things item: $item")
                                items.invokeMethodAutoAs<Any>("remove", i)
                                break
                            }
                        }
                    }
                }
                result
            }
        }.onFailure { log(Log.ERROR, TAG, "hookHomeHeaderSection failed", it) }
    }

    /**
     * 隐藏首页金刚栏(取包裹/寄包裹/物换物/出库码)里的"物换物"入口。
     * 在 setBizData 前把 kingkong 块 items 里 title 含"物换物"的条目删掉，
     * 让 DinamicX 直接用剩余条目渲染，三个按钮天然均分。
     */
    private fun hookHomeHeaderSetBizData(classLoader: ClassLoader) {
        runCatching {
            val clazz = Class.forName("com.cainiao.wireless.homepage.v9.HomeHeaderSection", false, classLoader)
            val method = clazz.findMethod { it.name == "setBizData" && it.parameterCount == 2 } ?: return
            hook(method).intercept { chain ->
                filterKingkongBlocks(chain.getArg(0))
                chain.proceed()
            }
        }.onFailure { log(Log.ERROR, TAG, "hookHomeHeaderSetBizData failed", it) }
    }

    private fun filterKingkongBlocks(dataArray: Any?) {
        if (dataArray == null) return
        runCatching {
            val size = dataArray.invokeMethodAutoAs<Int>("size") ?: 0
            for (i in 0 until size) {
                val block = dataArray.invokeMethodAutoAs<Any>("getJSONObject", i) ?: continue
                val type = block.invokeMethodAutoAs<String>("getString", "type") ?: continue
                if (type != "kingkong") continue
                val bizData = block.invokeMethodAutoAs<Any>("getJSONObject", "bizData") ?: continue
                val items = bizData.invokeMethodAutoAs<Any>("getJSONArray", "items") ?: continue
                val itemCount = items.invokeMethodAutoAs<Int>("size") ?: 0
                for (j in itemCount - 1 downTo 0) {
                    val item = items.invokeMethodAutoAs<Any>("getJSONObject", j) ?: continue
                    val title = item.invokeMethodAutoAs<String>("getString", "title") ?: continue
                    if (title.contains(TARGET_WUHUANWU)) {
                        Log.d(TAG, "kingkong 移除物换物: $title")
                        items.invokeMethodAutoAs<Any>("remove", j)
                    }
                }
            }
        }.onFailure { log(Log.ERROR, TAG, "filterKingkongBlocks failed", it) }
    }

    /**
     * 兜底：DinamicX 若用缓存渲染出"物换物"文字，setText 时立即隐藏所在的
     * 小尺寸 FrameLayout，避免按钮残留。
     */
    private fun hookTextViewHider() {
        runCatching {
            val clazz = TextView::class.java
            clazz.declaredMethods
                .filter {
                    it.name == "setText" &&
                        it.parameterCount >= 1 &&
                        it.parameterTypes[0] == CharSequence::class.java
                }
                .forEach { method ->
                    hook(method).intercept { chain ->
                        val result = chain.proceed()
                        val text = chain.getArg(0)?.toString()
                        if (text != null && text.contains(TARGET_WUHUANWU)) {
                            hideWuhuanwuEntry(chain.thisObject as View)
                        }
                        result
                    }
                }
        }.onFailure { log(Log.ERROR, TAG, "hookTextViewHider failed", it) }
    }

    private fun hideWuhuanwuEntry(textView: View) {
        runCatching {
            val root = textView.rootView ?: return
            val dm = textView.resources.displayMetrics
            var cur: View? = textView.parent as? View
            var target: View? = null
            while (cur != null && cur !== root) {
                if (cur is FrameLayout &&
                    cur.width > 0 && cur.height > 0 &&
                    cur.width < dm.widthPixels * 0.9f &&
                    cur.height < dm.heightPixels * 0.9f
                ) {
                    target = cur
                    break
                }
                cur = cur.parent as? View
            }
            val parent = textView.parent as? View
            if (target == null && parent != null && parent !== root) {
                target = parent
            }
            if (target != null && target !== root && target.visibility != View.GONE) {
                target.visibility = View.GONE
                Log.d(TAG, "setText 拦截隐藏物换物: ${target.javaClass.simpleName}")
            }
        }.onFailure { log(Log.ERROR, TAG, "hideWuhuanwuEntry failed", it) }
    }

    /**
     * 双保险：首页获得焦点后延迟扫描整棵视图树，隐藏任何残留的"物换物"
     * 文字/无障碍描述所在的 FrameLayout。
     */
    private fun hookActivityFocusScanner() {
        runCatching {
            val method = Activity::class.java.findMethod { it.name == "onWindowFocusChanged" && it.parameterCount == 1 }
                ?: return
            hook(method).intercept { chain ->
                val result = chain.proceed()
                val focused = chain.getArg(0) as? Boolean
                val activity = chain.thisObject as? Activity
                if (focused == true && activity != null && activity.javaClass.name == HOME_PAGE_ACTIVITY) {
                    scheduleWuhuanwuScan(activity.window.decorView)
                }
                result
            }
        }.onFailure { log(Log.ERROR, TAG, "hookActivityFocusScanner failed", it) }
    }

    private fun scheduleWuhuanwuScan(rootView: View) {
        if (rootView == null) return
        val handler = Handler(Looper.getMainLooper())
        for (delay in longArrayOf(3000L, 6000L)) {
            handler.postDelayed({
                runCatching {
                    if (!rootView.isAttachedToWindow) return@runCatching
                    hideWuhuanwuInTree(rootView, rootView)
                }.onFailure { log(Log.ERROR, TAG, "wuhuanwu scan failed", it) }
            }, delay)
        }
    }

    private fun hideWuhuanwuInTree(view: View, root: View) {
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                hideWuhuanwuInTree(view.getChildAt(i), root)
            }
        }
        val text = (view as? TextView)?.text?.toString()
        val description = view.contentDescription?.toString()
        if (text?.contains(TARGET_WUHUANWU) != true && description?.contains(TARGET_WUHUANWU) != true) {
            return
        }
        val dm = view.resources.displayMetrics
        var cur: View? = view.parent as? View
        while (cur != null && cur !== root) {
            if (cur is FrameLayout &&
                cur.width > 0 && cur.height > 0 &&
                cur.width < dm.widthPixels * 0.9f &&
                cur.height < dm.heightPixels * 0.9f
            ) {
                if (cur.visibility != View.GONE) {
                    cur.visibility = View.GONE
                    Log.d(TAG, "扫描隐藏物换物: ${cur.javaClass.simpleName}")
                }
                break
            }
            cur = cur.parent as? View
        }
    }

    private fun hookPackageTimeLineDecorateView(classLoader: ClassLoader) {
        runCatching {
            val clazz = Class.forName("com.cainiao.wireless.homepage.view.widget.PackageTimeLineDecorateView", false, classLoader)
            val method = clazz.findMethod { it.name == "setData" } ?: return
            hook(method).intercept { chain ->
                val data = chain.getArg(0)
                if (data != null && data.getObjectOrNull("tagIconList") != null) {
                    val field = data.javaClass.findField { it.name == "tagIconList" } ?: return@intercept chain.proceed()
                    field.isAccessible = true
                    field.set(data, arrayListOf<Any>())
                }
                chain.proceed()
            }
        }.onFailure { log(Log.ERROR, TAG, "hookPackageTimeLineDecorateView failed", it) }
    }

    private fun hookNewBottomFloatBanner(classLoader: ClassLoader) {
        runCatching {
            val clazz = Class.forName("com.cainiao.wireless.homepage.view.widget.bottom.NewBottomFloatBanner", false, classLoader)
            val method = clazz.findMethod { it.name == "init" } ?: return
            hook(method).intercept {
                Log.d(TAG, "NewBottomFloatBanner init")
                null
            }
        }.onFailure { log(Log.ERROR, TAG, "hookNewBottomFloatBanner failed", it) }
    }

    private fun hookDXRecyclerViewAdapter(classLoader: ClassLoader) {
        runCatching {
            val clazz = Class.forName("com.cainiao.wireless.cubex.mvvm.adapter.DXRecyclerViewAdapter", false, classLoader)
            val method = clazz.findMethod { it.name == "setData" } ?: return
            hook(method).intercept { chain ->
                val data = chain.getArg(0) as? MutableList<Any> ?: return@intercept chain.proceed()
                if (data.isEmpty()) return@intercept chain.proceed()
                data.removeIf { i ->
                    // 我的-休闲娱乐
                    i.toString().contains("\"group_id\":\"entertainment\"") ||
                            // 我的底部banner
                            i.toString().contains("\"group_type\":\"bottom_place_holder\"")
                }
                if (data.isEmpty()) return@intercept chain.proceed()
                val last = data.last() as? Map<String, Any> ?: return@intercept chain.proceed()
                val template = last["template"] as? Map<String, Any> ?: return@intercept chain.proceed()
                val name = template["name"] as? String ?: return@intercept chain.proceed()
                if (name == "guoguo_new_my_settings_quit") {
                    if (data.size < 4) return@intercept chain.proceed()
                    val guoguoNewMySettingsItem = data[data.size - 4]
                    Log.d(TAG, "$guoguoNewMySettingsItem")
                    val clone = guoguoNewMySettingsItem.invokeMethod("clone") as? MutableMap<String, Any>
                        ?: return@intercept chain.proceed()
                    val mapperClone = (clone["materialContentMapper"] as? MutableMap<String, Any>)?.invokeMethod("clone")
                        as? MutableMap<String, Any> ?: return@intercept chain.proceed()
                    val aboutFuckCainiao = clone.apply {
                        this["adUtArgs"] = "xxx" // 改这么多也许没必要 但是它工作
                        this["utLdArgs"] = "xxx"
                        this["id"] = "114514"
                        this["materialId"] = "114514"
                        this["pitId"] = "114514"
                        this["materialContentMapper"] = mapperClone
                        mapperClone.apply {
                            this["hasGroupHeader"] = ""
                            this["groupTitle"] = ""
                            this["title"] = "关于 FuckCainiao"
                            this["title_right"] = "版本 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
                            this["type"] = "normal"
                            this["jumpUrl"] = URL_ABOUT_FUCK_CAINIAO
                        }
                    }
                    data.add(data.size - 1, aboutFuckCainiao)
                    Log.d(TAG, "final aboutCainiao\n$aboutFuckCainiao")
                }
                chain.proceed()
            }
        }.onFailure { log(Log.ERROR, TAG, "hookDXRecyclerViewAdapter failed", it) }
    }

    /**
     * 拦截"关于 FuckCainiao"入口的跳转：点击后不跳转、不弹原作者的捐赠提示，
     * 让该按钮完全无响应。
     */
    private fun hookAboutFuckCainiaoIntent() {
        runCatching {
            val method = Activity::class.java.findMethod { it.name == "startActivity" && it.parameterCount == 2 } ?: return
            hook(method).intercept { chain ->
                val intent = chain.getArg(0) as? Intent
                if (intent?.dataString?.startsWith(URL_ABOUT_FUCK_CAINIAO) == true) {
                    Log.d(TAG, "拦截关于 FuckCainiao 跳转")
                    return@intercept null
                }
                chain.proceed()
            }
        }.onFailure { log(Log.ERROR, TAG, "hookAboutFuckCainiaoIntent failed", it) }
    }

    private fun hookLogisticDetailTANXBannerView(classLoader: ClassLoader) {
        runCatching {
            val adsEntityClass = Class.forName("com.taobao.cainiao.logistic.response.model.LdAdsCommonEntity", false, classLoader)
            val clazz = Class.forName("com.taobao.cainiao.logistic.ui.view.component.LogisticDetailTANX_BannerView", false, classLoader)
            val method = clazz.findMethod { it.parameterCount == 1 && it.parameterTypes[0] == adsEntityClass } ?: return
            hook(method).intercept { chain ->
                Log.d(TAG, "LogisticDetailTANX_BannerView hook")
                chain.proceed(arrayOfNulls(1))
            }
        }.onFailure { log(Log.ERROR, TAG, "hookLogisticDetailTANXBannerView failed", it) }
    }

    /**
     * 快递详情页头部的活动推广/通知横幅(LogisticNoticeProtocolView，根视图 R.id.rootLayout)。
     * setData 收到 null 时会自行 setVisibility(GONE)，故把模板参数置空即可隐藏横幅。
     */
    private fun hookLogisticNoticeProtocolView(classLoader: ClassLoader) {
        runCatching {
            val modelClass = Class.forName("com.taobao.cainiao.logistic.js.entity.page.LogisticMtopTemplateModel", false, classLoader)
            val clazz = Class.forName("com.taobao.cainiao.logistic.component.header.LogisticNoticeProtocolView", false, classLoader)
            val method = clazz.findMethod { it.parameterCount == 1 && it.parameterTypes[0] == modelClass } ?: return
            hook(method).intercept { chain ->
                Log.d(TAG, "LogisticNoticeProtocolView setData hook")
                chain.proceed(arrayOfNulls(1))
            }
        }.onFailure { log(Log.ERROR, TAG, "hookLogisticNoticeProtocolView failed", it) }
    }

    /**
     * 快递详情页"会员福利"活动推广横幅(LogisticVipBannerView，根视图 R.id.rootLayout)。
     * setData 解析 model 为 null 时会自行 setVisibility(GONE)，故把模板参数置空即可隐藏。
     */
    private fun hookLogisticVipBannerView(classLoader: ClassLoader) {
        runCatching {
            val modelClass = Class.forName("com.taobao.cainiao.logistic.js.entity.page.LogisticMtopTemplateModel", false, classLoader)
            val clazz = Class.forName("com.taobao.cainiao.logistic.ui.view.protocol_component.LogisticVipBannerView", false, classLoader)
            val method = clazz.findMethod { it.parameterCount == 1 && it.parameterTypes[0] == modelClass } ?: return
            hook(method).intercept { chain ->
                Log.d(TAG, "LogisticVipBannerView setData hook")
                chain.proceed(arrayOfNulls(1))
            }
        }.onFailure { log(Log.ERROR, TAG, "hookLogisticVipBannerView failed", it) }
    }

    /**
     * 拦截"新人送裹酱"等裹酱任务推广弹层：
     * 1. TaskGuideManager.onEvent 是 ACCS 推送入口，直接阻断不再解析展示；
     * 2. GuoJiangChainManager.a/b 是快递详情页裹酱动画弹窗的展示入口，兜底置空。
     */
    private fun hookGuoJiangPop(classLoader: ClassLoader) {
        runCatching {
            val clazz = Class.forName("com.cainiao.wireless.shop.task.guide.TaskGuideManager", false, classLoader)
            val method = clazz.findMethod { it.name == "onEvent" && it.parameterCount == 1 } ?: return
            hook(method).intercept {
                Log.d(TAG, "TaskGuideManager onEvent hook, block guojiang pop")
                null
            }
        }.onFailure { log(Log.ERROR, TAG, "hookTaskGuideManager failed", it) }
        runCatching {
            val clazz = Class.forName("com.cainiao.wireless.shop.task.guide.chain.GuoJiangChainManager", false, classLoader)
            clazz.findAllMethods {
                (it.name == "a" || it.name == "b") && it.parameterCount == 2 && it.parameterTypes[0] == Activity::class.java
            }.forEach { method ->
                hook(method).intercept {
                    Log.d(TAG, "GuoJiangChainManager ${method.name} hook, block guojiang dialog")
                    null
                }
            }
        }.onFailure { log(Log.ERROR, TAG, "hookGuoJiangChainManager failed", it) }
    }

    /**
     * "我的"页是 H5(page.cainiao.com/cn-app-web/profile)，向 WebView 注入清理脚本：
     * 移除"热门活动"卡片(标题文本锚点)和底部轮播推广横幅(热门活动后的最后一张白色圆角卡片)。
     */
    private fun hookProfileH5Cleaner(classLoader: ClassLoader) {
        runCatching {
            val clazz = Class.forName("android.taobao.windvane.extra.uc.WVUCWebView", false, classLoader)
            val method = clazz.findMethod { it.name == "loadUrl" && it.parameterCount == 1 && it.parameterTypes[0] == String::class.java }
                ?: return
            hook(method).intercept { chain ->
                val result = chain.proceed()
                val url = chain.getArg(0) as? String
                if (url != null && url.contains("cn-app-web/profile")) {
                    val webView = chain.thisObject
                    val handler = Handler(Looper.getMainLooper())
                    for (delay in longArrayOf(0L, 300L, 800L, 1500L, 3000L)) {
                        handler.postDelayed({
                            runCatching {
                                webView.invokeEvaluateJs(PROFILE_CLEAN_JS)
                            }.onFailure { log(Log.ERROR, TAG, "profile clean js failed", it) }
                        }, delay)
                    }
                }
                result
            }
        }.onFailure { log(Log.ERROR, TAG, "hookProfileH5Cleaner failed", it) }
    }

    private fun Any.invokeEvaluateJs(js: String) {
        val method = javaClass.methods.firstOrNull {
            it.name == "evaluateJavascript" && it.parameterCount == 2 && it.parameterTypes[0] == String::class.java
        } ?: return
        method.isAccessible = true
        method.invoke(this, js, null)
    }

    private fun hookGuideAdsSection(classLoader: ClassLoader) {
        runCatching {
            val clazz = Class.forName("com.cainiao.wireless.homepage.v9.GuideAdsSection", false, classLoader)
            val method = clazz.findMethod { it.name == "getBizDataCount" } ?: return
            hook(method).intercept {
                // Log.d(TAG, "GuideAdsSection getBizDataCount hook")
                0
            }
        }.onFailure { log(Log.ERROR, TAG, "hookGuideAdsSection failed", it) }
    }

    private fun hookPackageListSection(classLoader: ClassLoader) {
        runCatching {
            val clazz = Class.forName("com.cainiao.wireless.homepage.v9.PackageListSection", false, classLoader)
            val method = clazz.findMethod { it.name == "bindPackageListData" } ?: return
            hook(method).intercept { chain ->
                val data = chain.getArg(0) as MutableList<*>
                data.removeAll {
                    it?.getObjectAs<String>("bizzType") == "PKG_ACTION_CARD"
                }
                chain.proceed()
            }
        }.onFailure { log(Log.ERROR, TAG, "hookPackageListSection failed", it) }
    }
}
