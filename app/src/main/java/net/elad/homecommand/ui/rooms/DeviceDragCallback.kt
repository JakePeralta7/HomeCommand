package net.elad.homecommand.ui.rooms

import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import java.util.Collections

/**
 * Drag-to-reorder callback for device tiles in a room grid.
 * Only vertical drag is enabled; the adapter's working list is swapped synchronously
 * on each move, and [onOrderChanged] fires when the gesture completes.
 */
class DeviceDragCallback(
    private val adapter: RoomTileAdapter,
    private val onOrderChanged: (List<String>) -> Unit,
) : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
        0,
    ) {
    override fun isLongPressDragEnabled() = false

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder,
    ): Boolean {
        val from = viewHolder.bindingAdapterPosition
        val to = target.bindingAdapterPosition
        Collections.swap(adapter.workingList, from, to)
        adapter.notifyItemMoved(from, to)
        return true
    }

    override fun clearView(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
    ) {
        super.clearView(recyclerView, viewHolder)
        onOrderChanged(adapter.workingList.map { it.id })
    }

    override fun onSwiped(
        viewHolder: RecyclerView.ViewHolder,
        direction: Int,
    ) = Unit
}
