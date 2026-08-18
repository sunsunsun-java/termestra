package dev.termestra.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import dev.termestra.platform.cli.team.TeamCli;
import java.util.Arrays;

@SpringBootApplication(scanBasePackages = "dev.termestra")
public class TermestraApplication {
    public static void main(String[] args) {
        if(args.length>0&&"team".equals(args[0])){TeamCli.main(Arrays.copyOfRange(args,1,args.length));return;}
        SpringApplication.run(TermestraApplication.class, args);
    }
}
