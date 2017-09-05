package com.arn.ytscrobble

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.*
import android.net.Uri
import android.os.Handler
import android.os.Message
import android.support.design.widget.AppBarLayout
import android.support.design.widget.CollapsingToolbarLayout
import android.support.design.widget.FloatingActionButton
import android.support.graphics.drawable.Animatable2Compat
import android.support.v4.widget.SwipeRefreshLayout
import android.support.v7.graphics.Palette
import android.text.format.DateUtils
import android.text.format.DateUtils.MINUTE_IN_MILLIS
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AnimationUtils
import android.view.animation.DecelerateInterpolator
import android.view.animation.GridLayoutAnimationController
import android.widget.*
import com.jjoe64.graphview.GraphView
import com.jjoe64.graphview.GridLabelRenderer
import com.jjoe64.graphview.LabelFormatter
import com.jjoe64.graphview.series.DataPoint
import com.jjoe64.graphview.series.LineGraphSeries
import com.squareup.picasso.Callback
import com.squareup.picasso.Picasso
import de.umass.lastfm.ImageSize
import de.umass.lastfm.PaginatedResult
import de.umass.lastfm.Track
import java.io.UnsupportedEncodingException
import java.net.URLEncoder

/**
 * Created by arn on 10/07/2017.
 */

internal class RecentsAdapter

