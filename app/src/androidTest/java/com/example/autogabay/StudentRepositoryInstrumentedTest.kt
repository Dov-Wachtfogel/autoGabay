package com.example.autogabay.assignments

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

class StudentRepositoryInstrumentedTest {

    @Test
    fun persistsStudentsAssignmentsAndPointDefaults() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = SqliteStudentRepository(context)
        val id = "test-${UUID.randomUUID()}"
        val student = Student(id = id, name = "Test Student")
        val recordedAt = Instant.parse("2026-01-02T03:04:05Z")
        val customPoints = 17

        repository.addStudent(student)
        repository.updateDefaultPoints(AssignmentType.DAILY_SHACHARIT, customPoints)
        repository.addAssignment(
            studentId = id,
            assignment = AssignmentRecord(
                id = UUID.randomUUID().toString(),
                assignmentType = AssignmentType.DAILY_SHACHARIT,
                pointsEarned = customPoints,
                timestamp = recordedAt,
                notes = "Instrumented test",
            ),
        )

        val reloadedRepository = SqliteStudentRepository(context)
        val restoredStudent = reloadedRepository.students.first { students ->
            students.any { it.id == id }
        }.first { it.id == id }

        assertEquals("Test Student", restoredStudent.name)
        assertEquals(1, restoredStudent.assignments.size)
        assertEquals(customPoints, restoredStudent.assignments.single().pointsEarned)
        assertEquals(recordedAt, restoredStudent.assignments.single().timestamp)
        assertEquals("Instrumented test", restoredStudent.assignments.single().notes)

        val restoredPoints = reloadedRepository.pointValues.first { values ->
            values[AssignmentType.DAILY_SHACHARIT] == customPoints
        }
        assertEquals(customPoints, restoredPoints[AssignmentType.DAILY_SHACHARIT])
    }

    @Test
    fun rejectsAssignmentsForUnknownStudents() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = SqliteStudentRepository(context)

        val error = runCatching {
            repository.addAssignment(
                studentId = "missing-${UUID.randomUUID()}",
                assignment = AssignmentRecord(
                    id = UUID.randomUUID().toString(),
                    assignmentType = AssignmentType.TORAH_READING_LONG,
                    pointsEarned = AssignmentType.TORAH_READING_LONG.defaultPoints,
                    timestamp = Instant.now(),
                ),
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
    }
}
