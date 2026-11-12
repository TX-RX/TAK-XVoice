package com.atakmap.android.xv.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ImageSpan
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import com.atakmap.android.xv.R

/**
 * Texas-TAK themed inline icons for the XV control panel. Replaces the OS
 * emoji glyphs (👥 ☁️ 📻 🕒 ⚙ 🔊 🕸) with crisp vector drawables tinted to
 * the brand palette so the plugin UI reads as one system instead of
 * borrowing the platform emoji font (which also renders differently per
 * OEM/Android version).
 *
 * Two mechanisms, both leaving the existing layout untouched:
 *  - [membersBadge] / [countSuffix] / [memberRow] embed the drawable
 *    inline in an existing TextView/Button's text via [CenteredImageSpan]
 *    (ImageSpan.ALIGN_CENTER is API 29+; this plugin is minSdk 26).
 *  - [setLeadingIcon] hangs a sized compound drawable off the start of a
 *    labelled view (Recent/Settings/audio-route/mesh), replacing a leading
 *    emoji in the label text.
 */
object XvGlyphs {
    /** px for a dp value at the current display density. */
    fun dp(ctx: Context, value: Int): Int =
        (ctx.resources.displayMetrics.density * value).toInt().coerceAtLeast(1)

    /** Drawable sized to a square box; optional tint overrides the baked
     *  fill (single-colour icons only — used for the mesh status glyph
     *  which tracks the text colour). */
    fun sized(ctx: Context, resId: Int, px: Int, tintColorRes: Int? = null): Drawable {
        val d = ContextCompat.getDrawable(ctx, resId)!!.mutate()
        if (tintColorRes != null) {
            DrawableCompat.setTint(d, ContextCompat.getColor(ctx, tintColorRes))
        }
        d.setBounds(0, 0, px, px)
        return d
    }

    /** Attach [resId] as a leading compound drawable on [tv], replacing any
     *  leading emoji. Clears a trailing/top/bottom drawable. */
    fun setLeadingIcon(tv: TextView, resId: Int, dpSize: Int = 16, tintColorRes: Int? = null) {
        val d = sized(tv.context, resId, dp(tv.context, dpSize), tintColorRes)
        tv.setCompoundDrawablesRelative(d, null, null, null)
        tv.compoundDrawablePadding = dp(tv.context, 6)
    }

    /** 3-line count badge for the per-PTT members button:
     *  [members] over "[cloud] server" over "[radio] local". */
    fun membersBadge(ctx: Context, server: Int, local: Int): CharSequence {
        val head = dp(ctx, 20)
        val row = dp(ctx, 15)
        return SpannableStringBuilder().apply {
            icon(ctx, R.drawable.ic_xv_members, head)
            append("\n")
            icon(ctx, R.drawable.ic_xv_cloud, row)
            append(" $server\n")
            icon(ctx, R.drawable.ic_xv_radio, row)
            append(" $local")
        }
    }

    /** "( [cloud] N  [radio] N )" count suffix for the members-picker header. */
    fun countSuffix(ctx: Context, server: Int, local: Int): CharSequence {
        val px = dp(ctx, 14)
        return SpannableStringBuilder().apply {
            append("(")
            icon(ctx, R.drawable.ic_xv_cloud, px)
            append("$server  ")
            icon(ctx, R.drawable.ic_xv_radio, px)
            append("$local)")
        }
    }

    /** A member-picker row: [lead] (talking dot or pad) + source icon(s) +
     *  [trailing] (callsign and any tag). */
    fun memberRow(
        ctx: Context,
        lead: String,
        onServer: Boolean,
        onMesh: Boolean,
        trailing: String,
    ): CharSequence {
        val px = dp(ctx, 15)
        return SpannableStringBuilder().apply {
            append(lead)
            if (onServer) {
                icon(ctx, R.drawable.ic_xv_cloud, px)
                append(" ")
            }
            if (onMesh) {
                icon(ctx, R.drawable.ic_xv_radio, px)
                append(" ")
            }
            append(trailing)
        }
    }

    /** Append one centred icon as a single spanned placeholder char. */
    private fun SpannableStringBuilder.icon(ctx: Context, resId: Int, px: Int) {
        val start = length
        append(" ")
        setSpan(
            CenteredImageSpan(sized(ctx, resId, px)),
            start,
            length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }

    /**
     * [ImageSpan] that centres its drawable on the text line's vertical
     * midpoint. ImageSpan.ALIGN_CENTER would do this but is API 29+; this
     * works back to minSdk 26.
     */
    private class CenteredImageSpan(d: Drawable) : ImageSpan(d) {
        override fun draw(
            canvas: Canvas,
            text: CharSequence?,
            start: Int,
            end: Int,
            x: Float,
            top: Int,
            y: Int,
            bottom: Int,
            paint: Paint,
        ) {
            val b = drawable
            canvas.save()
            val fm = paint.fontMetricsInt
            val transY = y + (fm.descent + fm.ascent) / 2 - b.bounds.height() / 2
            canvas.translate(x, transY.toFloat())
            b.draw(canvas)
            canvas.restore()
        }
    }
}
