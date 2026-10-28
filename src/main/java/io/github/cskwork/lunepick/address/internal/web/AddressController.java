package io.github.cskwork.lunepick.address.internal.web;

import io.github.cskwork.lunepick.address.AddressApi.Address;
import io.github.cskwork.lunepick.address.internal.application.*;
import io.github.cskwork.lunepick.shared.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/me/addresses")
public class AddressController {
    private final AddressService addresses;

    public AddressController(AddressService addresses) {
        this.addresses = addresses;
    }

    @GetMapping
    @Operation(summary = "내 배송지 목록", description = "인증 회원의 배송지를 등록 순서로 조회합니다.")
    ApiResponse<List<Address>> list(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(addresses.list(Long.parseLong(jwt.getSubject())));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "배송지 등록", description = "공백과 휴대전화 번호를 정규화하고 최초 배송지를 기본 배송지로 지정합니다.")
    ApiResponse<Address> create(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateAddress request) {
        return ApiResponse.ok(addresses.create(Long.parseLong(jwt.getSubject()), request));
    }
}
