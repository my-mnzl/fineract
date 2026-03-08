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
package co.mnzl.fineract.custom.security.starter.token;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import org.apache.fineract.infrastructure.security.data.FineractJwtAuthenticationToken;
import org.apache.fineract.useradministration.domain.AppUser;
import org.apache.fineract.useradministration.domain.Permission;
import org.apache.fineract.useradministration.domain.Role;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.jwt.Jwt;

public class MnzlJwtAuthenticationToken extends FineractJwtAuthenticationToken {

    public MnzlJwtAuthenticationToken(Jwt jwt, Collection<GrantedAuthority> authorities, UserDetails user, boolean useJwtPermissions) {
        super(jwt, authorities, user);
        if (useJwtPermissions && user instanceof AppUser appUser) {
            Role role = new Role("JWT", "JWT");
            for (GrantedAuthority authority : authorities) {
                String permissionCode = authority.getAuthority();
                if (permissionCode == null) {
                    continue;
                }
                int separatorIndex = permissionCode.indexOf('_');
                if (separatorIndex <= 0 || separatorIndex >= permissionCode.length() - 1
                        || permissionCode.indexOf('_', separatorIndex + 1) != -1) {
                    continue;
                }
                Permission permission = new Permission("JWT", permissionCode.substring(separatorIndex + 1),
                        permissionCode.substring(0, separatorIndex));
                role.updatePermission(permission, true);
            }
            appUser.updateRoles(new HashSet<>(Collections.singleton(role)));
        }
    }

    @Override
    public AbstractAuthenticationToken withAuthorities(Collection<? extends GrantedAuthority> authorities) {
        return new MnzlJwtAuthenticationToken(getToken(), authorities.stream().map(GrantedAuthority.class::cast).toList(), getUserDetails(),
                false);
    }
}
