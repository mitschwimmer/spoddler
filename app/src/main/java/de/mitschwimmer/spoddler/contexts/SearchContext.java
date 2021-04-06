package de.mitschwimmer.spoddler.contexts;

import org.jetbrains.annotations.NotNull;

/**
 * @author Gianlu
 */
public final class SearchContext extends GeneralFiniteContext {
    public final String searchTerm;

    public SearchContext(@NotNull String context, @NotNull String searchTerm) {
        super(context);
        this.searchTerm = searchTerm;
    }
}
