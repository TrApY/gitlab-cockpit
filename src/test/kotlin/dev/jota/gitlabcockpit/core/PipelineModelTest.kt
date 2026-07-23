package dev.jota.gitlabcockpit.core

import dev.jota.gitlabcockpit.api.GitLabJob
import dev.jota.gitlabcockpit.api.GitLabPipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for the platform-free pipeline logic: [groupByStage] order preservation,
 * [aggregateStatus] worst-of precedence (including the failed-with-allow_failure special case),
 * the [isJobRetryable] / [isJobCancelable] / [isJobPlayable] truth tables and the compact
 * attention-first rows of [compactStages] / [compactStageRow] (GLC-59).
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

    // --- mergePostMergePipelines (GLC-62) -----------------------------------------------------

    @Test
    fun `mergePostMergePipelines prepends post-merge pipelines missing from the list`() {
        val list = listOf(pipeline(1), pipeline(2))
        val postMerge = listOf(pipeline(9, status = "failed"))
        assertEquals(
            listOf(pipeline(9, status = "failed"), pipeline(1), pipeline(2)),
            mergePostMergePipelines(list, postMerge),
        )
    }

    @Test
    fun `mergePostMergePipelines does not duplicate a post-merge pipeline already present by id`() {
        val list = listOf(pipeline(1), pipeline(2))
        // id 2 already present (even with a different status) → only the missing id 9 is prepended.
        val postMerge = listOf(pipeline(9), pipeline(2, status = "failed"))
        assertEquals(
            listOf(pipeline(9), pipeline(1), pipeline(2)),
            mergePostMergePipelines(list, postMerge),
        )
    }

    @Test
    fun `mergePostMergePipelines returns the list unchanged when there are no post-merge pipelines`() {
        val list = listOf(pipeline(1), pipeline(2))
        assertEquals(list, mergePostMergePipelines(list, emptyList()))
    }

    @Test
    fun `mergePostMergePipelines yields the post-merge pipelines when the MR list is empty`() {
        val postMerge = listOf(pipeline(9), pipeline(8))
        assertEquals(postMerge, mergePostMergePipelines(emptyList(), postMerge))
    }

    @Test
    fun `mergePostMergePipelines with both lists empty is empty`() {
        assertEquals(emptyList<GitLabPipeline>(), mergePostMergePipelines(emptyList(), emptyList()))
    }

    @Test
    fun `mergePostMergePipelines keeps a stable order when prepending several`() {
        val list = listOf(pipeline(1))
        val postMerge = listOf(pipeline(30), pipeline(20), pipeline(10))
        assertEquals(
            listOf(pipeline(30), pipeline(20), pipeline(10), pipeline(1)),
            mergePostMergePipelines(list, postMerge),
        )
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

    // --- compactStages / compactStageRow (GLC-59) ---------------------------------------------

    /**
     * A stage with [jobCount] jobs named `name-1..n` and the given aggregate [status]; the jobs carry
     * a status consistent with the aggregate (`warning` → `failed` with allow_failure).
     */
    private fun stage(name: String, status: String, jobCount: Int = 1): StageGroup {
        val jobStatus = if (status == "warning") "failed" else status
        val jobs = (1..jobCount).map { job("$name-$it", name, jobStatus, allowFailure = status == "warning") }
        return StageGroup(name, jobs, status)
    }

    @Test
    fun `compactStages folds an all-success pipeline into a single summary row`() {
        val stages = listOf(
            stage("build", "success"),
            stage("test", "success", jobCount = 2),
            stage("deploy", "success"),
        )

        val rows = compactStages(stages, showAll = false)

        val summary = rows.single() as PipelineRow.Summary
        assertEquals(listOf("build", "test", "deploy"), summary.stages.map { it.name })
        assertEquals(4, summary.jobCount)
    }

    @Test
    fun `compactStages keeps a failed multi-job stage as a stage row and folds even non-consecutive greens`() {
        val stages = listOf(
            stage("build", "success"),
            stage("test", "failed", jobCount = 2),
            stage("deploy", "success"),
        )

        val rows = compactStages(stages, showAll = false)

        assertEquals(2, rows.size)
        assertEquals("test", (rows[0] as PipelineRow.Stage).stage.name)
        assertEquals(listOf("build", "deploy"), (rows[1] as PipelineRow.Summary).stages.map { it.name })
    }

    @Test
    fun `compactStages flattens a running single-job stage and keeps the summary last`() {
        val stages = listOf(
            stage("build", "success"),
            stage("deploy", "running"),
            stage("verify", "success"),
        )

        val rows = compactStages(stages, showAll = false)

        val flat = rows[0] as PipelineRow.FlatStage
        assertEquals("deploy", flat.stage.name)
        assertEquals("deploy-1", flat.job.name)
        assertTrue(rows[1] is PipelineRow.Summary)
        assertEquals(2, rows.size)
    }

    @Test
    fun `compactStages leaves a lone success stage as a flattened row instead of a summary`() {
        val rows = compactStages(listOf(stage("build", "success")), showAll = false)
        assertEquals("build", (rows.single() as PipelineRow.FlatStage).stage.name)
    }

    @Test
    fun `compactStages keeps a lone multi-job success stage as a classic stage row`() {
        val rows = compactStages(listOf(stage("build", "success", jobCount = 3)), showAll = false)
        assertEquals("build", (rows.single() as PipelineRow.Stage).stage.name)
    }

    @Test
    fun `compactStages keeps pipeline order when only one stage passed and no summary is built`() {
        val stages = listOf(stage("build", "success"), stage("test", "failed", jobCount = 2))

        val rows = compactStages(stages, showAll = false)

        assertEquals("build", (rows[0] as PipelineRow.FlatStage).stage.name)
        assertEquals("test", (rows[1] as PipelineRow.Stage).stage.name)
    }

    @Test
    fun `compactStages treats warning as needing attention and never folds it into the summary`() {
        val stages = listOf(stage("lint", "warning"), stage("build", "success"), stage("test", "success"))

        val rows = compactStages(stages, showAll = false)

        assertEquals("lint", (rows[0] as PipelineRow.FlatStage).stage.name)
        assertEquals(listOf("build", "test"), (rows[1] as PipelineRow.Summary).stages.map { it.name })
    }

    @Test
    fun `compactStages keeps every non-success aggregate as its own row in pipeline order`() {
        val attention = listOf("failed", "running", "pending", "manual", "canceled", "warning")
        val stages = attention.map { stage("s-$it", it) } + listOf(stage("g1", "success"), stage("g2", "success"))

        val rows = compactStages(stages, showAll = false)

        assertEquals(attention.map { "s-$it" }, rows.dropLast(1).map { (it as PipelineRow.FlatStage).stage.name })
        assertEquals(listOf("g1", "g2"), (rows.last() as PipelineRow.Summary).stages.map { it.name })
    }

    @Test
    fun `compactStages with showAll renders every stage as a classic stage row in pipeline order`() {
        val stages = listOf(
            stage("build", "success"),
            stage("test", "failed", jobCount = 2),
            stage("deploy", "success"),
        )

        val rows = compactStages(stages, showAll = true)

        assertEquals(listOf("build", "test", "deploy"), rows.map { (it as PipelineRow.Stage).stage.name })
    }

    @Test
    fun `compactStages of an empty pipeline is empty in both modes`() {
        assertEquals(emptyList<PipelineRow>(), compactStages(emptyList(), showAll = false))
        assertEquals(emptyList<PipelineRow>(), compactStages(emptyList(), showAll = true))
    }

    @Test
    fun `compactStageRow flattens only single-job stages`() {
        assertTrue(compactStageRow(stage("build", "failed")) is PipelineRow.FlatStage)
        assertTrue(compactStageRow(stage("test", "failed", jobCount = 2)) is PipelineRow.Stage)
    }
}
