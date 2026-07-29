package ru.petus.launcher.game;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Progress sink for long running work (installing a version, downloading a
 * modpack). Implementations forward to the UI on the JavaFX thread.
 */
public interface Progress {
    /** Human readable stage, e.g. "Скачиваем библиотеки". */
    void stage(String stage);

    /** 0..1, or a negative value for indeterminate. */
    void fraction(double fraction);

    /** Secondary detail line, e.g. the current file name. */
    void detail(String detail);

    void log(String line);

    default boolean cancelled() {
        return false;
    }

    /** Throws when the user pressed cancel, so callers can bail out early. */
    default void checkCancelled() {
        if (cancelled()) {
            throw new CancelledException();
        }
    }

    final class CancelledException extends RuntimeException {
        public CancelledException() {
            super("Операция отменена");
        }
    }

    /** Discards everything — handy for tests and background refreshes. */
    Progress NOOP = new Progress() {
        @Override
        public void stage(String stage) {
        }

        @Override
        public void fraction(double fraction) {
        }

        @Override
        public void detail(String detail) {
        }

        @Override
        public void log(String line) {
        }
    };

    /** Simple cancellable base class. */
    abstract class Cancellable implements Progress {
        private final AtomicBoolean flag = new AtomicBoolean(false);

        public void cancel() {
            flag.set(true);
        }

        @Override
        public boolean cancelled() {
            return flag.get();
        }
    }
}
