package io.github.cskwork.lunepick.address.internal.application;

import io.github.cskwork.lunepick.address.AddressApi.Address;
import java.util.List;
import java.util.Optional;

public interface AddressStore {
    void lockMember(long memberId);

    List<Address> list(long memberId);

    Optional<Address> owned(long memberId, long addressId);

    boolean hasAddresses(long memberId);

    void clearDefault(long memberId);

    Address create(long memberId, CreateAddress request, boolean isDefault);
}
