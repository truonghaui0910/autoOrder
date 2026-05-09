package com.autoorder

import java.util.Calendar

object ZaloTimeParser {

    fun parse(text: String, now: Long = System.currentTimeMillis()): Long {
        val raw = text.trim().lowercase().replace(Regex("\\s+"), " ")
        if (raw.isEmpty()) return 0L

        if (raw.contains("vài giây") || raw == "bây giờ") return now - 5_000L

        Regex("(\\d+)\\s*(giây|phút|giờ)").find(raw)?.let { m ->
            val n = m.groupValues[1].toLong()
            val ms = when (m.groupValues[2]) {
                "giây" -> n * 1_000L
                "phút" -> n * 60_000L
                "giờ" -> n * 3_600_000L
                else -> 0L
            }
            return now - ms
        }

        if (raw.contains("hôm qua")) {
            val cal = Calendar.getInstance().apply {
                timeInMillis = now
                add(Calendar.DAY_OF_YEAR, -1)
                set(Calendar.HOUR_OF_DAY, 12)
                set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            return cal.timeInMillis
        }

        val dow: Int? = when (raw) {
            "cn", "chủ nhật" -> Calendar.SUNDAY
            "t2", "thứ 2", "thứ hai" -> Calendar.MONDAY
            "t3", "thứ 3", "thứ ba" -> Calendar.TUESDAY
            "t4", "thứ 4", "thứ tư" -> Calendar.WEDNESDAY
            "t5", "thứ 5", "thứ năm" -> Calendar.THURSDAY
            "t6", "thứ 6", "thứ sáu" -> Calendar.FRIDAY
            "t7", "thứ 7", "thứ bảy" -> Calendar.SATURDAY
            else -> null
        }
        if (dow != null) {
            val cal = Calendar.getInstance().apply { timeInMillis = now }
            var diff = cal.get(Calendar.DAY_OF_WEEK) - dow
            if (diff <= 0) diff += 7
            cal.add(Calendar.DAY_OF_YEAR, -diff)
            cal.set(Calendar.HOUR_OF_DAY, 12)
            cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }

        Regex("(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?").find(raw)?.let { m ->
            val day = m.groupValues[1].toInt()
            val month = m.groupValues[2].toInt()
            val yearStr = m.groupValues[3]
            val cal = Calendar.getInstance().apply { timeInMillis = now }
            val year = if (yearStr.isNotBlank()) {
                val y = yearStr.toInt()
                if (y < 100) 2000 + y else y
            } else cal.get(Calendar.YEAR)
            cal.set(year, month - 1, day, 12, 0, 0)
            cal.set(Calendar.MILLISECOND, 0)
            if (yearStr.isBlank() && cal.timeInMillis > now) {
                cal.add(Calendar.YEAR, -1)
            }
            return cal.timeInMillis
        }

        return 0L
    }
}
