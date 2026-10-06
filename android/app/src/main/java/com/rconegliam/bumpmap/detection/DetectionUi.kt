package com.rconegliam.bumpmap.detection

import androidx.annotation.ColorInt
import androidx.annotation.StringRes
import com.rconegliam.bumpmap.R

val RoadQuality.labelRes: Int
    @StringRes get() = when (this) {
        RoadQuality.GOOD -> R.string.quality_good
        RoadQuality.FAIR -> R.string.quality_fair
        RoadQuality.POOR -> R.string.quality_poor
        RoadQuality.BAD -> R.string.quality_bad
    }

val RoadQuality.color: Int
    @ColorInt get() = when (this) {
        RoadQuality.GOOD -> 0xFF2E7D32.toInt()
        RoadQuality.FAIR -> 0xFFF9A825.toInt()
        RoadQuality.POOR -> 0xFFEF6C00.toInt()
        RoadQuality.BAD -> 0xFFC62828.toInt()
    }

val EventType.labelRes: Int
    @StringRes get() = when (this) {
        EventType.POTHOLE -> R.string.mark_pothole
        EventType.SPEED_BUMP -> R.string.mark_bump
    }

val EventType.color: Int
    @ColorInt get() = when (this) {
        EventType.POTHOLE -> 0xFF212121.toInt()
        EventType.SPEED_BUMP -> 0xFF1565C0.toInt()
    }
