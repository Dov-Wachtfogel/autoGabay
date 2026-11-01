package com.example.autogabay.assignments

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.Instant

data class StudentCsvBackup(
    val students: List<Student>,
    val assignmentTypes: List<AssignmentType>,
    val activeTypeIds: Set<String>,
)

object StudentCsvBackupFile {
    private val headers = listOf(
        "kind", "student_id", "student_name", "type_id", "type_name",
        "category", "default_points", "is_custom", "is_active",
        "assignment_id", "points_earned", "timestamp", "notes",
    )

    fun export(
        context: Context,
        students: List<Student>,
        activeTypes: List<AssignmentType>,
        pointValues: Map<AssignmentType, Int> = emptyMap(),
        authority: String = "${context.packageName}.fileprovider",
    ): Uri {
        val typesById = linkedMapOf<String, AssignmentType>()
        (AssignmentType.predefined + activeTypes +
            students.flatMap { student -> student.assignments.map { it.assignmentType } }
            ).forEach { typesById.putIfAbsent(it.id, it) }
        val allTypes = typesById.values.map { type ->
            AssignmentType(
                id = type.id,
                defaultPoints = pointValues[type] ?: type.defaultPoints,
                category = type.category,
                displayName = type.displayName,
                isCustom = type.isCustom,
            )
        }
        val activeIds = activeTypes.mapTo(mutableSetOf()) { it.id }
        val rows = mutableListOf<List<String>>()
        allTypes.forEach { type ->
            rows += listOf(
                "TYPE", "", "", type.id, type.displayName, type.category.name,
                type.defaultPoints.toString(), type.isCustom.toString(),
                (type.id in activeIds).toString(), "", "", "", "",
            )
        }
        students.forEach { student ->
            rows += listOf(
                "STUDENT", student.id, student.name, "", "", "", "", "", "", "", "", "", "",
            )
            student.assignments.forEach { assignment ->
                rows += listOf(
                    "ASSIGNMENT", student.id, student.name,
                    assignment.assignmentType.id, assignment.assignmentType.displayName,
                    assignment.assignmentType.category.name, assignment.assignmentType.defaultPoints.toString(),
                    assignment.assignmentType.isCustom.toString(), "true",
                    assignment.id, assignment.pointsEarned.toString(),
                    assignment.timestamp.toString(), assignment.notes.orEmpty(),
                )
            }
        }

        val csv = buildString {
            append('\uFEFF')
            appendLine(headers.joinToString(",") { it.csvField() })
            rows.forEach { row -> appendLine(row.joinToString(",") { it.csvField() }) }
        }
        val directory = File(context.cacheDir, "exports")
        check(directory.isDirectory || directory.mkdirs()) { "לא ניתן ליצור תיקיית יצוא." }
        val file = File(directory, "student_backup.csv")
        file.writeText(csv, StandardCharsets.UTF_8)
        return FileProvider.getUriForFile(context, authority, file)
    }

    fun parse(csv: String): StudentCsvBackup {
        val rows = parseRows(csv.removePrefix("\uFEFF"))
        require(rows.isNotEmpty() && rows.first() == headers) {
            "מבנה קובץ CSV אינו תואם לגיבוי של האפליקציה."
        }
        val typeMap = linkedMapOf<String, AssignmentType>()
        val activeIds = mutableSetOf<String>()
        val studentNames = linkedMapOf<String, String>()
        val assignments = mutableMapOf<String, MutableList<AssignmentRecord>>()

        rows.drop(1).forEachIndexed { index, row ->
            require(row.size == headers.size) { "שורה ${index + 2}: מספר העמודות אינו תקין." }
            when (row[0]) {
                "TYPE" -> {
                    val type = row.toAssignmentType()
                    typeMap[type.id] = type
                    if (row[8].toBooleanStrictOrNull() == true) activeIds += type.id
                }
                "STUDENT" -> {
                    require(row[1].isNotBlank() && row[2].isNotBlank()) {
                        "שורה ${index + 2}: חסרים מזהה או שם תלמיד."
                    }
                    studentNames[row[1]] = row[2]
                }
                "ASSIGNMENT" -> {
                    require(row[1].isNotBlank() && row[9].isNotBlank()) {
                        "שורה ${index + 2}: חסר מזהה תלמיד או מטלה."
                    }
                    val type = typeMap[row[3]] ?: row.toAssignmentType().also { typeMap[it.id] = it }
                    val points = row[10].toIntOrNull()
                        ?: error("שורה ${index + 2}: ערך נקודות לא תקין.")
                    require(points >= 0) { "שורה ${index + 2}: נקודות שליליות אינן תקינות." }
                    assignments.getOrPut(row[1]) { mutableListOf() }.add(
                        AssignmentRecord(
                            id = row[9],
                            assignmentType = type,
                            pointsEarned = points,
                            timestamp = runCatching { Instant.parse(row[11]) }
                                .getOrElse { error("שורה ${index + 2}: תאריך לא תקין.") },
                            notes = row[12].ifBlank { null },
                        ),
                    )
                    studentNames.putIfAbsent(row[1], row[2])
                }
                else -> error("שורה ${index + 2}: סוג רשומה לא מוכר.")
            }
        }

        require(studentNames.isNotEmpty()) { "קובץ הגיבוי אינו מכיל תלמידים." }
        val students = studentNames.map { (id, name) ->
            Student(id, name, assignments[id].orEmpty())
        }
        return StudentCsvBackup(students, typeMap.values.toList(), activeIds)
    }

    private fun List<String>.toAssignmentType(): AssignmentType {
        val id = get(3)
        val name = get(4)
        val category = runCatching { AssignmentCategory.valueOf(get(5)) }
            .getOrElse { error("קטגוריית מטלה לא מוכרת.") }
        val points = get(6).toIntOrNull() ?: error("ניקוד ברירת מחדל לא תקין.")
        val custom = get(7).toBooleanStrictOrNull()
            ?: error("סוג מטלה לא תקין.")
        return if (custom) {
            require(category == AssignmentCategory.PRAYER) { "תפילה מותאמת אישית חייבת להיות מסוג תפילה." }
            AssignmentType.customPrayer(id, name, points)
        } else {
            AssignmentType.fromId(id)?.copyWithPoints(points) ?: AssignmentType(
                id, points, category, name, isCustom = false,
            )
        }
    }

    private fun AssignmentType.copyWithPoints(points: Int) =
        AssignmentType(id, points, category, displayName, isCustom)

    private fun String.csvField(): String = "\"${replace("\"", "\"\"")}\""

    /** Supports escaped quotes, commas, and line breaks inside quoted CSV fields. */
    private fun parseRows(csv: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0
        while (index < csv.length) {
            val char = csv[index]
            when {
                quoted && char == '"' && index + 1 < csv.length && csv[index + 1] == '"' -> {
                    field.append('"')
                    index++
                }
                char == '"' -> quoted = !quoted
                char == ',' && !quoted -> {
                    row += field.toString()
                    field.clear()
                }
                (char == '\n' || char == '\r') && !quoted -> {
                    row += field.toString()
                    field.clear()
                    if (row.any(String::isNotEmpty)) rows += row.toList()
                    row.clear()
                    if (char == '\r' && index + 1 < csv.length && csv[index + 1] == '\n') index++
                }
                else -> field.append(char)
            }
            index++
        }
        require(!quoted) { "קובץ CSV מכיל שדה עם מרכאות לא סגורות." }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row += field.toString()
            rows += row
        }
        return rows
    }
}
