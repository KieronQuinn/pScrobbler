package com.arn.scrobble

import android.app.Fragment
import android.net.http.HttpResponseCache
import android.os.Bundle
import android.support.design.widget.AppBarLayout
import android.support.design.widget.CollapsingToolbarLayout
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.Toolbar
import java.io.File
import java.io.IOException


class Main : AppCompatActivity() {

//    private var tReceiver: BroadcastReceiver? = null
    //    private TrackMetaListener tReceiver;

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null){
            try {
                val httpCacheDir = File(cacheDir, "http")
                val httpCacheSize = (10 * 1024 * 1024).toLong() // 10 MiB
                HttpResponseCache.install(httpCacheDir, httpCacheSize)
            } catch (e: IOException) {
                Stuff.log("HTTP cache installation failed:" + e)
            }
        }

        setContentView(R.layout.activity_main)
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        val ctl = findViewById<CollapsingToolbarLayout>(R.id.toolbar_layout)
        ctl.tag = " "
        val abl = findViewById<AppBarLayout>(R.id.app_bar)
        abl.addOnOffsetChangedListener(object : AppBarLayout.OnOffsetChangedListener {
            internal var scrollRange = -1

            override fun onOffsetChanged(appBarLayout: AppBarLayout, verticalOffset: Int) {
                if (scrollRange == -1) {
                    scrollRange = appBarLayout.totalScrollRange
                }
                if (scrollRange + verticalOffset == 0) {
                    val f: Fragment? = fragmentManager.findFragmentByTag(Stuff.GET_RECENTS)
                    if (f != null && f.isVisible) {
                        ctl.title = getString(R.string.app_name)
                    }
                    heroExpanded = true
                } else if (heroExpanded) {
                    ctl.title = ctl.tag as CharSequence //careful there should a space between double quote otherwise it wont work
                    heroExpanded = false
                }
            }
        })

        val recentsFragment = RecentsFragment()
        fragmentManager.beginTransaction()
                .add(R.id.frame, recentsFragment, Stuff.GET_RECENTS)
//                .hide(recentsFragment)
//                .add(R.id.frame, PrefFragment())
//                .add(R.id.frame,FirstThingsFragment())
                .commit()
        //TODO: remove it later


        if( !FirstThingsFragment.checkAuthTokenExists(this) ||
                !FirstThingsFragment.checkNLAccess(this))
            fragmentManager.beginTransaction()
                    .hide(recentsFragment)
                    .add(R.id.frame, FirstThingsFragment())
                    .addToBackStack(null)
                    .commit()

    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }

    override fun onStop() {
        super.onStop()
        val cache = HttpResponseCache.getInstalled()
        cache?.flush()
    }
/*
    override fun onDestroy() {
        super.onDestroy()
        if (tReceiver != null)
            unregisterReceiver(tReceiver)
    }
*/
    override fun onResume() {
        super.onResume()
    }

    companion object {
        var heroExpanded = false
    }
}