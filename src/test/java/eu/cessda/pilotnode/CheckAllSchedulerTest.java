package eu.cessda.pilotnode;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

import eu.cessda.pilotnode.common.CheckMode;

/** The scheduled Check All runs in the mode the configuration names. */
class CheckAllSchedulerTest {

    private final CheckRunnerController controller = mock(CheckRunnerController.class);

    @Test
    void aScheduledRunIsLenientByDefault() {
        new CheckAllScheduler(true, "lenient", controller).runScheduledCheckAll();
        verify(controller).startCheckAll(CheckMode.LENIENT);
    }

    @Test
    void aBlankModeIsLenient() {
        new CheckAllScheduler(true, "", controller).runScheduledCheckAll();
        verify(controller).startCheckAll(CheckMode.LENIENT);
    }

    @Test
    void aStrictScheduledRunIsStrict() {
        new CheckAllScheduler(true, "strict", controller).runScheduledCheckAll();
        verify(controller).startCheckAll(CheckMode.STRICT);
    }

    @Test
    void theModeIsReadWhateverItsCase() {
        new CheckAllScheduler(true, " Strict ", controller).runScheduledCheckAll();
        verify(controller).startCheckAll(CheckMode.STRICT);
    }

    @Test
    void nothingRunsWhenSchedulingIsOff() {
        new CheckAllScheduler(false, "strict", controller).runScheduledCheckAll();
        verify(controller, never()).startCheckAll(any());
    }

    @Test
    void anUnknownModeStopsTheApplicationStartingAndNamesTheSetting() {
        var e = assertThrows(IllegalArgumentException.class, () -> new CheckAllScheduler(true, "harsh", controller));
        assertTrue(e.getMessage().contains("check.check-all.scheduled.mode"), e.getMessage());
        assertTrue(e.getMessage().contains("harsh"), e.getMessage());
    }
}
