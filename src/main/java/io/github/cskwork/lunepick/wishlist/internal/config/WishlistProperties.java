package io.github.cskwork.lunepick.wishlist.internal.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("lunepick.wishlist")
public record WishlistProperties(@DefaultValue("500") @Min(1) @Max(5000) int maxEntries) {}
