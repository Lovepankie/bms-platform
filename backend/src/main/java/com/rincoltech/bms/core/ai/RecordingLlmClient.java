package com.rincoltech.bms.core.ai;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The recording stub (spec 5.3): it records every request it receives and answers from a script, so
 * tests of the gateway and of the advisor never reach a network. It is created only when
 * {@code BMS_AI_PROVIDER=recording}, which startup refuses outside the dev and test profiles. With
 * an empty script it answers a fixed, harmless JSON object. It keeps the last 100 requests in
 * memory, which is why it is a test and development tool and never a production adapter.
 */
public final class RecordingLlmClient implements LlmClient {

    static final int KEPT = 100;
    private static final String DEFAULT_TEXT = "{\"summary\":\"Recorded stub answer.\",\"points\":[]}";

    private final String model;
    private final Deque<Object> script = new ArrayDeque<>();
    private final List<Request> requests = new ArrayList<>();
    private volatile Runnable gate = () -> {};

    public RecordingLlmClient(String model) {
        this.model = model;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public Result complete(Request request) throws LlmException {
        // Outside the lock, so a test can hold several calls in flight at once.
        gate.run();
        return answer(request);
    }

    private synchronized Result answer(Request request) throws LlmException {
        requests.add(request);
        if (requests.size() > KEPT) {
            requests.removeFirst();
        }
        Object next = script.pollFirst();
        if (next instanceof LlmException failure) {
            throw failure;
        }
        if (next instanceof Result result) {
            return result;
        }
        return new Result(DEFAULT_TEXT, Math.max(1, request.characters() / 4), 20, "stop");
    }

    /** Runs before every call answers, in the caller's thread; a test blocks in it to keep calls in flight. */
    public RecordingLlmClient onEachCall(Runnable gate) {
        this.gate = gate;
        return this;
    }

    /** The next call is answered with this. */
    public synchronized RecordingLlmClient thenAnswer(Result result) {
        script.addLast(result);
        return this;
    }

    /** The next call fails with this. */
    public synchronized RecordingLlmClient thenFail(LlmException failure) {
        script.addLast(failure);
        return this;
    }

    /** Every request received since the last {@link #reset()}, oldest first. */
    public synchronized List<Request> requests() {
        return List.copyOf(requests);
    }

    public synchronized void reset() {
        gate = () -> {};
        script.clear();
        requests.clear();
    }
}
