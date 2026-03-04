package com.filecleaner.app.utils

import androidx.core.content.ContextCompat
import com.filecleaner.app.R
import com.google.android.material.snackbar.Snackbar

fun Snackbar.styleAsError(): Snackbar = apply {
    setBackgroundTint(ContextCompat.getColor(context, R.color.colorError))
    setTextColor(ContextCompat.getColor(context, R.color.textOnPrimary))
}