(c: Context, private val layoutResourceId: Int) : ArrayAdapter<Track>(c, layoutResourceId, ArrayList()) {

    private val hero: ImageView = (c as Activity).findViewById(R.id.img_hero) as ImageView
    private var gotLoved = false
    var lastClicked = -1

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        var convertView : View? = convertView
        /*
         * The convertView argument is essentially a "ScrapView" as described is Lucas post
         * http://lucasr.org/2012/04/05/performance-tips-for-androids-listview/
         * It will have a non-null value when ListView is asking you recycle the row layout.
         * So, when convertView is not null, you should simply update its contents instead of inflating a new row layout.
         */
        if (convertView == null) {
            // inflate the layout
            val inflater = (context as Activity).layoutInflater
            convertView = inflater.inflate(layoutResourceId, parent, false)
        }
        convertView = convertView!!
        // object item based on the position
        val t = getItem(position) ?: return convertView

// get the TextView and then set the text (item name) and tag (item ID) values
        val title = convertView.findViewById(R.id.recents_title) as TextView
        val subtitle = convertView.findViewById(R.id.recents_subtitle) as TextView
        val date = convertView.findViewById(R.id.recents_date) as TextView
        val np = convertView.findViewById(R.id.recents_playing) as ImageView
        var relDate: CharSequence = ""

        if (t.isNowPlaying) {
            relDate = "   now"
            np.visibility = View.VISIBLE
            val anim = np.getDrawable() as AnimatedVectorDrawable
            anim.registerAnimationCallback(object : Animatable2.AnimationCallback() {
                override fun onAnimationEnd(drawable: Drawable?) {
                    if (drawable != null && drawable.isVisible)
                        anim.start()
                }
            })
            anim.start()
        } else
            np.visibility = View.GONE
        title.text = t.name
        subtitle.text = t.artist

        if (t.playedWhen != null) {
            relDate = DateUtils.getRelativeTimeSpanString(
                    t.playedWhen.time, System.currentTimeMillis(), MINUTE_IN_MILLIS)
            if (relDate[0] == '0')
                relDate = "Just now"
        }
        date.text = relDate
        val love = convertView.findViewById(R.id.recents_love) as ImageView
        val play = convertView.findViewById(R.id.recents_play) as ImageView

        love.setOnClickListener(loveToggle)

        if (gotLoved) {
            if (t.isLoved) {
                love.setImageResource(R.drawable.ic_line_heart_enabled)
                love.setTag(R.id.recents_love, FILLED)
            } else {
                love.setImageResource(R.drawable.ic_line_heart_disabled)
                love.setTag(R.id.recents_love, 0)
            }
        }
        val albumArt = convertView.findViewById(R.id.recents_album_art) as ImageView

        val imgUrl = t.getImageURL(ImageSize.MEDIUM)

        if (imgUrl != null && imgUrl != "") {
            albumArt.clearColorFilter()
            Picasso.with(context)
                    .load(imgUrl)
                    .fit()
                    .centerInside()
//                    .placeholder(R.drawable.ic_lastfm)
//                    .error(R.drawable.ic_placeholder_music)
                    .into(albumArt)

        } else {
            albumArt.setImageResource(R.drawable.ic_placeholder_music)
            albumArt.setColorFilter(Stuff.getMatColor(context, "500", t.name.hashCode().toLong()))
        }

        if ((position == 0 && lastClicked == -1 || position == lastClicked) && t.url != hero.tag) {
            val fab = (context as Activity).findViewById(R.id.fab) as FloatingActionButton
            val heroInfo = (context as Activity).findViewById(R.id.hero_info) as ImageButton
            val heroYt = (context as Activity).findViewById(R.id.hero_yt) as ImageButton
            fab.setOnClickListener {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(t.url))
                context.startActivity(browserIntent)
            }
            heroInfo.setOnClickListener {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(t.url))
                context.startActivity(browserIntent)
            }
            heroYt.setOnClickListener {
                val ytUrl = "https://www.youtube.com/results?search_query=" + URLEncoder.encode(t.artist + " - "+ t.album, "UTF-8")
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(ytUrl))
                context.startActivity(browserIntent)
            }
            Scrobbler(context, handler).execute(Stuff.TRACK_HERO, t.url, t.getImageURL(ImageSize.EXTRALARGE))

            hero.tag = t.url
            //                if (imgUrl != null && !imgUrl.equals("")) {
            setHero(t, imgUrl) //better set a blurred one

            //                }
            play.visibility = View.VISIBLE
            play.setTag(R.id.recents_play, t.artist + " - " + t.name)
            play.setOnClickListener(playClickListener)
        } else if (position != lastClicked) {
            play.visibility = View.INVISIBLE
        }
        return convertView
    }

    fun loadURL(page: Int) {
        Scrobbler(context, handler).execute(Stuff.GET_RECENTS, page.toString() + "")
    }

    fun setHero(imgUrl: String?) {
        setHero(null, imgUrl)
    }

    private fun setHero(t: Track?, imgUrl: String? = null) {
        val ctl = (context as Activity).findViewById(R.id.toolbar_layout) as CollapsingToolbarLayout

        if (t != null) {
            val text = t.artist + " - " + t.name
//            if (Main.heroExpanded)
                ctl.title = text
            ctl.tag = text
        }

//        if (imgUrl == null && t != null) //called from getview
//            imgUrl = t.getImageURL(ImageSize.MEDIUM)
//        else if (imgUrl == null && t == null) // called from handler
//            imgUrl = t.getImageURL(ImageSize.LARGE)
        if (imgUrl != null && imgUrl != "")
            Picasso.with(context)
                    .load(imgUrl)
//                    .error(R.drawable.ic_placeholder_music)
                    .fit()
                    .centerCrop()
                    .noFade()
                    .into(hero, object : Callback {
                        override fun onSuccess() {
                            hero.clearColorFilter()
                            val list = (context as Activity).findViewById(R.id.recents_list) as ListView? ?: return
                            val fab = (context as Activity).findViewById(R.id.fab) as FloatingActionButton? ?: return
                            val heroInfo = (context as Activity).findViewById(R.id.hero_info) as ImageButton
                            val heroYt = (context as Activity).findViewById(R.id.hero_yt) as ImageButton

                            val b = (hero.drawable as BitmapDrawable).bitmap
                            Palette.generateAsync(b) { palette ->
                                val colorDomPrimary = palette.getDominantColor(context.resources.getColor(R.color.colorPrimary))
                                val colorLightAccent = palette.getLightMutedColor(context.resources.getColor(R.color.colorAccent))
                                val colorMutedDark = palette.getDarkMutedColor(context.resources.getColor(R.color.colorPrimaryDark))
                                val colorMutedBlack = palette.getDarkMutedColor(context.resources.getColor(android.R.color.background_dark))

                                ctl.setContentScrimColor(colorDomPrimary)
                                ctl.setStatusBarScrimColor(colorMutedDark)
                                if (Stuff.isDark(colorDomPrimary)) {
                                    ctl.setCollapsedTitleTextColor(context.resources.getColor(android.R.color.white))
                                    fab.imageTintList = null
                                } else {
                                    ctl.setCollapsedTitleTextColor(context.resources.getColor(android.R.color.black))
                                    fab.imageTintList = ColorStateList.valueOf(0xff000000.toInt())
                                }

//                                fab.backgroundTintList = ColorStateList.valueOf(colorDomPrimary)
//                                list.setBackgroundColor(colorMutedBlack)
                                val listBgFrom = (list.background as ColorDrawable).color
                                val fabBgFrom = fab.contentBackground

                                val listBgAnimator = ObjectAnimator.ofObject(list, "backgroundColor", ArgbEvaluator(), listBgFrom, colorMutedBlack)
                                val fabBgAnimator = ObjectAnimator.ofArgb(fab.contentBackground.mutate(), "tint", lastColorDomPrimary, colorDomPrimary)
                                val infoBgAnimator = ObjectAnimator.ofObject(heroInfo, "colorFilter", ArgbEvaluator(), lastColorLightAccent, colorLightAccent)
                                val ytBgAnimator = ObjectAnimator.ofObject(heroYt, "colorFilter", ArgbEvaluator(), lastColorLightAccent, colorLightAccent)
                                Stuff.log(context, "fab bg: "+ fabBgFrom)
                                val animSet = AnimatorSet()
                                animSet.playTogether(listBgAnimator, fabBgAnimator, infoBgAnimator, ytBgAnimator)
                                animSet.interpolator = AccelerateDecelerateInterpolator()
                                animSet.duration = 1500
                                animSet.start()

                                lastColorDomPrimary = colorDomPrimary
                                lastColorLightAccent = colorLightAccent
                                lastColorMutedDark = colorMutedDark
                                lastColorMutedBlack = colorMutedBlack

//                                if (lastClicked > list.lastVisiblePosition - 5)
//                                    list.setSelection(lastClicked)
                                //                                        list.post(new Runnable() {
                                //                                            @Override
                                //                                            public void run() {
                                //
                                //
                                //                                            }
                                //                                        });
                                //
                            }
                        }

                        override fun onError() {
                            Stuff.log(context, "onerr")
                        }
                    })
        else {
            if (t!= null)
                hero.setColorFilter(Stuff.getMatColor(context, "500", t.name.hashCode().toLong()))
            hero.setImageResource(R.drawable.ic_placeholder_music)


        }
    }

    private fun setGraph(points: String?) {
        if (points == null)
            return

        val graph = (context as Activity).findViewById(R.id.graph) as GraphView
        val series = graph.series.get(0) as LineGraphSeries<DataPoint>
        val dps = mutableListOf<DataPoint>()
        var i: Double = 0.0
        points.split(", ").forEach({
                    dps.add(DataPoint(i++, it.toDouble()))
                })

        Stuff.log(context,"points: $points")

        series.resetData(dps.toTypedArray())

        graph.alpha = 0f
        graph.animate()
                .alpha(0.7f)
                .setInterpolator(DecelerateInterpolator())
                .setDuration(500)
                .start()
    }

    fun populate(res: PaginatedResult<Track>, page: Int = 1) {
        val refresh = (context as Activity).findViewById(R.id.swiperefresh) as SwipeRefreshLayout?

        refresh?.isRefreshing = false
        if (page == 1) {
            //                gotLoved = false;
            clear()
        }
        res.forEach {
            if (!it.isNowPlaying || page==1)
                add(it)
        }
        lastClicked = -1
        gotLoved = false
        notifyDataSetChanged()
    }

    fun markLoved(res: PaginatedResult<Track>) {
        val loved = res.filterNotNullTo(mutableListOf<Track>())
        for (i in loved.indices) {
            (0 until count)
                    .filter { loved[i].name == getItem(it).name && loved[i].artist == getItem(it).artist }
                    .forEach { getItem(it).isLoved = true }
        }
        gotLoved = true
        notifyDataSetChanged()
    }

    private val loveToggle = View.OnClickListener { v ->
        val love = v as ImageView
        val parentRow = v.getParent() as View
        val listView = parentRow.parent as ListView
        val pos = listView.getPositionForView(parentRow) - 1

        if (v.getTag(R.id.recents_love) == FILLED) {
            Scrobbler(context, handler).execute(Stuff.UNLOVE,
                    getItem(pos).artist, getItem(pos).name)
            getItem(pos).isLoved = false
            love.setImageResource(R.drawable.ic_line_heart_disabled)
            love.setTag(R.id.recents_love, 0)

            love.animate()
                    .alpha(0f)
                    .scaleX(5f)
                    .scaleY(5f)
                    .setInterpolator(DecelerateInterpolator())
                    .setDuration(500)
                    .withEndAction {
                        love.alpha = 1f
                        love.scaleX = 1f
                        love.scaleY = 1f
//                        love.setImageResource(R.drawable.ic_line_heart_disabled)
                    }
                    .start()
        } else {
            Scrobbler(context, handler).execute(Stuff.LOVE,
                    getItem(pos).artist, getItem(pos).name)
            getItem(pos).isLoved = true
            love.setTag(R.id.recents_love, FILLED)
            love.alpha = 0f
            love.scaleX = 5f
            love.scaleY = 5f
            love.setImageResource(R.drawable.ic_line_heart_enabled)
            love.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setInterpolator(DecelerateInterpolator())
                    .setDuration(500)
                    .start()

        }
    }

    private val playClickListener = View.OnClickListener { v ->
        var url = v.getTag(R.id.recents_play) as String
        try {
            url = "https://www.youtube.com/results?search_query=" + URLEncoder.encode(url, "UTF-8")
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            context.startActivity(browserIntent)
        } catch (e: UnsupportedEncodingException) {
            Stuff.toast(context, "failed to encode url")
        }
    }

    private val handler: Handler = @SuppressLint("HandlerLeak")
    object: Handler(){
        override fun handleMessage(m: Message) {
            //usually:
            // obj = command, paginatedresult; arg1 = page num
            val pair = m.obj as Pair<String, Any>
            val command = pair.first
            var data = pair.second
            when(command){
                Stuff.GET_LOVED -> markLoved(data as PaginatedResult<Track>)
                Stuff.TRACK_HERO -> {
                        data = data as MutableList<String?>
                        setHero(data[0])
                        setGraph(data[1])
                }
                Stuff.GET_RECENTS -> populate(data as PaginatedResult<Track>, m.arg1)
            }
        }
    }

    companion object {
        private var lastColorDomPrimary = 0
        private var lastColorLightAccent = 0
        private var lastColorMutedDark = 0
        private var lastColorMutedBlack = 0

        private val FILLED = 5
    }

}