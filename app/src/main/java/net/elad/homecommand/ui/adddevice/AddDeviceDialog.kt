package net.elad.homecommand.ui.adddevice

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.gson.Gson
import net.elad.homecommand.R
import net.elad.homecommand.data.Device
import net.elad.homecommand.data.DeviceType
import net.elad.homecommand.data.MqttSettings
import net.elad.homecommand.data.Room
import net.elad.homecommand.ui.home.HomeViewModel
import net.elad.homecommand.ui.settings.SettingsViewModel

/**
 * Add/edit device dialog. Topics are auto-constructed from the device name and the
 * configured topic base — no manual topic entry needed.
 */
class AddDeviceDialog : DialogFragment() {
    private val viewModel: HomeViewModel by lazy { HomeViewModel.get(requireActivity()) }
    private val settingsViewModel: SettingsViewModel by lazy { SettingsViewModel.get(requireContext()) }
    private val gson = Gson()

    private lateinit var inputName: TextInputLayout
    private lateinit var editName: TextInputEditText
    private lateinit var textTopicPreview: TextView
    private lateinit var spinnerType: AutoCompleteTextView
    private lateinit var spinnerRoom: AutoCompleteTextView
    private lateinit var rowReadOnly: LinearLayout
    private lateinit var switchReadOnly: SwitchMaterial
    private lateinit var switchNotify: SwitchMaterial

    private var selectedType: DeviceType = DeviceType.SMART_PLUG
    private var selectedRoomId: String? = null
    private var existing: Device? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        existing = arguments?.getString(ARG_EXISTING_JSON)?.let { gson.fromJson(it, Device::class.java) }
        val view = layoutInflater.inflate(R.layout.dialog_add_device, null)
        bindViews(view)

        setupTypeSpinner()
        setupRoomSpinner(viewModel.rooms.value)
        setupTopicPreview()
        populateExisting()

        val dialog =
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(if (existing == null) R.string.add_device else R.string.edit_device)
                .setView(view)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(if (existing == null) R.string.add else R.string.save, null)
                .create()
        dialog.setOnShowListener {
            dialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener { submit() }
        }
        return dialog
    }

    private fun bindViews(view: View) {
        inputName = view.findViewById(R.id.input_device_name)
        editName = view.findViewById(R.id.edit_device_name)
        textTopicPreview = view.findViewById(R.id.text_topic_preview)
        spinnerType = view.findViewById(R.id.spinner_type)
        spinnerRoom = view.findViewById(R.id.spinner_room)
        rowReadOnly = view.findViewById(R.id.row_read_only)
        switchReadOnly = view.findViewById(R.id.switch_read_only)
        switchNotify = view.findViewById(R.id.switch_notify)
    }

    private fun setupTopicPreview() {
        val base = topicBase()
        textTopicPreview.text = getString(R.string.topic_preview_format, "$base/")
        editName.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) = Unit

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int,
                ) = Unit

                override fun afterTextChanged(s: Editable?) {
                    updateTopicPreview(s?.toString()?.trim().orEmpty())
                }
            },
        )
    }

    private fun updateTopicPreview(name: String) {
        if (name.isEmpty()) {
            textTopicPreview.visibility = View.GONE
            return
        }
        val base = topicBase()
        val stateTopic = "$base/$name"
        val writable = selectedType == DeviceType.SMART_PLUG || selectedType == DeviceType.IR_REMOTE
        textTopicPreview.text =
            if (writable) {
                getString(R.string.topic_preview_command_format, stateTopic, "$stateTopic/set")
            } else {
                getString(R.string.topic_preview_format, stateTopic)
            }
        textTopicPreview.visibility = View.VISIBLE
    }

    private fun topicBase(): String = settingsViewModel.settings.value?.effectiveTopicBase() ?: MqttSettings.DEFAULT_TOPIC_BASE

    private fun setupTypeSpinner() {
        val labels = DeviceType.entries.map { it.label }
        spinnerType.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, labels))
        spinnerType.setText(selectedType.label, false)
        spinnerType.setOnItemClickListener { _, _, position, _ ->
            selectedType = DeviceType.entries[position]
            applyTypeVisibility()
            updateTopicPreview(editName.text.toString().trim())
        }
        applyTypeVisibility()
    }

    private fun applyTypeVisibility() {
        val writable = selectedType == DeviceType.SMART_PLUG || selectedType == DeviceType.IR_REMOTE
        rowReadOnly.visibility = if (writable) View.VISIBLE else View.GONE
        if (!writable) switchReadOnly.isChecked = false
    }

    private fun setupRoomSpinner(rooms: List<Room>) {
        val roomNames = rooms.map { it.name } + listOf(getString(R.string.unassigned_none))
        spinnerRoom.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, roomNames))

        val prefillId = arguments?.getString(ARG_PREFILL_ROOM_ID) ?: existing?.roomId
        selectedRoomId = prefillId?.takeIf { id -> rooms.any { it.id == id } }
        spinnerRoom.setText(roomNameFor(selectedRoomId, rooms), false)
        spinnerRoom.setOnItemClickListener { _, _, position, _ ->
            selectedRoomId = rooms.getOrNull(position)?.id
        }
    }

    private fun roomNameFor(
        roomId: String?,
        rooms: List<Room>,
    ): String = roomId?.let { id -> rooms.firstOrNull { it.id == id }?.name } ?: getString(R.string.unassigned_none)

    private fun populateExisting() {
        val device = existing ?: return
        editName.setText(device.name)
        selectedType = device.type
        spinnerType.setText(device.type.label, false)
        applyTypeVisibility()
        switchReadOnly.isChecked = device.readOnly
        switchNotify.isChecked = device.notifyOnStateChange
        updateTopicPreview(device.name)
    }

    private fun submit() {
        val device = buildValidatedDevice() ?: return
        setFragmentResult(
            REQUEST_KEY,
            Bundle(1).apply { putString(RESULT_DEVICE_JSON, gson.toJson(device)) },
        )
        dismiss()
    }

    private fun buildValidatedDevice(): Device? {
        val name = editName.text.toString().trim()
        if (name.isEmpty()) {
            inputName.error = getString(R.string.required)
            return null
        }

        val base = topicBase()
        val writable = selectedType == DeviceType.SMART_PLUG || selectedType == DeviceType.IR_REMOTE

        return (existing ?: Device(name = "", type = selectedType)).copy(
            name = name,
            type = selectedType,
            roomId = selectedRoomId,
            stateTopic = "$base/$name",
            commandTopic = if (writable) "$base/$name/set" else null,
            readOnly = switchReadOnly.isChecked,
            notifyOnStateChange = switchNotify.isChecked,
        )
    }

    companion object {
        const val REQUEST_KEY = "device_edit_result"
        const val RESULT_DEVICE_JSON = "device_json"

        fun newInstance(
            existing: Device? = null,
            prefillRoomId: String? = null,
        ): AddDeviceDialog =
            AddDeviceDialog().apply {
                arguments =
                    Bundle(2).apply {
                        putString(ARG_EXISTING_JSON, existing?.let { Gson().toJson(it) })
                        putString(ARG_PREFILL_ROOM_ID, prefillRoomId)
                    }
            }

        private const val ARG_EXISTING_JSON = "existingJson"
        private const val ARG_PREFILL_ROOM_ID = "prefillRoomId"
    }
}
