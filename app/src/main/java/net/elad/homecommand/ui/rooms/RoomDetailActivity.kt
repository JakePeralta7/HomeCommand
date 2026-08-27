package net.elad.homecommand.ui.rooms

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.elad.homecommand.R
import net.elad.homecommand.data.Device
import net.elad.homecommand.mqtt.MqttPayloads
import net.elad.homecommand.mqtt.isLearnedCodeArrival
import net.elad.homecommand.ui.adddevice.AddDeviceDialog
import net.elad.homecommand.ui.home.HomeViewModel
import net.elad.homecommand.ui.widgets.BreadcrumbBarView
import net.elad.homecommand.ui.widgets.applySubScreenMotion
import net.elad.homecommand.ui.widgets.installNavigationDrawer
import net.elad.homecommand.ui.widgets.installSubScreenChrome

/** Interactive grid of predefined tiles for one room; a separate activity so system back just finishes. */
class RoomDetailActivity : AppCompatActivity() {
    private val viewModel = HomeViewModel.get(this)
    private val gson = Gson()

    /** Device ids currently in IR learning mode, for the tile's waiting indicator. */
    private val learningDevices = MutableStateFlow<Set<String>>(emptySet())

    private lateinit var breadcrumb: BreadcrumbBarView
    private lateinit var adapter: RoomTileAdapter
    private lateinit var itemTouchHelper: ItemTouchHelper
    private lateinit var textEmpty: View
    private var roomId: String = ""

