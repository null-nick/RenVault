package io.lauren.renvault.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalWindowInfo

enum class RvEditAction(val mask: Int) {
    Undo(1), Redo(2), Cut(4), Copy(8), Paste(16), SelectAll(32),
}

/** Publishes capabilities only; field contents never cross the native menu bridge. */
class RvEditMenu(private val onChange: (Int) -> Unit) {
    private var owner: Any? = null
    private var mask = 0

    internal fun update(source: Any, actions: Int) {
        owner = source
        if (mask == actions) return
        mask = actions
        onChange(actions)
    }

    internal fun clear(source: Any) {
        if (owner !== source) return
        update(source, 0)
        owner = null
    }
}

val LocalEditMenu = staticCompositionLocalOf<RvEditMenu?> { null }

@Composable
internal fun rememberEditFieldState(value: String, onValueChange: (String) -> Unit): TextFieldState {
    val state = remember { TextFieldState(value) }
    val currentValue by rememberUpdatedState(value)
    val onChange by rememberUpdatedState(onValueChange)
    LaunchedEffect(value) {
        if (state.text.toString() != value) state.setTextAndPlaceCursorAtEnd(value)
    }
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }.collect { text ->
            if (text != currentValue) onChange(text)
        }
    }
    return state
}

@OptIn(ExperimentalFoundationApi::class)
internal fun TextFieldState.editMenuActions(sensitive: Boolean): Int {
    var mask = RvEditAction.Paste.mask // AppKit checks the current pasteboard when validating.
    if (undoState.canUndo) mask = mask or RvEditAction.Undo.mask
    if (undoState.canRedo) mask = mask or RvEditAction.Redo.mask
    if (!sensitive && !selection.collapsed) mask = mask or RvEditAction.Cut.mask or RvEditAction.Copy.mask
    if (selection.length != text.length) mask = mask or RvEditAction.SelectAll.mask
    return mask
}

@Composable
internal fun ObserveEditMenu(
    state: TextFieldState,
    interactionSource: MutableInteractionSource,
    enabled: Boolean = true,
    sensitive: Boolean = false,
) {
    val menu = LocalEditMenu.current ?: return
    val focused by interactionSource.collectIsFocusedAsState()
    val window = LocalWindowInfo.current
    val currentEnabled by rememberUpdatedState(enabled)
    val currentProtected by rememberUpdatedState(sensitive)
    LaunchedEffect(menu, state) {
        snapshotFlow {
            if (focused && currentEnabled && window.isWindowFocused) state.editMenuActions(currentProtected) else null
        }.collect { actions ->
            if (actions == null) menu.clear(state) else menu.update(state, actions)
        }
    }
    DisposableEffect(menu, state) {
        onDispose { menu.clear(state) }
    }
}
