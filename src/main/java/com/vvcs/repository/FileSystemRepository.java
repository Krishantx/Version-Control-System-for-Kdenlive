package com.vvcs.repository;

import com.vvcs.hash.StateHasher;
import com.vvcs.model.Commit;
import com.vvcs.model.Project;
import com.vvcs.model.ProjectState;
import com.vvcs.serialization.VvcsJson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A repository kept as plain files under {@code .vvcs} beside the project it tracks.
 *
 * <pre>
 * .vvcs/
 * ├── repository.json   format version, creation time, default author
 * ├── HEAD               the current commit id, empty while nothing is committed
 * ├── states/            &lt;64 hex characters&gt;.json
 * └── commits/           &lt;64 hex characters&gt;.json
 * </pre>
 *
 * <p>One file per object, no database and no index to fall out of step with the data. Every
 * read goes to disk, so reopening a directory is enough to recover the full picture.
 */
public final class FileSystemRepository implements Repository {

    /** The directory a repository lives in, relative to the project root. */
    public static final String DIRECTORY = ".vvcs";

    private static final String METADATA_FILE = "repository.json";
    private static final String HEAD_FILE = "HEAD";
    private static final String STATES_DIR = "states";
    private static final String COMMITS_DIR = "commits";

    private static final Pattern HEX_FILE = Pattern.compile("[0-9a-f]{64}\\.json");

    private final Path root;
    private final Clock clock;

    public FileSystemRepository(Path root) {
        this(root, Clock.systemDefaultZone());
    }

    /**
     * @param clock supplies the repository creation time; injected so that tests are
     *              deterministic
     */
    public FileSystemRepository(Path root, Clock clock) {
        this.root = root.toAbsolutePath().normalize();
        this.clock = clock;
    }

    @Override
    public Path root() {
        return root;
    }

    private Path directory() {
        return root.resolve(DIRECTORY);
    }

    private Path statesDirectory() {
        return directory().resolve(STATES_DIR);
    }

    private Path commitsDirectory() {
        return directory().resolve(COMMITS_DIR);
    }

