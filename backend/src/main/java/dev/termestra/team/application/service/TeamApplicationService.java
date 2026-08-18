package dev.termestra.team.application.service;

import dev.termestra.shared.id.*;
import dev.termestra.shared.concurrency.RuntimeOperationCoordinator;
import dev.termestra.team.application.exception.*;
import dev.termestra.team.application.port.in.*;
import dev.termestra.team.application.port.out.*;
import dev.termestra.team.domain.model.*;

import java.time.Clock;
import java.time.Instant;
import java.util.*;

public final class TeamApplicationService implements TeamUseCase, TeamAdminUseCase, DispatchQuery {
    private final TeamLedger ledger;
    private final TeamMemberRepository members;
    private final AgentAuthenticator authenticator;
    private final AgentTeamNotifier notifier;
    private final WorkerRuntimeStatus runtime;
    private final PendingTaskProjection pendingTasks;
    private final Clock clock;
    private final RuntimeOperationCoordinator operations;
    private final DispatchDeliveryScheduler deliveryScheduler;

    public TeamApplicationService(TeamLedger ledger, TeamMemberRepository members, AgentAuthenticator authenticator,
                                  AgentTeamNotifier notifier, WorkerRuntimeStatus runtime,
                                  PendingTaskProjection pendingTasks, Clock clock) {
        this(ledger, members, authenticator, notifier, runtime, pendingTasks, clock,
                new RuntimeOperationCoordinator(), () -> { });
    }

    public TeamApplicationService(TeamLedger ledger, TeamMemberRepository members, AgentAuthenticator authenticator,
                                  AgentTeamNotifier notifier, WorkerRuntimeStatus runtime,
                                  PendingTaskProjection pendingTasks, Clock clock,
                                  RuntimeOperationCoordinator operations) {
        this(ledger, members, authenticator, notifier, runtime, pendingTasks, clock, operations,
                () -> { });
    }

    public TeamApplicationService(TeamLedger ledger, TeamMemberRepository members,
                                  AgentAuthenticator authenticator, AgentTeamNotifier notifier,
                                  WorkerRuntimeStatus runtime, PendingTaskProjection pendingTasks,
                                  Clock clock, RuntimeOperationCoordinator operations,
                                  DispatchDeliveryScheduler deliveryScheduler) {
        this.ledger = ledger;
        this.members = members;
        this.authenticator = authenticator;
        this.notifier = notifier;
        this.runtime = runtime;
        this.pendingTasks = pendingTasks;
        this.clock = clock;
        this.operations = operations;
        this.deliveryScheduler = deliveryScheduler;
    }

    @Override public TeamOperationResult send(SendTaskCommand command) {
        requireRole(command.workspaceId(), command.actorId(), command.token(), "send", AgentRole.ORCHESTRATOR);
        String workerName = TeamInputLimits.memberName(command.workerName());
        String taskText = TeamInputLimits.taskText(command.text());
        TeamMember worker = members.findByName(command.workspaceId(), workerName)
                .orElseThrow(() -> new TeamConflict("Worker not found: " + workerName));
        return operations.withAgent(command.workspaceId(), worker.id().toString(),
                () -> enqueueForWorker(command, worker.id().toString(), workerName, taskText));
    }

    private TeamOperationResult enqueueForWorker(SendTaskCommand command, String workerId,
                                                 String workerName, String taskText) {
        TeamMember worker = members.findById(command.workspaceId(), workerId)
                .orElseThrow(() -> new TeamConflict("Worker no longer exists: " + workerName));
        Instant now = Instant.now(clock);
        Dispatch dispatch = Dispatch.create(WorkspaceId.parse(command.workspaceId()), command.actorId(),
                worker.id(), new TaskText(taskText), now);
        String runtimePort = TeamInputLimits.runtimePort(command.runtimePort());
        String idempotencyKey = command.idempotencyKey() == null ? null
                : TeamInputLimits.idempotencyKey(command.idempotencyKey());
        DispatchEnqueueResult accepted = ledger.enqueue(dispatch,
                new TeamMessage(command.workspaceId(), worker.id().toString(), "send",
                        command.actorId(), worker.id().toString(), taskText, null, List.of(), now),
                runtimePort, idempotencyKey);
        if (accepted.created()) {
            pendingTasks.invalidate(command.workspaceId());
            deliveryScheduler.wake();
        }
        return new TeamOperationResult(accepted.dispatchId(), false, null);
    }

    @Override public TeamOperationResult cancel(CancelTaskCommand command) {
        requireRole(command.workspaceId(), command.actorId(), command.token(), "cancel", AgentRole.ORCHESTRATOR);
        requireText(command.dispatchId(), "dispatch_id");
        String reason = TeamInputLimits.cancelReason(command.reason());
        String workerId=ledger.findOpenRecipient(command.workspaceId(),command.dispatchId())
                .orElseThrow(()->new TeamConflict("No open dispatch: "+command.dispatchId()));
        return operations.withAgent(command.workspaceId(),workerId,()->cancelForWorker(command, reason));
    }

