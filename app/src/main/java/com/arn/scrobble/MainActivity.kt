package com.arn.scrobble

import android.animation.ValueAnimator
import android.app.ActivityManager
import android.app.Notification.INTENT_CATEGORY_NOTIFICATION_PREFERENCES
import android.content.*
import android.content.pm.LabeledIntent
import android.media.session.MediaSessionManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MenuItem
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ImageButton
import androidx.activity.viewModels
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.FileProvider
import androidx.core.view.GravityCompat
import androidx.core.view.children
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import androidx.viewpager.widget.ViewPager
import coil.Coil
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.imageLoader
import coil.load
import coil.size.Precision
import com.arn.scrobble.LocaleUtils.getLocaleContextWrapper
import com.arn.scrobble.Stuff.getScrobblerExitReasons
import com.arn.scrobble.billing.BillingFragment
import com.arn.scrobble.billing.BillingViewModel
import com.arn.scrobble.databinding.ActivityMainBinding
import com.arn.scrobble.databinding.HeaderNavBinding
import com.arn.scrobble.db.PanoDb
import com.arn.scrobble.info.InfoFragment
import com.arn.scrobble.pending.PendingScrService
import com.arn.scrobble.pref.AppListFragment
import com.arn.scrobble.pref.MainPrefs
import com.arn.scrobble.pref.PrefFragment
import com.arn.scrobble.search.SearchExperimentFragment
import com.arn.scrobble.search.SearchFragment
import com.arn.scrobble.themes.ColorPatchUtils
import com.arn.scrobble.ui.*
import com.arn.scrobble.ui.UiUtils.isTv
import com.arn.scrobble.ui.UiUtils.memoryCacheKey
import com.arn.scrobble.ui.UiUtils.openInBrowser
import com.arn.scrobble.ui.UiUtils.popBackStackTill
import com.arn.scrobble.ui.UiUtils.toast
import com.google.android.material.internal.NavigationMenuItemView
import com.google.android.material.navigation.NavigationView
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.text.NumberFormat
import java.util.*


class MainActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener,
    FragmentManager.OnBackStackChangedListener {

    private lateinit var toggle: ActionBarDrawerToggle
    private val prefs by lazy { MainPrefs(this) }
    private var lastDrawerOpenTime = 0L
    private var backArrowShown = false
    var coordinatorPadding = 0
    private var drawerInited = false
    var pendingSubmitAttempted = false
    lateinit var binding: ActivityMainBinding
    private lateinit var navHeaderbinding: HeaderNavBinding
    private lateinit var connectivityCb: ConnectivityManager.NetworkCallback
    val billingViewModel by viewModels<BillingViewModel>()
    val mainNotifierViewModel by viewModels<MainNotifierViewModel>()
    private val npReceiver by lazy { NPReceiver(mainNotifierViewModel) }

    override fun onCreate(savedInstanceState: Bundle?) {
        Stuff.timeIt("onCreate start")
        super.onCreate(savedInstanceState)

        ColorPatchUtils.setTheme(this, billingViewModel.proStatus.value == true)

        binding = ActivityMainBinding.inflate(layoutInflater)
        navHeaderbinding = HeaderNavBinding.inflate(layoutInflater, binding.navView, false)
        binding.navView.addHeaderView(navHeaderbinding.root)

//        if (resources.getDimension(R.dimen.coordinator_padding) > 0)
        binding.navView.inflateMenu(R.menu.homepager_menu)

        binding.navView.inflateMenu(R.menu.nav_menu)
        binding.drawerLayout.drawerElevation = 0f
        setContentView(binding.root)
        Stuff.timeIt("onCreate setContentView")
        setSupportActionBar(binding.coordinatorMain.toolbar)
        supportActionBar!!.setDisplayHomeAsUpEnabled(true)

        coordinatorPadding = binding.coordinatorMain.coordinator.paddingStart


        val imageLoader = ImageLoader.Builder(applicationContext)
            .components {
                add(AppIconKeyer())
                add(AppIconFetcher.Factory())
                add(MusicEntryImageInterceptor())
                add(StarInterceptor())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }

                if (Stuff.DEMO_MODE)
                    add(DemoInterceptor())
            }
            .crossfade(Stuff.CROSSFADE_DURATION)
            .precision(Precision.INEXACT)
            .build()
        Coil.setImageLoader(imageLoader)

        binding.coordinatorMain.appBar.onStateChangeListener = { state ->

            when (state) {
                StatefulAppBar.EXPANDED -> {
                    binding.coordinatorMain.toolbar.title = null
                    binding.coordinatorMain.tabBar.visibility = View.GONE
                }
                StatefulAppBar.IDLE -> {
                    binding.coordinatorMain.tabBar.visibility = View.GONE
                }
                StatefulAppBar.COLLAPSED -> {
                    if (supportFragmentManager.findFragmentByTag(Stuff.TAG_HOME_PAGER)?.isVisible == true ||
                        supportFragmentManager.findFragmentByTag(Stuff.TAG_CHART_PAGER)?.isVisible == true
                    ) {
                        binding.coordinatorMain.tabBar.visibility = View.VISIBLE
                    } else {
                        binding.coordinatorMain.tabBar.visibility = View.GONE
                    }
                }
            }
        }

        toggle = object : ActionBarDrawerToggle(
            this,
            binding.drawerLayout,
            R.string.navigation_drawer_open,
            R.string.navigation_drawer_close
        ) {
            override fun onDrawerOpened(drawerView: View) {
                mainNotifierViewModel.drawerData.value?.let {
                    this@MainActivity.onDrawerOpened()
                }
            }
        }
        toggle.drawerArrowDrawable =
            ShadowDrawerArrowDrawable(drawerToggleDelegate?.actionBarThemedContext)

        if (isTv) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
                binding.coordinatorMain.toolbar.children
                    .find { it is ImageButton }
                    ?.isFocusable = false
        }
        binding.drawerLayout.addDrawerListener(toggle)
        binding.navView.setNavigationItemSelectedListener(this)

        val hidePassBox =
            if (intent.data?.isHierarchical == true && intent.data?.path == "/testFirstThings") {
                prefs.lastfmSessKey = null
                true
            } else
                false

        if (savedInstanceState == null) {
            if (FirstThingsFragment.checkAuthTokenExists(prefs) &&
                FirstThingsFragment.checkNLAccess(this)
            ) {

                var directOpenExtra = intent?.getIntExtra(Stuff.DIRECT_OPEN_KEY, 0) ?: 0
                if (intent?.categories?.contains(INTENT_CATEGORY_NOTIFICATION_PREFERENCES) == true)
                    directOpenExtra = Stuff.DL_SETTINGS

                when (directOpenExtra) {
                    Stuff.DL_SETTINGS -> supportFragmentManager.beginTransaction()
                        .replace(R.id.frame, PrefFragment())
                        .addToBackStack(null)
                        .commit()
                    Stuff.DL_APP_LIST -> supportFragmentManager.beginTransaction()
                        .replace(R.id.frame, AppListFragment())
                        .addToBackStack(null)
                        .commit()
                    Stuff.DL_MIC -> supportFragmentManager.beginTransaction()
                        .replace(R.id.frame, RecFragment())
                        .addToBackStack(null)
                        .commit()
                    Stuff.DL_SEARCH -> supportFragmentManager.beginTransaction()
                        .replace(R.id.frame, SearchFragment())
                        .addToBackStack(null)
                        .commit()
                    Stuff.DL_PRO -> supportFragmentManager.beginTransaction()
                        .replace(R.id.frame, BillingFragment())
                        .addToBackStack(null)
                        .commit()
                    else -> {
                        if (coordinatorPadding > 0)
                            binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED) //for some devices
                        showHomePager()

                        if (!handleDeepLink(intent)) {
                            if (intent.getStringExtra(NLService.B_ARTIST) != null)
                                showInfoFragment(intent)
                            else {
                                val wasKilled = !Stuff.isScrobblerRunning(this)

                                if (wasKilled) {
                                    showNotRunning()
                                } else if (!isTv && billingViewModel.proStatus.value != true) {
                                    AppRater(this, prefs).appLaunched()
                                    Updater(this, prefs).withSnackbar()
                                }
                            }
                        }
                    }
                }
            } else {
                showFirstThings(hidePassBox)
            }
        } else {
            binding.coordinatorMain.tabBar.visibility =
                savedInstanceState.getInt("tab_bar_visible", View.GONE)
            if (supportFragmentManager.findFragmentByTag(Stuff.TAG_HOME_PAGER)?.isAdded == true &&
                supportFragmentManager.backStackEntryCount == 0
            )
                openLockDrawer()
        }
        supportFragmentManager.addOnBackStackChangedListener(this)
        billingViewModel.proStatus.observe(this) {
            if (it == true) {
                binding.navView.menu.removeItem(R.id.nav_pro)
            }
        }
        billingViewModel.queryPurchases()
        mainNotifierViewModel.drawerData.observe(this) { drawerData ->
            drawerData ?: return@observe
            val nf = NumberFormat.getInstance()
            navHeaderbinding.navNumScrobbles.text = getString(
                R.string.num_scrobbles_nav,
                nf.format(drawerData.scrobblesTotal), nf.format(drawerData.scrobblesToday)
            )

            if (navHeaderbinding.navProfilePic.tag != drawerData.profilePicUrl) // prevent flash
                navHeaderbinding.navProfilePic.load(drawerData.profilePicUrl) {
                    placeholderMemoryCacheKey(navHeaderbinding.navProfilePic.memoryCacheKey)
                    error(
                        InitialsDrawable(
                            this@MainActivity,
                            prefs.lastfmUsername ?: "nobody",
                            colorFromHash = false
                        )
                    )
                    listener(
                        onSuccess = { _, _ ->
                            navHeaderbinding.navProfilePic.tag = drawerData.profilePicUrl
                        },
                        onError = { _, _ ->
                            navHeaderbinding.navProfilePic.tag = drawerData.profilePicUrl
                        }
                    )
                }
        }

        if (prefs.proStatus && prefs.showScrobbleSources) {
            val filter = IntentFilter().apply {
                addAction(NLService.iNOW_PLAYING_INFO_S)
            }
            registerReceiver(npReceiver, filter, NLService.BROADCAST_PERMISSION, null)
            sendBroadcast(
                Intent(NLService.iNOW_PLAYING_INFO_REQUEST_S),
                NLService.BROADCAST_PERMISSION
            )
        }
