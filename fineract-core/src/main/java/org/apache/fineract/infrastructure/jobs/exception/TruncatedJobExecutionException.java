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

package org.apache.fineract.infrastructure.jobs.exception;

import java.util.List;
import java.io.PrintStream;
import java.io.PrintWriter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TruncatedJobExecutionException extends JobExecutionException {

    private static final int MAX_ERROR_LOG_LENGTH = 60000; // Fit 64k column size

    public TruncatedJobExecutionException(List<Throwable> problems) {
        super(problems);
    }

    @Override
    public String getMessage() {
        String truncated = truncateMessage(super.getMessage());
        int totalErrors = getCauses().size();
        return truncated + "\n\n[ERROR LOG TRUNCATED - Total errors: " + totalErrors +
                " - Full details logged separately]";
    }

    @Override
    public void printStackTrace() {
        log.error("{}", getMessage());
    }

    @Override
    public void printStackTrace(PrintStream s) {
        s.println(getMessage());
    }

    @Override
    public void printStackTrace(PrintWriter s) {
        s.println(getMessage());
    }

    private String truncateMessage(String message) {
        byte[] fullBytes = message.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String truncatedMessage;
        if (fullBytes.length <= MAX_ERROR_LOG_LENGTH) {
            truncatedMessage = message;
        } else {
            // Find the largest substring whose UTF-8 bytes fit within the limit
            int end = message.length();
            int low = 0, high = end;
            while (low < high) {
                int mid = (low + high + 1) / 2;
                String sub = message.substring(0, mid);
                if (sub.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= MAX_ERROR_LOG_LENGTH) {
                    low = mid;
                } else {
                    high = mid - 1;
                }
            }
            truncatedMessage = message.substring(0, low);
        }
        return truncatedMessage;
    }
}
