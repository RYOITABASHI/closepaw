package ai.closepaw.trace

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import ai.closepaw.util.isCheckedCompat
import ai.closepaw.util.recycleCompat
import kotlinx.serialization.Serializable

@Serializable
internal data class A11yNodeDump(
    val className: String? = null,
    val packageName: String? = null,
    val viewIdResourceName: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val bounds: List<Int>? = null,
    val clickable: Boolean = false,
    val enabled: Boolean = false,
    val focusable: Boolean = false,
    val focused: Boolean = false,
    val scrollable: Boolean = false,
    val editable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val rangeInfo: A11yRangeInfo? = null,
    val selected: Boolean = false,
    val password: Boolean = false,
    val visibleToUser: Boolean = false,
    val childCount: Int = 0,
    val children: List<A11yNodeDump> = emptyList()
)

@Serializable
internal data class A11yRangeInfo(
    val current: Float,
    val min: Float,
    val max: Float
)

internal object A11yTreeDumper {
    private const val MAX_TEXT_LEN = 2000
    private const val MAX_DESC_LEN = 2000
    private const val MAX_ID_LEN = 300
    private const val MAX_CLASS_LEN = 200

    fun dump(root: AccessibilityNodeInfo?): A11yNodeDump? {
        if (root == null) return null
        return dumpNode(root, shouldRecycle = false)
    }

    private fun dumpNode(node: AccessibilityNodeInfo, shouldRecycle: Boolean): A11yNodeDump {
        val rect = Rect().also(node::getBoundsInScreen)
        val children =
            buildList {
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    add(dumpNode(child, shouldRecycle = true))
                }
            }

        val dump = describe(node, rect, children, markTruncation = false)
        if (shouldRecycle) {
            node.recycleCompat()
        }
        return dump
    }

    /** Format the current node without traversing its children. */
    fun describe(node: AccessibilityNodeInfo): A11yNodeDump =
        describe(node, Rect().also(node::getBoundsInScreen), emptyList(), markTruncation = true)

    private fun describe(
        node: AccessibilityNodeInfo,
        rect: Rect,
        children: List<A11yNodeDump>,
        markTruncation: Boolean,
    ): A11yNodeDump {
        val viewId = clip(node.viewIdResourceName, MAX_ID_LEN, markTruncation)
        val className = clip(node.className, MAX_CLASS_LEN, markTruncation)
        val text = clip(node.text, MAX_TEXT_LEN, markTruncation)
        val desc = clip(node.contentDescription, MAX_DESC_LEN, markTruncation)
        val rangeInfo =
            node.rangeInfo?.let {
                A11yRangeInfo(current = it.current, min = it.min, max = it.max)
            }

        return A11yNodeDump(
            className = className,
            packageName = node.packageName?.toString(),
            viewIdResourceName = viewId,
            text = text,
            contentDescription = desc,
            bounds = listOf(rect.left, rect.top, rect.right, rect.bottom),
            clickable = node.isClickable,
            enabled = node.isEnabled,
            focusable = node.isFocusable,
            focused = node.isFocused,
            scrollable = node.isScrollable,
            editable = node.isEditable,
            checkable = node.isCheckable,
            checked = node.isCheckedCompat(),
            rangeInfo = rangeInfo,
            selected = node.isSelected,
            password = node.isPassword,
            visibleToUser = node.isVisibleToUser,
            childCount = node.childCount,
            children = children,
        )
    }

    private fun clip(value: CharSequence?, limit: Int, markTruncation: Boolean): String? {
        if (value == null || value.length <= limit) return value?.toString()
        if (!markTruncation) return value.toString().take(limit)
        val end = if (Character.isHighSurrogate(value[limit - 1])) limit - 1 else limit
        return value.subSequence(0, end).toString() + " [truncated]"
    }
}
