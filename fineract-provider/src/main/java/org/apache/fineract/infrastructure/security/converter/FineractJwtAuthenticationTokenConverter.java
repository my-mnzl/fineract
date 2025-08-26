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
package org.apache.fineract.infrastructure.security.converter;

import java.util.ArrayList;
import java.util.Collection;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.config.FineractProperties; // pragma: allowlist secret
import org.apache.fineract.infrastructure.security.data.FineractJwtAuthenticationToken;
import org.apache.fineract.infrastructure.security.service.TenantAwareJpaPlatformUserDetailsService;
import org.springframework.core.convert.converter.Converter;
import org.springframework.lang.NonNull;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

@RequiredArgsConstructor
public class FineractJwtAuthenticationTokenConverter implements Converter<Jwt, FineractJwtAuthenticationToken> {

    private final TenantAwareJpaPlatformUserDetailsService userDetailsService;
    private final FineractProperties fineractProperties; // pragma: allowlist secret

    @Override
    @NonNull
    public FineractJwtAuthenticationToken convert(@NonNull Jwt jwt) {
        try {
            UserDetails user = userDetailsService.loadUserByUsername(jwt.getSubject());
            JwtGrantedAuthoritiesConverter jwtGrantedAuthoritiesConverter = new JwtGrantedAuthoritiesConverter();
            jwtGrantedAuthoritiesConverter.setAuthorityPrefix("");

            boolean useJwtPermissions = fineractProperties.getSecurity().getOauth2().isUseJwtPermissions(); // pragma: allowlist secret
            Collection<GrantedAuthority> authorities;
            if (useJwtPermissions) {
                authorities = extractPermissionsFromJwt(jwt);
                if (authorities.isEmpty() && fineractProperties.getSecurity().getOauth2().isFallbackToDatabasePermissions()) { // pragma: allowlist secret
                    useJwtPermissions = false;
                    authorities = jwtGrantedAuthoritiesConverter.convert(jwt);
                }
            } else {
                authorities = jwtGrantedAuthoritiesConverter.convert(jwt);
            }

            return new FineractJwtAuthenticationToken(jwt, authorities, user, useJwtPermissions); // pragma: allowlist secret
        } catch (UsernameNotFoundException ex) {
            throw new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN), ex);
        }
    }

    private Collection<GrantedAuthority> extractPermissionsFromJwt(Jwt jwt) {
        Collection<GrantedAuthority> authorities = new ArrayList<>();
        String permissionClaimName = fineractProperties.getSecurity().getOauth2().getJwtPermissionsClaimName(); // pragma: allowlist secret
        if (permissionClaimName == null) {
            return authorities;
        }

        Object claimValue = jwt.getClaim(permissionClaimName);
        if (claimValue != null) {
            authorities.addAll(convertClaimToAuthorities(claimValue));
        }
        return authorities;
    }

    private Collection<GrantedAuthority> convertClaimToAuthorities(Object claimValue) {
        Collection<GrantedAuthority> authorities = new ArrayList<>();
        if (claimValue instanceof String permission) {
            authorities.add(new SimpleGrantedAuthority(permission));
        } else if (claimValue instanceof Collection<?> items) {
            for (Object item : items) {
                if (item instanceof String permission) {
                    authorities.add(new SimpleGrantedAuthority(permission));
                }
            }
        } else if (claimValue instanceof String[] permissions) {
            for (String permission : permissions) {
                authorities.add(new SimpleGrantedAuthority(permission));
            }
        }
        return authorities;
    }
}
