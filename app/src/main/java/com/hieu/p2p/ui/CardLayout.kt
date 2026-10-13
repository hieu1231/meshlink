package com.hieu.p2p.ui

import android.content.Context
import android.util.AttributeSet
import android.view.ViewOutlineProvider
import android.widget.LinearLayout

/**
 * A rounded card that clips its children (and their ripples) to its background's outline.
 * The XML attribute for this only exists from API 31, so it is set here.
 */
class CardLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    init {
        outlineProvider = ViewOutlineProvider.BACKGROUND
        clipToOutline = true
    }
}
