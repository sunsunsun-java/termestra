package dev.termestra.team.application.service;

import dev.termestra.team.application.port.in.AppliedTeamScenario;
import dev.termestra.team.application.port.in.ApplyTeamScenarioCommand;
import dev.termestra.team.application.exception.TeamConflict;
import dev.termestra.team.application.port.out.*;
import dev.termestra.team.domain.model.TeamMember;
import dev.termestra.shared.concurrency.RuntimeOperationCoordinator;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class TeamScenarioApplicationServiceTest {
    @Test void startsScenarioMembersInCatalogOrderWithoutOverlap() throws Exception {
        String workspaceId = UUID.randomUUID().toString();
        InMemoryMembers members = new InMemoryMembers(workspaceId);
        List<String> startedRoles = new CopyOnWriteArrayList<>();
        CountDownLatch firstStart = new CountDownLatch(1);
        CountDownLatch nextStart = new CountDownLatch(1);
        CountDownLatch releaseStarts = new CountDownLatch(1);
        AtomicInteger startedCount = new AtomicInteger();
        AtomicInteger activeStarts = new AtomicInteger();
        AtomicInteger maximumActiveStarts = new AtomicInteger();
        TeamScenarioRuntime runtime = runtime(members, startedRoles, role -> {
            int active = activeStarts.incrementAndGet();
            maximumActiveStarts.accumulateAndGet(active, Math::max);
            int number = startedCount.incrementAndGet();
            if (number == 1) firstStart.countDown();
            else nextStart.countDown();
            try {
                await(releaseStarts);
                return new TeamScenarioRuntime.StartedRun("run-" + role, "running");
            } finally {
                activeStarts.decrementAndGet();
            }
        });
        TeamScenarioApplicationService service = service(members, runtime);

        try (ExecutorService caller = Executors.newSingleThreadExecutor(
                Thread.ofPlatform().name("team-scenario-test-caller").factory())) {
            Future<AppliedTeamScenario> result = caller.submit(() -> service.apply(command(workspaceId)));
            try {
                assertTrue(firstStart.await(1, TimeUnit.SECONDS), "the first member did not start");
                assertFalse(nextStart.await(200, TimeUnit.MILLISECONDS),
                        "the next member must wait until the current PTY start returns");
            } finally {
                releaseStarts.countDown();
            }
            AppliedTeamScenario applied = result.get(1, TimeUnit.SECONDS);

            assertEquals(List.of("coder", "reviewer", "tester"), startedRoles);
            assertEquals(List.of("coder", "reviewer", "tester"), applied.createdWorkers().stream()
                    .map(AppliedTeamScenario.StartedMember::role).toList());
            assertEquals(1, maximumActiveStarts.get());
            assertTrue(applied.injected());
        }
    }

    @Test void attemptsAllScenarioMembersBeforeReportingTheFirstStartFailure() {
        String workspaceId = UUID.randomUUID().toString();
        InMemoryMembers members = new InMemoryMembers(workspaceId);
        List<String> startedRoles = new ArrayList<>();
        TeamScenarioRuntime runtime = runtime(members, startedRoles, role -> new TeamScenarioRuntime.StartedRun(
                "run-" + role, "reviewer".equals(role) ? "error" : "running"));

        TeamConflict failure = assertThrows(TeamConflict.class,
                () -> service(members, runtime).apply(command(workspaceId)));

        assertTrue(failure.getMessage().startsWith("Failed to start scenario member: "));
        assertEquals(List.of("coder", "reviewer", "tester"), startedRoles);
        assertEquals(3, members.list(workspaceId).size());
    }

    private static TeamScenarioApplicationService service(InMemoryMembers members, TeamScenarioRuntime runtime) {
        return new TeamScenarioApplicationService(members, (member, plan) -> members.save(member), runtime,
                Clock.systemUTC(),new RuntimeOperationCoordinator());
    }

    private static ApplyTeamScenarioCommand command(String workspaceId) {
        return new ApplyTeamScenarioCommand(workspaceId, "build_review_test", "Implement the feature", "en", "4010");
    }

    private static TeamScenarioRuntime runtime(InMemoryMembers members, List<String> startedRoles,
                                               Function<String, TeamScenarioRuntime.StartedRun> start) {
        return new TeamScenarioRuntime() {
            @Override public boolean hasActiveOrchestrator(String ignored) { return true; }
            @Override public String resolveAndStoreLocale(String ignored, String requested) { return "en"; }
            @Override public WorkerLaunchPlan resolveDefaultWorkerLaunch(String ignored) {
                return new WorkerLaunchPlan("agent", List.of(), "preset", null,null, null,Map.of(),null,true);
            }
            @Override public StartedRun startWorker(String workspace, String workerId, String runtimePort) {
                String role = members.findById(workspace, workerId).orElseThrow().role().wireValue();
                startedRoles.add(role);
                return start.apply(role);
            }
            @Override public DeliveryResult deliverUserInput(String ignored, String text) {
                return new DeliveryResult(true, null);
            }
        };
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(1, TimeUnit.SECONDS)) throw new AssertionError("scenario start was not released");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static final class InMemoryMembers implements TeamMemberRepository {
        private final String workspaceId;
        private final Map<String, TeamMember> values = new LinkedHashMap<>();

        private InMemoryMembers(String workspaceId) { this.workspaceId = workspaceId; }

        @Override public boolean workspaceExists(String candidate) { return workspaceId.equals(candidate); }
        @Override public void save(TeamMember member) { values.put(member.id().toString(), member); }
        @Override public Optional<TeamMember> findById(String workspace, String agentId) {
            return Optional.ofNullable(values.get(agentId)).filter(member -> member.workspaceId().toString().equals(workspace));
        }
        @Override public Optional<TeamMember> findByName(String workspace, String name) {
            return values.values().stream().filter(member -> member.workspaceId().toString().equals(workspace))
                    .filter(member -> member.name().equals(name)).findFirst();
        }
        @Override public List<TeamMemberSummary> list(String workspace) {
            return values.values().stream().filter(member -> member.workspaceId().toString().equals(workspace))
                    .map(member -> new TeamMemberSummary(member.id().toString(), member.name(),
                            member.role().wireValue(), "preset"))
                    .toList();
        }
        @Override public boolean rename(String workspace, String agentId, String name) { return false; }
        @Override public boolean delete(String workspace, String agentId) { return values.remove(agentId) != null; }
    }
}
