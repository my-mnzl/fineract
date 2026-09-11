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
package co.mnzl.fineract.custom.receivables.service;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.Map;
import org.springframework.stereotype.Component;

@Provider
@Component
public class ReceivablesExceptionMapper implements ExceptionMapper<ReceivablesException> {

    @Override
    public Response toResponse(ReceivablesException exception) {
        int status = switch (exception.code()) {
            case "INVALID_DATA" -> 400;
            case "OWNERSHIP_CONFLICT" -> 403;
            case "BANK_PROOF_MISMATCH" -> 422;
            default -> 409;
        };
        return Response.status(status).type(MediaType.APPLICATION_JSON)
                .entity(Map.of("code", exception.code(), "message", exception.code())).build();
    }
}
