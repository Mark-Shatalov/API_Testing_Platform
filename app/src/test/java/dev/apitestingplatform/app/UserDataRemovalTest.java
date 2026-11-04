package dev.apitestingplatform.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.channels.FileChannel;
import java.nio.file.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class UserDataRemovalTest {
    @TempDir Path local;

    private Path populatedDataDirectory() throws Exception {
        Path data = Files.createDirectories(local.resolve(UserDataRemoval.FOLDER));
        Files.writeString(data.resolve("platform.sqlite"), "db");
        Files.writeString(data.resolve("platform.sqlite-wal"), "wal");
        Files.writeString(data.resolve("operations.properties"), "proxyMode=SYSTEM");
        Files.createDirectories(data.resolve("logs")); Files.writeString(data.resolve("logs").resolve("events-0.log"), "{}");
        Files.createDirectories(data.resolve("migration-backups"));
        Files.createDirectories(data.resolve("cache").resolve("0.1.0").resolve("openjfx"));
        return data;
    }

    @Test void removesTheCompleteDataFolderOnlyWhenExplicitlyRun() throws Exception {
        Path data = populatedDataDirectory();
        Path sibling = Files.writeString(local.resolve("Other application.txt"), "keep");

        UserDataRemoval.Result result = UserDataRemoval.remove(local, data);

        assertThat(result.outcome()).isEqualTo(UserDataRemoval.Outcome.REMOVED);
        assertThat(data).doesNotExist();
        assertThat(sibling).hasContent("keep");
    }

    @Test void refusesWhileTheApplicationHoldsItsInstanceLock() throws Exception {
        Path data = populatedDataDirectory();
        try (FileChannel running = FileChannel.open(data.resolve("platform.sqlite.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = running.lock()) {
            assertThat(lock.isValid()).isTrue();
            assertThat(UserDataRemoval.remove(local, data).outcome()).isEqualTo(UserDataRemoval.Outcome.IN_USE);
        }
        assertThat(data.resolve("platform.sqlite")).hasContent("db");
        assertThat(data.resolve("logs").resolve("events-0.log")).exists();
    }

    @Test void refusesFoldersOutsideTheExpectedLocation() throws Exception {
        Path unrelated = Files.createDirectories(local.resolve("Documents"));
        Files.writeString(unrelated.resolve("keep.txt"), "keep");
        Path nested = Files.createDirectories(local.resolve("x").resolve(UserDataRemoval.FOLDER));

        assertThat(UserDataRemoval.remove(local, unrelated).outcome()).isEqualTo(UserDataRemoval.Outcome.REFUSED);
        assertThat(UserDataRemoval.remove(local, nested).outcome()).isEqualTo(UserDataRemoval.Outcome.REFUSED);
        assertThat(UserDataRemoval.remove(local, local.resolve(UserDataRemoval.FOLDER).resolve("..").resolve("Documents")).outcome())
                .isEqualTo(UserDataRemoval.Outcome.REFUSED);
        assertThat(unrelated.resolve("keep.txt")).exists();
        assertThat(nested).exists();
    }

    @Test void reportsMissingDataWithoutCreatingIt() {
        Path data = local.resolve(UserDataRemoval.FOLDER);
        assertThat(UserDataRemoval.remove(local, data).outcome()).isEqualTo(UserDataRemoval.Outcome.NOTHING_TO_REMOVE);
        assertThat(data).doesNotExist();
    }

    @Test void removesLinksWithoutVisitingTheirTargets() throws Exception {
        Path data = populatedDataDirectory();
        Path outside = Files.createDirectories(local.resolve("outside"));
        Files.writeString(outside.resolve("keep.txt"), "keep");
        try { Files.createSymbolicLink(data.resolve("linked"), outside); }
        catch (UnsupportedOperationException | java.io.IOException | SecurityException noPrivilege) {
            assumeTrue(false, "Creating symbolic links requires Developer Mode or elevation on Windows");
        }

        assertThat(UserDataRemoval.remove(local, data).outcome()).isEqualTo(UserDataRemoval.Outcome.REMOVED);
        assertThat(outside.resolve("keep.txt")).hasContent("keep");
    }

    @Test void messagesNeverClaimRemovalThatDidNotHappen() {
        Path data = local.resolve(UserDataRemoval.FOLDER);
        assertThat(UserDataRemovalApplication.message(new UserDataRemoval.Result(UserDataRemoval.Outcome.IN_USE, data, 0))).contains("Nothing was removed");
        assertThat(UserDataRemovalApplication.message(new UserDataRemoval.Result(UserDataRemoval.Outcome.INCOMPLETE, data, 2))).contains("2 item(s) could not be removed");
        assertThat(UserDataRemovalApplication.description(data)).contains("cannot be undone").contains(data.toString());
    }
}
