package fr.cobbledollars.commandshops.admin;

import java.io.IOException;

/** Keeps diagnostic exceptions English while carrying translated UI messages separately. */
public final class AdminMessages {
    private AdminMessages() { }

    public interface Failure {
        AdminMessage adminMessage();
    }

    public static IOException io(String id, String englishPattern, Object... arguments) {
        return new IoFailure(AdminMessage.translated(id, englishPattern, arguments));
    }

    public static IllegalArgumentException argument(String id, String englishPattern, Object... arguments) {
        return new ArgumentFailure(AdminMessage.translated(id, englishPattern, arguments));
    }

    public static IllegalStateException state(String id, String englishPattern, Object... arguments) {
        return new StateFailure(AdminMessage.translated(id, englishPattern, arguments));
    }

    public static AdminMessage fromException(Throwable exception) {
        if (exception instanceof Failure failure) return failure.adminMessage();
        String message = exception.getMessage();
        return AdminMessage.literal(message == null || message.isBlank()
                ? exception.getClass().getSimpleName() : message);
    }

    private static final class IoFailure extends IOException implements Failure {
        private final AdminMessage message;
        private IoFailure(AdminMessage message) { super(message.english()); this.message = message; }
        @Override public AdminMessage adminMessage() { return message; }
    }

    private static final class ArgumentFailure extends IllegalArgumentException implements Failure {
        private final AdminMessage message;
        private ArgumentFailure(AdminMessage message) { super(message.english()); this.message = message; }
        @Override public AdminMessage adminMessage() { return message; }
    }

    private static final class StateFailure extends IllegalStateException implements Failure {
        private final AdminMessage message;
        private StateFailure(AdminMessage message) { super(message.english()); this.message = message; }
        @Override public AdminMessage adminMessage() { return message; }
    }
}
