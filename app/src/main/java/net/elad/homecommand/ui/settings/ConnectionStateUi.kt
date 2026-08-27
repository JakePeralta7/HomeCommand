package net.elad.homecommand.ui.settings

import net.elad.homecommand.R
import net.elad.homecommand.mqtt.ConnectionState

/** Shared mappings so the hub row and the connection page render states identically. */
internal val ConnectionState.labelRes: Int
    get() =
        when (this) {
            ConnectionState.Connected -> R.string.status_connected
            ConnectionState.Connecting -> R.string.status_connecting
            ConnectionState.Disconnected -> R.string.status_disconnected
        }

internal val ConnectionState.dotColorRes: Int
    get() =
        when (this) {
            ConnectionState.Connected -> R.color.status_subscribed
            ConnectionState.Connecting -> R.color.status_connecting
            ConnectionState.Disconnected -> R.color.status_unsubscribed
        }
