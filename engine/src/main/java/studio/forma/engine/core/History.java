package studio.forma.engine.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Bounded recording of a generation run as batched cell diffs.
 *
 * <p>Changes are buffered and flushed into frames of roughly {@code frameBudget} distinct
 * cells. Repeated writes to one cell within a frame are coalesced. Frames are grouped into
 * stages. When the frame limit is reached, further changes of a stage are merged into its
 * last frame, so memory stays bounded and replay remains exact (replaying all frames in
 * order always reproduces the final grid).
 */
public final class History implements Grid.ChangeSink {
    public record Stage(String id, String label, String kind, String description, int firstFrame, int lastFrame) {}

    public record Frame(int stage, String label, int[] indices, byte[] states) {
        public int size() {
            return indices.length;
        }
    }

    private final int gridSize;
    private final int defaultBudget;
    private int frameBudget;
    private final int maxFrames;
    private final List<Frame> frames = new ArrayList<>();
    private final List<Stage> stages = new ArrayList<>();

    private int[] bufIdx = new int[1024];
    private byte[] bufVal = new byte[1024];
    private int bufLen;
    private final int[] posInFrame;   // position+1 of an index inside the current buffer, valid when stamp matches
    private final int[] stamp;
    private int currentStamp = 1;

    private int stageIndex = -1;
    private String stageId, stageLabel, stageKind, stageDesc;
    private int stageFirstFrame;
    private long totalChanges;

    public History(int gridSize, int frameBudget, int maxFrames) {
        this.gridSize = gridSize;
        this.frameBudget = Math.max(16, frameBudget);
        this.defaultBudget = this.frameBudget;
        this.maxFrames = Math.max(8, maxFrames);
        this.posInFrame = new int[gridSize];
        this.stamp = new int[gridSize];
    }

    public static History forGrid(Grid g) {
        int budget = Math.max(150, g.size() / 640);
        return new History(g.size(), budget, 720);
    }

    @Override
    public void changed(int index, byte newState) {
        totalChanges++;
        if (stamp[index] == currentStamp) {
            bufVal[posInFrame[index] - 1] = newState;
            return;
        }
        if (bufLen == bufIdx.length) {
            bufIdx = Arrays.copyOf(bufIdx, bufLen * 2);
            bufVal = Arrays.copyOf(bufVal, bufLen * 2);
        }
        bufIdx[bufLen] = index;
        bufVal[bufLen] = newState;
        bufLen++;
        stamp[index] = currentStamp;
        posInFrame[index] = bufLen;
        if (bufLen >= frameBudget) flush(null);
    }

    /** Temporarily changes how many cells make up one frame (reset at the next stage). */
    public void setFrameBudget(int cells) {
        flush(null);
        frameBudget = Math.max(16, cells);
    }

    public void beginStage(String id, String label, String kind, String description) {
        if (stageIndex >= 0) endStage();
        frameBudget = defaultBudget;
        stageIndex = stages.size();
        stageId = id;
        stageLabel = label;
        stageKind = kind;
        stageDesc = description;
        stageFirstFrame = frames.size();
    }

    public void endStage() {
        if (stageIndex < 0) return;
        flush(null);
        int last = frames.size() - 1;
        if (last < stageFirstFrame) {
            // empty stage: add an empty frame so the stage is addressable in the timeline
            frames.add(new Frame(stageIndex, stageLabel, new int[0], new byte[0]));
            last = frames.size() - 1;
        }
        stages.add(new Stage(stageId, stageLabel, stageKind, stageDesc, stageFirstFrame, last));
        stageIndex = -1;
    }

    /** Ends the current frame early (e.g. after one rule node finished) with an optional label. */
    public void mark(String label) {
        flush(label);
    }

    private void flush(String label) {
        if (bufLen == 0) return;
        int st = Math.max(stageIndex, 0);
        String lbl = label != null ? label : stageLabel;
        int[] idx = Arrays.copyOf(bufIdx, bufLen);
        byte[] val = Arrays.copyOf(bufVal, bufLen);
        boolean full = frames.size() >= maxFrames;
        if (full && !frames.isEmpty() && frames.get(frames.size() - 1).stage() == st) {
            Frame prev = frames.remove(frames.size() - 1);
            frames.add(merge(prev, idx, val));
        } else {
            frames.add(new Frame(st, lbl, idx, val));
        }
        bufLen = 0;
        currentStamp++;
        if (currentStamp == Integer.MAX_VALUE) {
            Arrays.fill(stamp, 0);
            currentStamp = 1;
        }
    }

    private Frame merge(Frame prev, int[] idx, byte[] val) {
        // later writes win; keep order stable
        java.util.LinkedHashMap<Integer, Byte> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < prev.indices.length; i++) m.put(prev.indices[i], prev.states[i]);
        for (int i = 0; i < idx.length; i++) m.put(idx[i], val[i]);
        int[] ni = new int[m.size()];
        byte[] nv = new byte[m.size()];
        int k = 0;
        for (var e : m.entrySet()) {
            ni[k] = e.getKey();
            nv[k] = e.getValue();
            k++;
        }
        return new Frame(prev.stage(), prev.label(), ni, nv);
    }

    public List<Frame> frames() {
        return Collections.unmodifiableList(frames);
    }

    public List<Stage> stages() {
        return Collections.unmodifiableList(stages);
    }

    public long totalChanges() {
        return totalChanges;
    }

    public int recordedChanges() {
        int n = 0;
        for (Frame f : frames) n += f.size();
        return n;
    }

    public int gridSize() {
        return gridSize;
    }

    /** Reconstructs the grid states after applying frames [0, upToFrameInclusive]. */
    public byte[] replay(int upToFrameInclusive) {
        byte[] s = new byte[gridSize];
        for (int f = 0; f <= upToFrameInclusive && f < frames.size(); f++) {
            Frame fr = frames.get(f);
            for (int i = 0; i < fr.indices.length; i++) s[fr.indices[i]] = fr.states[i];
        }
        return s;
    }
}
