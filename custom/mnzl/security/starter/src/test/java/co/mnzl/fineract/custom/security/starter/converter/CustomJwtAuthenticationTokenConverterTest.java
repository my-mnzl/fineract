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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.mnzl.fineract.custom.security.starter.MnzlSecurityProperties;
import co.mnzl.fineract.custom.security.starter.token.MnzlJwtAuthenticationToken;
import java.util.List;
import java.util.Set;
import org.apache.fineract.infrastructure.security.service.TenantAwareJpaPlatformUserDetailsService;
import org.apache.fineract.useradministration.domain.AppUser;
import org.apache.fineract.useradministration.domain.Role;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.jwt.Jwt;

class CustomJwtAuthenticationTokenConverterTest {

    @Test
    void convertUsesPermissionsClaimWhenEnabled() {
        TenantAwareJpaPlatformUserDetailsService userDetailsService = mock(TenantAwareJpaPlatformUserDetailsService.class);
        AppUser user = mock(AppUser.class);
        when(user.getUsername()).thenReturn("custom-user");
        when(userDetailsService.loadUserByUsername("custom-user")).thenReturn(user);

        MnzlSecurityProperties properties = properties(true, "permissions", false);
        CustomJwtAuthenticationTokenConverter converter = new CustomJwtAuthenticationTokenConverter(userDetailsService, properties);

        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject("custom-user")
                .claim("permissions", List.of("READ_LOAN", "CREATE_LOAN")).build();

        MnzlJwtAuthenticationToken token = (MnzlJwtAuthenticationToken) converter.convert(jwt);

        assertThat(token.getAuthorities()).extracting("authority").containsExactlyInAnyOrder("READ_LOAN", "CREATE_LOAN");
        assertThat(token.getPrincipal()).isSameAs(user);
    }

    @Test
    void convertFallsBackToJwtScopeAuthoritiesWhenPermissionsClaimMissing() {
        TenantAwareJpaPlatformUserDetailsService userDetailsService = mock(TenantAwareJpaPlatformUserDetailsService.class);
        UserDetails user = mock(UserDetails.class);
        when(user.getUsername()).thenReturn("custom-user");
        when(userDetailsService.loadUserByUsername("custom-user")).thenReturn(user);

        MnzlSecurityProperties properties = properties(true, "permissions", true);
        CustomJwtAuthenticationTokenConverter converter = new CustomJwtAuthenticationTokenConverter(userDetailsService, properties);

        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject("custom-user").claim("scope", List.of("READ_CLIENT")).build();

        MnzlJwtAuthenticationToken token = (MnzlJwtAuthenticationToken) converter.convert(jwt);

        assertThat(token.getAuthorities()).extracting("authority").containsExactly("READ_CLIENT");
        assertThat(token.getPrincipal()).isSameAs(user);
    }

    private MnzlSecurityProperties properties(boolean useJwtPermissions, String claimName, boolean fallback) {
        MnzlSecurityProperties properties = new MnzlSecurityProperties();
        properties.getJwt().setUsePermissions(useJwtPermissions);
        properties.getJwt().setPermissionsClaimName(claimName);
        properties.getJwt().setFallbackToDatabasePermissions(fallback);
        return properties;
    }

    @Test
    void usePermissionsFalse_delegatesToUpstream_flagFalse() {
        TenantAwareJpaPlatformUserDetailsService userDetailsService = mock(TenantAwareJpaPlatformUserDetailsService.class);
        AppUser user = mock(AppUser.class);
        when(user.getUsername()).thenReturn("custom-user");
        when(userDetailsService.loadUserByUsername("custom-user")).thenReturn(user);

        MnzlSecurityProperties properties = properties(false, "permissions", true);
        CustomJwtAuthenticationTokenConverter converter = new CustomJwtAuthenticationTokenConverter(userDetailsService, properties);

        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject("custom-user").claim("permissions", List.of("PERM_A", "PERM_B"))
                .claim("scope", List.of("READ_CLIENT")).build();

        MnzlJwtAuthenticationToken token = (MnzlJwtAuthenticationToken) converter.convert(jwt);

        // usePermissions=false → delegate (scope) wins, JWT permissions claim is ignored
        assertThat(token.getAuthorities()).extracting("authority").containsExactly("READ_CLIENT");
        // useJwtPermissions=false → AppUser.updateRoles is never invoked
        verify(user, never()).updateRoles(org.mockito.ArgumentMatchers.<Set<Role>>any());
    }

