package com.vvcs.cli;

import com.vvcs.comparison.CommitRef;
import com.vvcs.comparison.TimelineComparison;
import com.vvcs.comparison.TimelineComparisonService;
import com.vvcs.model.Commit;
import com.vvcs.model.Project;
import com.vvcs.model.ProjectState;
import com.vvcs.parser.KdenliveParser;
import com.vvcs.parser.KdenliveParserException;
import com.vvcs.repository.FileSystemRepository;
import com.vvcs.repository.Repository;
import com.vvcs.repository.RepositoryException;
import com.vvcs.serialization.VvcsJson;
import com.vvcs.workflow.CommitWorkflow;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The {@code vvcs} commands: initialise a repository, commit the current project, walk the
 * history, and retrieve the state a commit stands for.
 *
 * <pre>
 * vvcs init
 * vvcs commit -m "&lt;message&gt;" [--project &lt;file&gt;] [--author &lt;name&gt;]
 * vvcs log
 * vvcs checkout &lt;commit-id&gt;
 * </pre>
 *
 * <p>{@link #run(String[], Path, PrintStream, PrintStream)} returns an exit code instead of
 * calling {@code System.exit}, so every command can be driven from a test without ending the
 * test run. {@link #main} is the thin wrapper that turns that code into a process exit.
 *
 * <p>Locating the project file happens here rather than in the parser or the repository, so
 * that neither of them learns anything about Kdenlive.
 */
public final class VvcsCli {

    private static final String KDENLIVE_SUFFIX = ".kdenlive";
    private static final DateTimeFormatter LOG_DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private final Path workingDirectory;
    private final Clock clock;
    private final PrintStream out;
    private final PrintStream err;

    public VvcsCli(Path workingDirectory, Clock clock, PrintStream out, PrintStream err) {
        this.workingDirectory = workingDirectory.toAbsolutePath().normalize();
        this.clock = clock;
        this.out = out;
        this.err = err;
    }

    public static void main(String[] args) {
        Path cwd = Paths.get("").toAbsolutePath();
        int code = new VvcsCli(cwd, Clock.systemDefaultZone(), System.out, System.err).run(args);
        if (code != 0) {
            System.exit(code);
        }
    }

    /**
     * Runs one command against the working directory this instance was created for.
     *
     * @return 0 on success, 1 on a handled failure, 2 on a usage error
     */
    public int run(String[] args) {
        if (args.length == 0) {
            usage(err);
            return 2;
        }
        // `web` is not a repository command: it reads through the same repository but serves a
        // viewer rather than reporting anything to a terminal. Handing it to its own class
        // keeps this one about reporting, and keeps the server out of the way of the five
        // commands that are used in scripts.
        if ("web".equals(args[0])) {
            return new com.vvcs.web.WebCli(out, err).run(java.util.Arrays.copyOfRange(args, 1, args.length));
        }
        try {
            return switch (args[0]) {
                case "init" -> init(args);
                case "commit" -> commit(args);
                case "log" -> log(args);
                case "checkout" -> checkout(args);
                case "diff" -> diff(args);
                case "-h", "--help", "help" -> {
                    usage(out);
                    yield 0;
                }
                default -> {
                    err.println("vvcs: unknown command: " + args[0]);
                    usage(err);
                    yield 2;
                }
            };
        } catch (RepositoryException | KdenliveParserException | UncheckedIOException e) {
            err.println("vvcs: " + e.getMessage());
            return 1;
        }
    }

    // -------------------------------------------------------------------- init

    private int init(String[] args) {
        Options options = Options.parse(args, List.of("--author"));
        int rejected = options.reject("init", err);
        if (rejected != -1) {
            return rejected;
        }
        Path root = workingDirectory;
        String author = options.value("--author");
        if (author == null || author.isBlank()) {
            author = defaultAuthor();
        }
        new FileSystemRepository(root, clock).initialize(author);
        out.println("initialised vvcs repository in " + root.resolve(FileSystemRepository.DIRECTORY));
        out.println("default author: " + author);
        return 0;
    }

    /**
     * The person committing, when the caller did not say. Falls back to the operating
     * system's user name so that a repository is never created anonymous.
     */
    private static String defaultAuthor() {
        String user = System.getProperty("user.name");
        return user == null || user.isBlank() ? "unknown" : user;
    }

    // ------------------------------------------------------------------ commit

    private int commit(String[] args) {
        Options options = Options.parse(args, List.of("-m", "--message", "--project", "--author"));
        int rejected = options.reject("commit", err);
        if (rejected != -1) {
            return rejected;
        }
        String message = options.value("-m", "--message");
        if (message == null || message.isBlank()) {
            err.println("vvcs: commit needs a message: vvcs commit -m \"<message>\"");
            return 2;
        }

        Repository repository = new FileSystemRepository(workingDirectory, clock);
        Path projectFile = locateProject(options.value("--project"));

        Project project = new KdenliveParser().parse(projectFile);
        Commit commit = new CommitWorkflow(repository, clock)
                .commit(project, message, options.value("--author"));

        out.println("[vvcs " + commit.shortId() + "] " + message);
        out.println("  state    " + commit.stateId());
        out.println("  parent   " + (commit.isRoot() ? "(none, first commit)" : commit.parentId()));
        out.println("  assets   " + project.assetCount()
                + ", clips " + project.timeline().clipCount()
                + ", " + project.timeline().duration() + " frames");
        return 0;
    }

    /**
     * Finds the project to commit. An explicit path wins; otherwise the working directory
     * must hold exactly one Kdenlive project, because guessing between several is worse than
     * asking.
     */
    private Path locateProject(String explicit) {
        if (explicit != null) {
            Path candidate = workingDirectory.resolve(explicit).normalize();
            if (!Files.isRegularFile(candidate)) {
                throw new RepositoryException("no such project file: " + candidate);
            }
            return candidate;
        }
        try (Stream<Path> entries = Files.list(workingDirectory)) {
            List<Path> found = entries.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(KDENLIVE_SUFFIX))
                    .sorted()
                    .toList();
            if (found.size() == 1) {
                return found.get(0);
            }
            if (found.isEmpty()) {
                throw new RepositoryException("no " + KDENLIVE_SUFFIX
                        + " project in " + workingDirectory + "; pass --project <file>");
            }
            throw new RepositoryException("more than one " + KDENLIVE_SUFFIX
                    + " project in " + workingDirectory + "; pass --project <file>: "
                    + found.stream().map(p -> p.getFileName().toString()).toList());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list " + workingDirectory, e);
        }
    }

    // --------------------------------------------------------------------- log

    private int log(String[] args) {
        Options options = Options.parse(args, List.of());
        int rejected = options.reject("log", err);
        if (rejected != -1) {
            return rejected;
        }
        Repository repository = new FileSystemRepository(workingDirectory, clock);
        Optional<Commit> head = repository.head();
        if (head.isEmpty()) {
            out.println("no commits yet");
            return 0;
        }
        for (Commit commit : repository.history(head.get().id())) {
            out.println();
            out.println("commit " + commit.shortId());
            out.println("Author: " + commit.author());
            out.println("Date:   " + LOG_DATE.format(commit.timestamp()) + " UTC");
            out.println("State:  " + commit.stateId());
            out.println();
            for (String line : commit.message().lines().toList()) {
                out.println("    " + line);
            }
        }
        return 0;
    }

    // ---------------------------------------------------------------- checkout

    /**
     * Resolves a commit to the state it stands for and reports it. Nothing is written back to
     * the project directory: turning a stored state back into a Kdenlive file is a separate
     * concern, and overwriting someone's working project with no way to undo it is not a
     * decision this phase should make.
     */
    private int checkout(String[] args) {
        Options options = Options.parse(args, List.of());
        if (!options.missingValue().isEmpty()) {
            return options.reject("checkout", err);
        }
        if (options.positional().size() != 1) {
            err.println("vvcs: checkout needs exactly one commit id: vvcs checkout <commit-id>");
            return 2;
        }
        Repository repository = new FileSystemRepository(workingDirectory, clock);
        Commit commit = repository.commit(options.positional().get(0));
        ProjectState state = repository.state(commit.stateId());
        Project project = state.project();

        out.println("commit " + commit.shortId() + "  " + commit.message());
        out.println("state   " + state.id());
        out.println("project " + project.name());
        out.println("format  " + project.resolution() + " at " + project.frameRate());
        out.println("assets  " + project.assetCount()
                + ", clips " + project.timeline().clipCount()
                + ", " + project.timeline().duration() + " frames");
        out.println();
        out.println("HEAD is still " + repository.head().map(Commit::shortId).orElse("(none)")
                + "; nothing was written to the project directory");
        return 0;
    }

    // -------------------------------------------------------------------- diff

    /**
     * Compares the states two commits stand for and reports what changed between them.
     *
     * <p>All this does is resolve the two commits and hand their states to the engine. The
     * comparison itself knows nothing about commits, repositories, or this command.
     */
    private int diff(String[] args) {
        // --json is pulled out before the shared parser runs, because that parser treats any
        // option it does not know as a stray positional for the command to complain about by
        // name. Teaching it about flags would mean changing option handling for every command
        // to serve one.
        boolean json = List.of(args).contains("--json") || List.of(args).contains("-j");
        String[] positionalArgs = Arrays.stream(args)
                .filter(arg -> !arg.equals("--json") && !arg.equals("-j"))
                .toArray(String[]::new);

        Options options = Options.parse(positionalArgs, List.of());
        if (!options.missingValue().isEmpty()) {
            return options.reject("diff", err);
        }
        if (options.positional().size() != 2) {
            err.println("vvcs: diff needs two commits: vvcs diff <commit-a> <commit-b>");
            return 2;
        }

        // The service is the only thing that knows how to turn two commit ids into something
        // comparable. This method chooses how to print it and nothing else, so a web API can
        // serve the same comparison later without a second implementation of these steps.
        Repository repository = new FileSystemRepository(workingDirectory, clock);
        TimelineComparison comparison = new TimelineComparisonService(repository)
                .compare(options.positional().get(0), options.positional().get(1));

        if (json) {
            try {
                out.println(VvcsJson.toJson(comparison));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return 0;
        }

        CommitRef from = comparison.from();
        CommitRef to = comparison.to();
        out.println("diff " + from.shortId() + " -> " + to.shortId());
        out.println("  " + from.message());
        out.println("  " + to.message());
        out.println();
        DiffReport.print(comparison.changes(), out);
        return 0;
    }

    private static void usage(PrintStream stream) {
        stream.println("""
                usage: vvcs <command> [options]

                  init                            create a .vvcs repository here
                  commit -m <message> [options]    freeze the project into a new state and commit it
                  log                             show the history, newest first
                  checkout <commit-id>            show the state a commit stands for
                  diff <commit-a> <commit-b>      report what changed between two commits
                  web [--port <n>]                serve the comparison viewer in a browser

                options:
                  -m, --message <text>   what changed, required by commit
                      --project <file>   the project to commit, if it is not the only one here
                      --author <name>    who is committing, overriding the repository default
                  -j, --json            print the comparison as JSON, used by diff
                """);
    }

    /**
     * Minimal option parsing: named values, {@code --flag=value} and {@code --flag value}
     * both accepted, everything else positional. Enough for four commands, and small enough
     * to see in one screen.
     */
    /**
     * Minimal option parsing: named values, given as {@code --name value} or
     * {@code --name=value}, with everything else positional. Enough for four commands, and
     * small enough to read in one sitting.
     *
     * <p>A known option that is missing its value is kept apart from the leftovers, so the
     * command can say that the value is missing instead of claiming the option is unknown.
     */
    private record Options(Map<String, String> values, List<String> positional,
                           List<String> missingValue) {

        static Options parse(String[] args, List<String> valueOptions) {
            Map<String, String> values = new LinkedHashMap<>();
            List<String> positional = new ArrayList<>();
            List<String> missingValue = new ArrayList<>();
            for (int i = 1; i < args.length; i++) {
                String arg = args[i];
                if (!arg.startsWith("-")) {
                    positional.add(arg);
                    continue;
                }
                int equals = arg.indexOf('=');
                if (equals > 0) {
                    values.put(arg.substring(0, equals), arg.substring(equals + 1));
                } else if (valueOptions.contains(arg) && i + 1 < args.length) {
                    values.put(arg, args[++i]);
                } else if (valueOptions.contains(arg)) {
                    missingValue.add(arg);
                } else {
                    // An option nobody claimed is kept as a positional so that the command
                    // can complain about it by name.
                    positional.add(arg);
                }
            }
            return new Options(values, positional, missingValue);
        }

        String value(String... names) {
            for (String name : names) {
                if (values.containsKey(name)) {
                    return values.get(name);
                }
            }
            return null;
        }

        /**
         * Reports arguments a command has no use for. Returns 2 for a usage error.
         */
        private int reject(String command, PrintStream err) {
            if (!missingValue.isEmpty()) {
                err.println("vvcs: " + command + ": " + String.join(" ", missingValue)
                        + " needs a value");
                return 2;
            }
            if (!positional.isEmpty()) {
                boolean allOptions = positional.stream().allMatch(p -> p.startsWith("-"));
                err.println("vvcs: " + command + (allOptions ? " takes no option: " : " takes no argument: ")
                        + String.join(" ", positional));
                return 2;
            }
            return -1;
        }
    }
}
