package com.vvcs.repository;

/**
 * Signals that a repository could not satisfy a request: it is not initialised, an object is
 * missing, or what is on disk does not agree with itself.
 *
 * <p>All repository failures arrive as this one type so that callers, and the command line
 * in particular, have a single thing to catch and report as a plain message.
 */
public class RepositoryException extends RuntimeException {

    public RepositoryException(String message) {
        super(message);
    }

    public RepositoryException(String message, Throwable cause) {
        super(message, cause);
    }
}
