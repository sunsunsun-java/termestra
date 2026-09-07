package dev.termestra.team.application.service;

import dev.termestra.team.application.port.in.*;
import dev.termestra.team.application.port.out.*;
import dev.termestra.team.domain.model.TeamMember;
import dev.termestra.shared.concurrency.RuntimeOperationCoordinator;

import java.time.Clock;

public final class CreateWorkerService implements CreateWorkerUseCase {
    private final TeamAdminUseCase team;
    private final TeamMemberRepository members;
    private final MemberProvisioningRepository provisioning;
    private final WorkerExecution execution;
    private final Clock clock;
    private final RuntimeOperationCoordinator operations;

    public CreateWorkerService(TeamAdminUseCase team,TeamMemberRepository members,
                               MemberProvisioningRepository provisioning,WorkerExecution execution,
                               Clock clock,RuntimeOperationCoordinator operations){
        this.team=team;this.members=members;this.provisioning=provisioning;
        this.execution=execution;this.clock=clock;this.operations=operations;
    }

    @Override public CreatedWorkerView create(CreateWorkerCommand command){
        TeamMember member=operations.withWorkspace(command.workspaceId(),()->{
            TeamMember candidate=TeamMemberCreator.create(members,new AddWorkerCommand(command.workspaceId(),
                    command.name(),command.description(),command.role()),clock);
            if(command.launch()==null)members.save(candidate);
            else provisioning.saveWithLaunch(candidate,execution.plan(command.workspaceId(),command.launch()));
            return candidate;
        });
        WorkerStartView start=WorkerStartView.disabled();
        if(command.autostart()){
            try{
                WorkerExecution.StartedWorker run=execution.start(command.workspaceId(),member.id().toString(),command.runtimePort());
                start=new WorkerStartView(true,null,run.runId());
            }catch(RuntimeException startFailure){
                start=new WorkerStartView(false,startFailure.getMessage(),null);
            }
        }
        TeamMemberView current=team.listForUi(command.workspaceId()).stream()
                .filter(value->value.id().equals(member.id().toString())).findFirst().orElseThrow();
        return new CreatedWorkerView(current,start);
    }
}
