package com.chargergreetings.app.core

/**
 * The only thing this app cares about: is external power attached or not.
 *
 * [UNKNOWN] is a real, expected value — the sticky battery broadcast is
 * occasionally unavailable in the first moments after boot — and it is always
 * treated as "no information", never as a state change.
 */
enum class PowerState {
    UNKNOWN,
    UNPLUGGED,
    PLUGGED;

    val isKnown: Boolean get() = this != UNKNOWN
}

/**
 * How the device is being charged. Recorded for the UI and the diagnostic log
 * only — the greeting itself is identical for all of them, because from the
 * user's point of view "the charger is on" is one event however it arrived.
 */
enum class ChargeKind {
    NONE,
    AC,
    USB,
    WIRELESS,
    DOCK,
    OTHER;

    val label: String
        get() = when (this) {
            NONE -> "not charging"
            AC -> "AC charger"
            USB -> "USB"
            WIRELESS -> "wireless"
            DOCK -> "dock"
            OTHER -> "external power"
        }
}

/** Which greeting to speak. */
enum class Greeting {
    CONNECTED,
    DISCONNECTED
}
