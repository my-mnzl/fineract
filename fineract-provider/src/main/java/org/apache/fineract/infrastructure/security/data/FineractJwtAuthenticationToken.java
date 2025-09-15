/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.fineract.infrastructure.security.data;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import org.apache.fineract.useradministration.domain.AppUser;
import org.apache.fineract.useradministration.domain.Permission;
import org.apache.fineract.useradministration.domain.Role;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

public class FineractJwtAuthenticationToken extends JwtAuthenticationToken {

    private final UserDetails user;

    public FineractJwtAuthenticationToken(Jwt jwt, Collection<GrantedAuthority> authorities, UserDetails user) {
        this(jwt, authorities, user, false);
    }

    public FineractJwtAuthenticationToken(Jwt jwt, Collection<GrantedAuthority> authorities, UserDetails user, boolean useJwtPermissions) {
        super(jwt, authorities, user.getUsername());
        this.user = Objects.requireNonNull(user, "user");

        if (useJwtPermissions) {
            Role role = new Role("JWT", "JWT");
            for (GrantedAuthority authority : authorities) {
                String permissionCode = authority.getAuthority();
                if (permissionCode == null) {
                    continue;
                }

                String[] parts = permissionCode.split("_");
                if (parts.length != 2) {
                    continue;
                }

                Permission permission = new Permission("JWT", parts[1], parts[0]);
                role.updatePermission(permission, true);
            }
            ((AppUser) user).updateRoles(new HashSet<>(Collections.singleton(role)));
        }
    }

    @Override
    public UserDetails getPrincipal() {
        return user;
    }
}