//        showNotRunning()
    }

    fun showHomePager() {
        openLockDrawer()
        supportFragmentManager.beginTransaction()
            .replace(R.id.frame, HomePagerFragment(), Stuff.TAG_HOME_PAGER)
            .commit()
    }

    private fun showFirstThings(hidePassBox: Boolean) {
        val f = FirstThingsFragment()
        f.arguments = Bundle().apply {
            putBoolean(Stuff.ARG_NOPASS, hidePassBox)
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.frame, f, Stuff.TAG_FIRST_THINGS)
            .commit()
        binding.coordinatorMain.appBar.setExpanded(expanded = false, animate = true)
        closeLockDrawer()
    }

    private fun showInfoFragment(intent: Intent) {
        val artist = intent.getStringExtra(NLService.B_ARTIST)
        val album = intent.getStringExtra(NLService.B_ALBUM)
        val track = intent.getStringExtra(NLService.B_TRACK)
        val info = InfoFragment()
        info.arguments = Bundle().apply {
            putString(NLService.B_ARTIST, artist)
            putString(NLService.B_ALBUM, album)
            putString(NLService.B_TRACK, track)
        }
        supportFragmentManager.findFragmentByTag(Stuff.TAG_INFO_FROM_WIDGET)?.let {
            (it as InfoFragment).dismiss()
        }
        info.show(supportFragmentManager, Stuff.TAG_INFO_FROM_WIDGET)
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        toggle.syncState()
        val lockMode = binding.drawerLayout.getDrawerLockMode(GravityCompat.START)
        backArrowShown = lockMode == DrawerLayout.LOCK_MODE_LOCKED_CLOSED
        toggle.onDrawerSlide(binding.drawerLayout, if (backArrowShown) 1f else 0f)

        Stuff.timeIt("onPostCreate")
    }

    private fun onDrawerOpened() {
        if (!binding.drawerLayout.isDrawerVisible(GravityCompat.START) || (
                    System.currentTimeMillis() - lastDrawerOpenTime < Stuff.RECENTS_REFRESH_INTERVAL)
        )
            return

        LFMRequester(
            applicationContext,
            lifecycleScope,
            mainNotifierViewModel.drawerData
        ).getDrawerInfo()

        val username = prefs.lastfmUsername ?: "nobody"
        val displayUsername = if (Stuff.DEMO_MODE) "nobody" else username
        if (navHeaderbinding.navName.tag == null)
            navHeaderbinding.navName.text = displayUsername

        navHeaderbinding.navProfileLinks.setOnClickListener {
            val servicesToUrls = mutableMapOf</*@StringRes */Int, String>()

            prefs.lastfmUsername?.let {
                servicesToUrls[R.string.lastfm] = "https://www.last.fm/user/$it"
                servicesToUrls[R.string.lastfm_reports] =
                    "https://www.last.fm/user/$it/listening-report/week"
            }
            prefs.librefmUsername?.let {
                servicesToUrls[R.string.librefm] = "https://www.libre.fm/user/$it"
            }
            prefs.gnufmUsername?.let {
                servicesToUrls[R.string.gnufm] = prefs.gnufmRoot + "user/$it"
            }
            prefs.listenbrainzUsername?.let {
                servicesToUrls[R.string.listenbrainz] = "https://listenbrainz.org/user/$it"
            }
            prefs.customListenbrainzUsername?.let {
                servicesToUrls[R.string.custom_listenbrainz] =
                    prefs.customListenbrainzRoot + "user/$it"
            }

            if (servicesToUrls.size == 1)
                openInBrowser(servicesToUrls.values.first())
            else {
                val popup = PopupMenu(this, it)
                servicesToUrls.forEach { (strRes, url) ->
                    val title = if (strRes == R.string.lastfm_reports)
                        getString(strRes)
                    else
                        getString(strRes) + " " + getString(R.string.profile)

                    popup.menu.add(0, strRes, 0, title)
                }

                popup.setOnMenuItemClickListener { menuItem ->
                    openInBrowser(servicesToUrls[menuItem.itemId]!!)
                    true
                }
                popup.show()
            }
        }

        lastDrawerOpenTime = System.currentTimeMillis()

        if (navHeaderbinding.navName.tag == null) {
            val cal = Calendar.getInstance()
            val c = (cal[Calendar.MONTH] == 11 && cal[Calendar.DAY_OF_MONTH] >= 25) ||
                    (cal[Calendar.MONTH] == 0 && cal[Calendar.DAY_OF_MONTH] <= 5)
            if (!c)
                return
            navHeaderbinding.navName.tag = "☃️"
            lifecycleScope.launch {
                while (true) {
                    if (navHeaderbinding.navName.tag == "☃️")
                        navHeaderbinding.navName.tag = "⛄️"
                    else
                        navHeaderbinding.navName.tag = "☃️"
                    navHeaderbinding.navName.text =
                        (navHeaderbinding.navName.tag as String) + displayUsername + "\uD83C\uDF84"

                    delay(500)
                }
            }
        }
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        if (coordinatorPadding == 0)
            binding.drawerLayout.closeDrawer(GravityCompat.START)

        when (item.itemId) {
            R.id.nav_recents -> {
                binding.coordinatorMain.tabBar.getTabAt(0)?.select()
            }
            R.id.nav_loved -> {
                binding.coordinatorMain.tabBar.getTabAt(1)?.select()
            }
            R.id.nav_friends -> {
                binding.coordinatorMain.tabBar.getTabAt(2)?.select()
            }
            R.id.nav_charts -> {
                binding.coordinatorMain.tabBar.getTabAt(3)?.select()
            }
            R.id.nav_random -> {
                enableGestures()
                supportFragmentManager.beginTransaction()
                    .replace(R.id.frame, RandomFragment())
                    .addToBackStack(null)
                    .commit()
            }
            R.id.nav_rec -> {
                enableGestures()
                supportFragmentManager.beginTransaction()
                    .replace(R.id.frame, RecFragment())
                    .addToBackStack(null)
                    .commit()
            }
            R.id.nav_search -> {
                enableGestures()
                supportFragmentManager.beginTransaction()
                    .replace(
                        R.id.frame,
                        if (BuildConfig.DEBUG)
                            SearchExperimentFragment()
                        else
                            SearchFragment()
                    )
                    .addToBackStack(null)
                    .commit()
            }
            R.id.nav_settings -> {
                enableGestures()
                supportFragmentManager.beginTransaction()
                    .replace(R.id.frame, PrefFragment())
                    .addToBackStack(null)
                    .commit()
            }
            R.id.nav_report -> {
                mailLogs()
            }
            R.id.nav_pro -> {
                enableGestures()
                supportFragmentManager.beginTransaction()
                    .replace(R.id.frame, BillingFragment())
                    .addToBackStack(null)
                    .commit()
            }
        }
        return true
    }

    fun enableGestures() {
        val hp =
            supportFragmentManager.findFragmentByTag(Stuff.TAG_HOME_PAGER) as? HomePagerFragment
        hp?.setGestureExclusions(false)
    }

    override fun onBackStackChanged() {
        val animate = true
        if (supportFragmentManager.backStackEntryCount == 0) {
            val firstThingsVisible =
                supportFragmentManager.findFragmentByTag(Stuff.TAG_FIRST_THINGS)?.isVisible

            if (firstThingsVisible != true)
                showBackArrow(false)

            if (supportFragmentManager.fragments.isEmpty()) //came back from direct open
                showHomePager()
        } else {
            showBackArrow(true)
        }

        val pager =
            supportFragmentManager.findFragmentByTag(Stuff.TAG_HOME_PAGER)?.view?.findViewById<ViewPager>(
                R.id.pager
            )

        val expand = pager != null && pager.currentItem != 2 && pager.currentItem != 3 &&
                supportFragmentManager.findFragmentByTag(Stuff.TAG_FIRST_THINGS)?.isVisible != true

        binding.coordinatorMain.appBar.setExpanded(expand, animate)
    }

    override fun onBackPressed() {
        if (binding.drawerLayout.isDrawerOpen(GravityCompat.START) && coordinatorPadding == 0)
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        else if (mainNotifierViewModel.backButtonEnabled)
            super.onBackPressed()
    }

    private fun showNotRunning() {
        Snackbar
            .make(binding.coordinatorMain.frame, R.string.not_running, Snackbar.LENGTH_INDEFINITE)
            .setAction(R.string.not_running_fix_action) {
                FixItFragment().show(supportFragmentManager, null)
            }
            .addCallback(object : Snackbar.Callback() {
                override fun onShown(sb: Snackbar?) {
                    super.onShown(sb)
                    if (sb != null && isTv)
                        sb.view.postDelayed({
                            sb.view.findViewById<View>(com.google.android.material.R.id.snackbar_action)
                                .requestFocus()
                        }, 200)
                }
            })
            .show()
        Timber.tag(Stuff.TAG).w(Exception("${Stuff.SCROBBLER_PROCESS_NAME} not running"))
    }

    private fun mailLogs() {
        val activeSessions = try {
            val sessManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            sessManager.getActiveSessions(ComponentName(this, NLService::class.java))
                .joinToString { it.packageName }
        } catch (e: SecurityException) {
            "SecurityException"
        }
        var bgRam = -1
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (proc in manager.runningAppProcesses) {
            if (proc?.processName?.contains(Stuff.SCROBBLER_PROCESS_NAME) == true) {
                // https://stackoverflow.com/questions/2298208/how-do-i-discover-memory-usage-of-my-application-in-android
                val memInfo = manager.getProcessMemoryInfo(intArrayOf(proc.pid)).first()
                bgRam = memInfo.totalPss / 1024
                break
            }
        }

        var lastExitInfo: String? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            lastExitInfo = getScrobblerExitReasons(printAll = true).firstOrNull()?.toString()
        }

        var text = ""
        text += getString(R.string.app_name) + " v" + BuildConfig.VERSION_NAME + "\n"
        text += "Android " + Build.VERSION.RELEASE + "\n"
        text += "ROM: " + Build.DISPLAY + "\n"
        text += "Device: " + Build.BRAND + " " + Build.MODEL + " / " + Build.DEVICE + "\n" //Build.PRODUCT is obsolete

        val mi = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(mi)
        val megs = mi.totalMem / 1048576L
        text += "RAM: " + megs + "M \n"
        text += "Background RAM usage: " + bgRam + "M \n"

        val dm = resources.displayMetrics

        text += "Screen: " + dm.widthPixels + " x " + dm.heightPixels + ",  " + dm.densityDpi + " DPI\n"

        if (!Stuff.isScrobblerRunning(this))
            text += "Background service isn't running\n"
        if (lastExitInfo != null)
            text += "Last exit reason: $lastExitInfo\n"
        text += "Active Sessions: $activeSessions\n"

        text += if (billingViewModel.proStatus.value == true)
            "~~~~~~~~~~~~~~~~~~~~~~~~"
        else
            "------------------------"
        text += "\n\n[describe the issue]\n"
        //keep the email in english

        val log = Stuff.exec("logcat -d")
        val logFile = File(filesDir, "log.txt")
        logFile.writeText(log)
        val logUri = FileProvider.getUriForFile(this, "com.arn.scrobble.fileprovider", logFile)

