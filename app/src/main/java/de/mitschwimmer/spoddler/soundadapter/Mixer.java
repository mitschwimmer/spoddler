package de.mitschwimmer.spoddler.soundadapter;

public class Mixer {

    public boolean isLineSupported(Line.Info info) {
        return false;
    }

    public Info getMixerInfo() {
        return null;
    }

    public SourceDataLine getLine(DataLine.Info info) {
        return null;
    }

    public static class Info {

        public String getName() {
            return null;
        }
    }
}
