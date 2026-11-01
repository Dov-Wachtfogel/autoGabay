package com.example.autogabay.assignments

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StudentCsvBackupFileTest {

    @Test
    fun parsesBackupWithHebrewAndQuotedMultilineNotes() {
        val csv = """
            kind,student_id,student_name,type_id,type_name,category,default_points,is_custom,is_active,assignment_id,points_earned,timestamp,notes
            TYPE,,,DAILY_SHACHARIT,שחרית,PRAYER,2,false,true,,,,
            STUDENT,student-1,יוסף,,,,,,,,,,
            ASSIGNMENT,student-1,יוסף,DAILY_SHACHARIT,שחרית,PRAYER,2,false,true,assignment-1,2,2026-03-01T09:00:00Z,"הערה, שורה ראשונה
            שורה שנייה"
        """.trimIndent()

        val backup = StudentCsvBackupFile.parse(csv)

        assertEquals("יוסף", backup.students.single().name)
        assertEquals(1, backup.students.single().assignments.size)
        assertEquals("הערה, שורה ראשונה\nשורה שנייה", backup.students.single().assignments.single().notes)
        assertTrue(AssignmentType.DAILY_SHACHARIT.id in backup.activeTypeIds)
    }
}
