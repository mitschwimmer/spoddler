package de.mitschwimmer.spoddler.crossfade;

class LinearDecreasingInterpolator implements GainInterpolator {
    @Override
    public float interpolate(float x) {
        return 1 - x;
    }

    @Override
    public float last() {
        return 0;
    }
}