    private TeamOperationResult cancelForWorker(CancelTaskCommand command, String reason) {
        StoredDispatch stored = ledger.cancelOne(command.workspaceId(), command.dispatchId(), reason, Instant.now(clock))
                .orElseThrow(() -> new TeamConflict("No open dispatch: " + command.dispatchId()));
        pendingTasks.invalidate(command.workspaceId());
        Optional<TeamMember> worker = members.findById(command.workspaceId(), stored.dispatch().toAgentId().toString());
        if (worker.isEmpty()) return new TeamOperationResult(stored.dispatch().id().toString(), false,
                "Worker no longer exists; dispatch was cancelled but no terminal notification could be sent");
        DeliveryResult delivery = notifier.cancel(stored.dispatch(), worker.orElseThrow());
        return new TeamOperationResult(stored.dispatch().id().toString(), delivery.forwarded(), delivery.error());
    }

    @Override public TeamOperationResult report(ReportTaskCommand command) {
        TeamMember worker = requireRole(command.workspaceId(), command.actorId(), command.token(), "report",
                AgentRole.CODER, AgentRole.REVIEWER, AgentRole.TESTER, AgentRole.CUSTOM);
        String result = TeamInputLimits.reportText(command.result());
        String status = TeamInputLimits.status(command.status());
        List<String> artifacts = TeamInputLimits.artifacts(command.artifacts());
        return operations.withAgent(command.workspaceId(),worker.id().toString(),
                ()->reportForWorker(command,worker.id().toString(),result,status,artifacts));
    }

    private TeamOperationResult reportForWorker(ReportTaskCommand command,String workerId,String result,
                                                String status,List<String> artifacts) {
        TeamMember worker=members.findById(command.workspaceId(),workerId)
                .orElseThrow(()->new TeamUnauthorized("Agent not found in workspace"));
        Instant now = Instant.now(clock);
        TeamMessage message = new TeamMessage(command.workspaceId(), command.actorId(), "report", command.actorId(), null,
                result, status, artifacts, now);
        StoredDispatch reported = ledger.reportOne(command.workspaceId(), command.actorId(), command.dispatchId(),
                        result, artifacts, now, message)
                .orElseThrow(() -> new TeamConflict("No open dispatch for worker: " + worker.name()));
        pendingTasks.invalidate(command.workspaceId());
        DeliveryResult delivery = notifier.report(reported.dispatch(), worker);
        return new TeamOperationResult(reported.dispatch().id().toString(), delivery.forwarded(), delivery.error());
    }

    @Override public TeamOperationResult status(StatusTaskCommand command) {
        TeamMember worker = requireRole(command.workspaceId(), command.actorId(), command.token(), "status",
                AgentRole.CODER, AgentRole.REVIEWER, AgentRole.TESTER, AgentRole.CUSTOM);
        String result = TeamInputLimits.reportText(command.result());
        List<String> artifacts = TeamInputLimits.artifacts(command.artifacts());
        return operations.withAgent(command.workspaceId(),worker.id().toString(),
                ()->statusForWorker(command,worker.id().toString(),result,artifacts));
    }

    private TeamOperationResult statusForWorker(StatusTaskCommand command,String workerId,String result,
                                                List<String> artifacts) {
        TeamMember worker=members.findById(command.workspaceId(),workerId)
                .orElseThrow(()->new TeamUnauthorized("Agent not found in workspace"));
        ledger.append(new TeamMessage(command.workspaceId(), command.actorId(), "status", command.actorId(), null,
                result, null, artifacts, Instant.now(clock)));
        DeliveryResult delivery = notifier.status(command.workspaceId(), worker, result, artifacts);
        return new TeamOperationResult(null, delivery.forwarded(), delivery.error());
    }

    @Override public List<TeamMemberView> listForAgent(String workspaceId, String actorId, String token) {
        return operations.withWorkspace(workspaceId, () -> {
            requireRole(workspaceId, actorId, token, "list", AgentRole.ORCHESTRATOR);
            return listViews(workspaceId);
        });
    }

    @Override public TeamMemberView addWorker(AddWorkerCommand command) {
        return operations.withWorkspace(command.workspaceId(), () -> addWorkerCoordinated(command));
    }

    private TeamMemberView addWorkerCoordinated(AddWorkerCommand command) {
        if (!members.workspaceExists(command.workspaceId())) throw new TeamConflict("Workspace not found: " + command.workspaceId());
        String name = TeamInputLimits.memberName(command.name());
        String description = TeamInputLimits.memberDescription(command.description());
        AgentRole role;
        try { role = AgentRole.parse(command.role() == null ? "coder" : command.role()); }
        catch (IllegalArgumentException error) { throw new TeamBadRequest(error.getMessage()); }
        if (!role.isWorker()) throw new TeamBadRequest("Unsupported worker role: " + command.role());
        if (members.findByName(command.workspaceId(), name).isPresent()) throw new TeamConflict("Worker already exists: " + name);
        TeamMember member = TeamMember.create(WorkspaceId.parse(command.workspaceId()), name, description, role, Instant.now(clock));
        members.save(member);
        return listViews(command.workspaceId()).stream().filter(item -> item.id().equals(member.id().toString())).findFirst().orElseThrow();
    }

