package de.mitschwimmer.spoddler.contexts;

import org.jetbrains.annotations.NotNull;

public class GeneralInfiniteContext extends AbsSpotifyContext {
    GeneralInfiniteContext(@NotNull String context) {
        super(context);
    }

    @Override
    public final boolean isFinite() {
        return false;
    }
}
