/*
 * SPDX-FileCopyrightText: 2024 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.msd.view

import android.content.Context
import android.util.AttributeSet
import androidx.preference.PreferenceViewHolder
import androidx.preference.SwitchPreferenceCompat
import com.chiller3.msd.R

/**
 * A switch preference with a divider between the main preference and the switch. It is both
 * clickable and switchable.
 */
class SplitSwitchPreference : SwitchPreferenceCompat {
    constructor(context: Context) : this(context, null)

    constructor(context: Context, attrs: AttributeSet?) :
            this(context, attrs, R.attr.splitSwitchPreferenceStyle)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) :
            this(context, attrs, defStyleAttr, 0)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) :
            super(context, attrs, defStyleAttr, defStyleRes)

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        val switchView = holder.findViewById(androidx.preference.R.id.switchWidget)

        // Perform the toggling only when clicking the switch itself.
        switchView.isClickable = true
    }

    override fun onClick() {
        // Don't toggle the switch when clicking the main preference area.
    }
}
