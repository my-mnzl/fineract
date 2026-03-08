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
package co.mnzl.fineract.custom.security.starter;

import co.mnzl.fineract.custom.security.starter.converter.CustomJwtAuthenticationTokenConverter;
import org.apache.fineract.infrastructure.security.service.TenantAwareJpaPlatformUserDetailsService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;

@AutoConfiguration
@ConditionalOnProperty(name = "mnzl.security.enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(MnzlSecurityProperties.class)
public class CustomSecurityAutoConfiguration {

    @Bean
    @Primary
    public Converter<Jwt, AbstractAuthenticationToken> customJwtAuthenticationTokenConverter(
            TenantAwareJpaPlatformUserDetailsService userDetailsService, MnzlSecurityProperties mnzlSecurityProperties) {
        return new CustomJwtAuthenticationTokenConverter(userDetailsService, mnzlSecurityProperties);
    }
}
