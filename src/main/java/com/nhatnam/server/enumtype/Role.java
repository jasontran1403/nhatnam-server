package com.nhatnam.server.enumtype;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.nhatnam.server.enumtype.Permission.*;

@RequiredArgsConstructor
public enum Role {

  UN_AUTH(Collections.emptySet()),
  SUPERADMIN(
            Set.of(
                    SUPERADMIN_READ,
                    SUPERADMIN_UPDATE,
                    SUPERADMIN_DELETE,
                    SUPERADMIN_CREATE
            )
    ),
  ADMIN(
          Set.of(
                  ADMIN_READ,
                  ADMIN_UPDATE,
                  ADMIN_DELETE,
                  ADMIN_CREATE,

                  ACCOUNTANT_READ,
                  ACCOUNTANT_UPDATE,
                  ACCOUNTANT_DELETE,
                  ACCOUNTANT_CREATE
          )
  ),
  ACCOUNTANT(
          Set.of(
                  ACCOUNTANT_READ,
                  ACCOUNTANT_UPDATE,
                  ACCOUNTANT_DELETE,
                  ACCOUNTANT_CREATE
          )
  ),
  WAREHOUSER(
          Set.of(
                  WAREHOUSER_READ,
                  WAREHOUSER_UPDATE,
                  WAREHOUSER_DELETE,
                  WAREHOUSER_CREATE
          )
  ),
    USER(
            Set.of(
                    USER_READ,
                    USER_UPDATE,
                    USER_DELETE,
                    USER_CREATE
            )
    ),
    SHIPPER(
            Set.of(
                    SHIPPER_READ,
                    SHIPPER_UPDATE,
                    SHIPPER_DELETE,
                    SHIPPER_CREATE
            )
    ),
    POS(
            Set.of(
                    POS_READ,
                    POS_UPDATE,
                    POS_DELETE,
                    POS_CREATE
            )
    ),
  SELLER(
          Set.of(
                  SELLER_READ,
                  SELLER_UPDATE,
                  SELLER_DELETE,
                  SELLER_CREATE
          )
  );


  @Getter
  private final Set<Permission> permissions;

  public List<SimpleGrantedAuthority> getAuthorities() {
    var authorities = getPermissions()
            .stream()
            .map(permission -> new SimpleGrantedAuthority(permission.getPermission()))
            .collect(Collectors.toList());
    authorities.add(new SimpleGrantedAuthority("ROLE_" + this.name()));
    return authorities;
  }
}
