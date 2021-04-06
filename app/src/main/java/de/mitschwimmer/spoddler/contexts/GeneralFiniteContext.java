package de.mitschwimmer.spoddler.contexts;

import org.jetbrains.annotations.NotNull;

public class GeneralFiniteContext extends AbsSpotifyContext {
    GeneralFiniteContext(@NotNull String context) {
        super(context);
    }

    @Override
    public final boolean isFinite() {
        return true;
    }
}
