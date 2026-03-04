package com.filecleaner.app.utils

import androidx.core.content.ContextCompat
import com.filecleaner.app.R
import com.google.android.material.snackbar.Snackbar

fun Snackbar.styleAsError(): Snackbar = apply {
    setBackgroundTint(ContextCompat.getColor(context, R.color.colorError))
    setTextColor(ContextCompat.getColor(context, R.color.textOnPrimary))
}

fun Snackbar.styleAsSuccess(): Snackbar = apply {
    setBackgroundTint(ContextCompat.getColor(context, R.color.colorSuccess))
    setTextColor(ContextCompat.getColor(context, R.color.textOnPrimary))
}

fun Snackbar.styleAsWarning(): Snackbar = apply {
    setBackgroundTint(ContextCompat.getColor(context, R.color.colorWarning))
    setTextColor(ContextCompat.getColor(context, R.color.textOnPrimary))
}
