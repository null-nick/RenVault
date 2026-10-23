package io.lauren.renvault.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.unit.IntSize
import java.awt.Canvas
import org.jetbrains.skia.Surface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(InternalComposeUiApi::class)
class EditMenuTest {
    @Test
    fun selectionAndRealUndoHistoryDriveTheMenu() = withField { field ->
        assertEquals(0, field.actions)
        field.focus()
        field.assertActions(RvEditAction.Paste)
        field.type("ab")
        assertEquals("ab", field.value)
        field.assertActions(RvEditAction.Paste, RvEditAction.Undo, RvEditAction.SelectAll)
        field.command(Key.A)
        field.assertActions(RvEditAction.Paste, RvEditAction.Undo, RvEditAction.Cut, RvEditAction.Copy)
        field.command(Key.Z)
        assertEquals("", field.value)
        field.assertActions(RvEditAction.Paste, RvEditAction.Redo)
        field.command(Key.Z, shift = true)
        assertEquals("ab", field.value)
        assertTrue(field.available(RvEditAction.Undo))
        assertFalse(field.available(RvEditAction.Redo))
    }

    @Test
    fun disabledUnfocusedAndDisposedFieldsClearTheMenu() = withField { field ->
        field.focus()
        field.type("hello")
        field.window.isWindowFocused = false
        field.pump()
        assertEquals(0, field.actions)
        field.window.isWindowFocused = true
        field.pump()
        assertTrue(field.available(RvEditAction.SelectAll))
        field.enabled = false
        field.pump()
        assertEquals(0, field.actions)
        field.enabled = true
        field.pump()
        field.focus()
        field.shown = false
        field.pump()
        assertEquals(0, field.actions)
    }

    @Test
    fun passwordSelectionDoesNotEnableCopyOrCut() = withField(secret = true) { field ->
        field.focus()
        field.type("test")
        field.command(Key.A)
        assertFalse(field.available(RvEditAction.Cut))
        assertFalse(field.available(RvEditAction.Copy))
        assertFalse(field.available(RvEditAction.SelectAll))
        assertTrue(field.available(RvEditAction.Paste))
        field.revealed = true
        field.pump()
        assertTrue(field.available(RvEditAction.Copy), "Revealed field actions: ${field.actions}")
        assertTrue(field.available(RvEditAction.Cut))
        field.revealed = false
        field.pump()
        assertFalse(field.available(RvEditAction.Copy))
        assertFalse(field.available(RvEditAction.Cut))
    }

    @Test
    fun externalUpdatesReachTheFieldAndPreserveRealUndoHistory() = withField { field ->
        field.focus()
        field.type("old")
        field.value = "new"
        field.pump()
        assertEquals("new", field.value)
        assertTrue(field.available(RvEditAction.Undo))
        field.command(Key.Z)
        assertEquals("old", field.value)
        field.command(Key.Z, shift = true)
        assertEquals("new", field.value)
        field.type("!")
        assertEquals("new!", field.value)
    }

    @Test
    fun searchSelectionAndClearingUpdateTheMenu() {
        FieldScene(search = true).use { field ->
            field.focus()
            field.type("search")
            field.command(Key.A)
            assertTrue(field.available(RvEditAction.Copy))
            field.value = ""
            field.pump()
            assertFalse(field.available(RvEditAction.Copy))
            assertFalse(field.available(RvEditAction.SelectAll))
        }
    }

    @Test
    fun verificationCodeKeepsItsInputFilterAndCompletionCallback() {
        FieldScene(code = true).use { field ->
            field.click()
            field.type("12a34567")
            assertEquals("123456", field.value)
            assertEquals(listOf("123456"), field.completed)
            field.command(Key.A)
            assertTrue(field.available(RvEditAction.Cut))
            field.type("9")
            assertEquals("9", field.value)
        }
    }

    @Test
    fun disposingThePreviousFieldDoesNotClearTheCurrentField() {
        var mask = 0
        val menu = RvEditMenu { mask = it }
        val previous = Any()
        val current = Any()
        menu.update(previous, RvEditAction.Undo.mask)
        menu.update(current, RvEditAction.Copy.mask)
        menu.clear(previous)
        assertEquals(RvEditAction.Copy.mask, mask)
        menu.clear(current)
        assertEquals(0, mask)
    }

    private fun withField(secret: Boolean = false, test: (FieldScene) -> Unit) {
        FieldScene(secret).use(test)
    }

    private class TestWindow : WindowInfo {
        override var isWindowFocused by mutableStateOf(true)
    }

    private class FieldScene(secret: Boolean = false, search: Boolean = false, code: Boolean = false) : AutoCloseable {
        var value by mutableStateOf("")
        var enabled by mutableStateOf(true)
        var shown by mutableStateOf(true)
        var revealed by mutableStateOf(false)
        var actions = 0
        val completed = mutableListOf<String>()
        val window = TestWindow()
        private val requester = FocusRequester()
        private val menu = RvEditMenu { actions = it }
        private val surface = Surface.makeRasterN32Premul(500, 300)
        private val scene = CanvasLayersComposeScene(
            size = IntSize(500, 300),
            platformContext = object : PlatformContext by PlatformContext.Empty() {
                override val windowInfo = window
            },
        )
        private var time = 0L

        init {
            scene.setContent {
                CompositionLocalProvider(LocalEditMenu provides menu) {
                    MaterialTheme {
                        Column {
                            if (shown && code) RvCodeInput(
                                value = value,
                                onValueChange = { value = it },
                                onComplete = { completed.add(it) },
                            ) else if (shown && search) RvSearchInput(
                                query = value,
                                onQuery = { value = it },
                                placeholder = "Search",
                                focusRequester = requester,
                            ) else if (shown) RvTextField(
                                value = value,
                                onValueChange = { value = it },
                                label = "Field",
                                secret = secret,
                                shown = revealed,
                                enabled = enabled,
                                focusRequester = requester,
                            )
                        }
                    }
                }
            }
            pump()
        }

        fun pump() {
            repeat(5) {
                Snapshot.sendApplyNotifications()
                time += 16_000_000L
                scene.render(surface.canvas.asComposeCanvas(), time)
            }
        }

        fun focus() {
            requester.requestFocus()
            pump()
        }

        fun click() {
            for (event in listOf(PointerEventType.Press, PointerEventType.Release)) {
                scene.sendPointerEvent(event, Offset(40f, 40f), type = PointerType.Mouse, button = PointerButton.Primary)
            }
            pump()
        }

        fun type(text: String) {
            for (char in text) {
                val native = java.awt.event.KeyEvent(Canvas(), java.awt.event.KeyEvent.KEY_TYPED, 0, 0, java.awt.event.KeyEvent.VK_UNDEFINED, char)
                scene.sendKeyEvent(KeyEvent(Key.Unknown, KeyEventType.Unknown, char.code, nativeEvent = native))
                pump()
            }
        }

        fun command(key: Key, shift: Boolean = false) {
            val macOS = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
            for (type in listOf(KeyEventType.KeyDown, KeyEventType.KeyUp)) {
                scene.sendKeyEvent(KeyEvent(key, type, isMetaPressed = macOS, isCtrlPressed = !macOS, isShiftPressed = shift))
            }
            pump()
        }

        fun available(action: RvEditAction) = actions and action.mask != 0
        fun assertActions(vararg expected: RvEditAction) = assertEquals(expected.fold(0) { mask, action -> mask or action.mask }, actions)

        override fun close() {
            scene.close()
            surface.close()
        }
    }
}
