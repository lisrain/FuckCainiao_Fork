package io.github.duzhaokun123.fuckcainiao

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
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
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

    override fun onHotReloading(param: HotReloadingParam): Boolean {
        log(Log.INFO, TAG, "onHotReloading")
        return true
    }

    override fun onHotReloaded(param: HotReloadedParam) {
        // onHotReloaded 拿不到目标包的 classloader，无法重新安装 hook，
        // 因此保留旧 hook 继续生效（默认行为是卸载全部旧 hook），新代码在进程重启后应用。
        log(Log.INFO, TAG, "onHotReloaded: ${param.processName}, keep ${param.oldHookHandles.size} old hooks")
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