    @Test
    void usePermissionsTrue_stringClaim_extractsPermissions() {
        TenantAwareJpaPlatformUserDetailsService userDetailsService = mock(TenantAwareJpaPlatformUserDetailsService.class);
        AppUser user = mock(AppUser.class);
        when(user.getUsername()).thenReturn("custom-user");
        when(userDetailsService.loadUserByUsername("custom-user")).thenReturn(user);

        MnzlSecurityProperties properties = properties(true, "permissions", false);
        CustomJwtAuthenticationTokenConverter converter = new CustomJwtAuthenticationTokenConverter(userDetailsService, properties);

        // Production does NOT split a String claim — entire string becomes a single authority.
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject("custom-user").claim("permissions", "READ_LOAN").build();

        MnzlJwtAuthenticationToken token = (MnzlJwtAuthenticationToken) converter.convert(jwt);

        assertThat(token.getAuthorities()).extracting("authority").containsExactly("READ_LOAN");
    }

    @Test
    void usePermissionsTrue_stringArrayClaim_extractsPermissions() {
        TenantAwareJpaPlatformUserDetailsService userDetailsService = mock(TenantAwareJpaPlatformUserDetailsService.class);
        AppUser user = mock(AppUser.class);
        when(user.getUsername()).thenReturn("custom-user");
        when(userDetailsService.loadUserByUsername("custom-user")).thenReturn(user);

        MnzlSecurityProperties properties = properties(true, "permissions", false);
        CustomJwtAuthenticationTokenConverter converter = new CustomJwtAuthenticationTokenConverter(userDetailsService, properties);

        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject("custom-user")
                .claim("permissions", new String[] { "PERM_A", "PERM_B" }).build();

        MnzlJwtAuthenticationToken token = (MnzlJwtAuthenticationToken) converter.convert(jwt);

        assertThat(token.getAuthorities()).extracting("authority").containsExactlyInAnyOrder("PERM_A", "PERM_B");
        // useJwtPermissions=true with AppUser principal triggers role update
        verify(user).updateRoles(org.mockito.ArgumentMatchers.<Set<Role>>any());
    }

    @Test
    void usePermissionsTrue_claimMissing_fallbackFalse_emptyAuthorities() {
        TenantAwareJpaPlatformUserDetailsService userDetailsService = mock(TenantAwareJpaPlatformUserDetailsService.class);
        AppUser user = mock(AppUser.class);
        when(user.getUsername()).thenReturn("custom-user");
        when(userDetailsService.loadUserByUsername("custom-user")).thenReturn(user);

        MnzlSecurityProperties properties = properties(true, "permissions", false);
        CustomJwtAuthenticationTokenConverter converter = new CustomJwtAuthenticationTokenConverter(userDetailsService, properties);

        // permissions claim missing, scope present — fallback disabled, so authorities must be empty
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject("custom-user").claim("scope", List.of("READ_CLIENT")).build();

        MnzlJwtAuthenticationToken token = (MnzlJwtAuthenticationToken) converter.convert(jwt);

        assertThat(token.getAuthorities()).isEmpty();
        // flag should remain true; with empty authorities, updateRoles is still called with an empty JWT role
        verify(user).updateRoles(org.mockito.ArgumentMatchers.<Set<Role>>any());
    }

    @Test
    void usePermissionsTrue_customClaimName_honored() {
        TenantAwareJpaPlatformUserDetailsService userDetailsService = mock(TenantAwareJpaPlatformUserDetailsService.class);
        AppUser user = mock(AppUser.class);
        when(user.getUsername()).thenReturn("custom-user");
        when(userDetailsService.loadUserByUsername("custom-user")).thenReturn(user);

        MnzlSecurityProperties properties = properties(true, "roles", false);
        CustomJwtAuthenticationTokenConverter converter = new CustomJwtAuthenticationTokenConverter(userDetailsService, properties);

        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject("custom-user").claim("roles", List.of("A", "B"))
                .claim("permissions", List.of("X", "Y")).build();

        MnzlJwtAuthenticationToken token = (MnzlJwtAuthenticationToken) converter.convert(jwt);

        assertThat(token.getAuthorities()).extracting("authority").containsExactlyInAnyOrder("A", "B");
    }

    @Test
    void tokenIsMnzlJwtAuthenticationToken_withCorrectFlag() {
        TenantAwareJpaPlatformUserDetailsService userDetailsService = mock(TenantAwareJpaPlatformUserDetailsService.class);
        AppUser user = mock(AppUser.class);
        when(user.getUsername()).thenReturn("custom-user");
        when(userDetailsService.loadUserByUsername("custom-user")).thenReturn(user);

        MnzlSecurityProperties properties = properties(true, "permissions", false);
        CustomJwtAuthenticationTokenConverter converter = new CustomJwtAuthenticationTokenConverter(userDetailsService, properties);

        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject("custom-user").claim("permissions", List.of("READ_LOAN"))
                .build();

        var result = converter.convert(jwt);

        assertThat(result).isExactlyInstanceOf(MnzlJwtAuthenticationToken.class);
        // flag is not exposed via getter; verify side effect — useJwtPermissions=true triggers AppUser.updateRoles
        verify(user).updateRoles(org.mockito.ArgumentMatchers.<Set<Role>>any());
    }
}
