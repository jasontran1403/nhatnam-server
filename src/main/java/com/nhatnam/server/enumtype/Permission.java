package com.nhatnam.server.enumtype;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum Permission {

    SUPERADMIN_READ("admin:read"),
    SUPERADMIN_UPDATE("admin:update"),
    SUPERADMIN_CREATE("admin:create"),
    SUPERADMIN_DELETE("admin:delete"),

    ADMIN_READ("admin:read"),
    ADMIN_UPDATE("admin:update"),
    ADMIN_CREATE("admin:create"),
    ADMIN_DELETE("admin:delete"),

    ACCOUNTANT_READ("accountant:read"),
    ACCOUNTANT_UPDATE("accountant:update"),
    ACCOUNTANT_CREATE("accountant:create"),
    ACCOUNTANT_DELETE("accountant:delete"),

    SELLER_READ("seller:read"),
    SELLER_UPDATE("seller:update"),
    SELLER_CREATE("seller:create"),
    SELLER_DELETE("seller:delete"),

    WAREHOUSER_READ("warehouse:read"),
    WAREHOUSER_UPDATE("warehouse:update"),
    WAREHOUSER_CREATE("warehouse:create"),
    WAREHOUSER_DELETE("warehouse:delete"),

    SHIPPER_READ("shipper:read"),
    SHIPPER_UPDATE("shipper:update"),
    SHIPPER_CREATE("shipper:create"),
    SHIPPER_DELETE("shipper:delete"),

    POS_READ("pos:read"),
    POS_UPDATE("pos:update"),
    POS_CREATE("pos:create"),
    POS_DELETE("pos:delete"),

    USER_READ("user:read"),
    USER_UPDATE("user:update"),
    USER_CREATE("user:create"),
    USER_DELETE("user:delete"),

    ;

    @Getter
    private final String permission;
}
