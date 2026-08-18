package dev.termestra.workspace.adapter.out.filesystem.browse;

import dev.termestra.platform.process.BoundedProcessRunner;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessNativeFolderPickerTest {
    @Test void reportsMacAutomationDenialAsAPermissionFailure() {
        ProcessNativeFolderPicker picker = picker(
                new BoundedProcessRunner.Result(1,
                        "execution error: Not authorized to send Apple events. (-1743)", false, false));

        var result = picker.pick();

        assertFalse(result.canceled());
        assertTrue(result.supported());
        assertTrue(result.error().contains("Privacy & Security > Automation"));
    }

    @Test void treatsOnlyExplicitMacUserCancellationAsCanceled() {
        var numeric = picker(new BoundedProcessRunner.Result(1,
                "execution error: User canceled. (-128)", false, false)).pick();
        var textual = picker(new BoundedProcessRunner.Result(1,
                "User canceled the dialog", false, false)).pick();
        var generic = picker(new BoundedProcessRunner.Result(1,
                "execution error: disk unavailable", false, false)).pick();
        var blank = picker(new BoundedProcessRunner.Result(1, "", false, false)).pick();

        assertTrue(numeric.canceled());
        assertTrue(textual.canceled());
        assertNull(numeric.error());
        assertFalse(generic.canceled());
        assertTrue(generic.error().contains("disk unavailable"));
        assertFalse(blank.canceled());
        assertTrue(blank.error().contains("exit code 1"));
    }

    @Test void rejectsASecondPickerWhileTheFirstDialogIsOpen() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ProcessNativeFolderPicker picker = new ProcessNativeFolderPicker((command, timeout, maximum) -> {
            entered.countDown();
            release.await();
            return new BoundedProcessRunner.Result(0, "/workspace/\n", false, false);
        }, "Mac OS X");

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(picker::pick);
            assertTrue(entered.await(1, TimeUnit.SECONDS));

            var duplicate = picker.pick();
            release.countDown();

            assertFalse(duplicate.canceled());
            assertTrue(duplicate.error().contains("already open"));
            assertTrue(first.get(1, TimeUnit.SECONDS).path().endsWith("workspace"));
        } finally {
            release.countDown();
        }
    }

    private static ProcessNativeFolderPicker picker(BoundedProcessRunner.Result result) {
        return new ProcessNativeFolderPicker((command, timeout, maximum) -> result, "Mac OS X");
    }
}