    /** Guards against rebuilding the trail on every unrelated state emission. */
    private var crumbedRoomName: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_room_detail)

        roomId = intent.getStringExtra(EXTRA_ROOM_ID).orEmpty()
        savedInstanceState?.getStringArrayList(KEY_LEARNING_DEVICES)?.let { learningDevices.value = it.toSet() }
        breadcrumb = findViewById(R.id.breadcrumb)
        textEmpty = findViewById(R.id.text_room_empty)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        installSubScreenChrome(root = findViewById(R.id.room_detail_root))
        installNavigationDrawer()
        applySubScreenMotion(R.id.room_detail_root)

        adapter =
            RoomTileAdapter(
                stateLookup = viewModel::getDeviceState,
                isLearning = { it.id in learningDevices.value },
                onPlugToggled = ::togglePlug,
                onChildLockToggled = ::toggleChildLock,
                onCountdownClicked = ::showCountdownDialog,
                onLearnIr = ::startIrLearning,
                onSaveLastIrCode = ::promptSaveIrCode,
                onSendIrCommand = ::sendIrCommand,
                onDeleteIrCommand = ::confirmDeleteIrCommand,
                onDeviceLongClick = ::showDeviceOptions,
                onDragStart = { holder -> itemTouchHelper.startDrag(holder) },
            )
        val recyclerView = findViewById<RecyclerView>(R.id.recycler_tiles)
        recyclerView.layoutManager = GridLayoutManager(this, GRID_SPAN)
        recyclerView.adapter = adapter
        // ItemTouchHelper manages all drag movement; the default animator conflicts with it.
        recyclerView.itemAnimator = null

        itemTouchHelper =
            ItemTouchHelper(
                DeviceDragCallback(adapter) { orderedIds -> viewModel.reorderDevices(orderedIds) },
            )
        itemTouchHelper.attachToRecyclerView(recyclerView)

        findViewById<FloatingActionButton>(R.id.fab_add_device_to_room).setOnClickListener {
            showAddDialog()
        }

        observeRoomsAndDevices()
        observeStates()
        observeCommandFailures()
        listenForDeviceEdits()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(KEY_LEARNING_DEVICES, ArrayList(learningDevices.value))
    }

    private fun observeRoomsAndDevices() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(viewModel.rooms, viewModel.devices) { rooms, devices ->
                    rooms to devices
                }.collect { (rooms, devices) ->
                    if (!viewModel.loaded.value) return@collect
                    val room = rooms.firstOrNull { it.id == roomId }
                    if (room == null) {
                        // Room was deleted while its detail screen was open.
                        finish()
                        return@collect
                    }
                    if (crumbedRoomName != room.name) {
                        crumbedRoomName = room.name
                        breadcrumb.setPath(
                            BreadcrumbBarView.Crumb(getString(R.string.tab_rooms)) { finish() },
                            BreadcrumbBarView.Crumb(room.name),
                        )
                    }
                    val roomDevices = devices.filter { it.roomId == room.id }.sortedBy { it.position }
                    adapter.submitList(roomDevices)
                    textEmpty.visibility = if (roomDevices.isEmpty()) View.VISIBLE else View.GONE
                    findViewById<RecyclerView>(R.id.recycler_tiles).visibility =
                        if (roomDevices.isEmpty()) View.GONE else View.VISIBLE
                }
            }
        }
    }

    private fun observeStates() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                var previous = emptyMap<String, String>()
                viewModel.states.collect { current ->
                    current.forEach { (topic, payload) ->
                        if (previous[topic] != payload) {
                            if (isLearnedCodeArrival(topic, previous[topic], payload, viewModel.devices.value, learningDevices.value)) {
                                stopLearningForTopic(topic)
                            }
                            adapter.notifyDeviceStateChanged(topic)
                        }
                    }
                    previous = current
                }
            }
        }
    }

    private fun observeCommandFailures() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.commandFailures.collect { failure ->
                    Snackbar
                        .make(
                            findViewById(R.id.room_detail_root),
                            getString(R.string.command_failed_format, failure.deviceName),
                            Snackbar.LENGTH_SHORT,
                        ).show()
                }
            }
        }
    }

    /** Learning ends only for devices bound to [topic]; another remote learning elsewhere keeps its indicator. */
    private fun stopLearningForTopic(topic: String) {
        learningDevices.update { ids ->
            ids
                .filter { id ->
                    viewModel.devices.value.none { it.id == id && it.stateTopic == topic }
                }.toSet()
        }
    }

    /** Learning ends by saving the code or abandoning the dialog; both paths clear the flag. */
    private fun stopLearning(device: Device) {
        learningDevices.update { it - device.id }
        device.stateTopic?.let(adapter::notifyDeviceStateChanged)
    }

    private fun togglePlug(
        device: Device,
        on: Boolean,
    ) {
        viewModel.sendCommand(device, if (on) MqttPayloads.STATE_ON_PAYLOAD else MqttPayloads.STATE_OFF_PAYLOAD)
    }

    private fun toggleChildLock(
        device: Device,
        lock: Boolean,
    ) {
        viewModel.sendCommand(device, if (lock) MqttPayloads.CHILD_LOCK_PAYLOAD else MqttPayloads.CHILD_UNLOCK_PAYLOAD)
    }

    private fun startIrLearning(device: Device) {
        learningDevices.update { it + device.id }
        viewModel.sendCommand(device, MqttPayloads.LEARN_IR_PAYLOAD)
        Toast.makeText(this, R.string.learn_ir_waiting, Toast.LENGTH_SHORT).show()
        device.stateTopic?.let(adapter::notifyDeviceStateChanged)
    }

    private fun sendIrCommand(
        device: Device,
        label: String,
    ) {
        device.irCommands?.get(label)?.let { code ->
            viewModel.sendCommand(device, MqttPayloads.sendIrPayload(code))
        }
    }

    private fun promptSaveIrCode(
        device: Device,
        code: String,
    ) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_ir_name, null)
        val edit = dialogView.findViewById<TextInputEditText>(R.id.edit_ir_command_name)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.save_last_ir_code)
            .setView(dialogView)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = edit.text.toString().trim()
                if (name.isNotEmpty()) {
                    viewModel.saveIrCommand(device, name, code)
                }
                stopLearning(device)
            }.setNegativeButton(R.string.cancel) { _, _ -> stopLearning(device) }
            .show()
    }

    private fun confirmDeleteIrCommand(
        device: Device,
        label: String,
    ) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_command)
            .setMessage(R.string.confirm_delete_command_message)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewModel.deleteIrCommand(device, label)
            }.setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showCountdownDialog(device: Device) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_countdown, null)
        val edit = dialogView.findViewById<TextInputEditText>(R.id.edit_countdown_seconds)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.countdown_title)
            .setView(dialogView)
            .setPositiveButton(R.string.save) { _, _ ->
                val seconds = MqttPayloads.parseCountdown(edit.text.toString())
                if (seconds == null) {
                    Toast.makeText(this, R.string.countdown_invalid, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                viewModel.sendCommand(device, MqttPayloads.countdownPayload(seconds))
            }.setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showAddDialog() {
        AddDeviceDialog
            .newInstance(prefillRoomId = roomId)
            .show(supportFragmentManager, ADD_TAG)
    }

    private fun showEditDialog(device: Device) {
        AddDeviceDialog
            .newInstance(existing = device)
            .show(supportFragmentManager, EDIT_TAG)
    }

    private fun showDeviceOptions(device: Device) {
        MaterialAlertDialogBuilder(this)
            .setTitle(device.name)
            .setItems(
                arrayOf(
                    getString(R.string.edit),
                    getString(R.string.device_info),
                    getString(R.string.delete),
                ),
            ) { _, which ->
                when (which) {
                    0 -> showEditDialog(device)
                    1 -> showDeviceInfo(device)
                    2 -> confirmDeleteDevice(device)
                }
            }.setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showDeviceInfo(device: Device) {
        val roomName =
            viewModel.rooms.value
                .find { it.id == device.roomId }
                ?.name
                ?: getString(R.string.none)
        val info =
            buildString {
                val none = getString(R.string.none)
                appendLine(
                    "${getString(R.string.device_info_type)}:" +
                        " ${device.type.label}",
                )
                appendLine(
                    "${getString(R.string.device_info_room)}: $roomName",
                )
                appendLine(
                    "${getString(R.string.device_info_state_topic)}:" +
                        " ${device.stateTopic ?: none}",
                )
                appendLine(
                    "${getString(R.string.device_info_command_topic)}:" +
                        " ${device.commandTopic ?: none}",
                )
                val yes = getString(R.string.yes)
                val no = getString(R.string.no)
                appendLine(
                    "${getString(R.string.device_info_read_only)}:" +
                        " ${if (device.readOnly) yes else no}",
                )
                append(
                    "${getString(R.string.device_info_notify)}:" +
                        " ${if (device.notifyOnStateChange) yes else no}",
                )
            }

        MaterialAlertDialogBuilder(this)
            .setTitle(device.name)
            .setMessage(info)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun confirmDeleteDevice(device: Device) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.confirm_delete)
            .setMessage(R.string.confirm_delete_message)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewModel.deleteDevice(device)
                Toast.makeText(this, R.string.device_deleted, Toast.LENGTH_SHORT).show()
            }.setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun listenForDeviceEdits() {
        supportFragmentManager.setFragmentResultListener(AddDeviceDialog.REQUEST_KEY, this) { _, bundle ->
            val json = bundle.getString(AddDeviceDialog.RESULT_DEVICE_JSON) ?: return@setFragmentResultListener
            val device = gson.fromJson(json, Device::class.java)
            val wasNew = viewModel.saveDevice(device)
            val messageRes = if (wasNew) R.string.device_added else R.string.device_updated
            Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val EXTRA_ROOM_ID = "roomId"
        private const val GRID_SPAN = 2
        private const val ADD_TAG = "add_device"
        private const val EDIT_TAG = "edit_device"
        private const val KEY_LEARNING_DEVICES = "learningDevices"
    }
}
