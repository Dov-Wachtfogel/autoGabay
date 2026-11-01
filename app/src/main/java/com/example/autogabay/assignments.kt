package com.example.autogabay.assignments

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

data class AssignmentUiState(
    val studentsByPoints: List<Student> = emptyList(),
    val defaultPoints: Map<AssignmentType, Int> = emptyMap(),
    val errorMessage: String? = null,
)

interface StudentRepository {
    val students: Flow<List<Student>>
    val pointValues: Flow<Map<AssignmentType, Int>>
    suspend fun addStudent(student: Student)
    suspend fun updateStudent(student: Student)
    suspend fun deleteStudent(studentId: String)
    suspend fun addCustomPrayer(name: String, points: Int)
    suspend fun removeAssignmentType(type: AssignmentType)
    suspend fun addAssignment(studentId: String, assignment: AssignmentRecord)
    suspend fun updateAssignment(assignment: AssignmentRecord)
    suspend fun deleteAssignment(assignmentId: String)
    suspend fun updateDefaultPoints(type: AssignmentType, points: Int)
    suspend fun restoreBackup(backup: StudentCsvBackup)
}

class GabayAssignmentViewModel(
    private val repository: StudentRepository,
) : ViewModel() {
    private val operationError = MutableStateFlow<String?>(null)

    val uiState: StateFlow<AssignmentUiState> =
        combine(repository.students, repository.pointValues, operationError) { students, points, error ->
            AssignmentUiState(
                studentsByPoints = AssignmentStats.sortByTotalPoints(students),
                defaultPoints = points,
                errorMessage = error,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AssignmentUiState(),
        )

    fun addStudent(student: Student) {
        require(student.id.isNotBlank()) { "Student id must not be blank." }
        require(student.name.isNotBlank()) { "Student name must not be blank." }
        launchOperation { repository.addStudent(student) }
    }

    fun updateStudent(student: Student) {
        require(student.id.isNotBlank()) { "מזהה התלמיד לא תקין." }
        require(student.name.isNotBlank()) { "יש להזין שם תלמיד." }
        launchOperation { repository.updateStudent(student) }
    }

    fun recordAssignment(studentId: String, type: AssignmentType, notes: String? = null) {
        require(studentId.isNotBlank()) { "Student id must not be blank." }
        launchOperation {
            val points = uiState.value.defaultPoints[type] ?: type.defaultPoints
            repository.addAssignment(
                studentId,
                AssignmentRecord(
                    id = UUID.randomUUID().toString(),
                    assignmentType = type,
                    pointsEarned = points,
                    timestamp = Instant.now(),
                    notes = notes?.trim()?.takeIf(String::isNotEmpty),
                ),
            )
        }
    }

    fun deleteStudent(studentId: String) {
        require(studentId.isNotBlank())
        launchOperation { repository.deleteStudent(studentId) }
    }

    fun updateAssignment(assignment: AssignmentRecord) {
        require(assignment.id.isNotBlank())
        require(assignment.pointsEarned >= 0)
        launchOperation { repository.updateAssignment(assignment) }
    }

    fun deleteAssignment(assignmentId: String) {
        require(assignmentId.isNotBlank())
        launchOperation { repository.deleteAssignment(assignmentId) }
    }

    fun addCustomPrayer(name: String, points: Int) {
        require(name.isNotBlank()) { "יש להזין שם תפילה." }
        require(points >= 0) { "הניקוד לא יכול להיות שלילי." }
        launchOperation { repository.addCustomPrayer(name, points) }
    }

    fun removeAssignmentType(type: AssignmentType) {
        launchOperation { repository.removeAssignmentType(type) }
    }

    fun restoreBackup(backup: StudentCsvBackup) {
        require(backup.students.isNotEmpty()) { "קובץ הגיבוי ריק." }
        launchOperation { repository.restoreBackup(backup) }
    }

    fun updateDefaultPoints(type: AssignmentType, points: Int) {
        require(points >= 0) { "הניקוד לא יכול להיות שלילי." }
        launchOperation { repository.updateDefaultPoints(type, points) }
    }

    fun clearError() {
        operationError.value = null
    }

    private fun launchOperation(block: suspend () -> Unit) {
        viewModelScope.launch {
            operationError.value = null
            runCatching { block() }
                .onFailure { operationError.value = it.message ?: "Operation failed." }
        }
    }
}

class AssignmentViewModelFactory(context: Context) : ViewModelProvider.Factory {
    private val repository = SqliteStudentRepository(context.applicationContext)

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(GabayAssignmentViewModel::class.java))
        return GabayAssignmentViewModel(repository) as T
    }
}