//        PendingScrobblesDb.destroyInstance()
//        val dbFile = File(filesDir, PendingScrobblesDb.tableName + ".sqlite")
//        getDatabasePath(PendingScrobblesDb.tableName).copyTo(dbFile, true)
//        val dbUri = FileProvider.getUriForFile(this, "com.arn.scrobble.fileprovider", dbFile)

        val emailIntent = Intent(
            Intent.ACTION_SENDTO, Uri.fromParts(
                "mailto", "huh@huh.com", null
            )
        )
        emailIntent.putExtra(Intent.EXTRA_SUBJECT, "huh?")
        val resolveInfos = packageManager.queryIntentActivities(emailIntent, 0)
        val intents = arrayListOf<LabeledIntent>()
        for (info in resolveInfos) {
            val intent = Intent(Intent.ACTION_SEND).apply {
                component = ComponentName(info.activityInfo.packageName, info.activityInfo.name)
                putExtra(Intent.EXTRA_EMAIL, arrayOf(getString(R.string.email)))
                putExtra(
                    Intent.EXTRA_SUBJECT,
                    getString(R.string.app_name) + " - Bug report"
                )
                putExtra(Intent.EXTRA_TEXT, text)
                putExtra(Intent.EXTRA_STREAM, logUri)
            }
            intents.add(
                LabeledIntent(
                    intent,
                    info.activityInfo.packageName,
                    info.loadLabel(packageManager),
                    info.icon
                )
            )
        }
        if (intents.size > 0) {
            val chooser = Intent.createChooser(
                intents.removeAt(intents.size - 1),
                getString(R.string.bug_report)
            )
            chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, intents.toTypedArray())
            startActivity(chooser)
        } else
            toast(R.string.no_mail_apps)
    }

    override fun onSupportNavigateUp(): Boolean {
        if (backArrowShown)
            supportFragmentManager.popBackStack()
        else
            binding.drawerLayout.openDrawer(GravityCompat.START)
        return true
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (handleDeepLink(intent))
            return
        else if (intent?.getStringExtra(NLService.B_ARTIST) != null)
            showInfoFragment(intent)
    }

    private fun handleDeepLink(intent: Intent?): Boolean {
        if (intent?.data?.isHierarchical != true) {
            return false
        }
        val uri = intent.data!!
        val scheme = uri.scheme!!
        val path = uri.path ?: return false
        if (scheme == "pscrobble") {
            val token = uri.getQueryParameter("token") ?: return false
            when (path) {
                "/lastfm" ->
                    LFMRequester(applicationContext, lifecycleScope).doAuth(
                        R.string.lastfm,
                        token
                    )
                "/librefm" ->
                    LFMRequester(applicationContext, lifecycleScope).doAuth(
                        R.string.librefm,
                        token
                    )
                "/gnufm" ->
                    LFMRequester(applicationContext, lifecycleScope).doAuth(
                        R.string.gnufm,
                        token
                    )
                "/testFirstThings" -> {
                    prefs.lastfmSessKey = null
                    for (i in 0..supportFragmentManager.backStackEntryCount)
                        supportFragmentManager.popBackStackImmediate()
                    showFirstThings(true)
                }
                else -> {
                    Stuff.log("handleDeepLink unknown path $path")
                    return false
                }
            }
        } else if (scheme == "https" && path.startsWith("/user/")) {
            val username = path.split("/").getOrNull(2) ?: return false
            if (username.isBlank()) return false

            supportFragmentManager.popBackStackTill(0)
            supportFragmentManager.beginTransaction()
                .replace(
                    R.id.frame,
                    HomePagerFragment().apply {
                        arguments = Bundle().apply {
                            putString(Stuff.ARG_USERNAME, username)
                        }
                    },
                    Stuff.TAG_HOME_PAGER
                )
                .addToBackStack(null)
                .commit()
        } else {
            Stuff.log("handleDeepLink unknown scheme $scheme")
            return false
        }
        return true
    }

    private fun showBackArrow(show: Boolean) {
        if (backArrowShown != show) {
            val start = if (show) 0f else 1f
            val anim = ValueAnimator.ofFloat(start, 1 - start)
            anim.addUpdateListener { valueAnimator ->
                val slideOffset = valueAnimator.animatedValue as Float
                toggle.onDrawerSlide(binding.drawerLayout, slideOffset)
            }
            anim.interpolator = DecelerateInterpolator()
            anim.startDelay = 200
            anim.duration = 1000
            anim.start()

            when {
                show -> closeLockDrawer()
                coordinatorPadding > 0 -> openLockDrawer()
                else -> binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
            }

            backArrowShown = show
        }
    }

    override fun attachBaseContext(newBase: Context?) {
        if (newBase != null)
            super.attachBaseContext(newBase.getLocaleContextWrapper())
    }

    public override fun onStart() {
        super.onStart()

        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val builder = NetworkRequest.Builder()
        connectivityCb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Stuff.isOnline = true
            }

            override fun onLost(network: Network) {
                Stuff.isOnline = cm.activeNetworkInfo?.isConnected == true
            }

            override fun onUnavailable() {
                Stuff.isOnline = cm.activeNetworkInfo?.isConnected == true
            }
        }

        cm.registerNetworkCallback(builder.build(), connectivityCb)

        val ni = cm.activeNetworkInfo
        Stuff.isOnline = ni?.isConnected == true
    }

    private fun closeLockDrawer() {
        binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
        if (coordinatorPadding > 0)
            binding.coordinatorMain.coordinator.setPadding(0, 0, 0, 0)
    }


    private fun openLockDrawer() {
        if (coordinatorPadding > 0) {
            binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_OPEN)
            if (!drawerInited) {
                binding.navView.addOnLayoutChangeListener { view, left, top, right, bottom,
                                                            leftWas, topWas, rightWas, bottomWas ->
                    if (left != leftWas || right != rightWas)
                        onDrawerOpened()
                }
                drawerInited = true
            }
            if (binding.coordinatorMain.coordinator.paddingStart != coordinatorPadding)
                binding.coordinatorMain.coordinator.setPaddingRelative(coordinatorPadding, 0, 0, 0)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
//        Stuff.log("focus: $currentFocus")
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            val f = currentFocus
            if (f is NavigationMenuItemView) {
                if (resources.getBoolean(R.bool.is_rtl))
                    f.nextFocusLeftId = R.id.pager
                else
                    f.nextFocusRightId = R.id.pager
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    public override fun onStop() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.unregisterNetworkCallback(connectivityCb)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (binding.drawerLayout.getDrawerLockMode(GravityCompat.START) == DrawerLayout.LOCK_MODE_LOCKED_OPEN)
            binding.drawerLayout.isSaveEnabled = false
        outState.putInt("tab_bar_visible", binding.coordinatorMain.tabBar.visibility)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (!PendingScrService.mightBeRunning)
            PanoDb.destroyInstance()
        try {
            unregisterReceiver(npReceiver)
        } catch (e: Exception) {
        }
        imageLoader.shutdown()
        super.onDestroy()
    }

    class NPReceiver(private val mainNotifierViewModel: MainNotifierViewModel) :
        BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == NLService.iNOW_PLAYING_INFO_S)
                mainNotifierViewModel.trackBundleLd.value = intent.extras
        }
    }

}
