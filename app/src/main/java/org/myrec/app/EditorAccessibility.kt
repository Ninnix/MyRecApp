package org.myrec.app

import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction as Action
import android.view.accessibility.AccessibilityNodeProvider

internal class EditorAccessibility(private val view: View, private val entries: () -> List<Item>) : AccessibilityNodeProvider() {
    data class Item(
        val key: String,
        val label: String,
        val bounds: Rect,
        val value: String? = null,
        val selected: Boolean = false,
        val click: (() -> Unit)? = null,
        val longClick: (() -> Unit)? = null,
        val range: ClosedFloatingPointRange<Float>? = null,
        val progress: Float = 0f,
        val adjust: ((Float) -> Unit)? = null,
        val increment: Float? = null,
    )

    private val manager = view.context.getSystemService(AccessibilityManager::class.java)
    private val ids = mutableMapOf<String, Int>()
    private var focused = View.NO_ID
    private var hovered = View.NO_ID
    private fun id(item: Item) = ids.getOrPut(item.key) { ids.size + 1 }
    private fun item(id: Int) = entries().firstOrNull { id(it) == id }

    override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfo? {
        if (virtualViewId == HOST_VIEW_ID) return AccessibilityNodeInfo.obtain(view).apply {
            view.onInitializeAccessibilityNodeInfo(this)
            entries().forEach { addChild(view, id(it)) }
        }
        val item = item(virtualViewId) ?: return null
        return AccessibilityNodeInfo.obtain().apply {
            setSource(view, virtualViewId)
            setParent(view)
            packageName = view.context.packageName
            className = if (item.range != null) "android.widget.SeekBar" else "android.widget.Button"
            contentDescription = if (item.value != null && android.os.Build.VERSION.SDK_INT < 30) "${item.label}, ${item.value}" else item.label
            if (android.os.Build.VERSION.SDK_INT >= 30) stateDescription = item.value
            isEnabled = true
            isFocusable = true
            isSelected = item.selected
            isClickable = item.click != null
            isLongClickable = item.longClick != null
            isVisibleToUser = view.isShown && view.windowVisibility == View.VISIBLE
            isAccessibilityFocused = focused == virtualViewId
            setBoundsInParent(item.bounds)
            val xy = IntArray(2)
            view.getLocationOnScreen(xy)
            setBoundsInScreen(Rect(item.bounds).apply { offset(xy[0], xy[1]) })
            addAction(if (isAccessibilityFocused) Action.ACTION_CLEAR_ACCESSIBILITY_FOCUS else Action.ACTION_ACCESSIBILITY_FOCUS)
            if (isClickable) addAction(Action.ACTION_CLICK)
            if (isLongClickable) addAction(Action.ACTION_LONG_CLICK)
            item.range?.let {
                rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT,
                    it.start, it.endInclusive, item.progress)
                addAction(Action.ACTION_SET_PROGRESS)
                addAction(Action.ACTION_SCROLL_FORWARD)
                addAction(Action.ACTION_SCROLL_BACKWARD)
                isScrollable = true
            }
        }
    }

    override fun findAccessibilityNodeInfosByText(text: String, virtualViewId: Int): List<AccessibilityNodeInfo> =
        entries().filter { (virtualViewId == HOST_VIEW_ID || id(it) == virtualViewId) && it.label.contains(text, true) }
            .mapNotNull { createAccessibilityNodeInfo(id(it)) }

    override fun findFocus(focus: Int): AccessibilityNodeInfo? =
        if (focus == AccessibilityNodeInfo.FOCUS_ACCESSIBILITY && focused != View.NO_ID) createAccessibilityNodeInfo(focused) else null

    override fun performAction(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
        if (virtualViewId == HOST_VIEW_ID) return view.performAccessibilityAction(action, arguments)
        if (action == Action.ACTION_CLEAR_ACCESSIBILITY_FOCUS.id && focused == virtualViewId) {
            setFocus(View.NO_ID)
            return true
        }
        val item = item(virtualViewId) ?: return false
        when (action) {
            Action.ACTION_ACCESSIBILITY_FOCUS.id -> {
                if (focused == virtualViewId) return false
                setFocus(virtualViewId)
                return true
            }
            Action.ACTION_CLICK.id -> { (item.click ?: return false).invoke(); event(virtualViewId, AccessibilityEvent.TYPE_VIEW_CLICKED) }
            Action.ACTION_LONG_CLICK.id -> { (item.longClick ?: return false).invoke(); event(virtualViewId, AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) }
            Action.ACTION_SET_PROGRESS.id, Action.ACTION_SCROLL_FORWARD.id, Action.ACTION_SCROLL_BACKWARD.id -> {
                val range = item.range ?: return false
                val adjust = item.adjust ?: return false
                val step = item.increment ?: ((range.endInclusive - range.start) / 20).coerceAtLeast(.033f)
                val position = when (action) {
                    Action.ACTION_SCROLL_FORWARD.id -> item.progress + step
                    Action.ACTION_SCROLL_BACKWARD.id -> item.progress - step
                    else -> arguments?.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, Float.NaN) ?: return false
                }
                if (!position.isFinite()) return false
                adjust(position.coerceIn(range))
            }
            else -> return false
        }
        view.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        return true
    }

    fun hover(event: MotionEvent): Boolean {
        if (!manager.isEnabled || !manager.isTouchExplorationEnabled) return false
        val next = when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> entries().asReversed().filter {
                it.bounds.contains(event.x.toInt(), event.y.toInt())
            }.minByOrNull { it.bounds.width() * it.bounds.height() }?.let(::id) ?: View.NO_ID
            MotionEvent.ACTION_HOVER_EXIT -> View.NO_ID
            else -> return false
        }
        val before = hovered
        if (next != before) {
            hovered = next
            event(next, AccessibilityEvent.TYPE_VIEW_HOVER_ENTER)
            event(before, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT)
        }
        return next != View.NO_ID || before != View.NO_ID
    }

    private fun setFocus(next: Int) {
        val before = focused
        focused = next
        event(before, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED)
        event(next, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
    }

    private fun event(id: Int, type: Int) {
        if (id == View.NO_ID || !manager.isEnabled) return
        val entry = item(id)
        val event = AccessibilityEvent.obtain(type).apply {
            setSource(view, id)
            packageName = view.context.packageName
            className = "android.view.View"
            isEnabled = true
            contentDescription = entry?.label
        }
        view.parent?.requestSendAccessibilityEvent(view, event)
    }
}
