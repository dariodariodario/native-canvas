package nativecanvas.ui;

import java.util.Objects;

/** Sizing rule for a row child or a grid track, in logical pixels. */
public final class Track {
    public static final Track AUTO = new Track(Kind.AUTO, 0);
    public static final Track FILL = fill(1);

    private enum Kind { AUTO, FILL, FIXED }
    private final Kind kind;
    private final double value;

    private Track(Kind kind, double value) {
        this.kind = kind;
        this.value = value;
    }

    public static Track size(double pixels) {
        if (!Double.isFinite(pixels) || pixels < 0) throw new IllegalArgumentException("Invalid size");
        return new Track(Kind.FIXED, pixels);
    }

    public static Track fill(double weight) {
        if (!Double.isFinite(weight) || weight <= 0) throw new IllegalArgumentException("Invalid fill weight");
        return new Track(Kind.FILL, weight);
    }

    static double spacing(double value) {
        if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException("Invalid spacing");
        return value;
    }

    static double gaps(int count, double gap) {
        return Math.max(0, count - 1) * gap;
    }

    static double[] resolve(Track[] tracks, double[] preferred, double available) {
        double[] sizes = new double[tracks.length];
        double used = 0;
        double maxWeight = 0;
        for (int i = 0; i < tracks.length; i++) {
            Track track = Objects.requireNonNull(tracks[i]);
            if (track.kind == Kind.FILL) maxWeight = Math.max(maxWeight, track.value);
            sizes[i] = track.kind == Kind.FIXED ? track.value : preferred[i];
            used += sizes[i];
        }
        if (maxWeight == 0 || available <= used) return sizes;
        double totalWeight = 0;
        for (Track track : tracks)
            if (track.kind == Kind.FILL) totalWeight += track.value / maxWeight;
        for (int i = 0; i < tracks.length; i++)
            if (tracks[i].kind == Kind.FILL)
                sizes[i] += (available - used) * ((tracks[i].value / maxWeight) / totalWeight);
        return sizes;
    }
}
