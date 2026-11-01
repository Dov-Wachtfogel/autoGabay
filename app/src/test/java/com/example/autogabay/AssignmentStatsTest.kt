package com.example.autogabay.assignments

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.util.UUID

class AssignmentStatsTest {

    @Test
    fun totalPointsSumsEarnedPoints() {
        val student = student(
            assignment(AssignmentType.DAILY_SHACHARIT, points = 2),
            assignment(AssignmentType.TORAH_READING_LONG, points = 5),
        )

        assertEquals(7, AssignmentStats.totalPoints(student))
    }

    @Test
    fun sortsStudentsFromFewestPointsToMost() {
        val high = student("high", assignment(AssignmentType.SHABBAT_SHACHARIT, 10))
        val low = student("low", assignment(AssignmentType.DAILY_MINCHA, 1))
        val none = student("none")

        assertEquals(
            listOf("none", "low", "high"),
            AssignmentStats.sortByTotalPoints(listOf(high, low, none)).map { it.id },
        )
    }

    @Test
    fun countsEachAssignmentTypeAndReturnsZeroForMissingTypes() {
        val student = student(
            assignment(AssignmentType.DAILY_SHACHARIT),
            assignment(AssignmentType.DAILY_SHACHARIT),
            assignment(AssignmentType.TORAH_READING_LONG),
        )

        val counts = AssignmentStats.countByType(student)

        assertEquals(2, counts[AssignmentType.DAILY_SHACHARIT])
        assertEquals(1, counts[AssignmentType.TORAH_READING_LONG])
        assertEquals(0, counts[AssignmentType.SHABBAT_SHACHARIT] ?: 0)
    }

    @Test
    fun combinesAssignmentsByPrayerAndReadingCategory() {
        val student = student(
            assignment(AssignmentType.DAILY_SHACHARIT),
            assignment(AssignmentType.ROSH_CHODESH),
            assignment(AssignmentType.TORAH_READING_LONG),
        )

        assertEquals(2, AssignmentStats.countByCategory(student, AssignmentCategory.PRAYER))
        assertEquals(1, AssignmentStats.countByCategory(student, AssignmentCategory.READING))
    }

    private fun student(vararg assignments: AssignmentRecord) =
        Student(id = UUID.randomUUID().toString(), name = "Student", assignments = assignments.toList())

    private fun student(id: String, vararg assignments: AssignmentRecord) =
        Student(id = id, name = id, assignments = assignments.toList())

    private fun assignment(
        type: AssignmentType,
        points: Int = type.defaultPoints,
    ) = AssignmentRecord(
        id = UUID.randomUUID().toString(),
        assignmentType = type,
        pointsEarned = points,
        timestamp = Instant.EPOCH,
    )
}
