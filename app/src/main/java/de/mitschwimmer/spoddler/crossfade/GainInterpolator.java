package de.mitschwimmer.spoddler.crossfade;

interface GainInterpolator {
    float interpolate(float x);

    float last();
}
