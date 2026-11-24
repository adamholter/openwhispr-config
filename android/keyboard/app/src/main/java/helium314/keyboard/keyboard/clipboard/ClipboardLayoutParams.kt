// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.keyboard.clipboard

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import helium314.keyboard.keyboard.internal.KeyboardParams
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ResourceUtils

class ClipboardLayoutParams(ctx: Context) {

    private val keyVerticalGap: Int
    private val keyHorizontalGap: Int
    private val listHeight: Int
    val bottomRowKeyboardHeight: Int

    init {
        val res = ctx.resources
        val sv = Settings.getValues()
        val defaultKeyboardHeight = ResourceUtils.getSecondaryKeyboardHeight(res, sv)
        val defaultKeyboardWidth = ResourceUtils.getKeyboardWidth(ctx, sv)

        keyVerticalGap = (res.getFraction(R.fraction.config_key_vertical_gap_holo,
            defaultKeyboardHeight, defaultKeyboardHeight) * sv.mKeyGapScale).toInt()
        keyHorizontalGap = (res.getFraction(R.fraction.config_key_horizontal_gap_holo,
            defaultKeyboardWidth, defaultKeyboardWidth) * sv.mKeyGapScale).toInt()
        val bottomPadding = (res.getFraction(R.fraction.config_keyboard_bottom_padding_holo,
                defaultKeyboardHeight, defaultKeyboardHeight) * sv.mBottomPaddingScale).toInt()
        val topPadding = res.getFraction(R.fraction.config_keyboard_top_padding_holo,
                defaultKeyboardHeight, defaultKeyboardHeight).toInt()

        val rowCount = KeyboardParams.DEFAULT_KEYBOARD_ROWS + if (sv.mShowsNumberRow) 1 else 0
        bottomRowKeyboardHeight = (defaultKeyboardHeight - bottomPadding - topPadding) / rowCount - keyVerticalGap / 2
        // height calculation is not good enough, probably also because keyboard top padding might be off by a pixel (see KeyboardParser)
        val offset = 1.25f * res.displayMetrics.density * sv.mKeyboardHeightScale
        listHeight = defaultKeyboardHeight - bottomRowKeyboardHeight - bottomPadding + offset.toInt()
    }

    fun setListProperties(recycler: RecyclerView, extraHeight: Int = 0) {
        (recycler.layoutParams as FrameLayout.LayoutParams).apply {
            if (height == listHeight + extraHeight) return
            height = listHeight + extraHeight
            recycler.layoutParams = this
        }
    }

    fun setItemProperties(view: View) {
        val minGap = (4 * view.resources.displayMetrics.density).toInt()
        (view.layoutParams as RecyclerView.LayoutParams).apply {
            topMargin = maxOf(keyHorizontalGap / 2, minGap)
            bottomMargin = maxOf(keyVerticalGap / 2, minGap)
            marginStart = maxOf(keyHorizontalGap / 2, minGap)
            marginEnd = maxOf(keyHorizontalGap / 2, minGap)
            view.layoutParams = this
        }
    }
}
