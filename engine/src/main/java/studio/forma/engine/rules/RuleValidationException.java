package studio.forma.engine.rules;

/** Thrown when a rule or rule program is malformed; the message names the problem precisely. */
public class RuleValidationException extends RuntimeException {
    public RuleValidationException(String message) {
        super(message);
    }
}
