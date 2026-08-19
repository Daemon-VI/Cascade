package com.rishi.cascade.ui

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import com.rishi.cascade.model.ActionDef

/** What is currently being dragged. */
sealed interface DragPayload {
    /** A brand new action pulled out of the palette. */
    data class New(val def: ActionDef) : DragPayload

    /** An existing step (and, for block openers, everything it contains). */
    data class Move(val from: Int, val count: Int, val title: String, val icon: String) : DragPayload
}

/**
 * One drag session shared by the palette and the canvas, so an action can be dragged
 * straight out of the palette and dropped between two steps.
 */
class DragState {
    var payload by mutableStateOf<DragPayload?>(null)
        private set
    var pointer by mutableStateOf(Offset.Zero)
    var grab by mutableStateOf(Offset.Zero)
    var ghostWidth by mutableStateOf(0)

    /** Bounds of every visible step row, in root coordinates. */
    val rows = mutableStateMapOf<Int, Rect>()
    var listTop by mutableStateOf(0f)
    var listBottom by mutableStateOf(0f)
    var stepCount by mutableStateOf(0)

    val dragging: Boolean get() = payload != null

    var onDrop: ((DragPayload, Int) -> Unit)? = null

    fun start(p: DragPayload, pointerRoot: Offset, grabOffset: Offset) {
        payload = p
        pointer = pointerRoot
        grab = grabOffset
    }

    fun cancel() { payload = null; }

    fun drop() {
        val p = payload ?: return
        val target = dropIndex()
        payload = null
        if (target >= 0) onDrop?.invoke(p, target)
    }

    /** Index the dragged item would be inserted at, or -1 when the pointer is off the canvas. */
    fun dropIndex(): Int {
        if (rows.isEmpty()) return if (pointer.y in listTop..listBottom) 0 else -1
        if (pointer.y < listTop - 40f || pointer.y > listBottom + 40f) return -1
        val sorted = rows.entries.sortedBy { it.key }
        for ((index, r) in sorted) {
            if (pointer.y < r.top + r.height / 2f) return index
        }
        return stepCount
    }

    /** True when the insertion line should be drawn above the row at this index. */
    fun showLineAt(index: Int): Boolean = dragging && dropIndex() == index
}

/** Makes any composable a drag source for the shared drag session.
 *  The position state is remembered so the gesture keeps working across recompositions. */
@Composable
fun dragSource(
    state: DragState,
    payload: () -> DragPayload,
    onStarted: () -> Unit = {}
): Modifier {
    val rootPos = remember { mutableStateOf(Offset.Zero) }
    val width = remember { mutableStateOf(0) }
    val currentPayload = rememberUpdatedState(payload)
    val currentStarted = rememberUpdatedState(onStarted)
    return Modifier
        .onGloballyPositioned {
            rootPos.value = it.positionInRoot()
            width.value = it.size.width
        }
        .pointerInput(state) {
            detectDragGesturesAfterLongPress(
                onDragStart = { offset ->
                    state.ghostWidth = width.value
                    state.start(currentPayload.value.invoke(), rootPos.value + offset, offset)
                    currentStarted.value.invoke()
                },
                onDrag = { change, _ ->
                    change.consume()
                    state.pointer = rootPos.value + change.position
                },
                onDragEnd = { state.drop() },
                onDragCancel = { state.cancel() }
            )
        }
}
