package net.elad.homecommand.ui.rooms

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import net.elad.homecommand.R
import net.elad.homecommand.data.Device
import net.elad.homecommand.data.DeviceType
import net.elad.homecommand.data.Room

/** Rows of the Rooms tab: room cards plus the unassigned-devices section. */
class RoomsAdapter(
    private val onRoomClick: (Room) -> Unit,
    private val onRoomLongClick: (Room) -> Unit,
    private val onUnassignedClick: (Device) -> Unit,
    private val onUnassignedLongClick: (Device) -> Unit,
    private val onDragStart: (RecyclerView.ViewHolder) -> Unit,
) : ListAdapter<RoomsAdapter.Row, RecyclerView.ViewHolder>(RowDiffCallback()) {
    /** Mutable working copy kept in sync with [submitList]; used by [RoomDragCallback] during drag. */
    val workingList: MutableList<Row> = mutableListOf()

    override fun submitList(list: List<Row>?) {
        super.submitList(list)
        workingList.clear()
        list?.let { workingList.addAll(it) }
    }

    sealed class Row {
        data class RoomCard(
            val room: Room,
            val summary: String,
        ) : Row()

        data object UnassignedHeader : Row()

        data class UnassignedDevice(
            val device: Device,
            val typeLabel: String,
        ) : Row()
    }

    override fun getItemViewType(position: Int): Int =
        when (getItem(position)) {
            is Row.RoomCard -> TYPE_ROOM
            Row.UnassignedHeader -> TYPE_HEADER
            is Row.UnassignedDevice -> TYPE_DEVICE
        }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_ROOM -> RoomCardHolder(inflater.inflate(R.layout.view_room_card, parent, false), this)
            TYPE_HEADER -> HeaderHolder(inflater.inflate(R.layout.view_unassigned_header, parent, false))
            else -> UnassignedHolder(inflater.inflate(R.layout.view_unassigned_device, parent, false), this)
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
    ) {
        when (val row = getItem(position)) {
            is Row.RoomCard -> (holder as RoomCardHolder).bind(row)
            Row.UnassignedHeader -> Unit
            is Row.UnassignedDevice -> (holder as UnassignedHolder).bind(row)
        }
    }

    private fun rowAt(adapterPosition: Int): Row? = currentList.getOrNull(adapterPosition)

    class RoomCardHolder(
        itemView: View,
        adapter: RoomsAdapter,
    ) : RecyclerView.ViewHolder(itemView) {
        private val name: TextView = itemView.findViewById(R.id.text_room_name)
        private val summary: TextView = itemView.findViewById(R.id.text_room_summary)

        init {
            itemView.setOnClickListener {
                adapter.rowAt(bindingAdapterPosition)?.let { r ->
                    (r as? Row.RoomCard)?.let(adapter::emitRoomClick)
                }
            }
            itemView.setOnLongClickListener {
                adapter.rowAt(bindingAdapterPosition)?.let { r -> (r as? Row.RoomCard)?.let(adapter::emitRoomLongClick) }
                true
            }
            itemView.findViewById<View>(R.id.btn_drag_handle)?.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    adapter.onDragStart(this@RoomCardHolder)
                }
                false
            }
        }

        fun bind(row: Row.RoomCard) {
            name.text = row.room.name
            summary.text = row.summary
        }
    }

    class HeaderHolder(
        itemView: View,
    ) : RecyclerView.ViewHolder(itemView)

    class UnassignedHolder(
        itemView: View,
        adapter: RoomsAdapter,
    ) : RecyclerView.ViewHolder(itemView) {
        private val icon: ImageView = itemView.findViewById(R.id.icon_unassigned_device)
        private val name: TextView = itemView.findViewById(R.id.text_unassigned_device_name)
        private val type: TextView = itemView.findViewById(R.id.text_unassigned_device_type)

        init {
            itemView.setOnClickListener {
                adapter.rowAt(bindingAdapterPosition)?.let { r ->
                    (r as? Row.UnassignedDevice)?.let(adapter::emitUnassignedClick)
                }
            }
            itemView.setOnLongClickListener {
                adapter.rowAt(bindingAdapterPosition)?.let { r -> (r as? Row.UnassignedDevice)?.let(adapter::emitUnassignedLongClick) }
                true
            }
        }

        fun bind(row: Row.UnassignedDevice) {
            icon.setImageResource(RoomTileAdapter.iconFor(row.device.type))
            name.text = row.device.name
            type.text = row.typeLabel
        }
    }

    // Lambdas live outside the holders so clicks never fire against a recycled position.
    private fun emitRoomClick(row: Row.RoomCard) = onRoomClick(row.room)

    private fun emitRoomLongClick(row: Row.RoomCard) = onRoomLongClick(row.room)

    private fun emitUnassignedClick(row: Row.UnassignedDevice) = onUnassignedClick(row.device)

    private fun emitUnassignedLongClick(row: Row.UnassignedDevice) = onUnassignedLongClick(row.device)

    private companion object {
        const val TYPE_ROOM = 0
        const val TYPE_HEADER = 1
        const val TYPE_DEVICE = 2
    }

    class RowDiffCallback : DiffUtil.ItemCallback<Row>() {
        override fun areItemsTheSame(
            oldItem: Row,
            newItem: Row,
        ): Boolean =
            when {
                oldItem is Row.RoomCard && newItem is Row.RoomCard -> {
                    oldItem.room.id == newItem.room.id
                }

                oldItem is Row.UnassignedHeader && newItem is Row.UnassignedHeader -> {
                    true
                }

                oldItem is Row.UnassignedDevice && newItem is Row.UnassignedDevice -> {
                    oldItem.device.id == newItem.device.id
                }

                else -> {
                    false
                }
            }

        override fun areContentsTheSame(
            oldItem: Row,
            newItem: Row,
        ): Boolean = oldItem == newItem
    }
}
