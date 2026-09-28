package com.vvcs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.vvcs.model.Project;
import com.vvcs.parser.KdenliveParser;
import com.vvcs.parser.KdenliveParserException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Command line entry point.
 *
 * <pre>
 * java -jar vvcs-parser.jar init
 * java -jar vvcs-parser.jar commit -m "Add intro"
 * java -jar vvcs-parser.jar log
 * java -jar vvcs-parser.jar checkout &lt;commit-id&gt;
 * java -jar vvcs-parser.jar diff &lt;commit-a&gt; &lt;commit-b&gt;
 * java -jar vvcs-parser.jar web [--repo &lt;dir&gt;] [--port &lt;n&gt;]
 * java -jar vvcs-parser.jar project.kdenlive [--out project.json] [--warnings]
 * </pre>
 *
 * <p>The first six forms are the vvcs commands, handled by
 * {@link com.vvcs.cli.VvcsCli}. Anything else is still the original job of this class:
 * converting a Kdenlive project into the VVCS model as JSON.
 */
public final class Main {

    private static final Logger LOG = Logger.getLogger(Main.class.getName());

    private static final List<String> VVCS_COMMANDS =
            List.of("init", "commit", "log", "checkout", "diff", "web", "help");

    private Main() {
    }

    public static void main(String[] args) {
        if (isVvcsCommand(args)) {
            com.vvcs.cli.VvcsCli.main(args);
            return;
        }
        convert(args);
    }

    /**
     * Whether these arguments name a vvcs command rather than a project file to convert.
     *
     * <p>Getting this wrong is not a cosmetic mistake: a command left out of the list falls
     * through to the converter, which then reports the commit id as an unusable filename.
     */
    static boolean isVvcsCommand(String[] args) {
        return args.length > 0 && VVCS_COMMANDS.contains(args[0]);
    }

    private static void convert(String[] args) {
        configureLogging();
        if (args.length == 0) {
            usage();
            return;
        }
        Path input = null;
        Path output = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--out", "-o" -> {
                    if (++i >= args.length) {
                        fail("--out needs a file name");
                        return;
                    }
                    output = Paths.get(args[i]);
                }
                case "--warnings", "-w" -> setLogLevel(Level.ALL);
                case "--quiet", "-q" -> setLogLevel(Level.OFF);
                case "-h", "--help" -> {
                    usage();
                    return;
                }
                default -> {
                    if (args[i].startsWith("-")) {
                        fail("unknown option: " + args[i]);
                        return;
                    }
                    if (input != null) {
                        fail("only one project file can be converted at a time: " + args[i]);
                        return;
                    }
                    input = Paths.get(args[i]);
                }
            }
        }
        if (input == null) {
            usage();
            return;
        }
        try {
            Project project = new KdenliveParser().parse(input);
            String json = toJson(project);
            if (output == null) {
                System.out.println(json);
            } else {
                Files.writeString(output, json + System.lineSeparator(), StandardCharsets.UTF_8);
                System.err.println("wrote " + project.assetCount() + " assets and "
                        + project.timeline().clipCount() + " clips to " + output);
            }
        } catch (KdenliveParserException e) {
            fail(e.reason() + ": " + e.getMessage());
        } catch (IOException e) {
            fail("cannot write output: " + e.getMessage());
        }
    }

    static String toJson(Project project) throws IOException {
        return new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .writeValueAsString(project);
    }

    private static void setLogLevel(Level level) {
        Logger root = Logger.getLogger("");
        root.setLevel(level);
        for (var handler : root.getHandlers()) {
            handler.setLevel(level);
        }
    }

    private static void configureLogging() {
        Logger root = Logger.getLogger("");
        root.setLevel(Level.WARNING);
        for (var handler : root.getHandlers()) {
            handler.setLevel(Level.WARNING);
            handler.setFormatter(new java.util.logging.SimpleFormatter() {
                @Override
                public String format(java.util.logging.LogRecord record) {
                    return record.getLevel() + " " + record.getLoggerName() + ": "
                            + record.getMessage() + System.lineSeparator();
                }
            });
        }
    }

    private static void usage() {
        System.err.println("""
                usage: java -jar vvcs-parser.jar <project.kdenlive> [--out <project.json>] [--warnings]

                  <project.kdenlive>  Kdenlive/MLT project to convert
                  --out, -o <file>    write JSON to a file instead of stdout
                  --warnings, -w      show parser warnings and fine grained progress
                """);
    }

    private static void fail(String message) {
        System.err.println("vvcs: " + message);
        System.exit(1);
    }
}
