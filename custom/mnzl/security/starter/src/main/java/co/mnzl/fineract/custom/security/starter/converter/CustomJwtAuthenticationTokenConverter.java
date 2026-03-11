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
package co.mnzl.fineract.custom.security.starter.converter;

import co.mnzl.fineract.custom.security.starter.MnzlSecurityProperties;
import co.mnzl.fineract.custom.security.starter.token.MnzlJwtAuthenticationToken;
import java.util.ArrayList;
import java.util.Collection;
import org.apache.fineract.infrastructure.security.converter.FineractJwtAuthenticationTokenConverter;
import org.apache.fineract.infrastructure.security.service.TenantAwareJpaPlatformUserDetailsService;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

public class CustomJwtAuthenticationTokenConverter extends FineractJwtAuthenticationTokenConverter {

    private final TenantAwareJpaPlatformUserDetailsService userDetailsService;
    private final MnzlSecurityProperties mnzlSecurityProperties;

    public CustomJwtAuthenticationTokenConverter(TenantAwareJpaPlatformUserDetailsService userDetailsService,
            MnzlSecurityProperties mnzlSecurityProperties) {
        super(userDetailsService);
        this.userDetailsService = userDetailsService;
        this.mnzlSecurityProperties = mnzlSecurityProperties;
    }

    @Override
    @NonNull
    public AbstractAuthenticationToken convert(@NonNull Jwt jwt) {
        try {
            UserDetails user = userDetailsService.loadUserByUsername(jwt.getSubject());
            JwtGrantedAuthoritiesConverter jwtGrantedAuthoritiesConverter = new JwtGrantedAuthoritiesConverter();
            jwtGrantedAuthoritiesConverter.setAuthorityPrefix("");

            boolean useJwtPermissions = mnzlSecurityProperties.getJwt().isUsePermissions();
            Collection<GrantedAuthority> authorities;
            if (useJwtPermissions) {
                authorities = extractPermissionsFromJwt(jwt);
                if (authorities.isEmpty() && mnzlSecurityProperties.getJwt().isFallbackToDatabasePermissions()) {
                    useJwtPermissions = false;
                    authorities = jwtGrantedAuthoritiesConverter.convert(jwt);
                }
            } else {
                authorities = jwtGrantedAuthoritiesConverter.convert(jwt);
            }

            return new MnzlJwtAuthenticationToken(jwt, authorities, user, useJwtPermissions);
        } catch (UsernameNotFoundException ex) {
            throw new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN), ex);
        }
    }

    private Collection<GrantedAuthority> extractPermissionsFromJwt(Jwt jwt) {
        Collection<GrantedAuthority> authorities = new ArrayList<>();
        String permissionClaimName = mnzlSecurityProperties.getJwt().getPermissionsClaimName();
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
