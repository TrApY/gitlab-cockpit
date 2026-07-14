package dev.jota.gitlabcockpit.api

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies the pipeline and job models tolerate the real GitLab payload shape (unknown fields). */
class GitLabPipelineSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `pipeline with unknown fields parses and keeps the modeled ones`() {
        val payload = """
            {
              "id": 201,
              "iid": 7,
              "project_id": 100,
              "status": "running",
              "source": "merge_request_event",
              "ref": "feature/x",
              "sha": "aaa111bbb222",
              "before_sha": "0000000",
              "tag": false,
              "web_url": "https://gitlab.com/g/r/-/pipelines/201",
              "created_at": "2026-07-14T09:00:00.000Z",
              "updated_at": "2026-07-14T10:00:00.000Z"
            }
        """.trimIndent()

        val pipeline = json.decodeFromString<GitLabPipeline>(payload)

        assertEquals(201L, pipeline.id)
        assertEquals("running", pipeline.status)
        assertEquals("feature/x", pipeline.ref)
        assertEquals("aaa111bbb222", pipeline.sha)
        assertEquals("https://gitlab.com/g/r/-/pipelines/201", pipeline.webUrl)
        assertEquals("2026-07-14T10:00:00.000Z", pipeline.updatedAt)
        assertEquals("2026-07-14T09:00:00.000Z", pipeline.createdAt)
    }

    @Test
    fun `pipeline defaults timestamps to null when absent`() {
        val pipeline = json.decodeFromString<GitLabPipeline>(
            """
            {
              "id": 200,
              "status": "success",
              "ref": "main",
              "sha": "ccc333",
              "web_url": "https://gitlab.com/g/r/-/pipelines/200"
            }
            """.trimIndent(),
        )

        assertNull(pipeline.updatedAt)
        assertNull(pipeline.createdAt)
    }

    @Test
    fun `job with unknown fields parses and keeps the modeled ones`() {
        val payload = """
            {
              "id": 900,
              "status": "failed",
              "stage": "test",
              "name": "unit",
              "ref": "feature/x",
              "tag": false,
              "coverage": null,
              "allow_failure": true,
              "duration": 42.75,
              "web_url": "https://gitlab.com/g/r/-/jobs/900",
              "user": {"id": 1, "username": "jota", "name": "Jo Ta"},
              "commit": {"id": "aaa111"},
              "pipeline": {"id": 201, "status": "failed"}
            }
        """.trimIndent()

        val job = json.decodeFromString<GitLabJob>(payload)

        assertEquals(900L, job.id)
        assertEquals("unit", job.name)
        assertEquals("test", job.stage)
        assertEquals("failed", job.status)
        assertEquals(42.75, job.duration!!, 0.0001)
        assertTrue(job.allowFailure)
        assertEquals("https://gitlab.com/g/r/-/jobs/900", job.webUrl)
    }

    @Test
    fun `job defaults duration to null and allow_failure to false when absent`() {
        val job = json.decodeFromString<GitLabJob>(
            """
            {
              "id": 901,
              "status": "created",
              "stage": "build",
              "name": "compile",
              "web_url": "https://gitlab.com/g/r/-/jobs/901"
            }
            """.trimIndent(),
        )

        assertNull(job.duration)
        assertFalse(job.allowFailure)
    }
}
