package io.github.cskwork.lunepick.address;

/** An immutable delivery address snapshot scoped to its owner. */
public interface AddressApi {
    Address owned(long memberId, long addressId);

    record Address(
            long id,
            String recipientName,
            String phone,
            String zipcode,
            String addressMain,
            String addressDetail,
            boolean isDefault) {}
}