    @Override public List<TeamMemberView> listForUi(String workspaceId) {
        return operations.withWorkspace(workspaceId, () -> listViews(workspaceId));
    }

    @Override public TeamMemberView renameWorker(String workspaceId,String workerId,String name){
        return operations.withAgent(workspaceId,workerId,()->{
            String normalized=TeamInputLimits.memberName(name);
            if(members.findByName(workspaceId,normalized).filter(member->!member.id().toString().equals(workerId)).isPresent())throw new TeamConflict("Worker already exists: "+normalized);
            if(!members.rename(workspaceId,workerId,normalized))throw new TeamConflict("Worker not found: "+workerId);
            return listViews(workspaceId).stream().filter(item->item.id().equals(workerId)).findFirst().orElseThrow();
        });
    }
    @Override public void deleteWorker(String workspaceId,String workerId){operations.withAgent(workspaceId,workerId,()->{if(!members.delete(workspaceId,workerId))throw new TeamConflict("Worker not found: "+workerId);pendingTasks.invalidate(workspaceId);});}

    @Override public List<DispatchSummaryView> listDispatches(String workspaceId, String state, int limit, int offset) {
        return ledger.listSummaries(workspaceId, state, limit, offset).stream().map(summary ->
                summaryView(summary)).toList();
    }

    @Override public List<DispatchSummaryView> listDeliveryIssues(String workspaceId, int limit) {
        return ledger.listDeliveryIssues(workspaceId, limit).stream().map(this::summaryView).toList();
    }

    private DispatchSummaryView summaryView(dev.termestra.team.application.port.out.DispatchSummaryProjection summary) {
        return
                new DispatchSummaryView(summary.id(), summary.workspaceId(), summary.fromAgentId(), summary.toAgentId(),
                        summary.text(), summary.state(), summary.createdAt(), summary.deliveredAt(), summary.submittedAt(),
                        summary.reportedAt(), summary.reportText(), summary.artifacts(), summary.truncated(),
                        summary.deliveryState(), summary.deliveryAttemptCount(), summary.deliveryError(),
                        summary.deliveryNextAttemptAt(), summary.deliveryInputAttempted());
    }

    @Override public Optional<DispatchView> findDispatch(String workspaceId, String dispatchId) {
        return ledger.findDetailById(workspaceId, dispatchId).map(detail ->
                new DispatchView(detail.id(), detail.workspaceId(), detail.fromAgentId(), detail.toAgentId(), detail.text(),
                        detail.state(), detail.createdAt(), detail.deliveredAt(), detail.submittedAt(), detail.reportedAt(),
                        detail.reportText(), detail.artifacts(), detail.truncated(), detail.deliveryState(),
                        detail.deliveryAttemptCount(), detail.deliveryError(), detail.deliveryNextAttemptAt(),
                        detail.deliveryInputAttempted()));
    }

    private TeamMember requireWorker(String workspaceId, String id) { return members.findById(workspaceId,id).orElseThrow(() -> new TeamUnauthorized("Agent not found in workspace")); }

    private List<TeamMemberView> listViews(String workspaceId) {
        List<TeamMemberSummary> persisted=members.list(workspaceId);
        if(persisted.isEmpty())return List.of();
        Map<String,Integer> pendingByWorker=pendingTasks.snapshot(workspaceId);
        Set<String> active = runtime.activeAgentIds(workspaceId);
        return persisted.stream().map(member -> {
            int pending = pendingByWorker.getOrDefault(member.id(),0);
            return new TeamMemberView(member.id(), member.name(), member.role(),
                    AgentStatus.derive(active.contains(member.id()), pending).wireValue(), pending,
                    null, member.commandPresetId());
        }).toList();
    }

    private TeamMember requireRole(String workspaceId, String actorId, String token, String command, AgentRole... allowed) {
        if (actorId == null || actorId.isBlank()) throw new TeamUnauthorized("Missing agent identity");
        if (!authenticator.validate(actorId, token)) throw new TeamUnauthorized("Invalid or missing agent token");
        AgentRole role;
        TeamMember member;
        if (actorId.equals(workspaceId + ":orchestrator") && members.workspaceExists(workspaceId)) {
            role = AgentRole.ORCHESTRATOR;
            member = null;
        } else {
            member = members.findById(workspaceId, actorId).orElseThrow(() -> new TeamUnauthorized("Agent not found in workspace"));
            role = member.role();
        }
        if (Arrays.stream(allowed).noneMatch(role::equals)) throw new TeamForbidden("Role '" + role.wireValue() + "' is not allowed to run team " + command);
        return member;
    }

    private static void requireText(String value, String field) { if (value == null || value.trim().isEmpty()) throw new TeamBadRequest("Missing " + field); }
}
