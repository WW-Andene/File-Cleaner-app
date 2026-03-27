package com.filecleaner.app.ui.onboarding

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.widget.ImageView
import android.widget.TextView
import com.filecleaner.app.R
import com.filecleaner.app.data.UserPreferences
import com.google.android.material.button.MaterialButton

/**
 * 3-step onboarding dialog shown on first launch.
 * Uses a transparent Dialog with MaterialCardView layout for
 * proper rounded corners matching the app's card radius.
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

        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCancelable(false)

        val container = LayoutInflater.from(context)
            .inflate(R.layout.dialog_onboarding_step, null)

        // Title
        container.findViewById<TextView>(R.id.tv_step_title).text = current.title

        // Step indicator
        val stepIndicator = container.findViewById<TextView>(R.id.tv_step_indicator)
        stepIndicator.text = context.getString(R.string.onboarding_step, step + 1, steps.size)
        stepIndicator.contentDescription = context.getString(R.string.a11y_onboarding_step, step + 1, steps.size)

        // Icon
        val iconRes = when (step) {
            0 -> R.drawable.ic_raccoon_logo
            1 -> R.drawable.ic_nav_browse
            2 -> R.drawable.ic_scan
            else -> R.drawable.ic_raccoon_logo
        }
        val iconDesc = when (step) {
            0 -> context.getString(R.string.a11y_onboarding_icon_welcome)
            1 -> context.getString(R.string.a11y_onboarding_icon_browse)
            2 -> context.getString(R.string.a11y_onboarding_icon_scan)
            else -> context.getString(R.string.a11y_onboarding_icon_welcome)
        }
        val iconView = container.findViewById<ImageView>(R.id.iv_step_icon)
        iconView.setImageResource(iconRes)
        iconView.contentDescription = iconDesc

        // Body
        container.findViewById<TextView>(R.id.tv_step_body).text = current.body

        // Buttons
        val btnBack = container.findViewById<MaterialButton>(R.id.btn_back)
        val btnSkip = container.findViewById<MaterialButton>(R.id.btn_skip)
        val btnNext = container.findViewById<MaterialButton>(R.id.btn_next)

        // Back button
        if (step > 0) {
            btnBack.text = context.getString(R.string.onboarding_back)
            btnBack.visibility = View.VISIBLE
            btnBack.setOnClickListener {
                dialog.dismiss()
                show(context, step - 1)
            }
        }

        // Skip / Next / Done
        if (isLast) {
            btnSkip.visibility = View.GONE
            btnNext.text = context.getString(R.string.onboarding_done)
            btnNext.setOnClickListener {
                dialog.dismiss()
                UserPreferences.hasSeenOnboarding = true
                (context as? com.filecleaner.app.MainActivity)?.requestPermissionsAndScan()
            }
        } else {
            btnSkip.text = context.getString(R.string.onboarding_skip)
            btnSkip.setOnClickListener {
                dialog.dismiss()
                UserPreferences.hasSeenOnboarding = true
                (context as? com.filecleaner.app.MainActivity)?.requestPermissionsAndScan()
            }
            btnNext.text = context.getString(R.string.onboarding_next)
            btnNext.setOnClickListener {
                dialog.dismiss()
                show(context, step + 1)
            }
        }

        dialog.setContentView(container)
        dialog.show()
    }
}
