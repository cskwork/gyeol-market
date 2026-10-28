package io.github.cskwork.lunepick.address.internal.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.github.cskwork.lunepick.address.AddressApi.Address;
import io.github.cskwork.lunepick.shared.ApiException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AddressServiceTest {
    AddressStore store = mock(AddressStore.class);
    AddressService service = new AddressService(store);
    CreateAddress request =
            new CreateAddress(" 수령인 ", " 01012345678 ", " 01234 ", " 서울 ", " ", false);

    @Test
    void requestNormalizesAllFieldsAndFirstAddressBecomesDefaultUnderLock() {
        assertThat(request.recipientName()).isEqualTo("수령인");
        assertThat(request.phone()).isEqualTo("010-1234-5678");
        assertThat(request.zipcode()).isEqualTo("01234");
        assertThat(request.addressMain()).isEqualTo("서울");
        assertThat(request.addressDetail()).isNull();
        service.create(1, request);
        var order = inOrder(store);
        order.verify(store).lockMember(1);
        order.verify(store).hasAddresses(1);
        order.verify(store).clearDefault(1);
        order.verify(store).create(1, request, true);
    }

    @Test
    void laterNonDefaultAddressPreservesDefault() {
        when(store.hasAddresses(1)).thenReturn(true);
        service.create(1, request);
        verify(store, never()).clearDefault(1);
        verify(store).create(1, request, false);
    }

    @Test
    void explicitDefaultClearsPriorDefault() {
        var defaultRequest = new CreateAddress("이름", "01012345678", "12345", "주소", "상세", true);
        service.create(1, defaultRequest);
        verify(store).clearDefault(1);
        verify(store).create(1, defaultRequest, true);
    }

    @Test
    void readsAreScopedAndMissingOwnerReturns404() {
        var address = new Address(3, "이름", "010-1234-5678", "12345", "주소", null, true);
        when(store.list(1)).thenReturn(List.of(address));
        when(store.owned(1, 3)).thenReturn(Optional.of(address));
        assertThat(service.list(1)).containsExactly(address);
        assertThat(service.owned(1, 3)).isEqualTo(address);
        when(store.owned(2, 3)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.owned(2, 3))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> {
                            assertThat(e.status()).isEqualTo(404);
                            assertThat(e.code()).isEqualTo("ADDRESS_NOT_FOUND");
                        });
    }
}
