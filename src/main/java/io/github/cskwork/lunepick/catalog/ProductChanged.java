package io.github.cskwork.lunepick.catalog;

/** Published after a catalog write commits, so search can refresh one document. */
public record ProductChanged(long productId) {}
