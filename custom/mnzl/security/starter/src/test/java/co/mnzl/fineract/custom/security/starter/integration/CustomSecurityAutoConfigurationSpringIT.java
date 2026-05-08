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
package co.mnzl.fineract.custom.security.starter.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import co.mnzl.fineract.custom.security.starter.CustomSecurityAutoConfiguration;
import co.mnzl.fineract.custom.security.starter.MnzlSecurityProperties;
import co.mnzl.fineract.custom.security.starter.converter.CustomJwtAuthenticationTokenConverter;
import org.apache.fineract.infrastructure.security.domain.PlatformUserRepository;
import org.apache.fineract.infrastructure.security.service.TenantAwareJpaPlatformUserDetailsService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * L2 Spring slice: verifies the mnzl JWT auth converter wires as {@code @Primary} and that
 * {@link MnzlSecurityProperties} binds to the expected defaults.
 */
class CustomSecurityAutoConfigurationSpringIT {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CustomSecurityAutoConfiguration.class))
            // The mock subclass of TenantAwareJpaPlatformUserDetailsService inherits its @Autowired field on
            // PlatformUserRepository, so Spring still post-processes the mock and fails wiring without a
            // PlatformUserRepository bean on the slice. Provide a mock for that too.
            .withBean(TenantAwareJpaPlatformUserDetailsService.class, () -> mock(TenantAwareJpaPlatformUserDetailsService.class))
            .withBean(PlatformUserRepository.class, () -> mock(PlatformUserRepository.class));

    @Test
    void mnzlSecurityEnabled_customJwtConverterRegistered() {
        contextRunner.withPropertyValues("mnzl.security.enabled=true").run(ctx -> {
            assertThat(ctx).hasSingleBean(CustomSecurityAutoConfiguration.class);
            // The converter is exposed as Converter<Jwt, AbstractAuthenticationToken>.
            Converter<Jwt, AbstractAuthenticationToken> converter = ctx.getBean("customJwtAuthenticationTokenConverter", Converter.class);
            assertThat(converter).isInstanceOf(CustomJwtAuthenticationTokenConverter.class);
        });
    }

    @Test
    void mnzlSecurityEnabled_default_customJwtConverterRegistered() {
        // matchIfMissing=true — without setting the flag, the auto-config still loads.
        contextRunner.run(ctx -> {
            assertThat(ctx).hasSingleBean(CustomSecurityAutoConfiguration.class);
            assertThat(ctx).hasBean("customJwtAuthenticationTokenConverter");
        });
    }

    @Test
    void mnzlSecurityDisabled_customJwtConverterAbsent() {
        contextRunner.withPropertyValues("mnzl.security.enabled=false").run(ctx -> {
            assertThat(ctx).doesNotHaveBean(CustomSecurityAutoConfiguration.class);
            assertThat(ctx).doesNotHaveBean("customJwtAuthenticationTokenConverter");
            assertThat(ctx).doesNotHaveBean(MnzlSecurityProperties.class);
        });
    }

    @Test
    void mnzlSecurityProperties_bound() {
        contextRunner.run(ctx -> {
            MnzlSecurityProperties props = ctx.getBean(MnzlSecurityProperties.class);
            // Defaults declared in MnzlSecurityProperties.
            assertThat(props.isEnabled()).isTrue();
            assertThat(props.getJwt()).isNotNull();
            assertThat(props.getJwt().isUsePermissions()).isFalse();
            assertThat(props.getJwt().getPermissionsClaimName()).isEqualTo("permissions");
            assertThat(props.getJwt().isFallbackToDatabasePermissions()).isTrue();
        });
    }

    @Test
    void mnzlSecurityProperties_overrides() {
        contextRunner.withPropertyValues("mnzl.security.jwt.usePermissions=true", "mnzl.security.jwt.permissionsClaimName=scopes",
                "mnzl.security.jwt.fallbackToDatabasePermissions=false").run(ctx -> {
                    MnzlSecurityProperties props = ctx.getBean(MnzlSecurityProperties.class);
                    assertThat(props.getJwt().isUsePermissions()).isTrue();
                    assertThat(props.getJwt().getPermissionsClaimName()).isEqualTo("scopes");
                    assertThat(props.getJwt().isFallbackToDatabasePermissions()).isFalse();
                });
    }

}
