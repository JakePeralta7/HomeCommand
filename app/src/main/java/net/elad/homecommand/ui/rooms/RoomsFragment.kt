package net.elad.homecommand.ui.rooms

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResultListener
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.transition.MaterialFadeThrough
import com.google.gson.Gson
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import net.elad.homecommand.R
import net.elad.homecommand.data.Device
import net.elad.homecommand.data.Room
import net.elad.homecommand.mqtt.DeviceStateReader
import net.elad.homecommand.ui.adddevice.AddDeviceDialog
import net.elad.homecommand.ui.home.HomeViewModel
import net.elad.homecommand.ui.widgets.pushOptions

/** Home screen: list of rooms plus an unassigned-devices section. */
class RoomsFragment : Fragment() {
    private val viewModel: HomeViewModel by lazy { HomeViewModel.get(requireContext()) }
    private val gson = Gson()
    private lateinit var adapter: RoomsAdapter
    private lateinit var itemTouchHelper: ItemTouchHelper

    /** Kept for [scrollToTop]; views persist under MainActivity's show/hide tabs. */
    private var roomsList: RecyclerView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Material's prescribed transition for bottom-nav destinations: fade through, no slide.
        enterTransition = MaterialFadeThrough()
        exitTransition = MaterialFadeThrough()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? = inflater.inflate(R.layout.fragment_rooms, container, false)

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        val recyclerView = view.findViewById<RecyclerView>(R.id.recycler_rooms).also { roomsList = it }

        adapter =
            RoomsAdapter(
                onRoomClick = ::openRoom,
                onRoomLongClick = ::showRoomOptions,
                onUnassignedClick = ::showEditDialog,
                onUnassignedLongClick = ::showDeviceOptions,
                onDragStart = { holder -> itemTouchHelper.startDrag(holder) },
            )
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter
        // ItemTouchHelper manages all drag movement; the default animator conflicts with it.
        recyclerView.itemAnimator = null

        itemTouchHelper =
            ItemTouchHelper(
                RoomDragCallback(adapter) { orderedIds -> viewModel.reorderRooms(orderedIds) },
            )
        itemTouchHelper.attachToRecyclerView(recyclerView)

        view.findViewById<FloatingActionButton>(R.id.fab_add_room).setOnClickListener {
            showRoomNameDialog(room = null)
        }

        observeViewModel(view)
        listenForDeviceEdits()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    /** Bottom-nav reselect reset: jump the room list back to the top. */
    fun scrollToTop() {
        roomsList?.smoothScrollToPosition(0)
    }

    private fun observeViewModel(root: View) {
        val textEmpty = root.findViewById<View>(R.id.text_rooms_empty)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(viewModel.rooms, viewModel.devices, viewModel.states) { rooms, devices, states ->
                        Triple(rooms, devices, states)
                    }.collect { (rooms, devices, _) ->
                        renderRows(rooms, devices)
                        textEmpty.visibility = if (rooms.isEmpty() && devices.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    viewModel.commandFailures.collect { failure ->
                        Snackbar
                            .make(root, getString(R.string.command_failed_format, failure.deviceName), Snackbar.LENGTH_SHORT)
                            .show()
                    }
                }
            }
        }
    }

    private fun renderRows(
        rooms: List<Room>,
        devices: List<Device>,
    ) {
        val rows =
            buildList {
                rooms.sortedBy { it.position }.forEach { room -> add(RoomsAdapter.Row.RoomCard(room, summaryFor(room, devices))) }
                val unassigned = devices.filter { it.roomId == null }
                if (unassigned.isNotEmpty()) {
                    add(RoomsAdapter.Row.UnassignedHeader)
                    unassigned.forEach { device ->
                        add(RoomsAdapter.Row.UnassignedDevice(device, device.type.label))
                    }
                }
            }
        adapter.submitList(rows)
    }

    /** One-line health summary: counts of open contacts, active motion and switched-on plugs. */
    private fun summaryFor(
        room: Room,
        allDevices: List<Device>,
    ): String {
        val roomDevices = allDevices.filter { it.roomId == room.id }
        val parts = mutableListOf<String>()

        fun countIf(predicate: (String) -> Boolean): Int =
            roomDevices.count { device ->
                device.stateTopic?.let { topic -> viewModel.getDeviceState(topic) }?.let(predicate) == true
            }

        val open = countIf { DeviceStateReader.contact(it) == false }
        if (open > 0) parts.add(getString(R.string.summary_open_format, open))

        val motion = countIf { DeviceStateReader.occupancy(it) == true }
        if (motion > 0) parts.add(getString(R.string.summary_motion_format, motion))

        val on = countIf { DeviceStateReader.state(it) == "ON" }
        if (on > 0) parts.add(getString(R.string.summary_on_format, on))

        val countLine = getString(R.string.device_count_format, roomDevices.size)
        return listOf(countLine, *parts.toTypedArray()).joinToString(getString(R.string.room_summary_separator))
    }

    private fun openRoom(room: Room) {
        startActivity(
            Intent(requireContext(), RoomDetailActivity::class.java)
                .putExtra(RoomDetailActivity.EXTRA_ROOM_ID, room.id),
            (activity as? AppCompatActivity)?.pushOptions(),
        )
    }

    private fun showRoomNameDialog(room: Room?) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_room_name, null)
        val edit = dialogView.findViewById<TextInputEditText>(R.id.edit_room_name)
        edit.setText(room?.name.orEmpty())

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (room == null) R.string.add_room else R.string.rename_room)
            .setView(dialogView)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = edit.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                if (room == null) {
                    viewModel.saveRoom(name)
                    Toast.makeText(requireContext(), R.string.room_added, Toast.LENGTH_SHORT).show()
                } else {
                    viewModel.renameRoom(room, name)
                    Toast.makeText(requireContext(), R.string.room_updated, Toast.LENGTH_SHORT).show()
                }
            }.setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showRoomOptions(room: Room) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(room.name)
            .setItems(arrayOf(getString(R.string.rename_room), getString(R.string.delete))) { _, which ->
                when (which) {
                    0 -> showRoomNameDialog(room)
                    1 -> confirmDeleteRoom(room)
                }
            }.setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteRoom(room: Room) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.confirm_delete_room)
            .setMessage(R.string.confirm_delete_room_message)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewModel.deleteRoom(room)
                Toast.makeText(requireContext(), R.string.room_deleted, Toast.LENGTH_SHORT).show()
            }.setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun listenForDeviceEdits() {
        setFragmentResultListener(AddDeviceDialog.REQUEST_KEY) { _, bundle ->
            val json = bundle.getString(AddDeviceDialog.RESULT_DEVICE_JSON) ?: return@setFragmentResultListener
            val device = gson.fromJson(json, Device::class.java)
            val wasNew = viewModel.saveDevice(device)
            val messageRes = if (wasNew) R.string.device_added else R.string.device_updated
            Toast.makeText(requireContext(), messageRes, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showEditDialog(device: Device) {
        AddDeviceDialog
            .newInstance(existing = device)
            .show(parentFragmentManager, EDIT_TAG)
    }

    private fun showDeviceOptions(device: Device) {
        MaterialAlertDialogBuilder(requireContext())
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

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(device.name)
            .setMessage(info)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun confirmDeleteDevice(device: Device) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.confirm_delete)
            .setMessage(R.string.confirm_delete_message)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewModel.deleteDevice(device)
                Toast.makeText(requireContext(), R.string.device_deleted, Toast.LENGTH_SHORT).show()
            }.setNegativeButton(R.string.cancel, null)
            .show()
    }

    private companion object {
        const val EDIT_TAG = "edit_device"
    }
}
