package dev.termestra.platform.web.error;

import dev.termestra.workspace.application.exception.InvalidWorkspacePath;
import dev.termestra.workspace.application.exception.InvalidWorkspaceRecord;
import dev.termestra.workspace.application.exception.WorkspaceNotFound;
import dev.termestra.workspace.application.exception.WorkspaceLimitReached;
import dev.termestra.team.application.exception.*;
import dev.termestra.execution.application.exception.*;
import dev.termestra.tasks.application.port.in.TasksWorkspaceNotFound;
import dev.termestra.tasks.application.port.in.TasksDocumentTooLarge;
import dev.termestra.tasks.application.port.in.TasksRevisionConflict;
import dev.termestra.tasks.application.port.in.TasksSubscriptionLimit;
import dev.termestra.configuration.application.port.in.*;
import dev.termestra.marketplace.application.MarketplaceNotFound;
import dev.termestra.shared.concurrency.RuntimeOperationBusyException;
import dev.termestra.workspace.application.exception.WorkspaceRegistrationConflict;
import dev.termestra.workspace.application.exception.WorkspaceRegistrationFailure;
import dev.termestra.workspace.application.exception.WorkspaceRegistrationNotFound;
import dev.termestra.workspace.application.exception.InvalidWorkspaceRegistrationRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.web.server.ServerWebInputException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.LinkedHashMap;

