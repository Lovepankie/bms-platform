package com.rincoltech.bms.core.documents.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.documents.Documents;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.kernel.TenantContext;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Issue #39: the daily sweep removes an upload object that no {@code documents} row names once it is a day old, and
 * never one a row still names or one written in the last 24 hours. It fails closed (a check that cannot see the
 * business's rows skips the business), it is bounded per business and per run, it goes on past a refused delete, and its
 * dry run removes nothing. Every name and key is fabricated.
 */
class OrphanSweepIT extends IntegrationTest {

    private static final byte[] BYTES = {1, 2, 3};

    /** No pause between delete requests, and no share rule unless a test asks for one. */
    private static final OrphanSweep.Limits OPEN = new OrphanSweep.Limits(1000, 10000, 1.0, Duration.ZERO);

    @Autowired
    OrphanSweep sweep;

    @Autowired
    Documents documents;

    @Autowired
    ObjectStorage storage;

    @Autowired
    BusinessClock clock;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    FakeObjectStorage fake() {
        return (FakeObjectStorage) storage;
    }

    @AfterEach
    void forgetRefusals() {
        fake().failDeletes.clear();
    }

    @Test
    void frDoc02_theSweepRemovesOnlyOldUnreferencedUploadsAndTheDryRunRemovesNothing() throws Exception {
        TestDatabase.Fixture t = TestDatabase.tenant("sweep", true);
        FakeObjectStorage fake = fake();
        String uploads = "tenants/" + t.tenantId() + "/upload/";
        Instant now = clock.now();

        // A real upload, its object then dated two days back: a row names it, so it stays.
        String referenced = upload(t);
        fake.putAt(referenced, BYTES, "image/png", now.minus(Duration.ofHours(48)));
        // No row, a day and an hour old: the one orphan to remove.
        String orphan = old(uploads, Duration.ofHours(25));
        // No row, but written an hour ago: its upload may still be committing, so it stays.
        String fresh = old(uploads, Duration.ofHours(1));
        // Just under the 24 hours: stays.
        String almost = old(uploads, Duration.ofHours(24).minusSeconds(60));
        // Old and unnamed but not an upload (a generated report): out of the sweep's scope.
        String report = "tenants/" + t.tenantId() + "/report/2026/01/" + UUID.randomUUID() + ".pdf";
        fake.putAt(report, BYTES, "application/pdf", now.minus(Duration.ofDays(30)));
        List<String> all = List.of(referenced, orphan, fresh, almost, report);

        // Dry run, this business: counts the orphan, removes nothing, audits nothing.
        OrphanSweep.Result dry = TenantContext.callAs(t.tenantId(), () -> sweep.sweep(t.tenantId(), true, OPEN));
        assertThat(dry.listed()).isEqualTo(4);
        assertThat(dry.old()).isEqualTo(2);
        assertThat(dry.orphans()).isEqualTo(1);
        assertThat(dry.removed()).isZero();
        assertThat(all).allMatch(fake::has);
        assertThat(auditedRemovals(t)).isEmpty();

        // Dry run, every business, with the configured bounds: nothing goes anywhere.
        OrphanSweep.Result dryAll = sweep.sweepAll(true);
        assertThat(dryAll.orphans()).isPositive();
        assertThat(dryAll.removed()).isZero();
        assertThat(all).allMatch(fake::has);
        assertThat(auditedRemovals(t)).isEmpty();

        // The real run removes the orphan alone, with one audit row naming its key.
        OrphanSweep.Result run = TenantContext.callAs(t.tenantId(), () -> sweep.sweep(t.tenantId(), false, OPEN));
        assertThat(run.removed()).isEqualTo(1);
        assertThat(fake.has(orphan)).isFalse();
        assertThat(List.of(referenced, fresh, almost, report)).allMatch(fake::has);
        assertThat(auditedRemovals(t)).hasSize(1).first().asString().contains(orphan);

        // A run again (as after a crash part way through) finds nothing more and audits nothing more.
        OrphanSweep.Result again = TenantContext.callAs(t.tenantId(), () -> sweep.sweep(t.tenantId(), false, OPEN));
        assertThat(again.orphans()).isZero();
        assertThat(auditedRemovals(t)).hasSize(1);
    }

    @Test
    void frDoc02_withoutTheBusinessBoundTheSweepDeletesNothingAndSkipsTheBusiness() throws Exception {
        TestDatabase.Fixture t = TestDatabase.tenant("sweep-unbound", true);
        upload(t);
        List<String> orphans = orphans(t, 5);
        // No tenant bound: the reference check cannot see this business's rows, so it must not delete.
        OrphanSweep.Result r = sweep.sweep(t.tenantId(), false, OPEN);
        assertThat(r.skipped()).isEqualTo(1);
        assertThat(r.removed()).isZero();
        assertThat(orphans).allMatch(fake()::has);
        // Bound to another business: the same.
        TestDatabase.Fixture other = TestDatabase.tenant("sweep-other", true);
        OrphanSweep.Result wrong = TenantContext.callAs(other.tenantId(), () -> sweep.sweep(t.tenantId(), false, OPEN));
        assertThat(wrong.skipped()).isEqualTo(1);
        assertThat(orphans).allMatch(fake()::has);
        assertThat(auditedRemovals(t)).isEmpty();
    }

    @Test
    void frDoc02_aBusinessWithOldObjectsButNoVisibleUploadRowIsSkipped() {
        TestDatabase.Fixture t = TestDatabase.tenant("sweep-norows", true);
        List<String> orphans = orphans(t, 3);
        OrphanSweep.Result r = TenantContext.callAs(t.tenantId(), () -> sweep.sweep(t.tenantId(), false, OPEN));
        assertThat(r.skipped()).isEqualTo(1);
        assertThat(r.removed()).isZero();
        assertThat(orphans).allMatch(fake()::has);
    }

    @Test
    void frDoc02_aBusinessWhoseOrphansPassTheAllowedShareIsSkippedWhole() throws Exception {
        TestDatabase.Fixture t = TestDatabase.tenant("sweep-share", true);
        upload(t);
        List<String> orphans = orphans(t, 30);
        OrphanSweep.Limits halfAtMost = new OrphanSweep.Limits(1000, 10000, 0.5, Duration.ZERO);
        OrphanSweep.Result r = TenantContext.callAs(t.tenantId(), () -> sweep.sweep(t.tenantId(), false, halfAtMost));
        assertThat(r.skipped()).isEqualTo(1);
        assertThat(r.removed()).isZero();
        assertThat(orphans).allMatch(fake()::has);
    }

    @Test
    void frDoc02_deletesAreBatchedAndCappedPerBusinessAndTheRestWaitsForTheNextRun() throws Exception {
        TestDatabase.Fixture t = TestDatabase.tenant("sweep-cap", true);
        upload(t);
        // More than two check and delete batches of 500.
        List<String> orphans = orphans(t, 1200);
        OrphanSweep.Limits cap = new OrphanSweep.Limits(1000, 10000, 1.0, Duration.ZERO);

        OrphanSweep.Result first = TenantContext.callAs(t.tenantId(), () -> sweep.sweep(t.tenantId(), false, cap));
        assertThat(first.orphans()).isEqualTo(1200);
        assertThat(first.removed()).isEqualTo(1000);
        assertThat(orphans.stream().filter(fake()::has)).hasSize(200);

        OrphanSweep.Result second = TenantContext.callAs(t.tenantId(), () -> sweep.sweep(t.tenantId(), false, cap));
        assertThat(second.removed()).isEqualTo(200);
        assertThat(orphans).noneMatch(fake()::has);
        assertThat(auditedRemovals(t)).hasSize(1200);
    }

    @Test
    void frDoc02_aRunForOneBusinessNeverTouchesAnotherBusinessesObjects() throws Exception {
        TestDatabase.Fixture a = TestDatabase.tenant("sweep-a", true);
        TestDatabase.Fixture b = TestDatabase.tenant("sweep-b", true);
        upload(a);
        upload(b);
        List<String> ofA = orphans(a, 2);
        List<String> ofB = orphans(b, 2);
        OrphanSweep.Result r = TenantContext.callAs(a.tenantId(), () -> sweep.sweep(a.tenantId(), false, OPEN));
        assertThat(r.removed()).isEqualTo(2);
        assertThat(ofA).noneMatch(fake()::has);
        assertThat(ofB).allMatch(fake()::has);
        assertThat(auditedRemovals(b)).isEmpty();
    }

    @Test
    void frDoc02_aRefusedDeleteIsCountedTheOthersStillGoAndTheRunFails() throws Exception {
        TestDatabase.Fixture t = TestDatabase.tenant("sweep-refused", true);
        upload(t);
        List<String> orphans = orphans(t, 3);
        fake().failDeletes.add(orphans.getFirst());

        OrphanSweep.Result r = TenantContext.callAs(t.tenantId(), () -> sweep.sweep(t.tenantId(), false, OPEN));
        assertThat(r.failed()).isEqualTo(1);
        assertThat(r.removed()).isEqualTo(2);
        assertThat(fake().has(orphans.getFirst())).isTrue();
        assertThat(orphans.subList(1, 3)).noneMatch(fake()::has);
        assertThat(auditedRemovals(t)).hasSize(2);
    }

    @Test
    void frDoc02_theWholePlatformRunRemovesEachBusinessesOrphansWithinTheRunBound() throws Exception {
        TestDatabase.Fixture a = TestDatabase.tenant("sweep-all-a", true);
        TestDatabase.Fixture b = TestDatabase.tenant("sweep-all-b", true);
        upload(a);
        upload(b);
        List<String> ofA = orphans(a, 1);
        List<String> ofB = orphans(b, 1);
        OrphanSweep.Result r = sweep.sweepAll(false, OPEN);
        assertThat(r.removed()).isGreaterThanOrEqualTo(2);
        assertThat(ofA).noneMatch(fake()::has);
        assertThat(ofB).noneMatch(fake()::has);
        assertThat(auditedRemovals(a)).hasSize(1);
        assertThat(auditedRemovals(b)).hasSize(1);
    }

    /** {@code n} unnamed upload objects of the business, two days old. */
    private List<String> orphans(TestDatabase.Fixture t, int n) {
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            keys.add(old("tenants/" + t.tenantId() + "/upload/", Duration.ofHours(48)));
        }
        return keys;
    }

    private String old(String prefix, Duration age) {
        String key = prefix + "2026/01/" + UUID.randomUUID() + ".png";
        fake().putAt(key, BYTES, "image/png", clock.now().minus(age));
        return key;
    }

    private String upload(TestDatabase.Fixture t) throws Exception {
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", png);
        CurrentPrincipal.set(Principal.uniform(UUID.randomUUID(), "staff", Set.of(), true, Set.of()));
        try {
            UUID id = TenantContext.callAs(
                    t.tenantId(),
                    () -> documents
                            .upload(new Documents.Upload(
                                    "test.subject", UUID.randomUUID(), t.headOffice(), png.toByteArray()))
                            .id());
            return TenantContext.callAs(
                    t.tenantId(),
                    () -> new TransactionTemplate(transactions)
                            .execute(s -> jdbc.sql("SELECT object_key FROM documents WHERE id = ?")
                                    .param(id)
                                    .query(String.class)
                                    .single()));
        } finally {
            CurrentPrincipal.clear();
        }
    }

    private List<String> auditedRemovals(TestDatabase.Fixture t) {
        return TenantContext.callAs(
                t.tenantId(),
                () -> new TransactionTemplate(transactions)
                        .execute(s -> jdbc.sql("""
                                        SELECT data::text FROM audit_log
                                        WHERE action = 'core.document.orphan_removed' AND actor_kind = 'system'
                                        """).query(String.class).list()));
    }
}
