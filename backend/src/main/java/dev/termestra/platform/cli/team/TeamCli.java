package dev.termestra.platform.cli.team;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import picocli.CommandLine;
import picocli.CommandLine.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;

@Command(name="team",mixinStandardHelpOptions=true,
        description="Coordinate Termestra agents through the local runtime.")
public final class TeamCli implements Callable<Integer> {
    static final int MAX_STDIN_BYTES = 1024 * 1024;
    static final int MAX_PROTOCOL_BYTES = 256 * 1024;
    private static final Path PROTOCOL_PATH = Path.of(".termestra", "PROTOCOL.md");
    private static final String USAGE="""
            Usage:
              team list
              team guide <core|dispatch|tasks|member>
              team send <worker-name> "<task>"
              team cancel --dispatch <dispatch-id> "<reason>"
              team report "<result>" [--dispatch <dispatch-id>] [--artifact <path>]
              team report --stdin [--dispatch <dispatch-id>] [--artifact <path>]
              team status "<current status>" [--artifact <path>]
              team status --stdin [--artifact <path>]
            """;
    @Parameters(arity="0..*") private List<String> arguments=new ArrayList<>();
    private final Map<String,String> environment; private final InputStream input; private final PrintWriter out; private final PrintWriter err; private final ObjectMapper json;private final Path workingDirectory;

    public TeamCli(){this(System.getenv(),System.in,new PrintWriter(System.out,true),new PrintWriter(System.err,true),new ObjectMapper());}
    TeamCli(Map<String,String> environment,InputStream input,PrintWriter out,PrintWriter err,ObjectMapper json){this(environment,input,out,err,json,Path.of(""));}
    TeamCli(Map<String,String> environment,InputStream input,PrintWriter out,PrintWriter err,ObjectMapper json,Path workingDirectory){this.environment=environment;this.input=input;this.out=out;this.err=err;this.json=json;this.workingDirectory=workingDirectory;}

    @Override public Integer call(){if(arguments.isEmpty()){out.print(USAGE);out.flush();return 0;}run(arguments);return 0;}
    void run(List<String> args){
        String command=args.getFirst();List<String> rest=args.subList(1,args.size());
        if(Set.of("help","--help","-h").contains(command)){out.print(USAGE);out.flush();return;}
        TeamRuntimeClient client=new TeamRuntimeClient(TeamRuntimeClient.defaultHttpClient(),json,TeamEnvironment.from(environment));
        switch(command){
            case "list" -> out.println(client.list());
            case "guide" -> guide(rest);
            case "send" -> send(client,rest);
            case "cancel" -> cancel(client,rest);
            case "report" -> report(client,rest,false);
            case "status" -> report(client,rest,true);
            default -> throw new ParameterException(new CommandLine(this),"Unsupported team command");
        }
    }

