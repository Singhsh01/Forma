package studio.forma.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import studio.forma.engine.GenerationConfig;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JobManagerTest {
    private JobManager jobs;

    @AfterEach
    void tearDown() {
        if (jobs != null) jobs.shutdown();
    }

    static Job await(Job j, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (!j.isTerminal() && System.currentTimeMillis() < end) Thread.sleep(20);
        return j;
    }

    @Test
    void generationCompletesAndIsDeterministic() throws Exception {
        jobs = new JobManager(2, 4, 8, 60_000);
        Job a = jobs.submitGenerate(GenerationConfig.of("canal", 11));
        Job b = jobs.submitGenerate(GenerationConfig.of("canal", 11));
        await(a, 30_000);
        await(b, 30_000);
        assertEquals(Job.Status.COMPLETED, a.status(), String.valueOf(a.error()));
        assertEquals(Job.Status.COMPLETED, b.status());
        assertEquals(a.result().grid().fingerprint(), b.result().grid().fingerprint(), "same config, same design");
        assertTrue(a.statusJson().contains("\"status\":\"completed\""));
    }

    @Test
    void unknownPresetIsRejectedBeforeQueueing() {
        jobs = new JobManager(1, 1, 4, 10_000);
        assertThrows(IllegalArgumentException.class, () -> jobs.submitGenerate(GenerationConfig.of("atlantis", 1)));
        assertEquals(0, jobs.activeCount());
    }

    @Test
    void fullQueueIsRejectedWith429() throws Exception {
        jobs = new JobManager(1, 1, 8, 60_000);
        Job running = jobs.submitGenerate(GenerationConfig.of("library", 1));
        Job queued = jobs.submitGenerate(GenerationConfig.of("library", 2));
        JobManager.Rejected r = assertThrows(JobManager.Rejected.class, () -> jobs.submitGenerate(GenerationConfig.of("library", 3)));
        assertEquals(429, r.status);
        jobs.cancel(running.id);
        jobs.cancel(queued.id);
        await(running, 30_000);
        await(queued, 30_000);
        assertTrue(running.isTerminal() && queued.isTerminal());
    }

    @Test
    void cancellationStopsAJob() throws Exception {
        jobs = new JobManager(1, 2, 8, 60_000);
        Job j = jobs.submitGenerate(GenerationConfig.of("library", 5));
        Thread.sleep(30);
        assertTrue(jobs.cancel(j.id));
        await(j, 30_000);
        // a job that finished before the cancel landed is also acceptable, but it must be terminal
        assertTrue(j.status() == Job.Status.CANCELLED || j.status() == Job.Status.COMPLETED, j.status().toString());
        assertFalse(jobs.cancel("missing-job"));
    }

    @Test
    void timeoutFailsALongJob() throws Exception {
        jobs = new JobManager(1, 2, 8, 1);   // 1 ms budget: the generator's cancellation checks trip it
        Job j = jobs.submitGenerate(GenerationConfig.of("library", 5));
        await(j, 30_000);
        assertNotEquals(Job.Status.COMPLETED, j.status());
    }

    @Test
    void refinementOnACompletedJobReportsTheKeptState() throws Exception {
        jobs = new JobManager(2, 4, 8, 60_000);
        Job base = await(jobs.submitGenerate(GenerationConfig.of("organic", 3)), 30_000);
        assertEquals(Job.Status.COMPLETED, base.status());
        Job r = await(jobs.submitRefine(null, base.id, new JobManager.RefineSpec(300, 0.3, 0.02, false, Map.of(), 2, true)), 60_000);
        assertEquals(Job.Status.COMPLETED, r.status(), String.valueOf(r.error()));
        String json = r.refinementJson();
        assertTrue(json.contains("\"kept\":\"best\""));
        assertTrue(json.contains("\"baseJobId\":\"" + base.id + "\""));
    }

    @Test
    void invalidAnnealingScheduleIsRejected() {
        jobs = new JobManager(1, 2, 8, 60_000);
        assertThrows(IllegalArgumentException.class, () ->
            jobs.submitRefine(GenerationConfig.of("library", 1), null, new JobManager.RefineSpec(100, 0.2, 0.5, true, Map.of(), 1, true)));
    }
}
