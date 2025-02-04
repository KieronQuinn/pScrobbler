package com.arn.scrobble.edits

import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.arn.scrobble.ui.UiUtils.setDragAlpha
import java.util.*


class RegexItemTouchHelper(
    adapter: RegexEditsAdapter,
    viewModel: RegexEditsVM,
) : ItemTouchHelper(object : SimpleCallback(0, UP or DOWN) {

    private var changed = false

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean {
        val fromPosition = viewHolder.bindingAdapterPosition
        val toPosition = target.bindingAdapterPosition
        if (fromPosition < toPosition) {
            for (i in fromPosition until toPosition) {
                Collections.swap(viewModel.regexes, i, i + 1)
//                val order1 = viewModel.regexes[i].order
//                val order2 = viewModel.regexes[i + 1].order
//                viewModel.regexes[i].order = order2
//                viewModel.regexes[i + 1].order = order1
            }
        } else {
            for (i in fromPosition downTo toPosition + 1) {
                Collections.swap(viewModel.regexes, i, i - 1)
//                val order1 = viewModel.regexes[i].order
//                val order2 = viewModel.regexes[i - 1].order
//                viewModel.regexes[i].order = order2
//                viewModel.regexes[i - 1].order = order1
            }
        }
        adapter.notifyItemMoved(fromPosition, toPosition)
        changed = true
        return true
    }

    override fun onSelectedChanged(
        viewHolder: RecyclerView.ViewHolder?,
        actionState: Int
    ) {
        super.onSelectedChanged(viewHolder, actionState)

        if (actionState == ACTION_STATE_DRAG) {
            viewHolder?.setDragAlpha(true)
        }
    }

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
    }

    override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) =
        makeMovementFlags(
            UP or DOWN,
            0
        )

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)
        viewHolder.setDragAlpha(false)
        if (changed) {
            changed = false
            viewModel.regexes.forEachIndexed { index, regex ->
                regex.order = index
            }
            viewModel.upsertAll(viewModel.regexes)
        }
    }
})