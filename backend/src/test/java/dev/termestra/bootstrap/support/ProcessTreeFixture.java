package dev.termestra.bootstrap.support;

import java.util.ArrayList;
import java.util.List;

/** Starts three direct Java children and stays alive long enough for process-tree tests to inspect it. */
public final class ProcessTreeFixture {
    private ProcessTreeFixture() { }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "child".equals(args[0])) {
            Thread.sleep(30_000);
            return;
        }
        String executable = TestJavaCommand.fixture(ProcessTreeFixture.class).command();
        String classPath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        List<Process> children = new ArrayList<>();
        try {
            for (int index = 0; index < 3; index++) {
                children.add(new ProcessBuilder(executable, "-cp", classPath,
                        ProcessTreeFixture.class.getName(), "child").start());
            }
            Thread.sleep(30_000);
        } finally {
            children.forEach(Process::destroyForcibly);
        }
    }
}
