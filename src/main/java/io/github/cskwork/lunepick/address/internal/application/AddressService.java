package io.github.cskwork.lunepick.address.internal.application;

import io.github.cskwork.lunepick.address.AddressApi;
import io.github.cskwork.lunepick.shared.ApiException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class AddressService implements AddressApi {
    private final AddressStore store;

    public AddressService(AddressStore store) {
        this.store = store;
    }

    @Transactional(readOnly = true)
    public List<Address> list(long memberId) {
        return store.list(memberId);
    }

    @Transactional(readOnly = true)
    public Address owned(long memberId, long addressId) {
        return store.owned(memberId, addressId)
                .orElseThrow(() -> new ApiException(404, "ADDRESS_NOT_FOUND", "배송지를 찾을 수 없습니다."));
    }

    public Address create(long memberId, CreateAddress request) {
        store.lockMember(memberId);
        boolean isDefault = request.isDefault() || !store.hasAddresses(memberId);
        if (isDefault) store.clearDefault(memberId);
        return store.create(memberId, request, isDefault);
    }
}
