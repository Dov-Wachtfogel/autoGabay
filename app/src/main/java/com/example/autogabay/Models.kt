package com.example.autogabay.assignments

import java.time.Instant

enum class AssignmentCategory { PRAYER, READING }

/** Stable id identifies a kind even when its default points are edited. */
class AssignmentType(
    val id: String,
    val defaultPoints: Int,
    val category: AssignmentCategory,
    val displayName: String,
    val isCustom: Boolean = false,
) {
    override fun equals(other: Any?): Boolean = other is AssignmentType && id == other.id
    override fun hashCode(): Int = id.hashCode()
    override fun toString(): String = id

    companion object {
        val DAILY_SHACHARIT = AssignmentType("DAILY_SHACHARIT", 1, AssignmentCategory.PRAYER, "שחרית")
        val DAILY_MINCHA = AssignmentType("DAILY_MINCHA", 1, AssignmentCategory.PRAYER, "מנחה")
        val DAILY_ARVIT = AssignmentType("DAILY_ARVIT", 1, AssignmentCategory.PRAYER, "ערבית")
        val ROSH_CHODESH = AssignmentType("ROSH_CHODESH", 2, AssignmentCategory.PRAYER, "תפילת ראש חודש")
        val SHABBAT_KABBALAT = AssignmentType("SHABBAT_KABBALAT", 2, AssignmentCategory.PRAYER, "קבלת שבת")
        val SHABBAT_SHACHARIT = AssignmentType("SHABBAT_SHACHARIT", 3, AssignmentCategory.PRAYER, "שחרית שבת")
        val SHABBAT_MUSAF = AssignmentType("SHABBAT_MUSAF", 3, AssignmentCategory.PRAYER, "מוסף שבת")
        val SHABBAT_MINCHA = AssignmentType("SHABBAT_MINCHA", 2, AssignmentCategory.PRAYER, "מנחה שבת")
        val TORAH_READING_SHORT = AssignmentType("TORAH_READING_SHORT", 2, AssignmentCategory.READING, "קריאה קצרה בתורה")
        val TORAH_READING_LONG = AssignmentType("TORAH_READING_LONG", 4, AssignmentCategory.READING, "קריאה ארוכה בתורה")

        val predefined: List<AssignmentType> = listOf(
            DAILY_SHACHARIT, DAILY_MINCHA, DAILY_ARVIT, ROSH_CHODESH,
            SHABBAT_KABBALAT, SHABBAT_SHACHARIT, SHABBAT_MUSAF, SHABBAT_MINCHA,
            TORAH_READING_SHORT, TORAH_READING_LONG,
        )

        fun customPrayer(id: String, name: String, points: Int) =
            AssignmentType(id, points, AssignmentCategory.PRAYER, name, isCustom = true)

        fun fromId(id: String): AssignmentType? = predefined.firstOrNull { it.id == id }
    }
}

fun AssignmentType.hebrewName(): String = displayName

data class AssignmentRecord(
    val id: String,
    val assignmentType: AssignmentType,
    val pointsEarned: Int,
    val timestamp: Instant,
    val notes: String? = null,
)

data class Student(
    val id: String,
    val name: String,
    val assignments: List<AssignmentRecord> = emptyList(),
)
