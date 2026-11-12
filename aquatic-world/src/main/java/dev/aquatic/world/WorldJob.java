package dev.aquatic.world;

/** A long running world edit that is executed in small slices on the main thread. */
interface WorldJob {

    /** Short description shown in progress messages. */
    String label();

    /**
     * Does work until the deadline (System.nanoTime()) is reached.
     *
     * @return true when the job is finished.
     */
    boolean tick(long deadlineNanos);

    /** 0..1 */
    double progress();

    /** Releases resources (chunk tickets) when the job finishes or is aborted. Must be idempotent. */
    void cleanup();
}
