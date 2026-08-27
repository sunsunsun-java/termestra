package dev.termestra.team.application.service;

import dev.termestra.team.application.exception.*;
import dev.termestra.team.application.port.in.*;
import dev.termestra.team.application.port.out.*;
import dev.termestra.team.domain.model.*;
import dev.termestra.shared.id.WorkspaceId;
import dev.termestra.shared.concurrency.RuntimeOperationCoordinator;

import java.time.*;
import java.util.*;

public final class TeamScenarioApplicationService implements ApplyTeamScenarioUseCase {
    private final TeamMemberRepository members;
    private final MemberProvisioningRepository provisioning;
    private final TeamScenarioRuntime runtime;
    private final Clock clock;
    private final RuntimeOperationCoordinator operations;

    public TeamScenarioApplicationService(TeamMemberRepository members,
                                          MemberProvisioningRepository provisioning,
                                          TeamScenarioRuntime runtime, Clock clock) {
        this(members,provisioning,runtime,clock,new RuntimeOperationCoordinator());
    }

    public TeamScenarioApplicationService(TeamMemberRepository members,
                                          MemberProvisioningRepository provisioning,
                                          TeamScenarioRuntime runtime, Clock clock,
                                          RuntimeOperationCoordinator operations) {
        this.members = members;
        this.provisioning = provisioning;
        this.runtime = runtime;
        this.clock = clock;
        this.operations = operations;
    }

    @Override public AppliedTeamScenario apply(ApplyTeamScenarioCommand command) {
        String workspaceId = require(command.workspaceId(), "workspace id");
        String scenarioId = require(command.scenarioId(), "scenario id");
        if (!members.workspaceExists(workspaceId)) throw new TeamScenarioWorkspaceNotFound();
        TeamScenario scenario = TeamScenarioCatalog.find(scenarioId)
                .orElseThrow(() -> new TeamScenarioNotFound(scenarioId));
        String goal = require(command.goal(), "goal");
        String locale = runtime.resolveAndStoreLocale(workspaceId, command.locale());
        if (!runtime.hasActiveOrchestrator(workspaceId)) {
            throw new TeamConflict("Start the Orchestrator first — the scenario goal is handed to its terminal");
        }

        WorkerLaunchPlan launchPlan = runtime.resolveDefaultWorkerLaunch(workspaceId);
        Set<String> usedNames = new HashSet<>();
        members.list(workspaceId).forEach(worker -> usedNames.add(worker.name()));
        List<TeamMemberSummary> created = new ArrayList<>();

        // Each worker and launch configuration is one transaction, while the
        // whole multi-member scenario is not. A later failure therefore leaves
        // earlier members visible for an explicit retry or removal.
        for (TeamScenario.MemberSpec spec : scenario.members()) {
            String name = ScenarioMemberNameGenerator.next(spec.nameStem(), usedNames);
            usedNames.add(name);
            if (members.findByName(workspaceId, name).isPresent()) {
                throw new TeamConflict("Worker already exists: " + name);
            }
            TeamMember member = TeamMember.create(WorkspaceId.parse(workspaceId), name,
                    spec.description(locale), spec.role(), Instant.now(clock));
            TeamMemberSummary worker = operations.withAgent(workspaceId, member.id().toString(), () -> {
                provisioning.saveWithLaunch(member, new WorkerLaunchProvisioning.Resolved(launchPlan));
                return members.list(workspaceId).stream()
                        .filter(value -> value.id().equals(member.id().toString()))
                        .findFirst().orElseThrow();
            });
            created.add(worker);
        }

        List<AppliedTeamScenario.StartedMember> started = startAll(
                workspaceId, Objects.requireNonNullElse(command.runtimePort(), ""), created);
        TeamScenarioRuntime.DeliveryResult delivery = runtime.deliverUserInput(
                workspaceId, kickoff(scenario.id(), goal, locale, started));
        if (!delivery.delivered()) {
            throw new IllegalStateException(Objects.requireNonNullElse(
                    delivery.error(), "Failed to hand the scenario goal to Orchestrator"));
        }
        return new AppliedTeamScenario(started, true);
    }

    private List<AppliedTeamScenario.StartedMember> startAll(String workspaceId, String runtimePort,
                                                              List<TeamMemberSummary> created) {
        List<AppliedTeamScenario.StartedMember> started = new ArrayList<>();
        RuntimeException firstFailure = null;
        for (TeamMemberSummary worker : created) {
            try {
                MemberStart value = operations.withAgent(workspaceId, worker.id(), () -> new MemberStart(worker,
                        runtime.startWorker(workspaceId, worker.id(), runtimePort)));
                if ("error".equals(value.run().status())) {
                    if (firstFailure == null) {
                        firstFailure = new TeamConflict("Failed to start scenario member: " + value.worker().name());
                    }
                    continue;
                }
                started.add(new AppliedTeamScenario.StartedMember(value.worker().id(),
                        value.worker().name(), value.worker().role(), value.run().runId()));
            } catch (RuntimeException failure) {
                if (firstFailure == null) firstFailure = failure;
            }
        }
        if (firstFailure != null) throw firstFailure;
        return List.copyOf(started);
    }

    private static String kickoff(String scenarioId, String goal, String locale,
                                  List<AppliedTeamScenario.StartedMember> workers) {
        String roster = workers.stream().map(worker -> "- " + worker.name() + " (" + worker.role() + ")")
                .reduce((left, right) -> left + "\n" + right).orElse("");
        if ("zh".equals(locale)) {
            return String.join("\n",
                    "用户选择了 \"" + scenarioId + "\" 场景，Termestra 已经为你创建这些团队成员：",
                    roster, "", "用户目标：", goal, "",
                    "请先运行 `team list` 确认团队成员，然后把目标拆成任务，并用 `team send \"<member-name>\" \"<task>\"` 派发。任务拆分由你决定；只有缺少真实决策时才回问用户。");
        }
        return String.join("\n",
                "The user picked the \"" + scenarioId + "\" scenario and Termestra has already created these team members for you:",
                roster, "", "Goal from the user:", goal, "",
                "Break the goal into tasks and dispatch them with `team send \"<member-name>\" \"<task>\"` — run `team list` first to confirm current members. Plan the split yourself; only come back to the user for genuinely missing decisions.");
    }

    private static String require(String value, String field) {
        if (value == null || value.trim().isEmpty()) throw new TeamBadRequest("Missing " + field);
        return value.trim();
    }

    private record MemberStart(TeamMemberSummary worker, TeamScenarioRuntime.StartedRun run) { }
}