@RestControllerAdvice
public final class ApiExceptionHandler {
    @ExceptionHandler(RuntimeOperationBusyException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, Object> runtimeOperationBusy(RuntimeOperationBusyException error) {
        return Map.of(
                "error", error.getMessage(),
                "error_code", "RUNTIME_OPERATION_BUSY",
                "resource_type", error.resourceType(),
                "retryable", true,
                "retry_after_ms", 1_000);
    }

    @ExceptionHandler(WorkspaceRegistrationConflict.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String,Object> workspaceRegistrationConflict(WorkspaceRegistrationConflict error) {
        Map<String,Object> body = new java.util.LinkedHashMap<>();
        body.put("error", error.getMessage());
        body.put("error_code", error.errorCode());
        body.put("retryable", "WORKSPACE_REGISTRATION_IN_PROGRESS".equals(error.errorCode()));
        body.put("workspace_id", error.workspaceId());
        return body;
    }

    @ExceptionHandler(WorkspaceRegistrationNotFound.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String,Object> workspaceRegistrationNotFound(WorkspaceRegistrationNotFound error) {
        return Map.of("error", error.getMessage(), "error_code", "WORKSPACE_REGISTRATION_NOT_FOUND");
    }

    @ExceptionHandler(WorkspaceRegistrationFailure.class)
    public ResponseEntity<Map<String,Object>> workspaceRegistrationFailure(
            WorkspaceRegistrationFailure error) {
        HttpStatus status = switch (error.errorCode()) {
            case "WORKSPACE_METADATA_INITIALIZATION_FAILED" -> HttpStatus.INTERNAL_SERVER_ERROR;
            default -> HttpStatus.CONFLICT;
        };
        Map<String,Object> body = new java.util.LinkedHashMap<>();
        body.put("error", error.getMessage());
        body.put("error_code", error.errorCode());
        body.put("registration_id", error.registrationId());
        body.put("retryable", error.retryable());
        return ResponseEntity.status(status).body(body);
    }

    @ExceptionHandler(InvalidWorkspaceRegistrationRequest.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String,String> invalidWorkspaceRegistrationRequest(
            InvalidWorkspaceRegistrationRequest error) {
        return coded(error, error.errorCode());
    }

    @ExceptionHandler(InvalidWorkspacePath.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> invalidWorkspacePath(InvalidWorkspacePath error) {
        return Map.of("error", error.getMessage());
    }
    @ExceptionHandler(InvalidWorkspaceRecord.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public Map<String, String> invalidWorkspaceRecord(InvalidWorkspaceRecord error) {
        return Map.of("error", error.getMessage(), "error_code", "WORKSPACE_RECORD_INVALID");
    }
    @ExceptionHandler(WorkspaceNotFound.class) @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String,String> workspaceNotFound(WorkspaceNotFound error){return Map.of("error","Workspace not found");}
    @ExceptionHandler(WorkspaceLimitReached.class) @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String,String> workspaceLimitReached(WorkspaceLimitReached error){return Map.of("error",error.getMessage());}

    @ExceptionHandler(TeamBadRequest.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String,String> badRequest(TeamBadRequest error){return Map.of("error",error.getMessage());}
    @ExceptionHandler(TeamUnauthorized.class) @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public Map<String,String> unauthorized(TeamUnauthorized error){return Map.of("error",error.getMessage());}
    @ExceptionHandler(TeamForbidden.class) @ResponseStatus(HttpStatus.FORBIDDEN)
    public Map<String,String> forbidden(TeamForbidden error){return Map.of("error",error.getMessage());}
    @ExceptionHandler(TeamConflict.class) @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String,String> conflict(TeamConflict error){return Map.of("error",error.getMessage());}
    @ExceptionHandler(InvalidTeamMemberRecord.class) @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public Map<String,String> invalidTeamMemberRecord(InvalidTeamMemberRecord error){return Map.of(
            "error",error.getMessage(),"error_code","TEAM_MEMBER_RECORD_INVALID");}
    @ExceptionHandler(InvalidDispatchRecord.class) @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public Map<String,String> invalidDispatchRecord(InvalidDispatchRecord error){return Map.of(
            "error",error.getMessage(),"error_code","TEAM_DISPATCH_RECORD_INVALID");}
    @ExceptionHandler(TeamScenarioNotFound.class) @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String,String> scenarioNotFound(TeamScenarioNotFound error){return Map.of("error",error.getMessage());}
    @ExceptionHandler(TeamScenarioWorkspaceNotFound.class) @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String,String> scenarioWorkspaceNotFound(TeamScenarioWorkspaceNotFound error){return Map.of("error",error.getMessage());}
    @ExceptionHandler(ExecutionConflict.class) @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String,String> executionConflict(ExecutionConflict error){return coded(error,error.errorCode());}
    @ExceptionHandler(InvalidLaunchRequest.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String,String> invalidLaunchRequest(InvalidLaunchRequest error){return coded(error,error.errorCode());}
    @ExceptionHandler(RunNotFound.class) @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String,String> runNotFound(RunNotFound error){return Map.of("error",error.getMessage());}
    @ExceptionHandler(TasksWorkspaceNotFound.class) @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String,String> tasksWorkspaceNotFound(TasksWorkspaceNotFound error){return Map.of("error",error.getMessage());}
    @ExceptionHandler(TasksDocumentTooLarge.class) @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    public Map<String,String> tasksDocumentTooLarge(TasksDocumentTooLarge error){return Map.of("error",error.getMessage());}
    @ExceptionHandler(TasksRevisionConflict.class) @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String,String> tasksRevisionConflict(TasksRevisionConflict error){return Map.of(
            "error",error.getMessage(),"error_code","TASKS_REVISION_CONFLICT",
            "content",error.current().content(),"revision",error.current().revision());}
    @ExceptionHandler(TasksSubscriptionLimit.class) @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    public Map<String,String> tasksSubscriptionLimit(TasksSubscriptionLimit error){return Map.of("error",error.getMessage());}
    @ExceptionHandler(ConfigurationNotFound.class) @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String,String> configurationNotFound(ConfigurationNotFound error){return Map.of("error",error.getMessage());}
    @ExceptionHandler(ConfigurationConflict.class) @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String,String> configurationConflict(ConfigurationConflict error){return Map.of("error",error.getMessage());}
    @ExceptionHandler(MarketplaceNotFound.class) @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String,String> marketplaceNotFound(MarketplaceNotFound error){return Map.of("error",error.getMessage());}
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String,String> illegalArgument(IllegalArgumentException error){return Map.of("error",error.getMessage());}
    @ExceptionHandler(DataBufferLimitException.class) @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    public Map<String,String> requestTooLarge(DataBufferLimitException error){return Map.of("error","Request body too large");}
    @ExceptionHandler(ServerWebInputException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String,String> invalidRequestBody(ServerWebInputException error){return Map.of("error","Invalid request body");}

    private static Map<String,String> coded(RuntimeException error,String errorCode){
        String message=error.getMessage()==null?error.getClass().getSimpleName():error.getMessage();
        Map<String,String> body=new LinkedHashMap<>();body.put("error",message);
        if(errorCode!=null)body.put("error_code",errorCode);
        return body;
    }
}
