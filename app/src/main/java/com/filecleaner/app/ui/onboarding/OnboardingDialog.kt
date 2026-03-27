package com.filecleaner.app.ui.onboarding

import android.content.Context
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.filecleaner.app.R
import com.filecleaner.app.data.UserPreferences

/**
 * 3-step onboarding dialog shown on first launch (P13).
 * F-060: Inflates dialog_onboarding_step.xml instead of programmatic construction.
 */
object OnboardingDialog {

    private data class Step(val title: String, val body: String)

    fun showIfNeeded(context: Context) {
        if (UserPreferences.hasSeenOnboarding) return
        show(context, step = 0)
    }

    private fun show(context: Context, step: Int) {
        val steps = listOf(
            Step(
                context.getString(R.string.onboarding_title_1),
                context.getString(R.string.onboarding_body_1)
            ),
            Step(
                context.getString(R.string.onboarding_title_2),
                context.getString(R.string.onboarding_body_2)
            ),
            Step(
                context.getString(R.string.onboarding_title_3),
                context.getString(R.string.onboarding_body_3)
            )
        )

        val current = steps[step]
        val isLast = step == steps.lastIndex

        // F-060: Inflate XML layout instead of building views programmatically
        val container = LayoutInflater.from(context)
            .inflate(R.layout.dialog_onboarding_step, null)

        val stepIndicator = container.findViewById<TextView>(R.id.tv_step_indicator)
        stepIndicator.text = context.getString(R.string.onboarding_step, step + 1, steps.size)
        // §G1: Announce step changes to TalkBack
        stepIndicator.contentDescription = context.getString(R.string.a11y_onboarding_step, step + 1, steps.size)

        val iconRes = when (step) {
            0 -> R.drawable.ic_raccoon_logo
            1 -> R.drawable.ic_nav_browse
            2 -> R.drawable.ic_scan
            else -> R.drawable.ic_raccoon_logo
        }
        // §G1: Descriptive contentDescription for each onboarding icon
        val iconDesc = when (step) {
            0 -> context.getString(R.string.a11y_onboarding_icon_welcome)
            1 -> context.getString(R.string.a11y_onboarding_icon_browse)
            2 -> context.getString(R.string.a11y_onboarding_icon_scan)
            else -> context.getString(R.string.a11y_onboarding_icon_welcome)
        }
        val iconView = container.findViewById<ImageView>(R.id.iv_step_icon)
        iconView.setImageResource(iconRes)
        iconView.contentDescription = iconDesc

        val bodyView = container.findViewById<TextView>(R.id.tv_step_body)
        bodyView.text = current.body

        val builder = MaterialAlertDialogBuilder(context)
            .setTitle(current.title)
            .setView(container)
            .setCancelable(false)

        if (isLast) {
            builder.setPositiveButton(context.getString(R.string.onboarding_done)) { _, _ ->
                UserPreferences.hasSeenOnboarding = true
                (context as? com.filecleaner.app.MainActivity)?.requestPermissionsAndScan()
            }
        } else {
            builder.setPositiveButton(context.getString(R.string.onboarding_next)) { _, _ ->
                show(context, step + 1)
            }
            if (step > 0) {
                builder.setNeutralButton(context.getString(R.string.onboarding_back)) { _, _ ->
                    show(context, step - 1)
                }
            }
            builder.setNegativeButton(context.getString(R.string.onboarding_skip)) { _, _ ->
                UserPreferences.hasSeenOnboarding = true
                (context as? com.filecleaner.app.MainActivity)?.requestPermissionsAndScan()
            }
        }

        builder.show()
    }
}
