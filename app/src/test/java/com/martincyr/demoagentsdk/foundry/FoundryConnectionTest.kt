package com.martincyr.demoagentsdk.foundry

import org.junit.Assert.assertEquals
import org.junit.Test

class FoundryConnectionTest {
    @Test
    fun `project endpoint builds thread and streaming urls`() {
        val connection = FoundryConnection(
            projectEndpoint = "https://resource.services.ai.azure.com/api/projects/demo",
            apiVersion = "v1",
            agentId = "agent-123"
        )

        assertEquals(
            "https://resource.services.ai.azure.com/api/projects/demo/agents/agent-123/endpoint/" +
                "protocols/openai/conversations?api-version=v1",
            connection.agentUrl("conversations")
        )
        assertEquals(
            "https://resource.services.ai.azure.com/api/projects/demo/agents/agent-123/endpoint/" +
                "protocols/openai/responses?api-version=v1",
            connection.agentUrl("responses")
        )
    }
}
