package com.yaoyihan.nikonconnect

import org.junit.Assert.*
import org.junit.Test

class AiPhotoTaskPolicyTest {
    private fun task(id: Int, active: Boolean = true) = AiPhotoTask(id.toString(), "photo-$id", "$id.jpg", AiPhotoStyle.WATERCOLOR, active)

    @Test fun admitsThreeButRejectsFourthActiveTask() {
        assertNull(aiSubmissionError(listOf(task(1), task(2)), "photo-3"))
        assertTrue(aiSubmissionError(listOf(task(1), task(2), task(3)), "photo-4")!!.contains("3 个"))
    }
    @Test fun rejectsDuplicatePhotoBeforeCapacityMessage() {
        assertTrue(aiSubmissionError(listOf(task(1), task(2), task(3)), "photo-1")!!.contains("该照片"))
    }
    @Test fun finishedTasksReleaseSlotsAndAllowExplicitResubmission() {
        assertNull(aiSubmissionError(listOf(task(1, false), task(2), task(3)), "photo-1"))
    }
}
