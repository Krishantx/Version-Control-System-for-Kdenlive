package com.vvcs.parser;

/**
 * Signals that a Kdenlive project could not be turned into a VVCS project.
 *
 * <p>Parsing errors are never swallowed. Optional metadata that VVCS does not model yet
 * produces a warning instead, never an exception.
 */
public class KdenliveParserException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Reason {
        FILE_NOT_FOUND,
        MALFORMED_XML,
        MISSING_PROFILE,
        SEQUENCE_NOT_FOUND,
        UNKNOWN_PRODUCER,
        UNRESOLVED_RESOURCE,
        UNSUPPORTED_TIMELINE
    }

    private final Reason reason;

    public KdenliveParserException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public KdenliveParserException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
