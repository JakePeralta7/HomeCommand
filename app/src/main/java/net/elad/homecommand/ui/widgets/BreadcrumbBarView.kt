package net.elad.homecommand.ui.widgets

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.doOnLayout
import androidx.core.widget.TextViewCompat
import com.google.android.material.appbar.MaterialToolbar
import net.elad.homecommand.R

/**
 * Horizontal breadcrumb trail for sub-screen toolbars: ancestors are muted and tappable, the last
 * crumb is the current page (prominent, never a link) per NN/g guidance and platform precedent.
 */
class BreadcrumbBarView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : HorizontalScrollView(context, attrs, 0) {
        data class Crumb(
            val label: String,
            val onClick: (() -> Unit)? = null,
        )

        private val row =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

        init {
            isHorizontalScrollBarEnabled = false
            addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        }

        fun setPath(vararg crumbs: Crumb) {
            row.removeAllViews()
            crumbs.forEachIndexed { index, crumb ->
                if (index > 0) {
                    row.addView(newSeparator())
                }
                row.addView(newCrumb(crumb))
            }
            doOnLayout { fullScroll(FOCUS_RIGHT) }
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            alignInsideToolbar()
        }

        /** Custom toolbar children ignore the content inset; mirror where a title would start. */
        private fun alignInsideToolbar() {
            val lp = layoutParams as? MarginLayoutParams ?: return
            val toolbar = parent as? MaterialToolbar ?: return
            lp.marginStart =
                if (toolbar.navigationIcon == null) toolbar.contentInsetStart else toolbar.contentInsetStartWithNavigation
            requestLayout()
        }

        private fun newCrumb(crumb: Crumb): TextView {
            val padV = dp(12f)
            val padH = dp(4f)
            val view =
                TextView(context).apply {
                    text = crumb.label
                    maxLines = 1
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(padH, padV, padH, padV)
                }
            if (crumb.onClick == null) {
                TextViewCompat.setTextAppearance(view, R.style.TextAppearance_Breadcrumb_Current)
            } else {
                TextViewCompat.setTextAppearance(view, R.style.TextAppearance_Breadcrumb_Ancestor)
                view.setBackgroundResource(themedRes(R.attr.touchSelectBackground))
                // Material's 48dp minimum touch target for the tappable ancestors.
                view.minimumHeight = dp(48f)
                view.minimumWidth = dp(48f)
                view.setOnClickListener { crumb.onClick.invoke() }
            }
            return view
        }

        private fun newSeparator(): TextView {
            val pad = dp(4f)
            val view =
                TextView(context).apply {
                    text = SEPARATOR
                    maxLines = 1
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(pad, 0, pad, 0)
                }
            TextViewCompat.setTextAppearance(view, R.style.TextAppearance_Breadcrumb_Ancestor)
            return view
        }

        private fun themedRes(attr: Int): Int {
            val value = TypedValue()
            context.theme.resolveAttribute(attr, value, true)
            return value.resourceId
        }

        private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

        private companion object {
            const val SEPARATOR = "›"
        }
    }