    private Path headFile() {
        return directory().resolve(HEAD_FILE);
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void initialize(String author) {
        if (isInitialized()) {
            throw new RepositoryException(
                    "a vvcs repository already exists at " + directory()
                            + "; remove it by hand if you really mean to start over");
        }
        if (Files.exists(directory()) && !Files.isDirectory(directory())) {
            throw new RepositoryException(directory() + " exists but is not a directory");
        }
        RepositoryMetadata metadata = new RepositoryMetadata(
                RepositoryMetadata.CURRENT_FORMAT_VERSION, clock.instant(), author);
        try {
            Files.createDirectories(statesDirectory());
            Files.createDirectories(commitsDirectory());
            writeJson(directory().resolve(METADATA_FILE), metadata);
            // Present but empty: an empty repository has a HEAD, and no commit in it.
            writeAtomically(headFile(), new byte[0]);
        } catch (IOException e) {
            throw new RepositoryException("cannot create repository at " + directory(), e);
        }
    }

    @Override
    public boolean isInitialized() {
        return Files.isRegularFile(directory().resolve(METADATA_FILE));
    }

    /**
     * @throws RepositoryException if the repository is not initialised
     */
    private void requireInitialized() {
        if (!isInitialized()) {
            throw new RepositoryException("no vvcs repository at " + directory()
                    + "; run 'vvcs init' first");
        }
    }

    @Override
    public String defaultAuthor() {
        requireInitialized();
        return metadata().author();
    }

    private RepositoryMetadata metadata() {
        try {
            return VvcsJson.read(directory().resolve(METADATA_FILE), RepositoryMetadata.class);
        } catch (IOException e) {
            throw new RepositoryException("cannot read repository metadata", e);
        }
    }

    // ------------------------------------------------------------------ states

    /**
     * A state is named by its content, so this is a pure function of the project and needs
     * nothing from disk. That is what lets a caller ask for the id before deciding whether
     * the state is already stored.
     */
    @Override
    public String stateIdFor(Project project) {
        return StateHasher.idOf(project);
    }

    @Override
    public void storeState(ProjectState state) {
        requireInitialized();
        if (!StateHasher.matchesIdOf(state.project(), state.id())) {
            throw new RepositoryException("state " + state.id()
                    + " does not match the content it holds; it would be stored under an"
                    + " identity that could never be found again");
        }
        writeUnchanged(statesDirectory().resolve(state.id() + ".json"), state,
                "state " + state.id().substring(0, 8));
    }

    @Override
    public ProjectState state(String stateId) {
        requireInitialized();
        Path file = statesDirectory().resolve(stateId + ".json");
        if (!Files.isRegularFile(file)) {
            throw new RepositoryException("no such state: " + stateId);
        }
        try {
            return VvcsJson.read(file, ProjectState.class);
        } catch (IOException e) {
            throw new RepositoryException("cannot read state " + stateId, e);
        }
    }

    @Override
    public List<String> stateIds() {
        requireInitialized();
        return listFiles(statesDirectory(), HEX_FILE)
                .map(path -> path.getFileName().toString().replace(".json", ""))
                .sorted()
                .toList();
    }

    // ----------------------------------------------------------------- commits

    @Override
    public void storeCommit(Commit commit) {
        requireInitialized();
        writeUnchanged(commitsDirectory().resolve(commit.id() + ".json"), commit,
                "commit " + commit.shortId());
    }

    @Override
    public Commit commit(String commitIdOrPrefix) {
        requireInitialized();
        String id = resolve(commitIdOrPrefix);
        Path file = commitsDirectory().resolve(id + ".json");
        if (!Files.isRegularFile(file)) {
            throw new RepositoryException("no such commit: " + id);
        }
        try {
            return VvcsJson.read(file, Commit.class);
        } catch (IOException e) {
            throw new RepositoryException("cannot read commit " + id, e);
        }
    }

    /**
     * Accepts a full identifier or any prefix that matches exactly one commit. Rejecting an
     * ambiguous prefix is better than silently picking one of the candidates.
     */
    private String resolve(String commitIdOrPrefix) {
        String prefix = commitIdOrPrefix == null ? "" : commitIdOrPrefix.trim().toLowerCase();
        if (prefix.isEmpty()) {
            throw new RepositoryException("no commit id given");
        }
        List<String> matches = commitIds().stream().filter(id -> id.startsWith(prefix)).toList();
        if (matches.isEmpty()) {
            throw new RepositoryException("no such commit: " + commitIdOrPrefix);
        }
        if (matches.size() > 1) {
            throw new RepositoryException("commit id is ambiguous: '" + commitIdOrPrefix
                    + "' matches " + matches.size() + " commits");
        }
        return matches.get(0);
    }

    @Override
    public List<String> commitIds() {
        requireInitialized();
        return listFiles(commitsDirectory(), null)
                .map(path -> path.getFileName().toString().replace(".json", ""))
                .sorted()
                .toList();
    }

    @Override
    public List<Commit> history(String fromId) {
        requireInitialized();
        List<Commit> chain = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        String cursor = fromId;
        while (cursor != null) {
            if (!visited.add(cursor)) {
                throw new RepositoryException("commit history contains a cycle at " + cursor);
            }
            Commit commit = commit(cursor);
            chain.add(commit);
            cursor = commit.parentId();
        }
        return List.copyOf(chain);
    }

    // -------------------------------------------------------------------- HEAD

    @Override
    public Optional<Commit> head() {
        requireInitialized();
        if (!Files.exists(headFile())) {
            return Optional.empty();
        }
        String id;
        try {
            id = Files.readString(headFile(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new RepositoryException("cannot read HEAD", e);
        }
        if (id.isEmpty()) {
            return Optional.empty();
        }
        if (!Files.isRegularFile(commitsDirectory().resolve(id + ".json"))) {
            throw new RepositoryException("HEAD points at commit " + id + ", which is not stored");
        }
        return Optional.of(commit(id));
    }

    @Override
    public void setHead(String commitId) {
        requireInitialized();
        if (!Files.isRegularFile(commitsDirectory().resolve(commitId + ".json"))) {
            throw new RepositoryException("cannot point HEAD at unknown commit " + commitId);
        }
        try {
            Files.createDirectories(directory());
            writeAtomically(headFile(), (commitId + System.lineSeparator())
                    .getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RepositoryException("cannot write HEAD", e);
        }
    }

    // ------------------------------------------------------------------ plumbing

    private static Stream<Path> listFiles(Path directory, Pattern nameFilter) {
        if (!Files.isDirectory(directory)) {
            return Stream.empty();
        }
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.filter(Files::isRegularFile)
                    .filter(path -> nameFilter == null
                            || nameFilter.matcher(path.getFileName().toString()).matches())
                    .toList()
                    .stream();
        } catch (IOException e) {
            throw new RepositoryException("cannot list " + directory, e);
        }
    }

    /**
     * Stores an immutable object. Writing something already stored is a no-op; finding that
     * an identity is already held by different bytes means the repository is not what it
     * claims to be, so refuse rather than overwrite.
     */
    private void writeUnchanged(Path file, Object value, String what) {
        String json;
        try {
            json = VvcsJson.toJson(value);
        } catch (IOException e) {
            throw new RepositoryException("cannot serialize " + what, e);
        }
        byte[] bytes = (json + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
        if (Files.isRegularFile(file)) {
            byte[] existing;
            try {
                existing = Files.readAllBytes(file);
            } catch (IOException e) {
                throw new RepositoryException("cannot read " + file, e);
            }
            if (Arrays.equals(existing, bytes)) {
                return;
            }
            throw new RepositoryException(file.getFileName()
                    + " already exists with different content; refusing to overwrite it");
        }
        try {
            Files.createDirectories(file.getParent());
            writeAtomically(file, bytes);
        } catch (IOException e) {
            throw new RepositoryException("cannot write " + what, e);
        }
    }

    private void writeJson(Path file, Object value) throws IOException {
        writeAtomically(file, (VvcsJson.toJson(value) + System.lineSeparator())
                .getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Writes through a temporary file so that an interrupted write cannot leave a
     * half-written object where a whole one is expected.
     */
    private static void writeAtomically(Path file, byte[] bytes) throws IOException {
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(temporary, bytes);
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.deleteIfExists(temporary);
            throw e;
        }
    }
}
