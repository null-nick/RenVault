package io.lauren.renvault.ui.components

data class RvAccountMenuActions(
    val local: Boolean,
    val openSettings: () -> Unit,
    val lock: () -> Unit,
    val signOut: () -> Unit,
    val editProfile: (() -> Unit)? = null,
    val openDevices: (() -> Unit)? = null,
)
