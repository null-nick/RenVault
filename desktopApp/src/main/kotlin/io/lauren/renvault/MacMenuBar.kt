package io.lauren.renvault

import io.lauren.renvault.lib.RvStrings
import io.lauren.renvault.ui.components.RvAccountMenuActions
import io.lauren.renvault.ui.components.RvEditAction
import java.awt.EventQueue
import java.awt.KeyboardFocusManager
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal object MacMenuBar {
    init {
        val library = Files.createTempFile("renvault-menu-bar-", ".dylib")
        library.toFile().deleteOnExit()
        val stream = checkNotNull(javaClass.getResourceAsStream("/native/librenvault-menu-bar.dylib")) {
            "The macOS menu library is missing from the runtime classpath"
        }
        stream.use { Files.copy(it, library, StandardCopyOption.REPLACE_EXISTING) }
        System.load(library.toAbsolutePath().toString())
    }

    private var onOpen: () -> Unit = {}
    private var accountActions: RvAccountMenuActions? = null
    private var installed = false
    private var editActions = 0

    fun install(strings: RvStrings, onOpen: () -> Unit) {
        this.onOpen = onOpen
        installNative(strings)
        installed = true
        syncAccountState()
        updateEditNative(editActions)
    }

    fun updateEdit(actions: Int) {
        editActions = actions
        if (installed) updateEditNative(actions)
    }

    fun updateAccount(actions: RvAccountMenuActions?) {
        if (accountActions === actions) return
        accountActions = actions
        if (installed) syncAccountState()
    }

    private fun syncAccountState() {
        val actions = accountActions
        updateAccountNative(actions != null, actions?.local == true, actions?.editProfile != null, actions?.openDevices != null)
    }

    // Called on AppKit's thread. Compose and AWT input must run on the EDT.
    @JvmStatic
    private fun performAction(keyCode: Int, shift: Boolean) {
        EventQueue.invokeLater {
            if (keyCode == 0) {
                onOpen()
                return@invokeLater
            }
            if (keyCode < 0) {
                val actions = accountActions ?: return@invokeLater
                val action = when (keyCode) {
                    -1 -> actions.openSettings
                    -2 -> actions.editProfile
                    -3 -> actions.openDevices
                    -4 -> actions.lock
                    -5 -> actions.signOut
                    else -> null
                } ?: return@invokeLater
                onOpen()
                action()
                return@invokeLater
            }
            val editAction = when (keyCode) {
                KeyEvent.VK_Z -> if (shift) RvEditAction.Redo else RvEditAction.Undo
                KeyEvent.VK_X -> RvEditAction.Cut
                KeyEvent.VK_C -> RvEditAction.Copy
                KeyEvent.VK_V -> RvEditAction.Paste
                KeyEvent.VK_A -> RvEditAction.SelectAll
                else -> return@invokeLater
            }
            if (editActions and editAction.mask == 0) return@invokeLater
            val manager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
            val target = manager.focusOwner ?: return@invokeLater
            val modifiers = InputEvent.META_DOWN_MASK or if (shift) InputEvent.SHIFT_DOWN_MASK else 0
            val whenMillis = System.currentTimeMillis()
            for (type in intArrayOf(KeyEvent.KEY_PRESSED, KeyEvent.KEY_RELEASED)) {
                manager.redispatchEvent(target, KeyEvent(target, type, whenMillis, modifiers, keyCode, KeyEvent.CHAR_UNDEFINED))
            }
        }
    }

    private external fun installNative(strings: RvStrings)
    private external fun updateAccountNative(enabled: Boolean, local: Boolean, profile: Boolean, devices: Boolean)
    private external fun updateEditNative(actions: Int)
}
