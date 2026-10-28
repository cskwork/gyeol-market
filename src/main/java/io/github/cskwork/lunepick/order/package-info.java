/** Immutable checkout snapshots and atomic order reservations. */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"shared", "member", "catalog", "address", "cart", "coupon"})
package io.github.cskwork.lunepick.order;
