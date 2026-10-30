package io.github.lisrain.fuckcainiao

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

class XposedInit : XposedModule() {
    companion object {
        const val TAG = "FuckCainiao"
        const val URL_ABOUT_FUCK_CAINIAO = "guoguo://go/about_fuck_cainiao"
        const val TARGET_PACKAGE = "com.cainiao.wireless"
        const val HOME_PAGE_ACTIVITY = "com.cainiao.wireless.homepage.view.activity.HomePageActivity"
        const val TARGET_WUHUANWU = "物换物"

        /** 首页「物换物」兜底扫描用的主线程 Handler（复用，别在每次焦点变化时新建）。 */
        private val MAIN_HANDLER = Handler(Looper.getMainLooper())
        private val WUHUANWU_SCAN_DELAYS = longArrayOf(3000L, 6000L)
        private val wuhuanwuScanScheduled = WeakHashMap<View, Boolean>()

        /** 首页 Activity 的 Class 缓存，供 onWindowFocusChanged 这种高频回调做常量时间比较。 */
        @Volatile
        private var homePageClass: Class<*>? = null

        /** 残留升级包每个进程只清一次，避免 Activity 反复创建时重复主线程 IO。 */
        @Volatile
        private var updatePackagePurged = false

        /** 应用内升级提示弹窗（"更新安装包已经下载好"）。 */
        private const val TARGET_UPDATE_KEYWORD = "更新安装包"
        private const val UPDATE_TITLE_STRING = "guoguo_download_done_notify_install"
        private const val UPDATE_TITLE_ID = "gg_head_img_dialog_title"
        private const val POPUP_TYPE_APPUPDATE = "APPUPDATE"

        /** 更新检查的网络层实现（唯一实现类，未混淆）与下载落盘位置。 */
        private const val APP_VERSION_API_IMPL = "com.cainiao.wireless.mvp.model.impl.mtop.AppVersionAPIAtlasImpl"
        private const val APK_UPDATE_TASK = "com.cainiao.wireless.components.update.ApkUpdateTask"
        private const val APK_DOWNLOAD_LISTENER = "com.cainiao.wireless.components.update.ApkUpdateTask\$ApkDownloadListener"
        private const val UPDATE_APK_DIR = "update"
        private const val UPDATE_APK_NAME = "cainiao4android_tmp.apk"

        private const val PROFILE_URL_MARKER = "cn-app-web/profile"
        private val PROFILE_HANDLER = Handler(Looper.getMainLooper())
        private val PROFILE_LOAD_RETRY_DELAYS = longArrayOf(0L, 16L, 64L, 160L, 400L, 900L)
        private val PROFILE_START_RETRY_DELAYS = longArrayOf(0L, 16L, 64L, 160L)

        private val PROFILE_CLEAN_JS = """
(function(){
  var old = window.__fcClean;
  if (String(location.href || '').indexOf('cn-app-web/profile') === -1) return;
  if (old && old.version === 2) {
    old.start();
    old.clean();
    return;
  }
  var container = null;
  var observer = null;
  var started = false;
  var cleaning = false;
  function findContainer(){
    if (container && document.documentElement && document.documentElement.contains(container)) return container;
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
    if (cleaning) return;
    cleaning = true;
    try {
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
    } finally {
      cleaning = false;
    }
  }
  function start(){
    if (started) { clean(); return; }
    var root = document.documentElement;
    if (!root) { setTimeout(start, 0); return; }
    started = true;
    observer = new MutationObserver(clean);
    observer.observe(root, {childList: true, subtree: true});
    clean();
  }
  window.__fcClean = {version: 2, start: start, clean: clean};
  start();
})();
        """.trimIndent()
    }

