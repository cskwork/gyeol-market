package io.github.cskwork.lunepick.address.internal.application;

import io.github.cskwork.lunepick.address.internal.domain.AddressNormalization;
import jakarta.validation.constraints.*;

public record CreateAddress(
        @NotBlank(message = "수령인을 입력해주세요.") @Size(max = 100, message = "수령인은 100자 이내로 입력해주세요.")
                String recipientName,
        @NotBlank(message = "연락처를 입력해주세요.")
                @Size(max = 20, message = "연락처는 20자 이내로 입력해주세요.")
                @Pattern(
                        regexp = "^01[016789]-\\d{3,4}-\\d{4}$",
                        message = "연락처를 010-1234-5678 형식으로 입력해주세요.")
                String phone,
        @NotBlank(message = "우편번호를 입력해주세요.") @Size(max = 10, message = "우편번호는 10자 이내로 입력해주세요.")
                String zipcode,
        @NotBlank(message = "기본 주소를 입력해주세요.") @Size(max = 255, message = "기본 주소는 255자 이내로 입력해주세요.")
                String addressMain,
        @Size(max = 255, message = "상세 주소는 255자 이내로 입력해주세요.") String addressDetail,
        Boolean isDefault) {
    public CreateAddress {
        isDefault = Boolean.TRUE.equals(isDefault);
        recipientName = AddressNormalization.trim(recipientName);
        phone = AddressNormalization.phone(phone);
        zipcode = AddressNormalization.trim(zipcode);
        addressMain = AddressNormalization.trim(addressMain);
        addressDetail = AddressNormalization.detail(addressDetail);
    }
}
