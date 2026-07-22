package dev.jota.gitlabcockpit.core

import dev.jota.gitlabcockpit.api.GitLabJob
import dev.jota.gitlabcockpit.api.GitLabPipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for the platform-free pipeline logic: [groupByStage] order preservation,
 * [aggregateStatus] worst-of precedence (including the failed-with-allow_failure special case) and
 * the [isJobRetryable] / [isJobCancelable] / [isJobPlayable] truth tables.
 */
class PipelineModelTest {

    private var seq = 0L
    private fun job(name: String, stage: String, status: String, allowFailure: Boolean = false) = GitLabJob(
        id = ++seq,
        name = name,
        stage = stage,
        status = status,
        allowFailure = allowFailure,
        webUrl = "https://gitlab.com/g/r/-/jobs/$seq",
    )

    // --- groupByStage -------------------------------------------------------------------------

    @Test
    fun `groupByStage preserves the order stages first appear and keeps job membership`() {
        val jobs = listOf(
            job("b1", "build", "success"),
            job("b2", "build", "success"),
            job("t1", "test", "failed"),
            job("d1", "deploy", "manual"),
            job("t2", "test", "success"),
        )

        val groups = groupByStage(jobs)

        assertEquals(listOf("build", "test", "deploy"), groups.map { it.name })
        assertEquals(listOf("b1", "b2"), groups[0].jobs.map { it.name })
        assertEquals(listOf("t1", "t2"), groups[1].jobs.map { it.name })
        assertEquals(listOf("d1"), groups[2].jobs.map { it.name })
    }

    @Test
    fun `groupByStage on empty list yields no groups`() {
        assertEquals(emptyList<StageGroup>(), groupByStage(emptyList()))
    }

    @Test
    fun `groupByStage orders stages by their smallest job id, not by arrival order`() {
        // Jobs arrive stage-shuffled, but the deploy stage's first job (id 1) was created before the
        // build stage's (id 2) before the test stage's (id 4): execution order is deploy, build, test.
        val deploy1 = job("d1", "deploy", "manual") // id 1
        val build1 = job("b1", "build", "success") // id 2
        val build2 = job("b2", "build", "success") // id 3
        val test1 = job("t1", "test", "success") // id 4

        val groups = groupByStage(listOf(build2, test1, deploy1, build1).sortedBy { it.name })

        assertEquals(listOf("deploy", "build", "test"), groups.map { it.name })
    }

    @Test
    fun `groupByStage sorts the jobs inside a stage by id ascending`() {
        val first = job("first", "build", "success") // id 1
        val second = job("second", "build", "success") // id 2
        // Fed newest-first; the stage must still list them by id ascending (1 then 2).
        val groups = groupByStage(listOf(second, first))
        assertEquals(listOf(1L, 2L), groups.single().jobs.map { it.id })
        assertEquals(listOf("first", "second"), groups.single().jobs.map { it.name })
    }

    @Test
    fun `groupByStage keeps pre first and deploy last regardless of arrival`() {
        // Created in execution order, so ids grow with the pipeline definition (.pre → build → deploy).
        val pre = job("prep", ".pre", "success") // id 1
        val build = job("compile", "build", "success") // id 2
        val deploy = job("ship", "deploy_to_preprod", "manual") // id 3
        // GitLab returns them shuffled (deploy, build, pre); execution order is pre, build, deploy.
        val groups = groupByStage(listOf(deploy, build, pre))
        assertEquals(listOf(".pre", "build", "deploy_to_preprod"), groups.map { it.name })
    }

    @Test
    fun `groupByStage sets each group status via aggregateStatus`() {
        val groups = groupByStage(
            listOf(
                job("a", "build", "success"),
                job("b", "test", "failed"),
            ),
        )
        assertEquals("success", groups.first { it.name == "build" }.status)
        assertEquals("failed", groups.first { it.name == "test" }.status)
    }

    // --- aggregateStatus, in the documented worst-of order ------------------------------------

