package de.mitschwimmer.spoddler.soundadapter;

public class AudioSystem {
    public static final int NOT_SPECIFIED = -1;

    public static Mixer.Info[] getMixerInfo() {
        return new Mixer.Info[0];
    }

    public static Mixer getMixer(Mixer.Info mixerInfo) {
        return null;
    }
}