    private void guide(List<String> args) {
        if (args.size() != 1 || !Set.of("core", "dispatch", "tasks", "member").contains(args.getFirst())) {
            throw usage("Usage: team guide <core|dispatch|tasks|member>");
        }
        Path metadata = workingDirectory.resolve(PROTOCOL_PATH.getParent());
        Path protocol = workingDirectory.resolve(PROTOCOL_PATH);
        if (Files.isSymbolicLink(metadata) || Files.isSymbolicLink(protocol)
                || !Files.isRegularFile(protocol, LinkOption.NOFOLLOW_LINKS)) {
            throw usage("Termestra protocol not found: " + PROTOCOL_PATH);
        }
        try (InputStream protocolInput = Files.newInputStream(
                protocol, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            String document = readBounded(protocolInput, MAX_PROTOCOL_BYTES, "protocol file");
            String guide = extractGuide(document, args.getFirst());
            if (guide == null) throw usage("Guide is unavailable: " + args.getFirst());
            out.println(guide);
        } catch (IOException error) {
            throw new UncheckedIOException("Unable to read Termestra protocol", error);
        }
    }

    static String extractGuide(String document, String topic) {
        String marker = "## Guide: " + topic;
        int start = document.indexOf(marker);
        if (start < 0) return null;
        int next = document.indexOf("\n## ", start + marker.length());
        return document.substring(start, next < 0 ? document.length() : next).stripTrailing();
    }

    private void send(TeamRuntimeClient client,List<String> args){if(args.size()<2||isUuid(args.getFirst()))throw usage("Usage: team send <worker-name> <task>");String task=String.join(" ",args.subList(1,args.size())).trim();if(task.isEmpty())throw usage("Usage: team send <worker-name> <task>");ObjectNode body=client.body();body.put("runtime_port",TeamEnvironment.from(environment).port());body.put("to",args.getFirst());body.put("text",task);body.put("idempotency_key",UUID.randomUUID().toString());out.println(client.post("/api/team/send",body));}
    private void cancel(TeamRuntimeClient client,List<String> args){Parsed parsed=parse(args,true);if(parsed.dispatchId==null)throw usage("Missing --dispatch <dispatch-id>\n\nUsage: team cancel --dispatch <dispatch-id> <reason>");if(parsed.positionals.isEmpty())throw usage("Missing <reason>\n\nUsage: team cancel --dispatch <dispatch-id> <reason>");ObjectNode body=client.body();body.put("dispatch_id",parsed.dispatchId);body.put("reason",String.join(" ",parsed.positionals).trim());client.post("/api/team/cancel",body);}
    private void report(TeamRuntimeClient client,List<String> args,boolean status){Parsed parsed=parse(args,!status);if(status&&parsed.dispatchId!=null)throw usage("team status does not accept --dispatch; use team report for assigned work");if(parsed.stdin&&!parsed.positionals.isEmpty())throw usage("--stdin is mutually exclusive with a positional argument");if(!parsed.stdin&&parsed.positionals.size()!=1)throw usage("Missing "+(status?"<current status>":"<result>")+" (or pass --stdin to read it from stdin)");String text=parsed.stdin?readInput():parsed.positionals.getFirst();ObjectNode body=client.body();if(parsed.dispatchId!=null)body.put("dispatch_id",parsed.dispatchId);body.put("result",text);body.set("artifacts",client.artifacts(parsed.artifacts));String response=client.post(status?"/api/team/status":"/api/team/report",body);try{JsonNode payload=json.readTree(response);if(payload.path("forwarded").isBoolean()&&!payload.path("forwarded").asBoolean()&&payload.path("forward_error").isTextual())err.println("Termestra recorded the "+(status?"status update":"report")+", but could not deliver it to Orchestrator in real time: "+payload.path("forward_error").asText());}catch(JsonProcessingException error){throw new IllegalStateException("Invalid runtime response",error);}}
    private Parsed parse(List<String> args,boolean allowDispatch){Parsed p=new Parsed();for(int i=0;i<args.size();i++){String arg=args.get(i);switch(arg){case "--stdin"->p.stdin=true;case "--success","--failed"->{}case "--artifact"->{if(i+1>=args.size()||args.get(i+1).startsWith("--"))throw usage("--artifact requires a value");p.artifacts.add(args.get(++i));}case "--dispatch"->{if(!allowDispatch)throw usage("team status does not accept --dispatch; use team report for assigned work");if(i+1>=args.size()||args.get(i+1).startsWith("--"))throw usage("--dispatch requires a value");p.dispatchId=args.get(++i);}default->{if(arg.startsWith("--"))throw usage("Unknown argument: "+arg);p.positionals.add(arg);}}}return p;}
    private String readInput(){try{String value=readBounded(input,MAX_STDIN_BYTES,"--stdin input");if(value.trim().isEmpty())throw usage("--stdin received empty input");return value;}catch(IOException error){throw new UncheckedIOException(error);}}
    private String readBounded(InputStream source,int limit,String label)throws IOException{byte[] content=source.readNBytes(limit+1);if(content.length>limit)throw usage(label+" exceeds "+limit+" bytes");return new String(content,StandardCharsets.UTF_8);}
    private ParameterException usage(String message){return new ParameterException(new CommandLine(this),message);}
    private static boolean isUuid(String value){return value.matches("(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");}
    private static final class Parsed{final List<String> positionals=new ArrayList<>();final List<String> artifacts=new ArrayList<>();String dispatchId;boolean stdin;}

    public static void main(String[] args){int code=new CommandLine(new TeamCli()).setUnmatchedOptionsArePositionalParams(true).setExecutionExceptionHandler((error,commandLine,parseResult)->{commandLine.getErr().println(error.getMessage());return 1;}).execute(args);System.exit(code);}
}
