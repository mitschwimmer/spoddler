package de.mitschwimmer.spoddler.soundadapter;

public class AudioFormat {
    public AudioFormat(int sampleRate, int i, int channels, boolean b, boolean b1) {

    }

    public int getSampleSizeInBits() {
        return 0;
    }

    public int getFrameSize() {
        return 0;
    }

    public boolean matches(AudioFormat format) {
        return false;
    }

    public int getChannels() {
        return 0;
    }

    public boolean isBigEndian() {
        return false;
    }

    public Encoding getEncoding() {
        return new Encoding();
    }

    public float getSampleRate() {
        return 0;
    }

    public int getFrameRate() {
        return 0;
    }

    public static class Encoding {

    }
}
