package com.example.autogabay.assignments

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.ZoneId

object StudentCsvExporter {
    fun export(
        context: Context,
        students: List<Student>,
        assignmentTypes: List<AssignmentType> = AssignmentType.predefined,
        startDate: LocalDate? = null,
        endDate: LocalDate? = null,
        fileProviderAuthority: String = "${context.packageName}.fileprovider",
    ): Uri {
        require(fileProviderAuthority.isNotBlank())
        require((startDate == null) == (endDate == null)) {
            "יש לבחור תאריך התחלה וסיום."
        }
        if (startDate != null && endDate != null) {
            require(!endDate.isBefore(startDate)) { "תאריך הסיום חייב להיות אחרי תאריך ההתחלה." }
        }

        val types = assignmentTypes
        val zone = ZoneId.systemDefault()
        val exportStudents = if (startDate != null && endDate != null) {
            students.map { student ->
                student.copy(assignments = student.assignments.filter { record ->
                    val date = record.timestamp.atZone(zone).toLocalDate()
                    date >= startDate && date <= endDate
                })
            }
        } else students
        val header = buildList {
            add("שם התלמיד")
            add("סך הנקודות")
            types.forEach { add("מספר פעמים: ${it.hebrewName()}") }
            add("סך תפילות")
            add("סך קריאות תורה")
        }

        val csv = buildString {
            append('\uFEFF') // Excel UTF-8 detection, including Hebrew names.
            appendLine(header.joinToString(",") { it.csvField() })
            exportStudents.forEach { student ->
                val counts = AssignmentStats.countByType(student)
                val row = buildList {
                    add(student.name)
                    add(AssignmentStats.totalPoints(student).toString())
                    types.forEach { add((counts[it] ?: 0).toString()) }
                    add(AssignmentStats.countByCategory(student, AssignmentCategory.PRAYER).toString())
                    add(AssignmentStats.countByCategory(student, AssignmentCategory.READING).toString())
                }
                appendLine(row.joinToString(",") { it.csvField() })
            }
        }

        val directory = File(context.cacheDir, "exports")
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create export directory." }
        val file = File(directory, "student_assignments.csv")
        file.writeText(csv, StandardCharsets.UTF_8)
        return FileProvider.getUriForFile(context, fileProviderAuthority, file)
    }

    private fun String.csvField(): String = "\"${replace("\"", "\"\"")}\""
}
