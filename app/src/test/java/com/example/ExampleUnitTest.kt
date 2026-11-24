package com.example

import com.example.afrina.core.ai.LocalSimulatedAiGateway
import com.example.afrina.core.domain.AiCompletionRequest
import com.example.afrina.core.i18n.stringsFor
import com.example.afrina.core.model.AgentRole
import com.example.afrina.core.model.AppLanguage
import com.example.afrina.core.network.ControlPlaneHealthDto
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun `persian and english language configs set expected layout direction`() {
        assertTrue(AppLanguage.PERSIAN.isRtl)
        assertFalse(AppLanguage.ENGLISH.isRtl)

        val fa = stringsFor(AppLanguage.PERSIAN)
        val en = stringsFor(AppLanguage.ENGLISH)
        assertTrue(fa.appTitle.isNotBlank())
        assertTrue(en.appTitle.isNotBlank())
        assertTrue(fa.phase0BannerTitle.isNotBlank())
        assertTrue(en.phase0BannerTitle.isNotBlank())
    }

    @Test
    fun `simulated AI gateway handles every agent role and marks output simulated`() = runTest {
        val gateway = LocalSimulatedAiGateway()
        assertTrue(gateway.supportedModels.isNotEmpty())

        for (role in AgentRole.entries) {
            val response = gateway.executePrompt(
                AiCompletionRequest(
                    agentRole = role,
                    modelId = gateway.supportedModels.first(),
                    prompt = "Verify architecture boundaries",
                    contextProjectSlug = "afrina-control-plane",
                )
            )
            assertTrue("Expected simulated flag for $role", response.isSimulated)
            assertTrue("Expected English summary for $role", response.outputTextEn.isNotBlank())
            assertTrue("Expected Persian summary for $role", response.outputTextFa.isNotBlank())
        }
    }

    @Test
    fun `moshi adapter serializes and deserializes ControlPlaneHealthDto`() {
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        val adapter = moshi.adapter(ControlPlaneHealthDto::class.java)
        val dto = ControlPlaneHealthDto(
            status = "healthy",
            apiVersion = "v1",
            vpsProfile = "ubuntu-24.04-docker",
            activeSandboxes = 2,
        )
        val json = adapter.toJson(dto)
        val parsed = adapter.fromJson(json)
        assertNotNull(parsed)
        assertEquals(dto, parsed)
    }
}
