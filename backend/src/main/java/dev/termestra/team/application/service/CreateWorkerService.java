package dev.termestra.team.application.service;

import dev.termestra.team.application.port.in.*;
import dev.termestra.team.application.port.out.WorkerExecution;

public final class CreateWorkerService implements CreateWorkerUseCase {
    private final TeamAdminUseCase team;
    private final WorkerExecution execution;

    public CreateWorkerService(TeamAdminUseCase team,WorkerExecution execution){
        this.team=team;this.execution=execution;
    }

    @Override public CreatedWorkerView create(CreateWorkerCommand command){
        TeamMemberView created=team.addWorker(new AddWorkerCommand(command.workspaceId(),command.name(),
                command.description(),command.role()));
        try {
            if(command.launch()!=null)execution.configure(command.workspaceId(),created.id(),command.launch());
        } catch(RuntimeException configurationFailure){
            try{team.deleteWorker(command.workspaceId(),created.id());}
            catch(RuntimeException rollbackFailure){configurationFailure.addSuppressed(rollbackFailure);}
            throw configurationFailure;
        }
        TeamMemberView configured=team.listForUi(command.workspaceId()).stream()
                .filter(value->value.id().equals(created.id())).findFirst().orElseThrow();
        if(!command.autostart())return new CreatedWorkerView(configured,WorkerStartView.disabled());
        try{
            WorkerExecution.StartedWorker run=execution.start(command.workspaceId(),created.id(),command.runtimePort());
            return new CreatedWorkerView(configured,new WorkerStartView(true,null,run.runId()));
        }catch(RuntimeException startFailure){
            return new CreatedWorkerView(configured,new WorkerStartView(false,startFailure.getMessage(),null));
        }
    }
}
