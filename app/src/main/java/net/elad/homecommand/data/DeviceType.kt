package net.elad.homecommand.data

enum class DeviceType(
    val label: String,
) {
    CONTACT_SENSOR("Door/Window Sensor"),
    SMART_PLUG("Smart Plug"),
    IR_REMOTE("IR Remote"),
    SMART_BUTTON("Smart Button"),
    TEMP_HUMIDITY_SENSOR("Temperature & Humidity"),
    MOTION_SENSOR("Motion Sensor"),
    VIBRATION_SENSOR("Vibration Sensor"),
}
