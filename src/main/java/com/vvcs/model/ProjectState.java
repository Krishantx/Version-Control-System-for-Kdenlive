package com.vvcs.model;

/**
 * An immutable snapshot of a whole project at one point in history.
 *
 * <p>A {@link Project} is the project as it stands right now. A {@code ProjectState} is
 * that project frozen: once a state has been committed it is never modified, and a later
 * edit produces a new state rather than a changed one.
 *
 * <p>The whole project graph is already immutable, so a state needs no defensive copying
 * beyond the defensive copies the nested records perform in their own constructors.
 *
 * <h2>Known limitation of the frozen model</h2>
 *
 * <p>The current Kdenlive adapter represents an audio/video split clip as two independent
 * clips on separate tracks. Kdenlive treats such a pair as one editorial clip, linked by a
 * shared {@code kdenlive:id} and an {@code AVSplit} group, so a project of four editor
 * visible clips currently yields five. The parser does not read either signal. This was
 * accepted deliberately for the first commit, so states stored before the adapter is fixed
 * will not match the shape of states stored after it. See {@code TimelineBuilder}.
 */
public record ProjectState(String id, Project project) {

    public ProjectState {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("project state id must be set");
        }
        if (project == null) {
            throw new IllegalArgumentException("project state " + id + " has no project");
        }
    }

    public static ProjectState of(String id, Project project) {
        return new ProjectState(id, project);
    }
}