    private val profileWebViews = WeakHashMap<Any, Boolean>()
    private val evaluateJavascriptMethods = ConcurrentHashMap<Class<*>, Method>()
    private val getUrlMethods = ConcurrentHashMap<Class<*>, Method>()
    private val injectJsEarlyMethods = ConcurrentHashMap<Class<*>, Method>()
    private val earlyInjectedProfileWebViews = WeakHashMap<Any, Boolean>()

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
        hookNavigationTabsRebuild(classLoader)
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
        hookHotGoodsRecommend(classLoader)
        hookHomeBackgroundLottie(classLoader)
        hookUpdateInstallDialog(classLoader)
        hookAppUpdateCheck(classLoader)
        hookApkDownloadTask(classLoader)
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
                activity.window?.decorView?.let { hideMiddleTabs(it) }
                syncNavigationBarColor(activity)
                purgeDownloadedUpdatePackage(activity)
                result
            }
        }.onFailure { log(Log.ERROR, TAG, "hookHomePageActivity failed", it) }
    }

    /**
     * 隐藏底栏中间 tab(保留首尾两个:首页、我的)，供初始渲染和重建时共用。
     */
    private fun hideMiddleTabs(root: View) {
        val layoutId = root.resources.getIdentifier("ll_navigation_tab_layout", "id", root.context.packageName)
        if (layoutId == 0) return
        val layout = root.findViewById<View>(layoutId) as? LinearLayout ?: return
        for (i in 1 until layout.childCount - 1) {
            layout.getChildAt(i)?.visibility = View.GONE
        }
    }

    /**
     * 底栏 tab 的每一次重建(登录成功 refreshNavigations、配置推送、灰度切换等)都会
     * 经 renderNavigationTabs 做 removeAllViews + 重新 addView，此前设置的 GONE 会全部丢失。
     * 这里在重建完成后重新隐藏中间 tab，覆盖首次安装登录后 hook 失效的问题。
     */
    private fun hookNavigationTabsRebuild(classLoader: ClassLoader) {
        runCatching {
            val clazz = Class.forName("com.cainiao.commonlibrary.navigation.NavigationView", false, classLoader)
            val method = clazz.findMethod { it.name == "renderNavigationTabs" && it.parameterCount == 1 } ?: return
            hook(method).intercept { chain ->
                val result = chain.proceed()
                runCatching {
                    val navView = chain.thisObject as? View ?: return@runCatching
                    hideMiddleTabs(navView)
                }.onFailure { log(Log.ERROR, TAG, "hideMiddleTabs after rebuild failed", it) }
                result
            }
        }.onFailure { log(Log.ERROR, TAG, "hookNavigationTabsRebuild failed", it) }
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
                        runCatching {
                            val cs = chain.getArg(0) as? CharSequence ?: return@runCatching
                            // 目标词只有 3 个字：短文本先挡掉，避免下面那次 String 分配。
                            // TextView.setText 是全 App 最高频的调用之一（列表滚动、复用、刷新都走），
                            // 原来的 `getArg(0)?.toString()` 会为每一次 setText 都创建一个 String。
                            if (cs.length < TARGET_WUHUANWU.length) return@runCatching
                            // TextUtils.indexOf 直接在 CharSequence 上匹配，不产生中间 String
                            if (TextUtils.indexOf(cs, TARGET_WUHUANWU) < 0) return@runCatching
                            hideWuhuanwuEntry(chain.thisObject as View)
                        }.onFailure { log(Log.ERROR, TAG, "wuhuanwu text hide failed", it) }
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
                // 用 Class 对象比较，避免每次焦点变化都取一次 class.name 字符串
                if (focused == true && activity != null && isHomePageActivity(activity)) {
                    scheduleWuhuanwuScan(activity.window.decorView)
                }
                result
            }
        }.onFailure { log(Log.ERROR, TAG, "hookActivityFocusScanner failed", it) }
    }

    /** 缓存首页 Activity 的 Class，避免高频回调上反复做字符串比较。 */
    private fun isHomePageActivity(activity: Activity): Boolean {
        val cached = homePageClass
        if (cached != null && activity.javaClass == cached) return true
        if (activity.javaClass.name != HOME_PAGE_ACTIVITY) return false
        homePageClass = activity.javaClass
        return true
    }

    private fun scheduleWuhuanwuScan(rootView: View) {
        // 前后台来回切会反复触发 focus，同一棵装饰树短时间内只排一次扫描。
        synchronized(wuhuanwuScanScheduled) {
            if (wuhuanwuScanScheduled[rootView] == true) return
            wuhuanwuScanScheduled[rootView] = true
        }
        val delays = WUHUANWU_SCAN_DELAYS
        for ((index, delay) in delays.withIndex()) {
            MAIN_HANDLER.postDelayed({
                runCatching {
                    if (rootView.isAttachedToWindow) hideWuhuanwuInTree(rootView, rootView)
                }.onFailure { log(Log.ERROR, TAG, "wuhuanwu scan failed", it) }
                if (index == delays.lastIndex) {
                    synchronized(wuhuanwuScanScheduled) { wuhuanwuScanScheduled.remove(rootView) }
                }
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
                        // 点击行为整体去掉：清空 DinamicX 的 tapEvent，卡片不再注册点击。
                        this["tapEvent"] = arrayListOf<Any>()
                        this["materialContentMapper"] = mapperClone
                        mapperClone.apply {
                            this["hasGroupHeader"] = ""
                            this["groupTitle"] = ""
                            this["title"] = "关于 FuckCainiao"
                            this["title_right"] = "版本 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
                            this["type"] = "normal"
                            // 模板 guoguo_new_my_settings_item(v5) 直接读 mapper 的 jumpUrl 跳转，
                            // 不看顶层 tapEvent。留空串让它判定为空、整行只渲染不响应点击。
                            // hookAboutFuckCainiaoIntent 继续留着兜底。
                            this["jumpUrl"] = ""
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
    /**
     * 兜底：万一「关于 FuckCainiao」的 jumpUrl 又被模板用上，也不让跳转。
     * 主防线在注入条目时把 jumpUrl 置空（点击根本不派发跳转）。
     *
     * `Activity.startActivity` 是每次页面跳转都会走的高频路径，所以先按 Uri 的 scheme
     * 做 O(1) 判断，只有 guoguo:// 的 Intent 才需要 toString 比对。
     */
    private fun hookAboutFuckCainiaoIntent() {
        runCatching {
            val method = Activity::class.java.findMethod { it.name == "startActivity" && it.parameterCount == 2 } ?: return
            hook(method).intercept { chain ->
                val intent = chain.getArg(0) as? Intent
                val data = intent?.data
                if (data != null && data.scheme == "guoguo" && data.toString().startsWith(URL_ABOUT_FUCK_CAINIAO)) {
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
     * 查件页（QueryPackageProActivity → MainPackageSearchFragment）的「今日好物推荐」商品标签网格。
     *
     * 8.11.727 的链路：
     *  MainPackageSearchFragment.initViews()
     *   → if (SearchHotGoodsApi.JL().JN())          // 读 OrangeConfig advertise/can_search_keyword_goods
     *       initSearchHotGoodsView()
     *         → mHomeRecommendHotGoodsView.setHotGoodsClickListener(...)
     *         → SearchHotGoodsApi.JL().a(listener)   // 发起推荐请求
     *
     * 拦在开关处：JN() 只有 initViews 一个调用点，返回 false 后既不初始化也不发请求，
     * 顺带把「搜索关键词商品」组件也一起挡掉（同一开关控制）。
     * 兜底再把这个 View 整体 GONE，避免布局里残留标题和空网格。
     */
    private fun hookHotGoodsRecommend(classLoader: ClassLoader) {
        runCatching {
            val apiClazz = Class.forName("com.cainiao.wireless.ads.api.SearchHotGoodsApi", false, classLoader)
            // JN() 在源码里是混淆名，按结构认：非静态 + 无参 + 返回 boolean，该类中唯一。
            val candidates = apiClazz.declaredMethods.filter {
                !Modifier.isStatic(it.modifiers) &&
                    it.parameterCount == 0 &&
                    it.returnType == java.lang.Boolean.TYPE
            }
            if (candidates.size != 1) {
                log(Log.WARN, TAG, "SearchHotGoodsApi 开关方法候选数为 ${candidates.size}，跳过（无法消歧）")
                return
            }
            hook(candidates[0]).intercept {
                Log.d(TAG, "拦截查件页好物推荐开关")
                false
            }
        }.onFailure { log(Log.ERROR, TAG, "hookHotGoodsRecommend(switch) failed", it) }

        runCatching {
            val viewClazz = Class.forName("com.cainiao.wireless.ads.widget.HomeRecommendHotGoodsView", false, classLoader)
            val attached = viewClazz.findMethod { it.name == "onAttachedToWindow" && it.parameterCount == 0 }
                ?: return@runCatching
            hook(attached).intercept { chain ->
                val result = chain.proceed()
                runCatching { (chain.thisObject as? View)?.visibility = View.GONE }
                    .onFailure { log(Log.ERROR, TAG, "hide HomeRecommendHotGoodsView failed", it) }
                result
            }
        }.onFailure { log(Log.ERROR, TAG, "hookHotGoodsRecommend(view) failed", it) }
    }

    /**
     * 首页背景层里的菜鸟 IP 形象动画（`CNGGLottieView`，bounds 0,436,1200,980）：
     * header 下边界在 533，它只在金刚栏下方露出半个头，看着像活动 UI 的残留。
     *
     * 这个 View 是 initBackground 之后**异步 addView** 上来的，按时间点延迟隐藏会有窗口期
     * （试过 6s 延迟，冷启动时先露好几秒）。改在 onAttachedToWindow 里判断：
     * 祖先链上有 home_page_iv_bg 就直接 GONE —— 挂载时即生效，绘制前就没了，不会闪。
     *
     * 同层还有两个 ImageView（顶部蓝色渐变 + 中部背景），不在这个类上，不受影响。
     */
    private fun hookHomeBackgroundLottie(classLoader: ClassLoader) {
        runCatching {
            val clazz = Class.forName("com.cainiao.wireless.widget.view.CNGGLottieView", false, classLoader)
            val method = clazz.findMethod { it.name == "onAttachedToWindow" && it.parameterCount == 0 } ?: run {
                log(Log.WARN, TAG, "CNGGLottieView.onAttachedToWindow 未找到，首页 IP 动画层跳过")
                return
            }
            hook(method).intercept { chain ->
                val result = chain.proceed()
                runCatching {
                    val view = chain.thisObject as? View ?: return@runCatching
                    if (isInHomeBackground(view)) {
                        view.visibility = View.GONE
                        Log.d(TAG, "挂载即隐藏首页 IP 动画层")
                    }
                }.onFailure { log(Log.ERROR, TAG, "hide home background lottie failed", it) }
                result
            }
        }.onFailure { log(Log.ERROR, TAG, "hookHomeBackgroundLottie failed", it) }
    }

    /** 向上找 8 层，判断这个 View 是否挂在首页背景容器 home_page_iv_bg 下。 */
    private fun isInHomeBackground(view: View): Boolean {
        val targetId = view.resources.getIdentifier("home_page_iv_bg", "id", view.context.packageName)
        if (targetId == 0) return false
        var parent: ViewParent? = view.parent
        var depth = 0
        while (parent is View && depth < 8) {
            if (parent.id == targetId) return true
            parent = parent.parent
            depth++
        }
        return false
    }

    /**
     * 拦截应用内升级提示弹窗「更新安装包已经下载好 / 优化更多细节…」。
     *
     * 8.11.727 的链路：
     *  ApkUpdateTask 下载完成 → components.update.a.e(bool,bool) → postTask(a$2)
     *  → new kw(ctx).kf(标题).kg(正文).a(取消).b(确定) → kp.Hj()
     *  → new GuoguoCommonDialog(ctx) + setContentView(GGTextDialogContentView) → show()
     *
     * kw / kp / a$2 全是混淆名（R8 每版重排），不能写死；而 GuoguoCommonDialog 是
     * commonlibrary 里未混淆的公共弹窗类，show() 也是未混淆的 public 方法，
     * 所有 Guoguo 弹窗都汇聚到这里，所以在 show() 处按标题文本 + PopupType 拦截。
     */
    private fun hookUpdateInstallDialog(classLoader: ClassLoader) {
        runCatching {
            val clazz = Class.forName("com.cainiao.commonlibrary.popupui.dialog.GuoguoCommonDialog", false, classLoader)
            val method = clazz.findMethod { it.name == "show" && it.parameterCount == 0 } ?: run {
                log(Log.WARN, TAG, "GuoguoCommonDialog.show 未找到，升级弹窗拦截跳过")
                return
            }
            hook(method).intercept { chain ->
                val dialog = chain.thisObject
                val blocked = runCatching { isUpdateInstallDialog(dialog) }
                    .onFailure { log(Log.ERROR, TAG, "isUpdateInstallDialog failed", it) }
                    .getOrDefault(false)
                if (blocked) {
                    log(Log.INFO, TAG, "拦截更新安装包弹窗")
                    return@intercept null
                }
                chain.proceed()
            }
        }.onFailure { log(Log.ERROR, TAG, "hookUpdateInstallDialog failed", it) }
    }

    /**
     * 读取 Guoguo 弹窗内容区的标题文案；内容区不是 GGText 系或标题未渲染时返回 null。
     */
    private fun readDialogTitle(dialog: Any): String? {
        val contentView = dialog.invokeMethod("Hq") as? View ?: return null
        val titleViewId = contentView.resources.getIdentifier(
            UPDATE_TITLE_ID, "id", contentView.context.packageName
        )
        if (titleViewId == 0) return null
        return (contentView.findViewById<View>(titleViewId) as? TextView)?.text?.toString()
    }

    /**
     * 判定当前 Guoguo 弹窗是否为升级提示：
     * 1. 走 popupmanager 队列时会被显式标记 PopupType.APPUPDATE；
     * 2. 直接 show 的路径没有标记，此时读内容区标题文案，与资源
     *    guoguo_download_done_notify_install 比对（资源名变了再退回关键词）。
     */
    private fun isUpdateInstallDialog(dialog: Any): Boolean {
        val popupType = dialog.invokeMethod("obtainType")
        if (popupType?.toString() == POPUP_TYPE_APPUPDATE) return true

        val contentView = dialog.invokeMethod("Hq") as? View ?: return false
        val res = contentView.resources
        val pkg = contentView.context.packageName

        val titleViewId = res.getIdentifier(UPDATE_TITLE_ID, "id", pkg)
        if (titleViewId == 0) return false
        val title = (contentView.findViewById<View>(titleViewId) as? TextView)?.text?.toString()
        if (title.isNullOrBlank()) return false

        val declared = res.getIdentifier(UPDATE_TITLE_STRING, "string", pkg)
            .takeIf { it != 0 }
            ?.let { runCatching { res.getString(it) }.getOrNull() }
        if (declared != null && title.trim() == declared.trim()) return true

        return title.contains(TARGET_UPDATE_KEYWORD)
    }

    /**
     * 掐断 App 更新检查请求。
     *
     * `AppVersionAPIAtlasImpl.checkUpdate(AppVersionInfoListener)` 是更新检查唯一的网络出口，
     * 构造 `MtopAtlasGetBaseUpdateListRequest`（带机型/城市/utdid/userId/cpuArch）后发出去。
     * 全部 4 个调用方都汇聚到这里：
     *   - `components.update.c.checkUpdate(Context)` / `c.a(Context, RemindType)`：常规自动检查
     *   - `Initscheduler.initjob.j.aeT()`：启动 initjob
     *   - `cubex.utils.ab.evalWithArgs`：DinamicX 模板里触发的检查
     *
     * 直接不执行 = App 永远不知道有新版本：不请求、不下载、不弹窗，也不再上传那份设备信息。
     * 回调用的是混淆类 `components.event.d`，构造不出来，只能静默跳过；已实测上层不卡。
     */
    private fun hookAppUpdateCheck(classLoader: ClassLoader) {
        runCatching {
            val clazz = Class.forName(APP_VERSION_API_IMPL, false, classLoader)
            val method = clazz.findMethod { it.name == "checkUpdate" && it.parameterCount == 1 } ?: run {
                log(Log.WARN, TAG, "AppVersionAPIAtlasImpl.checkUpdate 未找到，更新检查拦截跳过")
                return
            }
            hook(method).intercept {
                log(Log.INFO, TAG, "拦截 App 更新检查请求")
                null
            }
        }.onFailure { log(Log.ERROR, TAG, "hookAppUpdateCheck failed", it) }
    }

    /**
     * 兜底：即使有未覆盖的路径把下载任务排进了队列，也不让它真的落盘。
     *
     * `ApkUpdateTask.run()` 只有一句 `eb(aiH())`：`aiH()` 用 downloadUrl 开
     * HttpURLConnection 拉 129MB 的 apk，`eb()` 把结果回调给 listener。整段跳过，
     * 但补一次 `downloadDone(false)`，让上层按「下载失败」收尾而不是停在「下载中」。
     */
    private fun hookApkDownloadTask(classLoader: ClassLoader) {
        runCatching {
            val clazz = Class.forName(APK_UPDATE_TASK, false, classLoader)
            val method = clazz.findMethod { it.name == "run" && it.parameterCount == 0 } ?: run {
                log(Log.WARN, TAG, "ApkUpdateTask.run 未找到，下载拦截跳过")
                return
            }
            hook(method).intercept { chain ->
                log(Log.INFO, TAG, "拦截 APK 下载任务，模拟下载失败")
                runCatching { notifyDownloadFailed(chain.thisObject) }
                    .onFailure { log(Log.ERROR, TAG, "notifyDownloadFailed failed", it) }
                null
            }
        }.onFailure { log(Log.ERROR, TAG, "hookApkDownloadTask failed", it) }
    }

    /**
     * 回调 `ApkUpdateTask.ApkDownloadListener.downloadDone(false)`。
     * listener 字段名是混淆的，按「类型是 ApkDownloadListener」结构匹配。
     */
    private fun notifyDownloadFailed(task: Any) {
        val listenerClass = Class.forName(APK_DOWNLOAD_LISTENER, false, task.javaClass.classLoader)
        val field = task.javaClass.declaredFields.firstOrNull {
            listenerClass.isAssignableFrom(it.type)
        } ?: return
        field.isAccessible = true
        val listener = field.get(task) ?: return
        listener.invokeMethod("downloadDone", false)
    }

    /**
     * 删掉历史遗留的已下载安装包。
     *
     * 拦掉检查后不会再下新的，但升级前已经下载好的那份会一直躺在
     * `<外部私有目录>/update/cainiao4android_tmp.apk`（实测 129MB）。
     * 文件还在时弹窗逻辑依然会被触发，所以顺手清理。
     */
    private fun purgeDownloadedUpdatePackage(context: Context) {
        // HomePageActivity 可能被反复创建，文件检查是主线程 IO，每个进程只做一次。
        if (updatePackagePurged) return
        updatePackagePurged = true
        runCatching {
            val dir = context.getExternalFilesDir(UPDATE_APK_DIR) ?: return
            val apk = File(dir, UPDATE_APK_NAME)
            if (!apk.exists()) return
            val size = apk.length()
            if (apk.delete()) {
                log(Log.INFO, TAG, "已删除残留更新包: ${apk.absolutePath} ($size bytes)")
            } else {
                log(Log.WARN, TAG, "残留更新包删除失败: ${apk.absolutePath}")
            }
        }.onFailure { log(Log.ERROR, TAG, "purgeDownloadedUpdatePackage failed", it) }
    }

    /**
     * "我的"页是 H5(page.cainiao.com/cn-app-web/profile)，向 WebView 注入清理脚本：
     * 移除"热门活动"卡片(标题文本锚点)和底部轮播推广横幅(热门活动后的最后一张白色圆角卡片)。
     */
    /**
     * "我的"页是 H5(page.cainiao.com/cn-app-web/profile)，向 WebView 注入清理脚本：
     * 移除"热门活动"卡片(标题文本锚点)和底部轮播推广横幅(热门活动后的最后一张白色圆角卡片)。
     *
     * 注入点分四路，覆盖 WindVane WebView 的各种加载方式与复用场景：
     * `loadUrl` / `loadDataWithBaseURL`（导航发起）、`onPageStarted` / `onPageFinished`
     * （document 已建立）、WebView 自身生命周期（复用到前台）、以及 `injectJsEarly`
     * （随 UC document 创建阶段执行，最早期）。
     */
    private fun hookProfileH5Cleaner(classLoader: ClassLoader) {
        runCatching {
            val clazz = Class.forName("android.taobao.windvane.extra.uc.WVUCWebView", false, classLoader)

            hookProfileLoadMethods(clazz)
            hookProfileWebViewLifecycle(clazz)
            hookProfilePageLifecycle(classLoader, clazz)

            log(Log.INFO, TAG, "profile H5 cleaner hooks installed")
        }.onFailure { log(Log.ERROR, TAG, "hookProfileH5Cleaner failed", it) }
    }

    /**
     * WindVane 首次登录后可能使用 loadUrl(String, headers) 而非单参数重载。
     * 所有首参数为 String 的 loadUrl 都挂上，导航返回后马上排一组前密后疏的注入。
     */
    private fun hookProfileLoadMethods(webViewClass: Class<*>) {
        webViewClass.findAllMethods {
            it.name == "loadUrl" &&
                it.parameterCount >= 1 &&
                it.parameterTypes[0] == String::class.java
        }.forEach { method ->
            hook(method).intercept { chain ->
                val webView = chain.thisObject
                val url = chain.getArg(0) as? String
                updateProfileState(webView, url)
                if (isProfileUrl(url)) {
                    ensureProfileEarlyInjection(webView)
                }
                val result = chain.proceed()
                if (isProfileUrl(url)) {
                    scheduleProfileInjection(webView, PROFILE_LOAD_RETRY_DELAYS)
                }
                result
            }
        }

        webViewClass.findAllMethods {
            it.name == "loadDataWithBaseURL" &&
                it.parameterCount >= 1 &&
                it.parameterTypes[0] == String::class.java
        }.forEach { method ->
            hook(method).intercept { chain ->
                val webView = chain.thisObject
                val baseUrl = chain.getArg(0) as? String
                updateProfileState(webView, baseUrl)
                if (isProfileUrl(baseUrl)) {
                    ensureProfileEarlyInjection(webView)
                }
                val result = chain.proceed()
                if (isProfileUrl(baseUrl)) {
                    scheduleProfileInjection(webView, PROFILE_LOAD_RETRY_DELAYS)
                }
                result
            }
        }
    }

    /**
     * 复用/预加载 WebView 切到前台时再立即唤醒脚本，覆盖底栏切页没有重新 loadUrl 的情况。
     * 只挂 WVUCWebView 自己覆写的方法，避免 Hook View 基类给全进程增加开销。
     */
    private fun hookProfileWebViewLifecycle(webViewClass: Class<*>) {
        val lifecycleNames = setOf("onResume", "onAttachedToWindow", "coreOnVisibilityChanged")
        webViewClass.declaredMethods
            .filter { it.name in lifecycleNames }
            .forEach { method ->
                hook(method).intercept { chain ->
                    val result = chain.proceed()
                    val webView = chain.thisObject
                    val becameVisible = method.name != "coreOnVisibilityChanged" ||
                        chain.getArg(1) == View.VISIBLE
                    if (becameVisible && isCurrentProfileWebView(webView)) {
                        scheduleProfileInjection(webView, PROFILE_START_RETRY_DELAYS)
                    }
                    result
                }
            }
    }

    /**
     * loadUrl 只能看到导航发起；onPageStarted 才是新 document 已建立的稳定注入点。
     * onPageFinished 再做一次兜底，适配登录重定向和 WindVane WebView 复用。
     */
    private fun hookProfilePageLifecycle(classLoader: ClassLoader, webViewClass: Class<*>) {
        val clientClass = Class.forName(
            "android.taobao.windvane.extra.uc.WVUCWebViewClient",
            false,
            classLoader
        )
        clientClass.findAllMethods {
            (it.name == "onPageStarted" || it.name == "onPageFinished") &&
                it.parameterCount >= 2
        }.forEach { method ->
            hook(method).intercept { chain ->
                val result = chain.proceed()
                var webView: Any? = null
                var url: String? = null
                for (index in 0 until method.parameterCount) {
                    val arg = chain.getArg(index)
                    if (webView == null && arg != null && webViewClass.isInstance(arg)) {
                        webView = arg
                    }
                    if (url == null && arg is String) {
                        url = arg
                    }
                }
                if (webView != null) {
                    updateProfileState(webView, url)
                    if (isProfileUrl(url) || isCurrentProfileWebView(webView)) {
                        ensureProfileEarlyInjection(webView)
                        val delays = if (method.name == "onPageStarted") {
                            PROFILE_START_RETRY_DELAYS
                        } else {
                            longArrayOf(0L)
                        }
                        scheduleProfileInjection(webView, delays)
                    }
                }
                result
            }
        }
    }

    private fun scheduleProfileInjection(webView: Any, delays: LongArray) {
        for (delay in delays) {
            PROFILE_HANDLER.postDelayed({
                if (!isCurrentProfileWebView(webView)) return@postDelayed
                runCatching {
                    webView.invokeEvaluateJs(PROFILE_CLEAN_JS)
                }.onFailure { log(Log.ERROR, TAG, "profile clean js failed", it) }
            }, delay)
        }
    }

    private fun updateProfileState(webView: Any, url: String?) {
        if (url.isNullOrBlank() || url.startsWith("javascript:")) return
        synchronized(profileWebViews) {
            profileWebViews[webView] = isProfileUrl(url)
        }
    }

    private fun isCurrentProfileWebView(webView: Any): Boolean {
        val currentUrl = webView.invokeCurrentUrl()
        if (!currentUrl.isNullOrBlank() && currentUrl != "about:blank") {
            updateProfileState(webView, currentUrl)
            return isProfileUrl(currentUrl)
        }
        return synchronized(profileWebViews) {
            profileWebViews[webView] == true
        }
    }

    private fun isProfileUrl(url: String?): Boolean =
        url?.contains(PROFILE_URL_MARKER, ignoreCase = true) == true

    private fun Any.invokeCurrentUrl(): String? {
        return runCatching {
            val method = getUrlMethods[javaClass] ?: (
                javaClass.methods.firstOrNull {
                    it.name == "getCurrentUrl" && it.parameterCount == 0
                } ?: javaClass.methods.firstOrNull {
                    it.name == "getUrl" && it.parameterCount == 0
                }
            )?.also { getUrlMethods[javaClass] = it } ?: return@runCatching null
            method.invoke(this) as? String
        }.getOrNull()
    }

    /**
     * WVUCWebView 的真实早期注入入口，脚本会随 UC document 创建阶段执行。
     * 该入口会累积脚本，所以同一个 WebView 只注册一次，后续页面通过 URL 判断是否执行。
     */
    private fun ensureProfileEarlyInjection(webView: Any) {
        synchronized(earlyInjectedProfileWebViews) {
            if (earlyInjectedProfileWebViews[webView] == true) return
            earlyInjectedProfileWebViews[webView] = true
        }
        val success = runCatching {
            val method = injectJsEarlyMethods[webView.javaClass] ?: webView.javaClass.methods.firstOrNull {
                it.name == "injectJsEarly" &&
                    it.parameterCount == 1 &&
                    it.parameterTypes[0] == String::class.java
            }?.also { injectJsEarlyMethods[webView.javaClass] = it } ?: return@runCatching false
            method.isAccessible = true
            method.invoke(webView, PROFILE_CLEAN_JS)
            true
        }.getOrElse {
            log(Log.ERROR, TAG, "profile early JS injection failed", it)
            false
        }
        if (!success) {
            synchronized(earlyInjectedProfileWebViews) {
                earlyInjectedProfileWebViews.remove(webView)
            }
        }
    }

    private fun Any.invokeEvaluateJs(js: String) {
        runCatching {
            val method = evaluateJavascriptMethods[javaClass] ?: javaClass.methods.firstOrNull {
                it.name == "evaluateJavascript" && it.parameterCount == 2 && it.parameterTypes[0] == String::class.java
            }?.also { evaluateJavascriptMethods[javaClass] = it } ?: return@runCatching
            method.isAccessible = true
            method.invoke(this, js, null)
        }.onFailure { log(Log.ERROR, TAG, "evaluateJavascript invoke failed", it) }
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
