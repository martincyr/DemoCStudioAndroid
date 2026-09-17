package com.martincyr.demoagentsdk.foundry

import org.junit.Assert.assertEquals
import org.junit.Test

class FoundryConnectionTest {
    @Test
    fun `project endpoint builds thread and streaming urls`() {
        val connection = FoundryConnection(
            projectEndpoint = "https://resource.services.ai.azure.com/api/projects/demo",
            apiVersion = "2025-05-01",
            agentId = "agent-123"
        )

        assertEquals(
            "https://resource.services.ai.azure.com/api/projects/demo/threads?api-version=2025-05-01",
            connection.url("threads")
        )
        assertEquals(
            "https://resource.services.ai.azure.com/api/projects/demo/threads/t1/runs" +
                "?stream=true&api-version=2025-05-01",
            connection.url("threads/t1/runs?stream=true")
        )
    }
}
