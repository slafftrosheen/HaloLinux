package com.halo.host.linux

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HaloCliInputTest {
    @Test fun stdinQuestionsDoNotRequireProcessArguments() {
        val question = "private offline prompt"
        val answer = HaloCli.questionFromArguments(
            listOf("-"), question.byteInputStream()
        )
        assertEquals(question, answer)
    }

    @Test fun stdinQuestionLengthIsBounded() {
        assertThrows(IllegalArgumentException::class.java) {
            HaloCli.questionFromArguments(
                listOf("-"), "A".repeat(32_001).byteInputStream()
            )
        }
    }

    @Test fun regularArgumentsStillWork() {
        assertEquals("hello Halo",
            HaloCli.questionFromArguments(listOf("hello", "Halo")))
    }
}