    @Test
    fun `empty stage aggregates to success`() {
        assertEquals("success", aggregateStatus(emptyList()))
    }

    @Test
    fun `all success aggregates to success`() {
        assertEquals("success", aggregateStatus(listOf(job("a", "s", "success"), job("b", "s", "success"))))
    }

    @Test
    fun `failed without allow_failure wins over everything`() {
        val jobs = listOf(
            job("f", "s", "failed"),
            job("r", "s", "running"),
            job("p", "s", "pending"),
            job("m", "s", "manual"),
            job("c", "s", "canceled"),
            job("ok", "s", "success"),
        )
        assertEquals("failed", aggregateStatus(jobs))
    }

    @Test
    fun `running wins over pending manual canceled success`() {
        val jobs = listOf(
            job("r", "s", "running"),
            job("p", "s", "pending"),
            job("m", "s", "manual"),
            job("c", "s", "canceled"),
            job("ok", "s", "success"),
        )
        assertEquals("running", aggregateStatus(jobs))
    }

    @Test
    fun `pending and created share one bucket above manual`() {
        assertEquals(
            "pending",
            aggregateStatus(listOf(job("p", "s", "pending"), job("m", "s", "manual"), job("ok", "s", "success"))),
        )
        assertEquals(
            "pending",
            aggregateStatus(listOf(job("c", "s", "created"), job("m", "s", "manual"), job("ok", "s", "success"))),
        )
    }

    @Test
    fun `manual wins over canceled and success`() {
        assertEquals(
            "manual",
            aggregateStatus(listOf(job("m", "s", "manual"), job("c", "s", "canceled"), job("ok", "s", "success"))),
        )
    }

    @Test
    fun `canceled wins over success`() {
        assertEquals("canceled", aggregateStatus(listOf(job("c", "s", "canceled"), job("ok", "s", "success"))))
    }

    @Test
    fun `failed with allow_failure counts as warning when the stage would otherwise be success`() {
        val jobs = listOf(
            job("flaky", "s", "failed", allowFailure = true),
            job("ok", "s", "success"),
        )
        assertEquals("warning", aggregateStatus(jobs))
    }

    @Test
    fun `failed with allow_failure never triggers the failed tier`() {
        // A real failure alongside an allowed failure still aggregates to failed.
        val jobs = listOf(
            job("hard", "s", "failed", allowFailure = false),
            job("flaky", "s", "failed", allowFailure = true),
        )
        assertEquals("failed", aggregateStatus(jobs))
    }

    @Test
    fun `allowed failure does not override a higher running tier`() {
        val jobs = listOf(
            job("flaky", "s", "failed", allowFailure = true),
            job("r", "s", "running"),
        )
        assertEquals("running", aggregateStatus(jobs))
    }

    @Test
    fun `skipped does not raise the aggregate above success`() {
        assertEquals("success", aggregateStatus(listOf(job("sk", "s", "skipped"), job("ok", "s", "success"))))
        assertEquals("success", aggregateStatus(listOf(job("sk", "s", "skipped"))))
    }

    // --- isJob* truth tables ------------------------------------------------------------------

    @Test
    fun `isJobRetryable is true only for finished statuses`() {
        assertTrue(isJobRetryable("failed"))
        assertTrue(isJobRetryable("canceled"))
        assertTrue(isJobRetryable("success"))
        assertFalse(isJobRetryable("running"))
        assertFalse(isJobRetryable("pending"))
        assertFalse(isJobRetryable("created"))
        assertFalse(isJobRetryable("manual"))
        assertFalse(isJobRetryable("skipped"))
    }

    @Test
    fun `isJobCancelable is true only for unfinished statuses`() {
        assertTrue(isJobCancelable("created"))
        assertTrue(isJobCancelable("pending"))
        assertTrue(isJobCancelable("running"))
        assertFalse(isJobCancelable("failed"))
        assertFalse(isJobCancelable("canceled"))
        assertFalse(isJobCancelable("success"))
        assertFalse(isJobCancelable("manual"))
    }

