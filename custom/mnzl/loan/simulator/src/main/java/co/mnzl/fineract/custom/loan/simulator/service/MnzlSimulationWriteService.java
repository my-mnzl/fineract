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
package co.mnzl.fineract.custom.loan.simulator.service;

import co.mnzl.fineract.custom.loan.simulator.data.SchedulePreviewPeriod;
import co.mnzl.fineract.custom.loan.simulator.data.SimulationResult;
import java.util.List;

public interface MnzlSimulationWriteService {

    SimulationResult runSimulation(String json);

    SimulationResult rerunSimulation(String uuid);

    void deleteSimulation(String uuid);

    /** Return the repayment schedule Fineract would produce for this request — without creating any loan. */
    List<SchedulePreviewPeriod> previewSchedule(String json);
}
