/*
 * SPDX-FileCopyrightText: 2026 CESSDA ERIC (support@cessda.eu)
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *    http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package eu.cessda.pilotnode;

import java.util.logging.Level;
import java.util.logging.Logger;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fires {@link CheckRunnerController#runCheckAll()} (the same "Check All"
 * job triggered from the dashboard's Run checks menu) on a cron schedule,
 * so the full set of checks can be kept up to date without an operator
 * clicking the button.
 *
 * <h2>Configuration (application.properties)</h2>
 * <pre>
 *   check.check-all.scheduled.enabled = false               # off by default
 *   check.check-all.scheduled.cron    = 0 0 3 * * *          # daily at 03:00
 * </pre>
 *
 * <p>The cron expression is always registered with Spring's scheduler (it
 * must be valid even when disabled), but {@code
 * check.check-all.scheduled.enabled} is checked on every firing, so
 * toggling it — followed by a restart, since {@code application.properties}
 * is not hot-reloaded — turns the scheduled run on or off without touching
 * {@code check.check-all.scheduled.cron}.</p>
 *
 * <p>A run started this way shows up alongside manually-triggered jobs at
 * {@code GET /api/run/status}, since it goes through the same
 * {@link JobRunner}-tracked code path as the "Check All" button.</p>
 */
@Component
public class CheckAllScheduler {

    private static final Logger log = Logger.getLogger(CheckAllScheduler.class.getName());

    private final boolean enabled;
    private final CheckRunnerController checkRunnerController;

    public CheckAllScheduler(
            @Value("${check.check-all.scheduled.enabled:false}") boolean enabled,
            CheckRunnerController checkRunnerController) {
        this.enabled = enabled;
        this.checkRunnerController = checkRunnerController;
    }

    @Scheduled(cron = "${check.check-all.scheduled.cron:0 0 3 * * *}")
    public void runScheduledCheckAll() {
        if (!enabled) {
            log.fine("Scheduled Check All run skipped — check.check-all.scheduled.enabled is false");
            return;
        }

        log.info("Scheduled Check All run starting");
        try {
            checkRunnerController.runCheckAll();
        } catch (Exception e) {
            // runCheckAll() only throws before a job is even started (e.g.
            // missing check.api-key-node); anything the job itself does
            // once running is captured on its JobRecord, not here.
            log.log(Level.WARNING, "Scheduled Check All run failed to start: " + e.getMessage(), e);
        }
    }
}
