package dev.termestra.workspace.application.service;

import dev.termestra.workspace.application.port.in.OrchestratorStartView;
import dev.termestra.workspace.application.port.in.CreateWorkspaceCommand;
import dev.termestra.workspace.application.port.out.WorkspaceRepository;
import dev.termestra.workspace.application.port.out.WorkspaceRegistration;
import dev.termestra.workspace.application.port.out.WorkspaceRuntimeCleaner;
import dev.termestra.workspace.domain.model.Workspace;
import dev.termestra.shared.concurrency.RuntimeOperationCoordinator;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkspaceApplicationServiceTest {
    @Test void commitsWorkspaceDeletionBeforeClearingRuntimeState() {
        List<String> events = new ArrayList<>();
        WorkspaceApplicationService service = service(new DeleteRepository(events, true, null),
                workspaceId -> events.add("runtime"));

        service.delete("workspace-1");

        assertEquals(List.of("database", "runtime"), events);
    }

    @Test void leavesRuntimeStateIntactWhenWorkspaceDeletionDoesNotCommit() {
        List<String> events = new ArrayList<>();
        IllegalStateException databaseFailure = new IllegalStateException("database unavailable");
        WorkspaceApplicationService service = service(
                new DeleteRepository(events, false, databaseFailure),
                workspaceId -> events.add("runtime"));

        assertEquals(databaseFailure, assertThrows(IllegalStateException.class,
                () -> service.delete("workspace-1")));
        assertEquals(List.of("database"), events);
    }

    @Test void derivesAValidNameWhenTheSelectedWorkspaceIsTheFilesystemRoot() {
        AtomicReference<Workspace> stored = new AtomicReference<>();
        WorkspaceRepository repository = new WorkspaceRepository() {
            @Override public WorkspaceRegistration register(Workspace workspace) {
                stored.set(workspace);
                return new WorkspaceRegistration(workspace, true);
            }
            @Override public List<Workspace> findAll() {
                return stored.get() == null ? List.of() : List.of(stored.get());
            }
            @Override public Optional<Workspace> find(String workspaceId) {
                return Optional.ofNullable(stored.get()).filter(value -> value.id().toString().equals(workspaceId));
            }
            @Override public boolean delete(String workspaceId) { return false; }
        };
        WorkspaceApplicationService service = new WorkspaceApplicationService(
                repository,
                ignored -> new dev.termestra.workspace.domain.model.WorkspacePath("/"),
                (workspace, startupCommand, commandPresetId, autostart) -> OrchestratorStartView.disabled(),
                ignored -> { },
                ignored -> { }, Clock.systemUTC());

        var result = service.create(new CreateWorkspaceCommand("/", "", null, null, false));

        assertEquals("/", result.workspace().name());
        assertEquals("/", result.workspace().path());
    }

    @Test void initializesMetadataBeforePreparingTheOrchestrator() {
        List<String> events = new ArrayList<>();
        MutableRepository repository = new MutableRepository();
        WorkspaceApplicationService service = new WorkspaceApplicationService(
                repository, ignored -> new dev.termestra.workspace.domain.model.WorkspacePath("/workspace"),
                (workspace, startupCommand, commandPresetId, autostart) -> {
                    events.add("orchestrator");
                    return OrchestratorStartView.disabled();
                },
                ignored -> events.add("metadata"), ignored -> { }, Clock.systemUTC());

        service.create(new CreateWorkspaceCommand("/workspace", "Lab", null, null, false));

        assertEquals(List.of("metadata", "orchestrator"), events);
    }

    @Test void slowOrchestratorPreparationDoesNotHoldTheWorkspaceExclusiveLock() throws Exception {
        MutableRepository repository = new MutableRepository();
        RuntimeOperationCoordinator operations = new RuntimeOperationCoordinator();
        CountDownLatch starterEntered = new CountDownLatch(1);
        CountDownLatch releaseStarter = new CountDownLatch(1);
        AtomicReference<String> workspaceId = new AtomicReference<>();
        WorkspaceApplicationService service = new WorkspaceApplicationService(
                repository, ignored -> new dev.termestra.workspace.domain.model.WorkspacePath("/workspace"),
                (workspace, startupCommand, commandPresetId, autostart) -> {
                    workspaceId.set(workspace.id().toString());
                    starterEntered.countDown();
                    await(releaseStarter);
                    return OrchestratorStartView.disabled();
                }, ignored -> { }, ignored -> { }, Clock.systemUTC(), operations);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var creation = executor.submit(() -> service.create(new CreateWorkspaceCommand(
                    "/workspace", "Lab", null, null, false)));

            assertTrue(starterEntered.await(1, TimeUnit.SECONDS));
            var coordinatedRead = executor.submit(() -> operations.withWorkspace(
                    workspaceId.get(),
                    () -> "workspace available"));

            try {
                assertEquals("workspace available", coordinatedRead.get(1, TimeUnit.SECONDS));
            } finally {
                releaseStarter.countDown();
                creation.get(1, TimeUnit.SECONDS);
            }
        }
    }

    @Test void serializesTheWholeCreateFlowByCanonicalPathBeforeRegisteringAgain() throws Exception {
        MutableRepository repository = new MutableRepository();
        RuntimeOperationCoordinator operations = new RuntimeOperationCoordinator(Duration.ofSeconds(2));
        CountDownLatch starterEntered = new CountDownLatch(1);
        CountDownLatch releaseStarter = new CountDownLatch(1);
        WorkspaceApplicationService service = new WorkspaceApplicationService(
                repository, ignored -> new dev.termestra.workspace.domain.model.WorkspacePath("/canonical/workspace"),
                (workspace, startupCommand, commandPresetId, autostart) -> {
                    starterEntered.countDown();
                    await(releaseStarter);
                    return OrchestratorStartView.disabled();
                }, ignored -> { }, ignored -> { }, Clock.systemUTC(), operations);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> service.create(new CreateWorkspaceCommand(
                    "/alias/one", "Lab", null, null, false)));
            assertTrue(starterEntered.await(1, TimeUnit.SECONDS));

            var second = executor.submit(() -> service.create(new CreateWorkspaceCommand(
                    "/alias/two", "Ignored", null, null, false)));
            Thread.sleep(150);
            int registrationsWhileFirstCreateWasUnfinished = repository.registerCalls.get();

            releaseStarter.countDown();
            assertTrue(first.get(1, TimeUnit.SECONDS).created());
            assertTrue(!second.get(1, TimeUnit.SECONDS).created());
            assertEquals(1, registrationsWhileFirstCreateWasUnfinished,
                    "a second create must not observe a registration whose preparation is unfinished");
            assertEquals(2, repository.registerCalls.get());
            assertEquals(1, repository.findAll().size());
        } finally {
            releaseStarter.countDown();
        }
    }

    @Test void rollsBackANewRegistrationWhenMetadataInitializationFails() {
        MutableRepository repository = new MutableRepository();
        AtomicInteger starterCalls = new AtomicInteger();
        IllegalStateException failure = new IllegalStateException("metadata unavailable");
        WorkspaceApplicationService service = new WorkspaceApplicationService(
                repository, ignored -> new dev.termestra.workspace.domain.model.WorkspacePath("/workspace"),
                (workspace, startupCommand, commandPresetId, autostart) -> {
                    starterCalls.incrementAndGet();
                    return OrchestratorStartView.disabled();
                },
                ignored -> { throw failure; }, ignored -> { }, Clock.systemUTC());

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service.create(new CreateWorkspaceCommand(
                        "/workspace", "Lab", null, null, false))));
        assertTrue(repository.findAll().isEmpty());
        assertEquals(1, repository.deleteCalls.get());
        assertEquals(0, starterCalls.get());
    }

    @Test void compensatesANewRegistrationWhenOrchestratorPreparationThrows() {
        List<String> events = new ArrayList<>();
        MutableRepository repository = new MutableRepository(events);
        IllegalStateException failure = new IllegalStateException("orchestrator preparation crashed");
        WorkspaceApplicationService service = new WorkspaceApplicationService(
                repository, ignored -> new dev.termestra.workspace.domain.model.WorkspacePath("/workspace"),
                (workspace, startupCommand, commandPresetId, autostart) -> { throw failure; },
                ignored -> { }, workspaceId -> events.add("runtime"), Clock.systemUTC());

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service.create(new CreateWorkspaceCommand(
                        "/workspace", "Lab", null, null, true))));

        assertTrue(repository.findAll().isEmpty());
        assertEquals(List.of("database", "runtime"), events,
                "durable registration must be removed before transient runtime cleanup");
    }

    @Test void safelyEnsuresMetadataForAnExistingRegistrationWithoutRestartingIt() {
        Workspace existing = Workspace.create(
                new dev.termestra.workspace.domain.model.WorkspaceName("Lab"),
                new dev.termestra.workspace.domain.model.WorkspacePath("/workspace"), Instant.now());
        MutableRepository repository = new MutableRepository(existing);
        AtomicInteger initializations = new AtomicInteger();
        AtomicInteger starterCalls = new AtomicInteger();
        WorkspaceApplicationService service = new WorkspaceApplicationService(
                repository, ignored -> existing.path(),
                (workspace, startupCommand, commandPresetId, autostart) -> {
                    starterCalls.incrementAndGet();
                    return OrchestratorStartView.disabled();
                }, ignored -> initializations.incrementAndGet(), ignored -> { }, Clock.systemUTC());

        var result = service.create(new CreateWorkspaceCommand(
                "/workspace", "Ignored", null, null, false));

        assertEquals(existing.id().toString(), result.workspace().id());
        assertEquals(1, initializations.get());
        assertEquals(0, starterCalls.get());
    }

    private static WorkspaceApplicationService service(WorkspaceRepository repository,
                                                         WorkspaceRuntimeCleaner cleaner) {
        return new WorkspaceApplicationService(repository, path -> {
            throw new UnsupportedOperationException("create is not exercised");
        }, (workspace, startupCommand, commandPresetId, autostart) -> OrchestratorStartView.disabled(),
                ignored -> { }, cleaner, Clock.systemUTC());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Test operation was interrupted", interrupted);
        }
    }

    private static final class MutableRepository implements WorkspaceRepository {
        private static final Object CREATE_MUTEX = new Object();
        private final AtomicReference<Workspace> workspace = new AtomicReference<>();
        private final AtomicInteger deleteCalls = new AtomicInteger();
        private final AtomicInteger registerCalls = new AtomicInteger();
        private final List<String> deleteEvents;

        private MutableRepository() { this((List<String>) null); }
        private MutableRepository(List<String> deleteEvents) { this.deleteEvents = deleteEvents; }
        private MutableRepository(Workspace existing) { this(); workspace.set(existing); }

        @Override public WorkspaceRegistration register(Workspace candidate) {
            registerCalls.incrementAndGet();
            synchronized (CREATE_MUTEX) {
                Workspace existing = workspace.get();
                if (existing != null) return new WorkspaceRegistration(existing, false);
                workspace.set(candidate);
                return new WorkspaceRegistration(candidate, true);
            }
        }

        @Override public List<Workspace> findAll() {
            return workspace.get() == null ? List.of() : List.of(workspace.get());
        }

        @Override public Optional<Workspace> find(String workspaceId) {
            return Optional.ofNullable(workspace.get())
                    .filter(value -> value.id().toString().equals(workspaceId));
        }

        @Override public boolean delete(String workspaceId) {
            deleteCalls.incrementAndGet();
            if (deleteEvents != null) deleteEvents.add("database");
            Workspace existing = workspace.get();
            return existing != null && existing.id().toString().equals(workspaceId)
                    && workspace.compareAndSet(existing, null);
        }
    }

    private static final class DeleteRepository implements WorkspaceRepository {
        private final List<String> events;
        private final boolean deleted;
        private final RuntimeException failure;

        private DeleteRepository(List<String> events, boolean deleted, RuntimeException failure) {
            this.events = events;
            this.deleted = deleted;
            this.failure = failure;
        }

        @Override public WorkspaceRegistration register(Workspace workspace) {
            throw new UnsupportedOperationException("create is not exercised");
        }

        @Override public List<Workspace> findAll() { return List.of(); }

        @Override public Optional<Workspace> find(String workspaceId) { return Optional.empty(); }

        @Override public boolean delete(String workspaceId) {
            events.add("database");
            if (failure != null) throw failure;
            return deleted;
        }
    }
}
