package io.github.cskwork.lunepick.address.internal.persistence;

import io.github.cskwork.lunepick.address.AddressApi.Address;
import io.github.cskwork.lunepick.address.internal.application.AddressStore;
import io.github.cskwork.lunepick.address.internal.application.CreateAddress;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAddressStore implements AddressStore {
    private static final String COLUMNS =
            "id,recipient_name,phone,zipcode,address_main,address_detail,is_default";
    private final JdbcClient jdbc;

    public JdbcAddressStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void lockMember(long memberId) {
        // No existing address row exists for the first insert, so row locks alone are insufficient.
        jdbc.sql("select 1 from pg_advisory_xact_lock(hashtextextended(:key,0))")
                .param("key", "addresses:" + memberId)
                .query(Integer.class)
                .single();
    }

    public List<Address> list(long memberId) {
        return jdbc.sql("select " + COLUMNS + " from addresses where member_id=:member order by id")
                .param("member", memberId)
                .query(Address.class)
                .list();
    }

    public Optional<Address> owned(long memberId, long addressId) {
        return jdbc.sql("select " + COLUMNS + " from addresses where member_id=:member and id=:id")
                .param("member", memberId)
                .param("id", addressId)
                .query(Address.class)
                .optional();
    }

    public boolean hasAddresses(long memberId) {
        return jdbc.sql("select exists(select 1 from addresses where member_id=:member)")
                .param("member", memberId)
                .query(Boolean.class)
                .single();
    }

    public void clearDefault(long memberId) {
        jdbc.sql("update addresses set is_default=false where member_id=:member and is_default")
                .param("member", memberId)
                .update();
    }

    public Address create(long memberId, CreateAddress request, boolean isDefault) {
        return jdbc.sql(
                        "insert into"
                                + " addresses(member_id,recipient_name,phone,zipcode,address_main,address_detail,is_default)"
                                + " values(:member,:recipient,:phone,:zipcode,:main,:detail,:isDefault) returning "
                                + COLUMNS)
                .param("member", memberId)
                .param("recipient", request.recipientName())
                .param("phone", request.phone())
                .param("zipcode", request.zipcode())
                .param("main", request.addressMain())
                .param("detail", request.addressDetail())
                .param("isDefault", isDefault)
                .query(Address.class)
                .single();
    }
}
