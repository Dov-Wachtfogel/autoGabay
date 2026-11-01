package com.example.autogabay.assignments

/** Pure assignment calculations shared by the UI, CSV export, and JVM tests. */
object AssignmentStats {
    fun totalPoints(student: Student): Int =
        student.assignments.sumOf { it.pointsEarned }

    fun countByType(student: Student): Map<AssignmentType, Int> =
        student.assignments.groupingBy { it.assignmentType }.eachCount()

    fun countByCategory(student: Student, category: AssignmentCategory): Int =
        student.assignments.count { it.assignmentType.category == category }

    fun sortByTotalPoints(students: Iterable<Student>): List<Student> =
        students.sortedBy(::totalPoints)
}
