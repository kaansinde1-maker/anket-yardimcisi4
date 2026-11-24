package com.formotomasyon

import android.graphics.drawable.GradientDrawable

fun rounded(color: Int, radius: Float): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }
