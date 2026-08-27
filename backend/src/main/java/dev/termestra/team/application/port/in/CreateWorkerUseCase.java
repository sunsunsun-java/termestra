package dev.termestra.team.application.port.in;

public interface CreateWorkerUseCase {
    CreatedWorkerView create(CreateWorkerCommand command);

    record CreatedWorkerView(TeamMemberView worker,WorkerStartView start){ }
    record WorkerStartView(boolean ok,String error,String runId){
        public static WorkerStartView disabled(){return new WorkerStartView(false,null,null);}
    }
}
