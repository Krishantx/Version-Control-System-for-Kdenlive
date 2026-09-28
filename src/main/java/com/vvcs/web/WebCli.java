package com.vvcs.web;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * The {@code vvcs web} command: serve the comparison viewer.
 *
 * <pre>
 * vvcs web [--repo &lt;dir&gt;] [--port &lt;n&gt;] [--web &lt;dir&gt;]
 * </pre>
 *
 * <p>It lives in the command line rather than in a script because the viewer is a local tool
 * for looking at a repository, and a repository already knows how to be worked in from a
 * terminal. Being able to type the same directory twice, once to commit and once to look,
 * is the point.
 *
 * <p>Like {@link com.vvcs.cli.VvcsCli}, {@link #run} returns an exit code instead of calling
 * {@code System.exit}, so a test can drive it. {@link #serve} is separated out because that
 * one blocks, and a test that blocks is a test that hangs.
 */
public final class WebCli {

    private static final int DEFAULT_PORT = 8080;

    private final PrintStream out;
    private final PrintStream err;

    public WebCli(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    public static void main(String[] args) {
        // Tolerate a leading `web`, so this class behaves the same whether it is reached
        // through the vvcs command line, which strips it, or run directly.
        if (args.length > 0 && "web".equals(args[0])) {
            args = java.util.Arrays.copyOfRange(args, 1, args.length);
        }
        System.exit(new WebCli(System.out, System.err).run(args));
    }

    public int run(String[] args) {
        Path repositoryRoot = Paths.get(".").toAbsolutePath().normalize();
        Path webRoot = Paths.get(VvcsWebServer.DEFAULT_WEB_ROOT).toAbsolutePath().normalize();
        int port = DEFAULT_PORT;

        // `args` arrives without the `web` command itself: VvcsCli strips it before handing
        // over. So the scan starts at the first option, not past a command name.
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--repo", "-r" -> {
                    if (++i >= args.length) {
                        return fail("--repo needs a directory");
                    }
                    repositoryRoot = Paths.get(args[i]).toAbsolutePath().normalize();
                }
                case "--web" -> {
                    if (++i >= args.length) {
                        return fail("--web needs a directory");
                    }
                    webRoot = Paths.get(args[i]).toAbsolutePath().normalize();
                }
                case "--port", "-p" -> {
                    if (++i >= args.length) {
                        return fail("--port needs a number");
                    }
                    try {
                        port = Integer.parseInt(args[i]);
                    } catch (NumberFormatException e) {
                        return fail("--port needs a number, not " + args[i]);
                    }
                }
                case "-h", "--help" -> {
                    usage(out);
                    return 0;
                }
                default -> {
                    return fail("unknown option: " + args[i]);
                }
            }
        }

        if (!Files.isDirectory(webRoot)) {
            return fail("no frontend at " + webRoot + "\n       run the viewer from the project"
                    + " root, or pass --web <dir>");
        }
        if (!Files.isDirectory(repositoryRoot)) {
            return fail("no such directory: " + repositoryRoot);
        }

        try {
            serve(repositoryRoot, webRoot, port);
            return 0;
        } catch (Exception e) {
            return fail(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    /** Starts the server and blocks until it stops. */
    private void serve(Path repositoryRoot, Path webRoot, int port) throws Exception {
        VvcsWebServer server = VvcsWebServer.start(repositoryRoot, webRoot, port);
        boolean empty = !server.repository().isInitialized();
        if (empty) {
            out.println("vvcs: " + repositoryRoot + " holds no repository yet, so comparisons"
                    + " will report that until you run vvcs init and vvcs commit");
        }
        out.println("vvcs: serving " + repositoryRoot);
        out.println("vvcs: open " + server.baseUrl() + "/commits to pick two commits");
        out.println("vvcs: press ctrl-c to stop");
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        // Parks the main thread; the server runs on its own executor.
        Thread.currentThread().join();
    }

    private int fail(String message) {
        err.println("vvcs web: " + message);
        err.println();
        usage(err);
        return 2;
    }

    private static void usage(PrintStream stream) {
        stream.println("""
                usage: vvcs web [--repo <dir>] [--port <n>] [--web <dir>]

                  --repo, -r <dir>   the VVCS repository to read, default the current directory
                  --port, -p <n>     the port to listen on, default 8080
                  --web <dir>        where the frontend lives, default ./web
                """);
    }

    /** The options a caller may ask about, for a caller that would rather not parse. */
    public static List<String> optionNames() {
        return List.of("--repo", "--port", "--web");
    }
}
