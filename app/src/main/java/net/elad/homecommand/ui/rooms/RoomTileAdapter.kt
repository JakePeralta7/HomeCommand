package net.elad.homecommand.ui.rooms

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.switchmaterial.SwitchMaterial
import net.elad.homecommand.R
import net.elad.homecommand.data.Device
import net.elad.homecommand.data.DeviceType
import net.elad.homecommand.mqtt.DeviceFields
import net.elad.homecommand.mqtt.DeviceStateReader
import net.elad.homecommand.mqtt.readDeviceFields

/**
 * Renders one dedicated tile layout per predefined device type.
 * Each bind parses the payload exactly once ([readDeviceFields]); [isLearning] reports
 * whether IR learning is in progress so the tile shows live feedback.
 */
class RoomTileAdapter(
    private val stateLookup: (String?) -> String?,
    private val isLearning: (Device) -> Boolean,
    private val onPlugToggled: (Device, Boolean) -> Unit,
    private val onChildLockToggled: (Device, Boolean) -> Unit,
    private val onCountdownClicked: (Device) -> Unit,
    private val onLearnIr: (Device) -> Unit,
    private val onSaveLastIrCode: (Device, String) -> Unit,
    private val onSendIrCommand: (Device, String) -> Unit,
    private val onDeleteIrCommand: (Device, String) -> Unit,
    private val onDeviceLongClick: (Device) -> Unit,
    private val onDragStart: (RecyclerView.ViewHolder) -> Unit,
) : ListAdapter<Device, RecyclerView.ViewHolder>(TileDiffCallback()) {
    /** Mutable working copy kept in sync with [submitList]; used by [DeviceDragCallback] during drag. */
    val workingList: MutableList<Device> = mutableListOf()

    override fun submitList(list: List<Device>?) {
        super.submitList(list)
        workingList.clear()
        list?.let { workingList.addAll(it) }
    }

    /** Re-binds tiles whose state topic matches [topic]. */
    fun notifyDeviceStateChanged(topic: String) {
        currentList.forEachIndexed { index, device ->
            if (device.stateTopic == topic) notifyItemChanged(index)
        }
    }

    override fun getItemViewType(position: Int): Int = getItem(position).type.ordinal

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (DeviceType.entries[viewType]) {
            DeviceType.CONTACT_SENSOR,
            DeviceType.MOTION_SENSOR,
            DeviceType.VIBRATION_SENSOR,
            -> {
                PresenceHolder(inflater.inflate(R.layout.tile_presence, parent, false))
            }

            DeviceType.TEMP_HUMIDITY_SENSOR -> {
                TempHumidityHolder(inflater.inflate(R.layout.tile_temp_humidity, parent, false))
            }

            DeviceType.SMART_PLUG -> {
                PlugHolder(inflater.inflate(R.layout.tile_plug, parent, false), this)
            }

            DeviceType.IR_REMOTE -> {
                IrRemoteHolder(inflater.inflate(R.layout.tile_ir_remote, parent, false), this)
            }

            DeviceType.SMART_BUTTON -> {
                ButtonHolder(inflater.inflate(R.layout.tile_button, parent, false))
            }
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
    ) {
        val device = getItem(position)
        // One parse per bind; extractors used to re-parse the same payload ~8x per tile.
        val fields = readDeviceFields(stateLookup(device.stateTopic))
        when (holder) {
            is PresenceHolder -> holder.bind(device, fields)
            is TempHumidityHolder -> holder.bind(device, fields)
            is PlugHolder -> holder.bind(device, fields)
            is IrRemoteHolder -> holder.bind(device, fields)
            is ButtonHolder -> holder.bind(device, fields)
        }
        holder.itemView.setOnLongClickListener {
            onDeviceLongClick(device)
            true
        }
        holder.itemView.findViewById<View>(R.id.btn_drag_handle)?.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                onDragStart(holder)
            }
            false
        }
    }

    abstract class TileHolder(
        itemView: View,
    ) : RecyclerView.ViewHolder(itemView) {
        protected val context = itemView.context

        protected fun batteryText(battery: Int?): String =
            battery?.let { context.getString(R.string.battery_format, it) } ?: context.getString(R.string.no_data)
    }

    class PresenceHolder(
        itemView: View,
    ) : TileHolder(itemView) {
        private val icon: ImageView = itemView.findViewById(R.id.icon_tile)
        private val name: TextView = itemView.findViewById(R.id.text_tile_name)
        private val battery: TextView = itemView.findViewById(R.id.text_tile_battery)
        private val state: TextView = itemView.findViewById(R.id.text_presence_state)
        private val warning: TextView = itemView.findViewById(R.id.text_presence_warning)

        fun bind(
            device: Device,
            fields: DeviceFields,
        ) {
            icon.setImageResource(iconFor(device.type))
            name.text = device.name
            battery.text = batteryText(fields.battery)
            renderState(device.type, fields)
            renderWarnings(fields)
        }

        private fun renderState(
            type: DeviceType,
            fields: DeviceFields,
        ) {
            val reading =
                when (type) {
                    // Zigbee2MQTT: contact=true means closed.
                    DeviceType.CONTACT_SENSOR -> fields.contact?.not()

                    DeviceType.MOTION_SENSOR -> fields.occupancy

                    else -> fields.vibration
                }
            state.setText(stateLabel(type, reading))
            state.setTextColor(
                ContextCompat.getColor(
                    context,
                    when (reading) {
                        null -> R.color.state_unknown
                        true -> R.color.state_alert
                        else -> R.color.state_ok
                    },
                ),
            )
        }

        private fun stateLabel(
            type: DeviceType,
            reading: Boolean?,
        ): Int =
            when (type) {
                DeviceType.CONTACT_SENSOR -> if (reading == true) R.string.state_open else R.string.state_closed
                DeviceType.MOTION_SENSOR -> if (reading == true) R.string.motion_detected else R.string.motion_clear
                else -> if (reading == true) R.string.vibration_detected else R.string.vibration_clear
            }

        private fun renderWarnings(fields: DeviceFields) {
            val warnings =
                buildList {
                    if (fields.tamper == true) add(context.getString(R.string.tamper_warning))
                    if (fields.batteryLow == true) add(context.getString(R.string.battery_low_warning))
                }
            warning.text = warnings.joinToString(" · ")
            warning.visibility = if (warnings.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    class TempHumidityHolder(
        itemView: View,
    ) : TileHolder(itemView) {
        private val name: TextView = itemView.findViewById(R.id.text_tile_name)
        private val battery: TextView = itemView.findViewById(R.id.text_tile_battery)
        private val temp: TextView = itemView.findViewById(R.id.text_temp_value)
        private val humidity: TextView = itemView.findViewById(R.id.text_humidity_value)

        fun bind(
            device: Device,
            fields: DeviceFields,
        ) {
            name.text = device.name
            battery.text = batteryText(fields.battery)
            temp.text =
                fields.temperature?.let { context.getString(R.string.temperature_format, it) }
                    ?: context.getString(R.string.no_data)
            humidity.text = fields.humidity?.let { context.getString(R.string.humidity_format, it) }.orEmpty()
        }
    }

    class PlugHolder(
        itemView: View,
        private val adapter: RoomTileAdapter,
    ) : TileHolder(itemView) {
        private val name: TextView = itemView.findViewById(R.id.text_tile_name)
        private val switch: SwitchMaterial = itemView.findViewById(R.id.switch_plug)
        private val power: TextView = itemView.findViewById(R.id.text_plug_power)
        private val energy: TextView = itemView.findViewById(R.id.text_plug_energy)
        private val childLock: ImageButton = itemView.findViewById(R.id.btn_child_lock)
        private val countdown: MaterialButton = itemView.findViewById(R.id.btn_countdown)
        private lateinit var bound: Device

        init {
            childLock.setOnClickListener {
                val locked = DeviceStateReader.childLock(adapter.stateLookup(bound.stateTopic)) == "LOCK"
                adapter.onChildLockToggled(bound, !locked)
            }
            countdown.setOnClickListener { adapter.onCountdownClicked(bound) }
        }

        fun bind(
            device: Device,
            fields: DeviceFields,
        ) {
            bound = device
            name.text = device.name

            // Detach before reflecting remote state so incoming payloads don't publish back.
            switch.setOnCheckedChangeListener(null)
            switch.isChecked = fields.state == "ON"
            // Read-only devices keep their controls visible (state/timer stay readable) but inert.
            val locked = device.readOnly
            switch.isEnabled = !locked
            childLock.isEnabled = !locked
            countdown.isEnabled = !locked
            if (!locked) {
                switch.setOnCheckedChangeListener { _, checked -> adapter.onPlugToggled(bound, checked) }
            }

            power.text = fields.power?.let { context.getString(R.string.power_watts_format, it) } ?: context.getString(R.string.no_data)
            energy.text = fields.energy?.let { context.getString(R.string.energy_kwh_format, it) }.orEmpty()

            val lockedChildLock = fields.childLock == "LOCK"
            childLock.setImageResource(if (lockedChildLock) R.drawable.ic_lock else R.drawable.ic_lock_open)
            childLock.imageTintList =
                android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(context, if (lockedChildLock) R.color.state_alert else R.color.state_ok),
                )

            val seconds = fields.countdown ?: 0
            countdown.text =
                if (seconds > 0) {
                    context.getString(R.string.countdown_active_format, seconds)
                } else {
                    context.getString(R.string.countdown_set)
                }
        }
    }

    class IrRemoteHolder(
        itemView: View,
        private val adapter: RoomTileAdapter,
    ) : TileHolder(itemView) {
        private val name: TextView = itemView.findViewById(R.id.text_tile_name)
        private val battery: TextView = itemView.findViewById(R.id.text_tile_battery)
        private val learnButton: MaterialButton = itemView.findViewById(R.id.btn_ir_learn)
        private val learnStatus: TextView = itemView.findViewById(R.id.text_ir_learn_status)
        private val chipGroup: ChipGroup = itemView.findViewById(R.id.chip_group_ir)
        private val emptyHint: TextView = itemView.findViewById(R.id.text_no_ir_commands)
        private val saveLast: MaterialButton = itemView.findViewById(R.id.btn_save_last_code)
        private lateinit var bound: Device

        /** Device id + command labels + lock state the current chips were built for; skips redundant rebuilds. */
        private var chipsFor: Triple<String, Set<String>, Boolean>? = null

        init {
            learnButton.setOnClickListener { adapter.onLearnIr(bound) }
            saveLast.setOnClickListener {
                DeviceStateReader.learnedIrCode(adapter.stateLookup(bound.stateTopic))?.let { code ->
                    adapter.onSaveLastIrCode(bound, code)
                }
            }
        }

        fun bind(
            device: Device,
            fields: DeviceFields,
        ) {
            bound = device
            name.text = device.name
            battery.text = batteryText(fields.battery)

            // Read-only remotes can neither learn nor fire commands; saved list stays visible.
            val locked = device.readOnly
            learnButton.isEnabled = !locked

            rebuildChipsIfNeeded(device)

            val commands = device.irCommands.orEmpty()
            val learned = fields.learnedIrCode
            val hasNewCode = learned != null && learned !in commands.values
            saveLast.isEnabled = !locked
            saveLast.visibility = if (hasNewCode) View.VISIBLE else View.GONE
            learnStatus.visibility =
                if (adapter.isLearning(device) && !hasNewCode) View.VISIBLE else View.GONE
            emptyHint.visibility = if (commands.isEmpty() && !hasNewCode) View.VISIBLE else View.GONE
        }

        private fun rebuildChipsIfNeeded(device: Device) {
            val locked = device.readOnly
            val key = Triple(device.id, device.irCommands.orEmpty().keys, locked)
            if (chipsFor == key) return
            chipsFor = key

            chipGroup.removeAllViews()
            device.irCommands.orEmpty().forEach { (label, _) ->
                val chip = Chip(context)
                chip.text = label
                chip.isCloseIconVisible = false
                chip.isCheckable = false
                chip.isClickable = true
                chip.isFocusable = true
                chip.isEnabled = !locked
                chip.setOnClickListener { adapter.onSendIrCommand(bound, label) }
                chip.setOnLongClickListener {
                    adapter.onDeleteIrCommand(bound, label)
                    true
                }
                chipGroup.addView(chip)
            }
        }
    }

    class ButtonHolder(
        itemView: View,
    ) : TileHolder(itemView) {
        private val name: TextView = itemView.findViewById(R.id.text_tile_name)
        private val battery: TextView = itemView.findViewById(R.id.text_tile_battery)
        private val action: TextView = itemView.findViewById(R.id.text_button_action)
        private val hint: TextView = itemView.findViewById(R.id.text_button_hint)

        fun bind(
            device: Device,
            fields: DeviceFields,
        ) {
            name.text = device.name
            battery.text = batteryText(fields.battery)

            val labelRes =
                when (fields.action) {
                    "single" -> R.string.action_single
                    "double" -> R.string.action_double
                    "hold" -> R.string.action_hold
                    else -> null
                }
            action.text =
                labelRes?.let { context.getString(R.string.last_action_format, context.getString(it)) }
                    ?: context.getString(R.string.no_data)
            hint.visibility = if (labelRes == null) View.VISIBLE else View.GONE
        }
    }

    companion object {
        fun iconFor(type: DeviceType): Int =
            when (type) {
                DeviceType.CONTACT_SENSOR -> R.drawable.ic_contact_sensor
                DeviceType.SMART_PLUG -> R.drawable.ic_smart_plug
                DeviceType.IR_REMOTE -> R.drawable.ic_ir_remote
                DeviceType.SMART_BUTTON -> R.drawable.ic_smart_button
                DeviceType.TEMP_HUMIDITY_SENSOR -> R.drawable.ic_temp_humidity
                DeviceType.MOTION_SENSOR -> R.drawable.ic_motion
                DeviceType.VIBRATION_SENSOR -> R.drawable.ic_vibration
            }
    }

    class TileDiffCallback : DiffUtil.ItemCallback<Device>() {
        override fun areItemsTheSame(
            oldItem: Device,
            newItem: Device,
        ) = oldItem.id == newItem.id

        override fun areContentsTheSame(
            oldItem: Device,
            newItem: Device,
        ) = oldItem == newItem
    }
}
