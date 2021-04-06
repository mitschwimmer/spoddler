package de.mitschwimmer.spoddler.soundadapter;

public interface DataLine extends Line {

    public static class Info extends Line.Info {
        public Info(Class<SourceDataLine> sourceDataLineClass, AudioFormat format, int notSpecified) {

        }
    }
}