    @Test
    fun `isJobPlayable is true only for manual`() {
        assertTrue(isJobPlayable("manual"))
        assertFalse(isJobPlayable("failed"))
        assertFalse(isJobPlayable("success"))
        assertFalse(isJobPlayable("created"))
        assertFalse(isJobPlayable("running"))
    }

    // --- mergeHeadPipeline ---------------------------------------------------------------------

    private fun pipeline(id: Long, status: String = "success") = GitLabPipeline(
        id = id,
        status = status,
        ref = "main",
        sha = "sha$id",
        webUrl = "https://gitlab.com/g/r/-/pipelines/$id",
    )

    @Test
    fun `mergeHeadPipeline with a null head returns the list unchanged`() {
        val list = listOf(pipeline(1), pipeline(2))
        assertEquals(list, mergeHeadPipeline(list, null))
    }

    @Test
    fun `mergeHeadPipeline keeps the list when the head is already present by id`() {
        val list = listOf(pipeline(1), pipeline(2))
        // Same id as an existing pipeline (even with a different status) → no duplicate, list as-is.
        assertEquals(list, mergeHeadPipeline(list, pipeline(1, status = "failed")))
    }

    @Test
    fun `mergeHeadPipeline prepends a head missing from the list`() {
        val list = listOf(pipeline(1))
        val head = pipeline(9)
        assertEquals(listOf(head, pipeline(1)), mergeHeadPipeline(list, head))
    }

    // --- isPipelineLive (GLC-43 B) ------------------------------------------------------------

    @Test
    fun `isPipelineLive is true when any job is created pending or running`() {
        assertTrue(isPipelineLive(listOf(job("a", "build", "success"), job("b", "test", "running"))))
        assertTrue(isPipelineLive(listOf(job("a", "build", "pending"))))
        assertTrue(isPipelineLive(listOf(job("a", "build", "created"))))
    }

    @Test
    fun `isPipelineLive is false when every job is terminal`() {
        assertFalse(
            isPipelineLive(
                listOf(
                    job("a", "build", "success"),
                    job("b", "test", "failed"),
                    job("c", "deploy", "canceled"),
                    job("d", "manual", "manual"),
                    job("e", "opt", "skipped"),
                ),
            ),
        )
    }

    @Test
    fun `isPipelineLive is false for an empty pipeline`() {
        assertFalse(isPipelineLive(emptyList()))
    }

    // --- stagesToExpand (GLC-43 B) ------------------------------------------------------------

    @Test
    fun `stagesToExpand keeps a failed stage even when it was not previously expanded`() {
        val stages = listOf(
            StageGroup("build", listOf(job("a", "build", "success")), "success"),
            StageGroup("test", listOf(job("b", "test", "failed")), "failed"),
        )
        assertEquals(setOf("test"), stagesToExpand(emptySet(), stages))
    }

    @Test
    fun `stagesToExpand keeps a previously expanded stage that still exists`() {
        val stages = listOf(
            StageGroup("build", listOf(job("a", "build", "success")), "success"),
            StageGroup("test", listOf(job("b", "test", "running")), "running"),
        )
        assertEquals(setOf("build"), stagesToExpand(setOf("build"), stages))
    }

    @Test
    fun `stagesToExpand drops a previously expanded stage that no longer exists`() {
        val stages = listOf(StageGroup("build", listOf(job("a", "build", "success")), "success"))
        assertEquals(emptySet<String>(), stagesToExpand(setOf("gone"), stages))
    }

    @Test
    fun `stagesToExpand unions the failed and previously expanded sets`() {
        val stages = listOf(
            StageGroup("build", listOf(job("a", "build", "success")), "success"),
            StageGroup("test", listOf(job("b", "test", "running")), "running"),
            StageGroup("deploy", listOf(job("c", "deploy", "failed")), "failed"),
        )
        assertEquals(setOf("build", "deploy"), stagesToExpand(setOf("build"), stages))
    }
}
