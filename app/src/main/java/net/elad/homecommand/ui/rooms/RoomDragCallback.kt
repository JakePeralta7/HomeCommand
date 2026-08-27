package net.elad.homecommand.ui.rooms

import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import java.util.Collections

/**
 * Drag-to-reorder callback for room cards on the home screen.
 * Only [RoomsAdapter.Row.RoomCard] items are draggable; unassigned-device rows
 * and the section header are immutable anchors. [onOrderChanged] fires with the
 * final room-id order when the gesture completes.
 */
class RoomDragCallback(
    private val adapter: RoomsAdapter,
    private val onOrderChanged: (List<String>) -> Unit,
) : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
    override fun isLongPressDragEnabled() = false

    override fun getDragDirs(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
    ): Int {
        if (adapter.currentList.getOrNull(viewHolder.bindingAdapterPosition) !is RoomsAdapter.Row.RoomCard) {
            return 0
        }
        return super.getDragDirs(recyclerView, viewHolder)
    }

    override fun canDropOver(
        recyclerView: RecyclerView,
        current: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder,
    ): Boolean {
        val rows = adapter.currentList
        return rows.getOrNull(current.bindingAdapterPosition) is RoomsAdapter.Row.RoomCard &&
            rows.getOrNull(target.bindingAdapterPosition) is RoomsAdapter.Row.RoomCard
    }

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
        val roomIds =
            adapter.workingList
                .filterIsInstance<RoomsAdapter.Row.RoomCard>()
                .map { it.room.id }
        onOrderChanged(roomIds)
    }

    override fun onSwiped(
        viewHolder: RecyclerView.ViewHolder,
        direction: Int,
    ) = Unit
}
