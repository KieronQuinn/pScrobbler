package com.arn.scrobble.recents

import android.os.Bundle
import android.view.View
import androidx.annotation.StringRes
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.arn.scrobble.*
import com.arn.scrobble.db.PanoDb
import com.arn.scrobble.db.PendingLove
import com.arn.scrobble.db.PendingScrobble
import com.arn.scrobble.edits.EditDialogFragment
import com.arn.scrobble.ui.UiUtils.showWithIcons
import com.arn.scrobble.ui.UiUtils.toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import de.umass.lastfm.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object PopupMenuUtils {

    fun csrfTokenExists(activity: FragmentActivity): Boolean {
        val exists = LastfmUnscrobbler(activity)
            .haveCsrfCookie()
        if (!exists) {
            MaterialAlertDialogBuilder(activity)
                .setMessage(R.string.lastfm_reauth)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val wf = WebViewFragment()
                    wf.arguments = Bundle().apply {
                        putString(Stuff.ARG_URL, Stuff.LASTFM_AUTH_CB_URL)
                        putBoolean(Stuff.ARG_SAVE_COOKIES, true)
                    }
                    activity.supportFragmentManager.beginTransaction()
                        .replace(R.id.frame, wf)
                        .addToBackStack(null)
                        .commit()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        return exists
    }

    fun editScrobble(activity: FragmentActivity, track: Track) {
        if (!Stuff.isOnline)
            activity.toast(R.string.unavailable_offline)
        else if (csrfTokenExists(activity)) {
            val b = Bundle().apply {
                putString(NLService.B_ARTIST, track.artist)
                putString(NLService.B_ALBUM, track.album)
                putString(NLService.B_TRACK, track.name)
            }

            val millis = track.playedWhen?.time
            if (millis != null)
                b.putLong(NLService.B_TIME, millis)

            EditDialogFragment().apply {
                arguments = b
                show(activity.supportFragmentManager, null)
            }
        }
    }

    fun deleteScrobble(
        activity: FragmentActivity,
        track: Track,
        deleteAction: suspend (Boolean) -> Unit
    ) {
        if (!Stuff.isOnline)
            activity.toast(R.string.unavailable_offline)
        else if (csrfTokenExists(activity)) {
            LFMRequester(activity, activity.lifecycleScope)
                .delete(track, deleteAction)
        }
    }

    fun openPendingPopupMenu(
        anchor: View,
        scope: CoroutineScope,
        p: Any,
        deleteAction: () -> Unit,
        loveAction: (() -> Unit)? = null
    ) {
        val context = anchor.context
        val popup = PopupMenu(context, anchor)
        popup.menuInflater.inflate(R.menu.pending_item_menu, popup.menu)

        if (p is PendingLove)
            popup.menu.removeItem(R.id.menu_love)

        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.menu_details -> {
                    val servicesList = mutableListOf<String>()
                    val state: Int

                    @StringRes
                    val actionRes: Int
                    if (p is PendingScrobble) {
                        state = p.state
                        actionRes = R.string.scrobble
                    } else if (p is PendingLove) {
                        state = p.state
                        actionRes = if (p.shouldLove)
                            R.string.love
                        else
                            R.string.unlove
                    } else
                        throw RuntimeException("Not a Pending Item")
                    Stuff.SERVICE_BIT_POS.forEach { (id, pos) ->
                        if (state and (1 shl pos) != 0)
                            servicesList += context.getString(id)
                    }
                    MaterialAlertDialogBuilder(context)
                        .setMessage(
                            context.getString(
                                R.string.details_text,
                                context.getString(actionRes).lowercase(),
                                servicesList.joinToString(", ")
                            )
                        )
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
                R.id.menu_delete -> {
                    scope.launch(Dispatchers.IO) {
                        try {
                            if (p is PendingScrobble)
                                PanoDb.getDb(context).getScrobblesDao().delete(p)
                            else if (p is PendingLove)
                                PanoDb.getDb(context).getLovesDao().delete(p)
                            withContext(Dispatchers.Main) {
                                deleteAction.invoke()
                            }
                        } catch (e: Exception) {
                        }
                    }
                }
                R.id.menu_love -> {
                    if (p is PendingScrobble) {
                        LFMRequester(context!!, scope).loveOrUnlove(
                            Track(
                                p.track,
                                null,
                                p.artist
                            ), true
                        ) {
                            if (!it) {
                                if (loveAction != null)
                                    loveAction.invoke()
                                else
                                    deleteAction.invoke()
                            }
                        }
                    }
                }
            }
            true
        }

        popup.showWithIcons()
    }
}