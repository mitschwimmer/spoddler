package de.mitschwimmer.spoddler.crossfade;

class LinearIncreasingInterpolator implements GainInterpolator {
    @Override
    public float interpolate(float x) {
        return x;
    }

    @Override
    public float last() {
        return 1;
    }
}
